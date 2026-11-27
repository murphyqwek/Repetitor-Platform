package tutor.educationservice.course;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CourseStudentRepository extends JpaRepository<CourseStudent, CourseStudent.Id> {
    @Query("select s.id.studentId from CourseStudent s where s.id.courseId = :courseId")
    List<UUID> studentIds(@Param("courseId") UUID courseId);

    @Modifying
    @Query(value = """
            INSERT INTO course_student (course_id, student_id, enrolled_at)
            VALUES (:courseId, :studentId, :enrolledAt)
            ON CONFLICT (course_id, student_id) DO NOTHING
            """, nativeQuery = true)
    int enroll(@Param("courseId") UUID courseId, @Param("studentId") UUID studentId, @Param("enrolledAt") Instant enrolledAt);

    @Modifying
    @Query(value = "DELETE FROM course_student WHERE course_id = :courseId AND student_id = :studentId", nativeQuery = true)
    int exclude(@Param("courseId") UUID courseId, @Param("studentId") UUID studentId);

    @Modifying
    @Query(value = """
            UPDATE course_student SET can_send_messages = :allowed
            WHERE course_id = :courseId AND student_id = :studentId
            """, nativeQuery = true)
    int permissions(@Param("courseId") UUID courseId, @Param("studentId") UUID studentId, @Param("allowed") boolean allowed);

    @Query(value = """
            SELECT * FROM course_student WHERE course_id = :courseId
              AND (CAST(:enrolledAt AS timestamptz) IS NULL
                   OR (enrolled_at, student_id) < (CAST(:enrolledAt AS timestamptz), CAST(:studentId AS uuid)))
            ORDER BY enrolled_at DESC, student_id DESC LIMIT :pageSize
            """, nativeQuery = true)
    List<CourseStudent> page(@Param("courseId") UUID courseId, @Param("enrolledAt") Instant enrolledAt,
            @Param("studentId") UUID studentId, @Param("pageSize") int pageSize);
}
