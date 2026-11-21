package tutor.educationservice.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ScheduleController {
    private final SingleLessonService lessons;
    private final CalendarService calendars;

    public ScheduleController(SingleLessonService lessons, CalendarService calendars) {
        this.lessons = lessons;
        this.calendars = calendars;
    }

    public record CreateRequest(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt, Boolean confirmConflicts) {}
    public record MoveRequest(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt, String reason, Boolean confirmConflicts) {}
    public record CancelRequest(String reason) {}

    @PostMapping("/courses/{courseId}/lessons")
    public ResponseEntity<LessonDto> create(@PathVariable UUID courseId, @Valid @RequestBody CreateRequest body) {
        var lesson = lessons.create(courseId, body.startsAt(), body.endsAt(), Boolean.TRUE.equals(body.confirmConflicts()));
        return ResponseEntity.created(URI.create("/api/v1/courses/" + courseId + "/lessons/" + lesson.singleLessonId()))
                .cacheControl(CacheControl.noStore()).body(lesson);
    }

    @PatchMapping("/courses/{courseId}/lessons/{lessonId}")
    public ResponseEntity<LessonDto> move(@PathVariable UUID courseId, @PathVariable UUID lessonId, @Valid @RequestBody MoveRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(lessons.move(courseId, lessonId, body.startsAt(), body.endsAt(), body.reason(), Boolean.TRUE.equals(body.confirmConflicts())));
    }

    @PostMapping("/courses/{courseId}/lessons/{lessonId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID courseId, @PathVariable UUID lessonId, @RequestBody CancelRequest body) {
        lessons.cancel(courseId, lessonId, body.reason());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/courses/{courseId}/schedule")
    public ResponseEntity<CalendarService.Calendar> course(@PathVariable UUID courseId, @RequestParam String month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendars.course(courseId, month));
    }

    @GetMapping("/me/schedule")
    public ResponseEntity<CalendarService.Calendar> personal(@RequestParam String month) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(calendars.personal(month));
    }
}
