package tutor.educationservice.schedule;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.CursorCodec;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.security.CurrentUser;
import tutor.educationservice.outbox.OutboxWriter;

@Service
public class LessonSeriesService {
    private final LessonSeriesRepository series;
    private final LessonExceptionRepository exceptions;
    private final CourseService courses;
    private final Clock clock;
    private final CursorCodec cursors;
    private final ScheduleConflictService conflicts;
    private final OutboxWriter outbox;

    public LessonSeriesService(LessonSeriesRepository series, LessonExceptionRepository exceptions, CourseService courses,
            Clock clock, CursorCodec cursors, ScheduleConflictService conflicts, OutboxWriter outbox) {
        this.series = series;
        this.exceptions = exceptions;
        this.courses = courses;
        this.clock = clock;
        this.cursors = cursors;
        this.conflicts = conflicts;
        this.outbox = outbox;
    }

    @Transactional
    public SeriesDto create(UUID courseId, LocalDate startDate, List<Integer> weekdays, LocalTime startTime, int durationMinutes, boolean confirmed) {
        courses.managedForUpdate(courseId);
        var rule = new LessonSeries(courseId, startDate, weekdays, startTime, durationMinutes, now());
        conflicts.series(courseId, rule, confirmed);
        return series.saveAndFlush(rule).dto();
    }

    @Transactional(readOnly = true)
    public SeriesDto.Page list(UUID courseId, Integer requestedLimit, String cursor) {
        courses.managed(courseId);
        int limit = CursorCodec.limit(requestedLimit);
        String binding = "lesson-series:v1:" + courseId + ":" + CurrentUser.get().id() + ":createdAtDesc,idDesc";
        var after = cursor == null ? null : cursors.decode(cursor, binding);
        var page = series.page(courseId, after == null ? null : after.createdAt(), after == null ? null : after.id(), limit + 1);
        boolean hasMore = page.size() > limit;
        var rows = page.subList(0, Math.min(limit, page.size()));
        String next = null;
        if (hasMore) {
            var last = rows.get(rows.size() - 1);
            next = cursors.encode(new CursorCodec.Position(last.createdAt(), last.id()), binding);
        }
        return new SeriesDto.Page(rows.stream().map(LessonSeries::dto).toList(), next, hasMore);
    }

    @Transactional
    public SeriesDto stop(UUID courseId, UUID seriesId, LocalDate date) {
        courses.managedForUpdate(courseId);
        var rule = find(courseId, seriesId);
        if (rule.stop(date)) outbox.stopped(rule.dto());
        return rule.dto();
    }

    @Transactional
    public LessonDto move(UUID courseId, UUID seriesId, LocalDate originalDate, OffsetDateTime start, OffsetDateTime end, String reason, boolean confirmed) {
        courses.managedForUpdate(courseId);
        var rule = find(courseId, seriesId);
        rule.requireOccurrence(originalDate);
        var exception = exception(seriesId, originalDate);
        var old = exception.rescheduled() ? exception.dto(rule) : rule.occurrence(originalDate);
        var interval = TimeInterval.lesson(start, end);
        exception.requireMovable();
        conflicts.lesson(courseId, interval, old, confirmed);
        exception.move(interval, reason);
        var current = exceptions.saveAndFlush(exception).dto(rule);
        outbox.moved(old, current, reason);
        return current;
    }

    @Transactional
    public void cancel(UUID courseId, UUID seriesId, LocalDate originalDate, String reason) {
        courses.managedForUpdate(courseId);
        var rule = find(courseId, seriesId);
        rule.requireOccurrence(originalDate);
        var exception = exception(seriesId, originalDate);
        var old = exception.rescheduled() ? exception.dto(rule) : rule.occurrence(originalDate);
        if (exception.cancel(reason)) outbox.cancelled(old, reason);
        exceptions.saveAndFlush(exception);
    }

    private LessonSeries find(UUID courseId, UUID seriesId) {
        return series.findByIdAndCourseId(seriesId, courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Series not found"));
    }

    private LessonException exception(UUID seriesId, LocalDate date) {
        return exceptions.findBySeriesIdAndOriginalDate(seriesId, date).orElseGet(() -> new LessonException(seriesId, date, now()));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
