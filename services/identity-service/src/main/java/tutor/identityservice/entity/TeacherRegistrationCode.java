package tutor.identityservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "teacher_registration_code")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeacherRegistrationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, columnDefinition = "text")
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "timestamp with time zone")
    private Instant createdAt;

    @Column(name = "expired_at", nullable = false, columnDefinition = "timestamp with time zone")
    private Instant expiredAt;

    public TeacherRegistrationCode(String codeHash, Instant createdAt, Instant expiredAt) {
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiredAt = expiredAt;
    }
}
