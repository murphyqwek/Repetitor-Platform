package tutor.educationservice.course;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tutor.educationservice.cleanup.DeletionReceipt;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {
    private final CourseService courses;
    private final CourseDeletion deletion;

    public CourseController(CourseService courses, CourseDeletion deletion) {
        this.courses = courses;
        this.deletion = deletion;
    }

    public record CreateRequest(@NotBlank String title, UUID teacherId) {}
    public record RenameRequest(@NotBlank String title) {}

    @PostMapping
    public ResponseEntity<CourseDto> create(@Valid @RequestBody CreateRequest body, @AuthenticationPrincipal Jwt jwt) {
        var course = courses.create(body.title(), body.teacherId(), jwt.getTokenValue());
        return ResponseEntity.created(URI.create("/api/v1/courses/" + course.id())).cacheControl(CacheControl.noStore()).body(course);
    }

    @GetMapping("/{courseId}")
    public ResponseEntity<CourseDto> get(@PathVariable UUID courseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(courses.get(courseId));
    }

    @PatchMapping("/{courseId}")
    public ResponseEntity<CourseDto> rename(@PathVariable UUID courseId, @Valid @RequestBody RenameRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(courses.rename(courseId, body.title()));
    }

    @DeleteMapping("/{courseId}")
    public ResponseEntity<DeletionReceipt> delete(@PathVariable UUID courseId) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(deletion.start(courseId));
    }

    @GetMapping
    public ResponseEntity<CoursePage> list(@RequestParam CourseService.Scope scope,
            @RequestParam(required = false) UUID teacherId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(courses.list(scope, teacherId, limit, cursor));
    }
}
