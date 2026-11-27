package tutor.educationservice;

import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class TestJwt {
    public static final UUID USER = UUID.fromString("dbd1cbe7-5b94-49f8-b07d-a420375d160b");
    public static final String CURSOR_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final KeyPair KEYS = keys();
    private static final String PUBLIC_KEY = publicKey();

    private TestJwt() {}

    public static void properties(DynamicPropertyRegistry registry) {
        registry.add("education.outbox.enabled", () -> "false");
        registry.add("education.cleanup.enabled", () -> "false");
        registry.add("education.security.public-key-location", () -> PUBLIC_KEY);
        registry.add("education.security.issuer", () -> "rp-identity");
        registry.add("education.identity.base-url", () -> "http://localhost:1");
        registry.add("education.cursor.key", () -> CURSOR_KEY);
    }

    public static String token(List<String> roles) { return token(roles, claims -> {}, false); }

    public static String token(List<String> roles, Consumer<JwtClaimsSet.Builder> change, boolean wrongKey) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("rp-identity").subject(USER.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles", roles);
        change.accept(claims);
        var pair = wrongKey ? keys() : KEYS;
        var encoder = NimbusJwtEncoder.withKeyPair((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String publicKey() {
        try {
            var file = Files.createTempFile("education-test-public-", ".pem");
            String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            file.toFile().deleteOnExit();
            return file.toUri().toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
