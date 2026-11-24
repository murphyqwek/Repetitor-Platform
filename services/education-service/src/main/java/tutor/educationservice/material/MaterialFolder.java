package tutor.educationservice.material;

import java.time.Instant;
import java.util.UUID;

public record MaterialFolder(UUID id, String name, UUID parentId, Instant createdAt) {}
