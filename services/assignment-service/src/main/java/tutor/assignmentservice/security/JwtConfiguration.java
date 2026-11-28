package tutor.assignmentservice.security;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${assignment.security.public-key-location}") Resource publicKey,
            @Value("${assignment.security.issuer}") String issuer) {
        try (var stream = publicKey.getInputStream()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(stream);
            var decoder = NimbusJwtDecoder.withPublicKey(key).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), this::validateClaims));
            return decoder;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Assignment JWT public key must be a readable X.509 RSA PEM resource");
        }
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            if (!UUID.fromString(jwt.getSubject()).toString().equalsIgnoreCase(jwt.getSubject())
                    || jwt.getAudience() == null || !jwt.getAudience().contains("assignment-service")
                    || jwt.hasClaim("tokenType") || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now()) || jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(60))
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())) throw new IllegalArgumentException();
            Object claim = jwt.getClaim("roles");
            if (!(claim instanceof List<?> roles) || roles.isEmpty()) throw new IllegalArgumentException();
            for (Object role : roles) {
                if (!(role instanceof String value)) throw new IllegalArgumentException();
                Role.valueOf(value);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token claims", null));
        }
    }
}
