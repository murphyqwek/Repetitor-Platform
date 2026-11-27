package tutor.educationservice.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tutor.educationservice.security.Role;

public record ChatMessage(UUID id, Author author, String text, Instant createdAt, List<Attachment> attachments) {
    public record Author(UUID id, String displayName, Role role) {}
    public record Attachment(UUID id, String fileName, String contentType, long sizeBytes, String kind, String downloadUrl) {}
}
