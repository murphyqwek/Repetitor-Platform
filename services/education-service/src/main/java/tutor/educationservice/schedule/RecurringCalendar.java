package tutor.educationservice.schedule;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

final class RecurringCalendar {
    private record Key(UUID seriesId, LocalDate originalDate) {}

    private RecurringCalendar() {}

    // A lesson lasts at most five hours, so only the previous day can cross into this month.
    static LocalDate fromDate(TimeInterval window) { return window.startsAt().atZone(CalendarService.ZONE).toLocalDate().minusDays(1); }
    static LocalDate toDate(TimeInterval window) {
        var end = window.endsAt().atZone(CalendarService.ZONE);
        return end.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? end.toLocalDate() : end.toLocalDate().plusDays(1);
    }

    static List<LessonDto> expand(List<LessonSeries> rules, List<LessonException> exceptions, TimeInterval window) {
        var changes = exceptions.stream().collect(Collectors.toMap(row -> new Key(row.seriesId(), row.originalDate()), row -> row));
        var byId = rules.stream().collect(Collectors.toMap(LessonSeries::id, rule -> rule));
        var result = new ArrayList<LessonDto>();
        LocalDate from = fromDate(window);
        LocalDate to = toDate(window);
        for (var rule : rules) {
            LocalDate first = rule.startDate().isAfter(from) ? rule.startDate() : from;
            for (LocalDate date = first; date.isBefore(to); date = date.plusDays(1)) {
                if (!rule.scheduled(date) || changes.containsKey(new Key(rule.id(), date))) continue;
                var occurrence = rule.occurrence(date);
                if (window.overlaps(occurrence.startsAt(), occurrence.endsAt())) result.add(occurrence);
            }
        }
        for (var exception : exceptions) {
            var rule = byId.get(exception.seriesId());
            if (!exception.rescheduled() || !rule.scheduled(exception.originalDate())) continue;
            var occurrence = exception.dto(rule);
            if (window.overlaps(occurrence.startsAt(), occurrence.endsAt())) result.add(occurrence);
        }
        return result;
    }
}
