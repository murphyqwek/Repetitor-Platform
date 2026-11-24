package tutor.educationservice.course;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.CursorCodec;
import tutor.educationservice.integration.identity.IdentityClient;
import tutor.educationservice.security.*;

@Service
public class CourseService {
    public enum Scope { OWNED, ENROLLED, ALL }
    private final CourseRepository courses;
    private final CourseStudentRepository students;
    private final IdentityClient identity;
    private final PermissionService permissions;
    private final CursorCodec cursors;
    private final Clock clock;

    public CourseService(CourseRepository courses, CourseStudentRepository students, IdentityClient identity,
            PermissionService permissions, CursorCodec cursors, Clock clock) {
        this.courses = courses;
        this.students = students;
        this.identity = identity;
        this.permissions = permissions;
        this.cursors = cursors;
        this.clock = clock;
    }

    public CourseDto create(String title, UUID teacherId, String accessToken) {
        var user = CurrentUser.get();
        permissions.requireTeacherOrAdmin();
        if (teacherId != null) {
            permissions.requireRole(Role.ADMIN);
            var teacher = identity.user(accessToken, teacherId);
            if (!teacher.roles().contains(Role.TEACHER)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEACHER", "Course owner must be a teacher");
            }
        } else {
            if (!user.roles().contains(Role.TEACHER)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", "teacherId is required for an administrator without a teacher role");
            }
            teacherId = user.id();
        }
        // Identity lookup is complete before the repository starts its write transaction.
        return courses.saveAndFlush(new Course(teacherId, title.strip(), clock.instant().truncatedTo(ChronoUnit.MICROS))).dto();
    }

    @Transactional(readOnly = true)
    public CourseDto get(UUID id) {
        return visible(courses.findByIdAndDeletionState(id, Course.DeletionState.ACTIVE).orElseThrow(CourseService::notFound)).dto();
    }

    @Transactional
    public CourseDto rename(UUID id, String title) {
        var course = managedForUpdate(id);
        course.rename(title.strip());
        return course.dto();
    }

    @Transactional(readOnly = true)
    public CourseDto managed(UUID id) {
        permissions.requireTeacherOrAdmin();
        return manageable(courses.findByIdAndDeletionState(id, Course.DeletionState.ACTIVE).orElseThrow(CourseService::notFound)).dto();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Course managedForUpdate(UUID id) {
        permissions.requireTeacherOrAdmin();
        return manageable(courses.findForUpdate(id, Course.DeletionState.ACTIVE).orElseThrow(CourseService::notFound));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Course visibleForUpdate(UUID id) {
        return visible(courses.findForUpdate(id, Course.DeletionState.ACTIVE).orElseThrow(CourseService::notFound));
    }

    Course managedForDeletion(UUID id) {
        permissions.requireTeacherOrAdmin();
        return manageable(courses.findForDeletion(id).orElseThrow(CourseService::notFound));
    }

    @Transactional(readOnly = true)
    public CoursePage list(Scope scope, UUID teacherId, Integer requestedLimit, String cursor) {
        int limit = CursorCodec.limit(requestedLimit);
        var user = CurrentUser.get();
        UUID owner = teacherId;
        switch (scope) {
            case ALL -> permissions.requireRole(Role.ADMIN);
            case OWNED -> {
                if (teacherId != null) permissions.requireRole(Role.ADMIN);
                else if (user.roles().contains(Role.TEACHER)) owner = user.id();
                else if (user.roles().contains(Role.ADMIN)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "teacherId is required for administrator OWNED scope");
                } else throw new AccessDeniedException("Access denied");
            }
            case ENROLLED -> {
                permissions.requireRole(Role.STUDENT);
                if (teacherId != null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "teacherId is not supported for ENROLLED scope");
            }
        }
        String binding = "courses:v1:" + user.id() + ":" + scope + ":" + owner + ":createdAtDesc,idDesc";
        var after = cursor == null ? null : cursors.decode(cursor, binding);
        var page = scope == Scope.ENROLLED
                ? courses.enrolledPage(user.id(), after == null ? null : after.createdAt(), after == null ? null : after.id(), limit + 1)
                : courses.page(owner, after == null ? null : after.createdAt(), after == null ? null : after.id(), limit + 1);
        boolean hasMore = page.size() > limit;
        var items = page.subList(0, Math.min(limit, page.size())).stream().map(Course::dto).toList();
        String next = null;
        if (hasMore) {
            var last = items.get(items.size() - 1);
            next = cursors.encode(new CursorCodec.Position(last.createdAt(), last.id()), binding);
        }
        return new CoursePage(items, next, hasMore);
    }

    private Course visible(Course course) {
        var user = CurrentUser.get();
        if (!user.roles().contains(Role.ADMIN)
                && !(user.roles().contains(Role.TEACHER) && user.id().equals(course.teacherId()))
                && !(user.roles().contains(Role.STUDENT) && students.existsById(new CourseStudent.Id(course.dto().id(), user.id())))) throw notFound();
        return course;
    }

    private Course manageable(Course course) {
        var user = CurrentUser.get();
        if (!user.roles().contains(Role.ADMIN) && !user.id().equals(course.teacherId())) throw notFound();
        return course;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Course not found");
    }
}
