package tutor.educationservice.course;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.CursorCodec;
import tutor.educationservice.integration.identity.*;
import tutor.educationservice.security.CurrentUser;
import tutor.educationservice.security.Role;
import tutor.educationservice.schedule.ScheduleConflictService;

@Service
public class EnrollmentService {
    public record Excluded(UUID courseId, UUID studentId) {}

    private final CourseService courses;
    private final CourseStudentRepository students;
    private final IdentityClient identity;
    private final CursorCodec cursors;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ScheduleConflictService conflicts;
    private final ApplicationEventPublisher events;

    public EnrollmentService(CourseService courses, CourseStudentRepository students, IdentityClient identity,
            CursorCodec cursors, Clock clock, PlatformTransactionManager transactionManager, ScheduleConflictService conflicts,
            ApplicationEventPublisher events) {
        this.courses = courses;
        this.students = students;
        this.identity = identity;
        this.cursors = cursors;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.conflicts = conflicts;
        this.events = events;
    }

    public CourseStudentDto enroll(UUID courseId, UUID studentId, String accessToken, boolean confirmed) {
        courses.managed(courseId);
        if (students.existsById(new CourseStudent.Id(courseId, studentId))) throw duplicate();
        var user = identity.user(accessToken, studentId);
        if (!user.roles().contains(Role.STUDENT)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_STUDENT", "User must have the student role");
        }
        if (user.blocked()) throw new ApiException(HttpStatus.CONFLICT, "STUDENT_BLOCKED", "Student is blocked");
        return transaction.execute(status -> {
            courses.managedForUpdate(courseId);
            if (students.existsById(new CourseStudent.Id(courseId, studentId))) throw duplicate();
            conflicts.enrollment(courseId, studentId, confirmed);
            if (students.enroll(courseId, studentId, clock.instant().truncatedTo(ChronoUnit.MICROS)) == 0) throw duplicate();
            return dto(students.findById(new CourseStudent.Id(courseId, studentId)).orElseThrow(EnrollmentService::notFound), user);
        });
    }

    public void exclude(UUID courseId, UUID studentId) {
        transaction.executeWithoutResult(status -> {
            courses.managedForUpdate(courseId);
            if (students.exclude(courseId, studentId) != 0) events.publishEvent(new Excluded(courseId, studentId));
        });
    }

    public CourseStudentDto.Permissions permissions(UUID courseId, UUID studentId, boolean allowed) {
        return transaction.execute(status -> {
            courses.managedForUpdate(courseId);
            if (students.permissions(courseId, studentId, allowed) == 0) throw notFound();
            return new CourseStudentDto.Permissions(studentId, allowed);
        });
    }

    public CourseStudentDto.Page list(UUID courseId, Integer requestedLimit, String cursor, String accessToken) {
        courses.managed(courseId);
        int limit = CursorCodec.limit(requestedLimit);
        String binding = "course-students:v1:" + courseId + ":" + CurrentUser.get().id() + ":enrolledAtDesc,studentIdDesc";
        var after = cursor == null ? null : cursors.decode(cursor, binding);
        var rows = students.page(courseId, after == null ? null : after.createdAt(), after == null ? null : after.id(), limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = new ArrayList<CourseStudentDto>();
        for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
            try {
                items.add(dto(row, identity.user(accessToken, row.id().studentId())));
            } catch (ApiException exception) {
                if (exception.status() == HttpStatus.NOT_FOUND) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Member profile is unavailable");
                }
                throw exception;
            }
        }
        courses.managed(courseId);
        String next = null;
        if (hasMore) {
            var last = items.get(items.size() - 1);
            next = cursors.encode(new CursorCodec.Position(last.enrolledAt(), last.studentId()), binding);
        }
        return new CourseStudentDto.Page(items, next, hasMore);
    }

    private static CourseStudentDto dto(CourseStudent row, IdentityUser user) {
        return new CourseStudentDto(row.id().studentId(), user.displayName(), user.blocked(), row.canSendMessages(), row.enrolledAt());
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "ALREADY_ENROLLED", "Student is already enrolled");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Course member not found");
    }
}
