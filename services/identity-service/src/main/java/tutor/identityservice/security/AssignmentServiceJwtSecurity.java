package tutor.identityservice.security;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class AssignmentServiceJwtSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain assignmentServiceJwt(HttpSecurity http,
            @Value("${ASSIGNMENT_IDENTITY_SIGNING_KEY:}") String key) throws Exception {
        var decoder = decoder(key);
        return http.securityMatcher("/internal/v1/users/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority("SCOPE_users:read"))
                .oauth2ResourceServer(o -> o.bearerTokenResolver(new DefaultBearerTokenResolver())
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(
                                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter()))
                        .authenticationEntryPoint((req, res, error) -> problem(res, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((req, res, error) -> problem(res, 403, "ACCESS_DENIED")))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, error) -> problem(res, 401, "UNAUTHORIZED"))
                        .accessDeniedHandler((req, res, error) -> problem(res, 403, "ACCESS_DENIED")))
                .build();
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, int status, String code) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        if (status == 401) res.setHeader("WWW-Authenticate", "Bearer");
        res.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }

    static JwtDecoder decoder(String encoded) {
        if (encoded.isBlank()) return token -> { throw new BadJwtException("Service credential is not configured"); };
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != 32) throw new IllegalArgumentException("ASSIGNMENT_IDENTITY_SIGNING_KEY requires 32 bytes");
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("rp-assignment"),
                jwt -> {
                    Instant now = Instant.now(), issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt();
                    boolean valid = "assignment-service".equals(jwt.getSubject())
                            && List.of("identity-service").equals(jwt.getAudience())
                            && "SERVICE".equals(jwt.getClaims().get("tokenType"))
                            && jwt.getClaims().get("scope") instanceof java.util.Collection<?> scopes
                            && scopes.stream().allMatch(String.class::isInstance)
                            && issued != null && expires != null && !issued.isAfter(now.plusSeconds(5))
                            && expires.isAfter(now) && expires.isAfter(issued) && !expires.isAfter(issued.plusSeconds(300));
                    return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
                }));
        return decoder;
    }
}
