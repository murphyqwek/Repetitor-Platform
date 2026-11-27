package tutor.educationservice.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record SeriesDto(UUID id, UUID courseId, LocalDate startDate, List<Integer> weekdays, LocalTime startTime,
                        int durationMinutes, LocalDate stopFromDate, String timeZone) {
    public record Page(List<SeriesDto> items, String nextCursor, boolean hasMore) {}
}
