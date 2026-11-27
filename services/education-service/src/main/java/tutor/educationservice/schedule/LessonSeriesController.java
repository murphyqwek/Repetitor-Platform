package tutor.educationservice.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/lesson-series")
public class LessonSeriesController {
    private final LessonSeriesService series;

    public LessonSeriesController(LessonSeriesService series) { this.series = series; }

    public record CreateRequest(@NotNull LocalDate startDate, @NotNull @Size(min = 1, max = 7) List<@NotNull @Min(1) @Max(7) Integer> weekdays,
                                @NotNull LocalTime startTime, @NotNull @Min(5) @Max(300) Integer durationMinutes, Boolean confirmConflicts) {}
    public record StopRequest(@NotNull LocalDate stopFromDate) {}

    @PostMapping
    public ResponseEntity<SeriesDto> create(@PathVariable UUID courseId, @Valid @RequestBody CreateRequest body) {
        var rule = series.create(courseId, body.startDate(), body.weekdays(), body.startTime(), body.durationMinutes(), Boolean.TRUE.equals(body.confirmConflicts()));
        return ResponseEntity.created(URI.create("/api/v1/courses/" + courseId + "/lesson-series/" + rule.id()))
                .cacheControl(CacheControl.noStore()).body(rule);
    }

    @GetMapping
    public ResponseEntity<SeriesDto.Page> list(@PathVariable UUID courseId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(series.list(courseId, limit, cursor));
    }

    @PostMapping("/{seriesId}/stop")
    public ResponseEntity<SeriesDto> stop(@PathVariable UUID courseId, @PathVariable UUID seriesId, @Valid @RequestBody StopRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(series.stop(courseId, seriesId, body.stopFromDate()));
    }

    @PostMapping("/{seriesId}/occurrences/{originalDate}/reschedule")
    public ResponseEntity<LessonDto> move(@PathVariable UUID courseId, @PathVariable UUID seriesId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate originalDate,
            @Valid @RequestBody ScheduleController.MoveRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(series.move(courseId, seriesId, originalDate, body.startsAt(), body.endsAt(), body.reason(), Boolean.TRUE.equals(body.confirmConflicts())));
    }

    @PostMapping("/{seriesId}/occurrences/{originalDate}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID courseId, @PathVariable UUID seriesId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate originalDate,
            @RequestBody ScheduleController.CancelRequest body) {
        series.cancel(courseId, seriesId, originalDate, body.reason());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
