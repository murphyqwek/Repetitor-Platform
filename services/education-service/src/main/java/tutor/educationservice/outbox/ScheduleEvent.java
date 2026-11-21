package tutor.educationservice.outbox;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ScheduleEvent(UUID eventId, int schemaVersion, String type, Instant occurredAt, UUID courseId,
                            LessonIdentity lesson, Instant oldStartsAt, Instant oldEndsAt, Instant newStartsAt,
                            Instant newEndsAt, LocalDate stopFromDate, String reason) {
    public record LessonIdentity(String kind, UUID singleLessonId, UUID seriesId, LocalDate originalDate) {}
}
