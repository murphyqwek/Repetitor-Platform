package tutor.identityservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tutor.identityservice.event.InvitationResultCommitted;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class InvitationIdempotencyService {
    public enum Operation {
        PARENT_INVITATION,
        TEACHER_REGISTRATION
    }

    private final StringRedisTemplate redis;
    private final InvitationCreationService creationService;
    private final Clock clock;

    public InvitationIdempotencyService(
            StringRedisTemplate redis, InvitationCreationService creationService, Clock clock
    ) {
        this.redis = redis;
        this.creationService = creationService;
        this.clock = clock;
    }

    public String create(String key, UUID userId, Operation operation) {
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(operation, "Operation must not be null");
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency key must contain 1 to 128 characters");
        }
        String cached = readCache(key, userId, operation);
        return cached != null ? cached : creationService.create(key, userId, operation);
    }

    private String getKey(String key, UUID userId, Operation operation) {
        return "invitationIdempotency:v2:" + operation.name() + ":" + userId + ":" + key;
    }

    private String readCache(String key, UUID userId, Operation operation) {
        try {
            String cached = redis.opsForValue().get(getKey(key, userId, operation));
            if (cached == null) {
                return null;
            }
            int separator = cached.indexOf(':');
            if (separator <= 0 || separator == cached.length() - 1) {
                return null;
            }
            Instant expiredAt = Instant.ofEpochMilli(Long.parseLong(cached.substring(0, separator)));
            return expiredAt.isAfter(clock.instant()) ? cached.substring(separator + 1) : null;
        } catch (RuntimeException exception) {
            log.warn("Invitation result cache unavailable or invalid; using PostgreSQL");
            return null;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cacheCommittedResult(InvitationResultCommitted result) {
        try {
            Duration remaining = Duration.between(clock.instant(), result.expiredAt());
            if (remaining.isNegative() || remaining.isZero()) {
                return;
            }
            String cached = result.expiredAt().toEpochMilli() + ":" + result.code();
            redis.opsForValue().set(
                    getKey(result.idempotencyKey(), result.userId(), result.operation()),
                    cached, remaining);
        } catch (RuntimeException exception) {
            // The database already committed: cache failure must not fail the request.
            log.warn("Could not cache committed invitation result; PostgreSQL retains the response");
        }
    }
}
