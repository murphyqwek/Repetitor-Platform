package tutor.identityservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ParentStudentId implements Serializable {

    @Column(name = "parent_id", nullable = false)
    private UUID parentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;
}
