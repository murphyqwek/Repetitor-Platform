package tutor.educationservice.course;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/students")
public class EnrollmentController {
    private final EnrollmentService enrollment;

    public EnrollmentController(EnrollmentService enrollment) { this.enrollment = enrollment; }

    public record EnrollRequest(@NotNull UUID studentId, Boolean confirmConflicts) {}
    public record PermissionsRequest(@NotNull Boolean canSendMessages) {}

    @PostMapping
    public ResponseEntity<CourseStudentDto> enroll(@PathVariable UUID courseId, @Valid @RequestBody EnrollRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        var student = enrollment.enroll(courseId, body.studentId(), jwt.getTokenValue(), Boolean.TRUE.equals(body.confirmConflicts()));
        return ResponseEntity.created(URI.create("/api/v1/courses/" + courseId + "/students/" + student.studentId()))
                .cacheControl(CacheControl.noStore()).body(student);
    }

    @GetMapping
    public ResponseEntity<CourseStudentDto.Page> list(@PathVariable UUID courseId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(enrollment.list(courseId, limit, cursor, jwt.getTokenValue()));
    }

    @DeleteMapping("/{studentId}")
    public ResponseEntity<Void> exclude(@PathVariable UUID courseId, @PathVariable UUID studentId) {
        enrollment.exclude(courseId, studentId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PatchMapping("/{studentId}/chat-permissions")
    public ResponseEntity<CourseStudentDto.Permissions> permissions(@PathVariable UUID courseId, @PathVariable UUID studentId,
            @Valid @RequestBody PermissionsRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(enrollment.permissions(courseId, studentId, body.canSendMessages()));
    }
}
