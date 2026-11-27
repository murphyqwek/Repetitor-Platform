package tutor.identityservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation_code")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvitationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, unique = true)
    private String codeHash;

    @Column(name = "teacher_id")
    private UUID teacherId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    public InvitationCode(String codeHash, Instant createdAt, Instant expiredAt, UUID teacherId) {
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiredAt = expiredAt;
        this.teacherId = teacherId;
    }
}
