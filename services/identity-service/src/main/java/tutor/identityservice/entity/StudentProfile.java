package tutor.identityservice.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Table(name = "student_profile")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    // Recoverable credentials for the parent; encrypt before persistence.
    @Column(nullable = false, columnDefinition = "text")
    private String password;

    @Column(name = "school_grade")
    private Short schoolGrade;

    @Column(name = "guardian_id")
    private UUID guardianId;

    @Column(name = "consent_at")
    private Instant consentAt;

    @Column(name = "policy_version")
    private String policyVersion;

    public StudentProfile(AppUser user, String password, Short schoolGrade) {
        this.user = user;
        this.password = password;
        this.schoolGrade = schoolGrade;
    }
}
