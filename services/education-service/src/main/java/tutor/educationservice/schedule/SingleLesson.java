package tutor.educationservice.schedule;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "single_lesson")
public class SingleLesson {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;
    @Column(name = "starts_at", nullable = false, columnDefinition = "timestamptz")
    private Instant startsAt;
    @Column(name = "ends_at", nullable = false, columnDefinition = "timestamptz")
    private Instant endsAt;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected SingleLesson() {}

    SingleLesson(UUID courseId, TimeInterval interval, Instant createdAt) {
        this.courseId = courseId;
        this.createdAt = createdAt;
        move(interval);
    }

    void move(TimeInterval interval) {
        this.startsAt = interval.startsAt();
        this.endsAt = interval.endsAt();
    }

    LessonDto dto() { return new LessonDto(courseId, "SINGLE", id, null, null, startsAt, endsAt, false); }
}
