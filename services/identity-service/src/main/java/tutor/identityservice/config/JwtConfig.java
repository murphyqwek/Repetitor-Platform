package tutor.identityservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;

@Configuration
public class JwtConfig {

    @Bean
    public JwtEncoder jwtEncoder(
            @Value("${identity.security.jwt.public-key-path}") String publicKeyPath,
            @Value("${identity.security.jwt.private-key-path}") String privateKeyPath
    ) throws IOException {
        RSAPublicKey publicKey;
        try (var input = Files.newInputStream(Path.of(publicKeyPath))) {
            publicKey = RsaKeyConverters.x509().convert(input);
        }

        RSAPrivateKey privateKey;
        try (var input = Files.newInputStream(Path.of(privateKeyPath))) {
            privateKey = RsaKeyConverters.pkcs8().convert(input);
        }

        return NimbusJwtEncoder.withKeyPair(publicKey, privateKey).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@Value("${identity.security.jwt.public-key-path}") String publicKeyPath, @Value("${identity.security.jwt.issuer}") String issuer) throws IOException {
        RSAPublicKey publicKey;
        try (var input = Files.newInputStream(Path.of(publicKeyPath))) {
            publicKey = RsaKeyConverters.x509().convert(input);
        }

        var decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));

        return decoder;
    }
}
