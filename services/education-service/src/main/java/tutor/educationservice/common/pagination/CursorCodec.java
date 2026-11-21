package tutor.educationservice.common.pagination;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tutor.educationservice.common.error.ApiException;

@Component
public class CursorCodec {
    private static final Duration TTL = Duration.ofDays(1);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;
    private final Clock clock;

    public record Position(Instant createdAt, UUID id) {
        public Position {
            if (createdAt == null || id == null) throw new IllegalArgumentException("Cursor position is required");
        }
    }

    public CursorCodec(CursorProperties properties, Clock clock) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.key()), "AES");
        this.clock = clock;
    }

    public String encode(Position position, String scope) {
        byte[] aad = scopeBytes(scope);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] payload = ByteBuffer.allocate(37).put((byte) 1).putLong(clock.instant().plus(TTL).getEpochSecond())
                .putLong(position.createdAt().getEpochSecond()).putInt(position.createdAt().getNano())
                .putLong(position.id().getMostSignificantBits()).putLong(position.id().getLeastSignificantBits()).array();
        try {
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce, aad);
            byte[] encrypted = cipher.doFinal(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cursor encryption unavailable");
        }
    }

    public Position decode(String cursor, String scope) {
        byte[] aad = scopeBytes(scope);
        try {
            if (cursor == null || cursor.length() != 87 || !cursor.matches("[A-Za-z0-9_-]+")) throw invalidCursor();
            byte[] encoded = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(encoded).equals(cursor)) throw invalidCursor();
            var bytes = ByteBuffer.wrap(encoded);
            byte[] nonce = new byte[12];
            bytes.get(nonce);
            byte[] ciphertext = new byte[bytes.remaining()];
            bytes.get(ciphertext);
            var payload = ByteBuffer.wrap(cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(ciphertext));
            if (payload.get() != 1 || !Instant.ofEpochSecond(payload.getLong()).isAfter(clock.instant())) throw invalidCursor();
            long seconds = payload.getLong();
            int nanos = payload.getInt();
            if (nanos < 0 || nanos >= 1_000_000_000) throw invalidCursor();
            return new Position(Instant.ofEpochSecond(seconds, nanos), new UUID(payload.getLong(), payload.getLong()));
        } catch (GeneralSecurityException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw invalidCursor();
        }
    }

    public static int limit(Integer requested) { return limit(requested, 30); }

    public static int limit(Integer requested, int defaultLimit) {
        int value = requested == null ? defaultLimit : requested;
        if (value < 1 || value > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "Limit must be between 1 and 100");
        return value;
    }

    private Cipher cipher(int mode, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    private static byte[] scopeBytes(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 512) throw new IllegalArgumentException("Server cursor scope is required");
        return scope.getBytes(StandardCharsets.UTF_8);
    }

    private static ApiException invalidCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Invalid, expired or mismatched cursor");
    }
}
