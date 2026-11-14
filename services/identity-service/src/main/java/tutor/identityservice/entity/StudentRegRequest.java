package tutor.identityservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "student_reg_request")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentRegRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private AppUser parent;

    @Column(name = "first_name", nullable = false, columnDefinition = "text")
    private String firstName;

    @Column(nullable = false, columnDefinition = "text")
    private String surname;

    @Column(columnDefinition = "text")
    private String patronymic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private StudentRegistrationStatus status = StudentRegistrationStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private AppUser student;

    public StudentRegRequest(AppUser parent, String firstName, String surname, String patronymic) {
        this.parent = parent;
        this.firstName = firstName;
        this.surname = surname;
        this.patronymic = patronymic;
    }
}
