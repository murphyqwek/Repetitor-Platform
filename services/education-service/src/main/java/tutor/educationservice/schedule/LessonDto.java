package tutor.educationservice.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LessonDto(UUID courseId, String kind, UUID singleLessonId, UUID seriesId, LocalDate originalDate,
                        Instant startsAt, Instant endsAt, boolean rescheduled) {}
