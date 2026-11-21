package tutor.educationservice.schedule;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.security.CurrentUser;
import tutor.educationservice.security.Role;

@Service
public class CalendarService {
    public static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private final SingleLessonRepository lessons;
    private final CourseService courses;
    private final LessonSeriesRepository series;
    private final LessonExceptionRepository exceptions;

    public CalendarService(SingleLessonRepository lessons, CourseService courses, LessonSeriesRepository series, LessonExceptionRepository exceptions) {
        this.lessons = lessons;
        this.courses = courses;
        this.series = series;
        this.exceptions = exceptions;
    }

    public record Calendar(String month, String timeZone, List<LessonDto> lessons) {}

    @Transactional(readOnly = true)
    public Calendar course(UUID courseId, String month) {
        courses.get(courseId);
        var window = month(month);
        return calendar(month, window, lessons.calendar(courseId, window.startsAt(), window.endsAt()), series.calendar(courseId));
    }

    @Transactional(readOnly = true)
    public Calendar personal(String month) {
        var window = month(month);
        var user = CurrentUser.get();
        boolean teacher = user.roles().contains(Role.TEACHER);
        boolean student = user.roles().contains(Role.STUDENT);
        return calendar(month, window, lessons.personalCalendar(user.id(), teacher, student, window.startsAt(), window.endsAt()),
                series.personalCalendar(user.id(), teacher, student));
    }

    static TimeInterval month(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000-")) throw new DateTimeException("Invalid month");
            var month = YearMonth.parse(value);
            return new TimeInterval(month.atDay(1).atStartOfDay(ZONE).toInstant(),
                    month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MONTH", "month must be YYYY-MM");
        }
    }

    private Calendar calendar(String month, TimeInterval window, List<SingleLesson> rows, List<LessonSeries> rules) {
        var items = new ArrayList<>(rows.stream().map(SingleLesson::dto).toList());
        if (!rules.isEmpty()) {
            var changes = exceptions.calendar(rules.stream().map(LessonSeries::id).toList(), RecurringCalendar.fromDate(window),
                    RecurringCalendar.toDate(window), window.startsAt(), window.endsAt());
            items.addAll(RecurringCalendar.expand(rules, changes, window));
        }
        items.sort(Comparator.comparing(LessonDto::startsAt)
                .thenComparing(lesson -> (lesson.singleLessonId() == null ? lesson.seriesId() : lesson.singleLessonId()).toString())
                .thenComparing(LessonDto::originalDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new Calendar(month, ZONE.getId(), List.copyOf(items));
    }
}
