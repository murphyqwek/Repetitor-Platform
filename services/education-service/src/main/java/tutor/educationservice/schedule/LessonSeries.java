package tutor.educationservice.schedule;

import jakarta.persistence.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpStatus;
import tutor.educationservice.common.error.ApiException;

@Entity
@Table(name = "lesson_series")
public class LessonSeries {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "smallint[]")
    private Short[] weekdays;
    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;
    @Column(name = "duration_minutes", nullable = false)
    private short durationMinutes;
    @Column(name = "stop_from_date")
    private LocalDate stopFromDate;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected LessonSeries() {}

    LessonSeries(UUID courseId, LocalDate startDate, List<Integer> weekdays, LocalTime startTime, int durationMinutes, Instant createdAt) {
        if (new HashSet<>(weekdays).size() != weekdays.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_WEEKDAYS", "Weekdays must be unique");
        }
        this.courseId = courseId;
        this.startDate = startDate;
        this.weekdays = weekdays.stream().sorted().map(Integer::shortValue).toArray(Short[]::new);
        this.startTime = startTime.truncatedTo(ChronoUnit.MICROS);
        this.durationMinutes = (short) durationMinutes;
        this.createdAt = createdAt;
    }

    UUID id() { return id; }
    UUID courseId() { return courseId; }
    LocalDate stopFromDate() { return stopFromDate; }
    Instant createdAt() { return createdAt; }
    LocalDate startDate() { return startDate; }
    boolean scheduled(LocalDate date) {
        return !date.isBefore(startDate) && (stopFromDate == null || date.isBefore(stopFromDate))
                && Arrays.asList(weekdays).contains((short) date.getDayOfWeek().getValue());
    }

    void requireOccurrence(LocalDate date) {
        if (date.isBefore(startDate) || !Arrays.asList(weekdays).contains((short) date.getDayOfWeek().getValue())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Occurrence not found");
        }
        if (stopFromDate != null && !date.isBefore(stopFromDate)) {
            throw new ApiException(HttpStatus.CONFLICT, "SERIES_STOPPED", "Series is stopped for this original date");
        }
    }

    boolean stop(LocalDate date) {
        if (stopFromDate != null && !stopFromDate.equals(date)) {
            throw new ApiException(HttpStatus.CONFLICT, "SERIES_ALREADY_STOPPED", "Series is already stopped");
        }
        if (stopFromDate != null) return false;
        stopFromDate = date;
        return true;
    }

    LessonDto occurrence(LocalDate date) {
        Instant start = date.atTime(startTime).atZone(CalendarService.ZONE).toInstant();
        return occurrence(date, start, start.plusSeconds(durationMinutes * 60L), false);
    }

    LessonDto occurrence(LocalDate date, Instant start, Instant end, boolean rescheduled) {
        return new LessonDto(courseId, "RECURRING", null, id, date, start, end, rescheduled);
    }

    SeriesDto dto() {
        return new SeriesDto(id, courseId, startDate, Arrays.stream(weekdays).map(Short::intValue).toList(),
                startTime, durationMinutes, stopFromDate, CalendarService.ZONE.getId());
    }
}
