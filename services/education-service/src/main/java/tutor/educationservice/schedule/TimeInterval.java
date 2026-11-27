package tutor.educationservice.schedule;

import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import tutor.educationservice.common.error.ApiException;

public record TimeInterval(Instant startsAt, Instant endsAt) {
    public boolean overlaps(Instant start, Instant end) { return start.isBefore(endsAt) && end.isAfter(startsAt); }

    public static TimeInterval lesson(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        var start = startsAt.toInstant();
        var end = endsAt.toInstant();
        var duration = Duration.between(start, end);
        if (duration.compareTo(Duration.ofMinutes(5)) < 0 || duration.compareTo(Duration.ofMinutes(300)) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_LESSON_TIME", "Lesson duration must be between 5 and 300 minutes");
        }
        return new TimeInterval(start.truncatedTo(ChronoUnit.MICROS), end.truncatedTo(ChronoUnit.MICROS));
    }
}
