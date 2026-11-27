package tutor.educationservice.cleanup;

import java.util.UUID;

public record DeletionReceipt(UUID resourceId, String deletionState, UUID cleanupJobId) {
    public DeletionReceipt(UUID resourceId, UUID cleanupJobId) { this(resourceId, "DELETING", cleanupJobId); }
}
