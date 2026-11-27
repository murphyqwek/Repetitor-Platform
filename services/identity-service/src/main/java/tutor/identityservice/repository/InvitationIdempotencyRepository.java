package tutor.identityservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tutor.identityservice.entity.InvitationIdempotency;
import tutor.identityservice.service.InvitationIdempotencyService.Operation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InvitationIdempotencyRepository extends JpaRepository<InvitationIdempotency, Long> {
    // The unique key serializes concurrent requests. The no-op update also locks
    // existing rows against expiry cleanup until this transaction finishes.
    @Modifying
    @Query(value = """
            INSERT INTO invitation_idempotency
                (user_id, operation, idempotency_key, result_code, created_at, expired_at)
            VALUES (:userId, :operation, :key, '', :now, :expiredAt)
            ON CONFLICT (user_id, operation, idempotency_key)
            DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
            """, nativeQuery = true)
    int reserve(@Param("userId") UUID userId,
                @Param("operation") String operation,
                @Param("key") String key,
                @Param("now") Instant now,
                @Param("expiredAt") Instant expiredAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from InvitationIdempotency r
            where r.userId = :userId and r.operation = :operation and r.idempotencyKey = :key
            """)
    Optional<InvitationIdempotency> findRequestForUpdate(
            @Param("userId") UUID userId,
            @Param("operation") Operation operation,
            @Param("key") String key);

    @Modifying
    @Query("delete from InvitationIdempotency r where r.expiredAt <= :now")
    int deleteExpiredRequests(@Param("now") Instant now);
}
