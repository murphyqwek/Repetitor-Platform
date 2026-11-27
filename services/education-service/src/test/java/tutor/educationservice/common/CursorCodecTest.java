package tutor.educationservice.common;

import java.time.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tutor.educationservice.TestJwt;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.*;
import static org.assertj.core.api.Assertions.*;

class CursorCodecTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
    private final CursorCodec codec = new CursorCodec(new CursorProperties(TestJwt.CURSOR_KEY), clock);
    private final CursorCodec.Position position = new CursorCodec.Position(clock.instant().plusNanos(123456789), UUID.randomUUID());

    @Test
    void roundTripUsesOpaqueUniqueTokensBoundToScope() {
        String token = codec.encode(position, "course:one:user:one");
        assertThat(codec.decode(token, "course:one:user:one")).isEqualTo(position);
        assertThat(token).doesNotContain(position.id().toString());
        assertThat(codec.encode(position, "course:one:user:one")).isNotEqualTo(token);
        assertInvalid(token, "course:two:user:one");
        assertInvalid(token, "course:one:user:two");
    }

    @Test
    void rejectsMalformedTamperedExpiredAndWrongKeyTokens() {
        String valid = codec.encode(position, "scope");
        for (String invalid : new String[]{"", "garbage", valid + "=", valid.substring(1), "!".repeat(87)}) assertInvalid(invalid, "scope");
        byte[] changed = Base64.getUrlDecoder().decode(valid);
        changed[20] ^= 1;
        assertInvalid(Base64.getUrlEncoder().withoutPadding().encodeToString(changed), "scope");
        var expired = new CursorCodec(new CursorProperties(TestJwt.CURSOR_KEY), Clock.offset(clock, Duration.ofDays(1)));
        assertThatThrownBy(() -> expired.decode(valid, "scope")).isInstanceOf(ApiException.class);
        var otherKey = new CursorCodec(new CursorProperties(Base64.getEncoder().encodeToString(new byte[32]).replaceFirst("A", "B")), clock);
        assertThatThrownBy(() -> otherKey.decode(valid, "scope")).isInstanceOf(ApiException.class);
    }

    @Test
    void limitsAndConfigurationFailSafely() {
        assertThat(CursorCodec.limit(null)).isEqualTo(30);
        assertThat(CursorCodec.limit(null, 50)).isEqualTo(50);
        assertThat(CursorCodec.limit(100)).isEqualTo(100);
        for (int invalid : new int[]{0, -1, 101}) {
            assertThatThrownBy(() -> CursorCodec.limit(invalid)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status().value()).isEqualTo(400));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new CursorProperties("private-invalid-key"))
                .withMessage("education.cursor.key must contain a Base64-encoded 32-byte key");
        assertThat(new CursorProperties(TestJwt.CURSOR_KEY).toString()).doesNotContain(TestJwt.CURSOR_KEY);
    }

    private void assertInvalid(String token, String scope) {
        assertThatThrownBy(() -> codec.decode(token, scope)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status().value()).isEqualTo(400);
            assertThat(error.code()).isEqualTo("INVALID_CURSOR");
        });
    }
}
