package tutor.educationservice.schedule;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tutor.educationservice.common.error.ApiException;

@Entity
@Table(name = "lesson_exception")
public class LessonException {
    public enum Kind { RESCHEDULED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "series_id", nullable = false, updatable = false)
    private UUID seriesId;
    @Column(name = "original_date", nullable = false, updatable = false)
    private LocalDate originalDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Kind kind;
    @Column(name = "new_starts_at", columnDefinition = "timestamptz")
    private Instant newStartsAt;
    @Column(name = "new_ends_at", columnDefinition = "timestamptz")
    private Instant newEndsAt;
    @Column(columnDefinition = "text")
    private String reason;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected LessonException() {}

    LessonException(UUID seriesId, LocalDate originalDate, Instant createdAt) {
        this.seriesId = seriesId;
        this.originalDate = originalDate;
        this.createdAt = createdAt;
    }

    UUID seriesId() { return seriesId; }
    LocalDate originalDate() { return originalDate; }
    boolean rescheduled() { return kind == Kind.RESCHEDULED; }

    void requireMovable() {
        if (kind == Kind.CANCELLED) throw new ApiException(HttpStatus.CONFLICT, "LESSON_CANCELLED", "Cancelled occurrence cannot be moved");
    }

    void move(TimeInterval interval, String reason) {
        requireMovable();
        kind = Kind.RESCHEDULED;
        newStartsAt = interval.startsAt();
        newEndsAt = interval.endsAt();
        this.reason = reason;
    }

    boolean cancel(String reason) {
        if (kind == Kind.CANCELLED) return false;
        kind = Kind.CANCELLED;
        newStartsAt = null;
        newEndsAt = null;
        this.reason = reason;
        return true;
    }

    LessonDto dto(LessonSeries series) { return series.occurrence(originalDate, newStartsAt, newEndsAt, true); }
}
