package tutor.educationservice.schedule;

import java.time.*;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CalendarTimeTest {
    @Test
    void monthBoundsUseMoscowIndependentlyOfJvmTimezone() {
        var original = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var december = CalendarService.month("2026-12");
                assertThat(december.startsAt()).isEqualTo(Instant.parse("2026-11-30T21:00:00Z"));
                assertThat(december.endsAt()).isEqualTo(Instant.parse("2026-12-31T21:00:00Z"));
                var february = CalendarService.month("2024-02");
                assertThat(Duration.between(february.startsAt(), february.endsAt())).isEqualTo(Duration.ofDays(29));
                var ordinary = CalendarService.month("2026-02");
                assertThat(Duration.between(ordinary.startsAt(), ordinary.endsAt())).isEqualTo(Duration.ofDays(28));
                assertThat(CalendarService.month("2026-12").endsAt()).isEqualTo(CalendarService.month("2027-01").startsAt());
            }
        } finally { TimeZone.setDefault(original); }
    }
}
