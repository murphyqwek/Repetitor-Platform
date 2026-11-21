package tutor.educationservice.schedule;

import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LessonSeriesRepository extends JpaRepository<LessonSeries, UUID> {
    List<LessonSeries> findByCourseIdIn(Collection<UUID> courseIds);
    Optional<LessonSeries> findByIdAndCourseId(UUID id, UUID courseId);
    @Query(value = """
            SELECT * FROM lesson_series WHERE course_id = :courseId
              AND (CAST(:createdAt AS timestamptz) IS NULL
                   OR (created_at, id) < (CAST(:createdAt AS timestamptz), CAST(:afterId AS uuid)))
            ORDER BY created_at DESC, id DESC LIMIT :pageSize
            """, nativeQuery = true)
    List<LessonSeries> page(@Param("courseId") UUID courseId, @Param("createdAt") Instant createdAt,
            @Param("afterId") UUID afterId, @Param("pageSize") int pageSize);

    @Query(value = """
            SELECT s.* FROM lesson_series s JOIN course c ON c.id = s.course_id
            WHERE c.id = :courseId AND c.deletion_state = 'ACTIVE'
            """, nativeQuery = true)
    List<LessonSeries> calendar(@Param("courseId") UUID courseId);

    @Query(value = """
            SELECT r.* FROM lesson_series r JOIN course c ON c.id = r.course_id
            WHERE c.deletion_state = 'ACTIVE'
              AND ((:teacher AND c.teacher_id = :actor)
                   OR (:student AND EXISTS (SELECT 1 FROM course_student s WHERE s.course_id = c.id AND s.student_id = :actor)))
            """, nativeQuery = true)
    List<LessonSeries> personalCalendar(@Param("actor") UUID actor, @Param("teacher") boolean teacher, @Param("student") boolean student);
}
