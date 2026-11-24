package tutor.educationservice.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.web.SecurityFilterChain;
import tutor.educationservice.common.error.Problems;

@Configuration(proxyBeanMethods = false)
class AssignmentSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain assignmentSecurity(HttpSecurity http, Problems problems,
            @Value("${education.assignment.token:}") String secret) throws Exception {
        byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
        return http.securityMatcher("/internal/v1/materials/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("MATERIAL_REFERENCES"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(oauth -> oauth.opaqueToken(opaque -> opaque.introspector(token -> {
                    if (expected.length < 32 || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
                        throw new BadOpaqueTokenException("Invalid Assignment credential");
                    }
                    return new DefaultOAuth2AuthenticatedPrincipal("assignment", Map.of("sub", "assignment"),
                            List.of(new SimpleGrantedAuthority("MATERIAL_REFERENCES")));
                })).authenticationEntryPoint((request, response, exception) ->
                        problems.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Assignment credential required")))
                .build();
    }
}
