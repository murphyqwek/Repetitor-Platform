package tutor.identityservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tutor.identityservice.service.InvitationIdempotencyService.Operation;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation_idempotency", uniqueConstraints = @UniqueConstraint(
        name = "uc_invitation_idempotency_request",
        columnNames = {"user_id", "operation", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvitationIdempotency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Operation operation;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "result_code", nullable = false, columnDefinition = "text")
    private String resultCode;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    private Instant createdAt;

    @Column(name = "expired_at", nullable = false, columnDefinition = "timestamp with time zone")
    private Instant expiredAt;

    public void complete(String resultCode, Instant now, Instant expiredAt) {
        this.resultCode = resultCode;
        this.createdAt = now;
        this.expiredAt = expiredAt;
    }
}
