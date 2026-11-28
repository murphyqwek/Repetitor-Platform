package tutor.assignmentservice.integration.education;

import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.integration.ProviderHttp;

@Component
public class EducationClient {
    private final ProviderHttp http;
    public EducationClient(@Value("${assignment.education.url}") URI url, @Value("${assignment.education.key}") String key, Clock clock) {
        http = new ProviderHttp(url, "education-service", "assignment:recipients:resolve", key, "EDUCATION_UNAVAILABLE", clock);
    }

    public record Course(UUID id, String title) {
        public Course { Objects.requireNonNull(id); if (title == null || title.isBlank()) throw new IllegalArgumentException(); }
    }
    public record Recipient(UUID studentId, List<UUID> courseIds, boolean selectedIndividually) {
        public Recipient { Objects.requireNonNull(studentId); courseIds = List.copyOf(courseIds); }
    }
    public record Snapshot(Instant resolvedAt, List<Course> courses, List<Recipient> recipients,
                           List<UUID> invalidCourseIds, List<UUID> ineligibleStudentIds) {
        public Snapshot {
            Objects.requireNonNull(resolvedAt);
            courses = List.copyOf(courses); recipients = List.copyOf(recipients);
            invalidCourseIds = List.copyOf(invalidCourseIds); ineligibleStudentIds = List.copyOf(ineligibleStudentIds);
        }
    }
    public record Membership(boolean enrolled) {}

    public boolean enrolled(UUID teacher, UUID student) {
        return http.request("/internal/v1/teachers/" + teacher + "/students/" + student + "/membership", null, Membership.class).enrolled();
    }

    public Snapshot resolve(UUID teacher, Set<UUID> courses, Set<UUID> students) {
        if (courses.size() > 20 || students.size() > 500)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "RECIPIENT_LIMIT", "Recipient selection exceeds limit");
        var data = http.request("/internal/v1/assignment-recipients/resolve",
                Map.of("teacherId", teacher, "courseIds", courses, "studentIds", students), Snapshot.class);
        var valid = new HashSet<UUID>();
        for (var c : data.courses()) if (!valid.add(c.id())) throw http.unavailable();
        var accounted = new HashSet<>(valid);
        for (var id : data.invalidCourseIds()) if (!accounted.add(id)) throw http.unavailable();
        if (!accounted.equals(courses) || data.recipients().size() > 500) throw http.unavailable();
        var seen = new HashSet<UUID>();
        var individual = new HashSet<UUID>();
        for (var r : data.recipients()) {
            if (!seen.add(r.studentId()) || !valid.containsAll(r.courseIds()) || r.courseIds().size() != Set.copyOf(r.courseIds()).size()
                    || r.selectedIndividually() != students.contains(r.studentId())
                    || (!r.selectedIndividually() && r.courseIds().isEmpty())) throw http.unavailable();
            if (r.selectedIndividually()) individual.add(r.studentId());
        }
        for (var id : data.ineligibleStudentIds()) if (!individual.add(id)) throw http.unavailable();
        if (!individual.equals(students)) throw http.unavailable();
        return data;
    }
}
