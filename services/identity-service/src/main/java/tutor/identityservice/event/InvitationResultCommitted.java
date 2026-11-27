package tutor.identityservice.event;

import tutor.identityservice.service.InvitationIdempotencyService.Operation;
import java.time.Instant;
import java.util.UUID;

public record InvitationResultCommitted(
        String idempotencyKey, UUID userId, Operation operation, String code, Instant expiredAt
) {
}
