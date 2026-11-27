package tutor.educationservice.material;

import java.time.Instant;
import java.util.UUID;

public record MaterialFile(UUID id, String name, UUID folderId, String contentType, long sizeBytes, Instant createdAt) {}
