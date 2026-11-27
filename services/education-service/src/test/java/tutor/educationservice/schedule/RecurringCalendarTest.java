package tutor.educationservice.schedule;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class RecurringCalendarTest {
    @Test
    void virtualAndMovedOccurrencesUseMoscowRegardlessOfJvmTimezone() {
        var original = TimeZone.getDefault();
        UUID id = UUID.randomUUID();
        var rule = new LessonSeries(UUID.randomUUID(), LocalDate.parse("2026-12-01"), List.of(2), LocalTime.of(17, 0), 90, Instant.EPOCH);
        ReflectionTestUtils.setField(rule, "id", id);
        var moved = new LessonException(id, LocalDate.parse("2026-12-15"), Instant.EPOCH);
        moved.move(TimeInterval.lesson(OffsetDateTime.parse("2026-12-25T17:00:00+03:00"), OffsetDateTime.parse("2026-12-25T18:30:00+03:00")), null);
        rule.stop(LocalDate.parse("2026-12-20"));
        try {
            for (String zone : List.of("UTC", "America/Los_Angeles", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var occurrences = RecurringCalendar.expand(List.of(rule), List.of(moved), CalendarService.month("2026-12"));
                assertThat(occurrences).hasSize(3);
                assertThat(occurrences.get(0).startsAt()).isEqualTo(Instant.parse("2026-12-01T14:00:00Z"));
                assertThat(occurrences.get(2).originalDate()).isEqualTo(LocalDate.parse("2026-12-15"));
                assertThat(occurrences.get(2).startsAt()).isEqualTo(Instant.parse("2026-12-25T14:00:00Z"));
                assertThat(occurrences.get(2).endsAt()).isEqualTo(Instant.parse("2026-12-25T15:30:00Z"));
            }
        } finally { TimeZone.setDefault(original); }
    }
}
