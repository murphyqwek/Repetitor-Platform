package tutor.educationservice.integration.assignment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tutor.educationservice.common.error.ApiException;

@RestController
@RequestMapping("/internal/v1")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AssignmentRecipientsController {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public AssignmentRecipientsController(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Selection(@NotNull UUID teacherId, @NotNull Set<@NotNull UUID> courseIds, @NotNull Set<@NotNull UUID> studentIds) {}
    public record Course(UUID id, String title) {}
    public record Recipient(UUID studentId, Set<UUID> courseIds, boolean selectedIndividually) {}
    public record Snapshot(Instant resolvedAt, List<Course> courses, List<Recipient> recipients,
                           Set<UUID> invalidCourseIds, Set<UUID> ineligibleStudentIds) {}
    public record Membership(boolean enrolled) {}

    @GetMapping("/teachers/{teacherId}/students/{studentId}/membership")
    public Membership membership(@PathVariable UUID teacherId, @PathVariable UUID studentId) {
        return new Membership(Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from course c join course_student s on c.id=s.course_id
                  where c.teacher_id=:teacher and c.deletion_state='ACTIVE' and s.student_id=:student)
                """, Map.of("teacher", teacherId, "student", studentId), Boolean.class)));
    }

    @PostMapping("/assignment-recipients/resolve")
    public Snapshot resolve(@Valid @RequestBody Selection request) {
        if (request.courseIds().size() > 20 || request.studentIds().size() > 500) throw limit();
        var args = Map.of("teacher", request.teacherId(), "courses", request.courseIds(), "students", request.studentIds());
        var courses = request.courseIds().isEmpty() ? List.<Course>of() : jdbc.query("""
                select id,title from course where teacher_id=:teacher and deletion_state='ACTIVE' and id in (:courses) order by id
                """, args, (r,i) -> new Course(r.getObject("id", UUID.class), r.getString("title")));
        var invalid = new LinkedHashSet<>(request.courseIds());
        courses.forEach(c -> invalid.remove(c.id()));
        var origins = new LinkedHashMap<UUID, Set<UUID>>();
        if (!courses.isEmpty()) {
            var valid = courses.stream().map(Course::id).toList();
            jdbc.query("select student_id,course_id from course_student where course_id in (:ids) order by student_id,course_id",
                    Map.of("ids", valid), (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                        origins.computeIfAbsent(r.getObject(1, UUID.class), id -> new LinkedHashSet<>()).add(r.getObject(2, UUID.class));
                        if (origins.size() > 500) throw limit();
                    });
        }
        var ineligible = new LinkedHashSet<>(request.studentIds());
        if (!ineligible.isEmpty()) {
            var enrolled = jdbc.queryForList("""
                    select distinct s.student_id from course_student s join course c on c.id=s.course_id
                      where c.teacher_id=:teacher and c.deletion_state='ACTIVE' and s.student_id in (:students) order by s.student_id
                    """, args, UUID.class);
            ineligible.removeAll(enrolled);
            enrolled.forEach(id -> origins.putIfAbsent(id, Set.of()));
        }
        if (origins.size() > 500) throw limit();
        var recipients = origins.entrySet().stream().map(e -> new Recipient(e.getKey(), e.getValue(),
                request.studentIds().contains(e.getKey()))).toList();
        return new Snapshot(clock.instant(), courses, recipients, invalid, ineligible);
    }

    private static ApiException limit() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "RECIPIENT_LIMIT", "Recipient selection exceeds limit");
    }
}
