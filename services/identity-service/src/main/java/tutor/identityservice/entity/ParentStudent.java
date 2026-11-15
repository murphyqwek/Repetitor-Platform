package tutor.identityservice.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "parent_student")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParentStudent {

    @EmbeddedId
    private ParentStudentId id;

    @MapsId("parentId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private AppUser parent;

    @MapsId("studentId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private AppUser student;
    public ParentStudent(AppUser parent, AppUser student) {
        this.parent = parent;
        this.student = student;
        this.id = new ParentStudentId(parent.getId(), student.getId());
    }
}
