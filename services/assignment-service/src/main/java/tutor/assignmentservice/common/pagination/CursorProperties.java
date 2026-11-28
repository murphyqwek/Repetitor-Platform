package tutor.assignmentservice.common.pagination;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("assignment.cursor")
public record CursorProperties(String key) {
    public CursorProperties {
        try {
            if (Base64.getDecoder().decode(key).length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("assignment.cursor.key must contain a Base64-encoded 32-byte key");
        }
    }

    @Override
    public String toString() { return "CursorProperties[redacted]"; }
}
