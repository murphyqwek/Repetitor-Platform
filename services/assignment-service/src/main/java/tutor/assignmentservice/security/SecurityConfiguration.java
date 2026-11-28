package tutor.assignmentservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tutor.assignmentservice.common.error.Problems;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, IdentityAuthentication identity, Problems problems) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .requestMatchers("/api/v1/**").hasAnyRole("TEACHER", "STUDENT", "ADMIN")
                .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                // Assignment accepts Authorization headers only, never browser cookies.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) ->
                                problems.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied")))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                        problems.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"))
                        .accessDeniedHandler((request, response, exception) ->
                                problems.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied")))
                .addFilterAfter(new IdentityAccessFilter(identity, problems), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
