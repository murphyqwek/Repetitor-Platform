package tutor.educationservice.course;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "course_student")
public class CourseStudent {
    @Embeddable
    public record Id(@Column(name = "course_id") UUID courseId,
                     @Column(name = "student_id") UUID studentId) implements Serializable {}

    @EmbeddedId
    private Id id;
    @Column(name = "can_send_messages", nullable = false)
    private boolean canSendMessages;
    @Column(name = "enrolled_at", nullable = false, columnDefinition = "timestamptz")
    private Instant enrolledAt;

    protected CourseStudent() {}

    Id id() { return id; }
    boolean canSendMessages() { return canSendMessages; }
    Instant enrolledAt() { return enrolledAt; }
}
