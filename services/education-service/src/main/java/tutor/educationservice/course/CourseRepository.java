package tutor.educationservice.course;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, UUID> {
    @Query(value = """
            SELECT c.id FROM course c WHERE c.deletion_state = 'ACTIVE'
              AND (c.teacher_id = :userId OR EXISTS
                   (SELECT 1 FROM course_student s WHERE s.course_id = c.id AND s.student_id = :userId))
            """, nativeQuery = true)
    List<UUID> participantCourses(@Param("userId") UUID userId);

    Optional<Course> findByIdAndDeletionState(UUID id, Course.DeletionState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id and c.deletionState = :state")
    Optional<Course> findForUpdate(@Param("id") UUID id, @Param("state") Course.DeletionState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id")
    Optional<Course> findForDeletion(@Param("id") UUID id);

    @Query(value = """
            SELECT * FROM course
            WHERE deletion_state = 'ACTIVE'
              AND (CAST(:teacherId AS uuid) IS NULL OR teacher_id = CAST(:teacherId AS uuid))
              AND (CAST(:createdAt AS timestamptz) IS NULL
                   OR (created_at, id) < (CAST(:createdAt AS timestamptz), CAST(:afterId AS uuid)))
            ORDER BY created_at DESC, id DESC
            LIMIT :pageSize
            """, nativeQuery = true)
    List<Course> page(@Param("teacherId") UUID teacherId, @Param("createdAt") Instant createdAt,
            @Param("afterId") UUID afterId, @Param("pageSize") int pageSize);

    @Query(value = """
            SELECT c.* FROM course c
            JOIN course_student s ON s.course_id = c.id
            WHERE s.student_id = :studentId AND c.deletion_state = 'ACTIVE'
              AND (CAST(:createdAt AS timestamptz) IS NULL
                   OR (c.created_at, c.id) < (CAST(:createdAt AS timestamptz), CAST(:afterId AS uuid)))
            ORDER BY c.created_at DESC, c.id DESC LIMIT :pageSize
            """, nativeQuery = true)
    List<Course> enrolledPage(@Param("studentId") UUID studentId, @Param("createdAt") Instant createdAt,
            @Param("afterId") UUID afterId, @Param("pageSize") int pageSize);
}
