package tutor.educationservice.schedule;

import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SingleLessonRepository extends JpaRepository<SingleLesson, UUID> {
    List<SingleLesson> findByCourseIdIn(Collection<UUID> courseIds);
    Optional<SingleLesson> findByIdAndCourseId(UUID id, UUID courseId);

    @Query(value = """
            SELECT l.* FROM single_lesson l JOIN course c ON c.id = l.course_id
            WHERE c.id = :courseId AND c.deletion_state = 'ACTIVE'
              AND l.starts_at < :endsAt AND l.ends_at > :startsAt
            ORDER BY l.starts_at, l.id
            """, nativeQuery = true)
    List<SingleLesson> calendar(@Param("courseId") UUID courseId, @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);

    @Query(value = """
            SELECT l.* FROM single_lesson l JOIN course c ON c.id = l.course_id
            WHERE c.deletion_state = 'ACTIVE' AND l.starts_at < :endsAt AND l.ends_at > :startsAt
              AND ((:teacher AND c.teacher_id = :actor)
                   OR (:student AND EXISTS (SELECT 1 FROM course_student s WHERE s.course_id = c.id AND s.student_id = :actor)))
            ORDER BY l.starts_at, l.id
            """, nativeQuery = true)
    List<SingleLesson> personalCalendar(@Param("actor") UUID actor, @Param("teacher") boolean teacher,
            @Param("student") boolean student, @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}
