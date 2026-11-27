package tutor.educationservice.schedule;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.*;
import tutor.educationservice.schedule.ScheduleConflictException.Conflict;

@Service
public class ScheduleConflictService {
    private record Subject(String type, UUID id) {}
    private record Snapshot(List<LessonDto> singles, List<LessonSeries> rules, List<LessonException> changes) {
        List<LessonDto> window(Set<UUID> scope, TimeInterval window, LessonDto excluded) {
            var selected = rules.stream().filter(rule -> scope.contains(rule.courseId())).toList();
            var ids = selected.stream().map(LessonSeries::id).collect(java.util.stream.Collectors.toSet());
            var exceptions = changes.stream().filter(change -> ids.contains(change.seriesId())).toList();
            var result = new ArrayList<>(singles.stream().filter(lesson -> scope.contains(lesson.courseId())
                    && window.overlaps(lesson.startsAt(), lesson.endsAt())).toList());
            result.addAll(RecurringCalendar.expand(selected, exceptions, window));
            return result.stream().filter(lesson -> !same(lesson, excluded)).toList();
        }
    }

    private final CourseRepository courses;
    private final CourseStudentRepository students;
    private final SingleLessonRepository singles;
    private final LessonSeriesRepository series;
    private final LessonExceptionRepository exceptions;
    private final Clock clock;

    public ScheduleConflictService(CourseRepository courses, CourseStudentRepository students, SingleLessonRepository singles,
            LessonSeriesRepository series, LessonExceptionRepository exceptions, Clock clock) {
        this.courses = courses;
        this.students = students;
        this.singles = singles;
        this.series = series;
        this.exceptions = exceptions;
        this.clock = clock;
    }

    public void lesson(UUID courseId, TimeInterval interval, LessonDto excluded, boolean confirmed) {
        check(courseId, List.of(new LessonDto(courseId, "SINGLE", null, null, null, interval.startsAt(), interval.endsAt(), false)), excluded, confirmed);
    }

    void series(UUID courseId, LessonSeries rule, boolean confirmed) {
        LocalDate today = clock.instant().atZone(CalendarService.ZONE).toLocalDate();
        LocalDate first = rule.startDate().isAfter(today) ? rule.startDate() : today;
        var candidates = new ArrayList<LessonDto>();
        for (LocalDate date = first; date.isBefore(first.plusDays(30)); date = date.plusDays(1)) {
            if (rule.scheduled(date)) {
                var candidate = rule.occurrence(date);
                if (candidate.endsAt().isAfter(clock.instant())) candidates.add(candidate);
            }
        }
        check(courseId, candidates, null, confirmed);
    }

    public void enrollment(UUID courseId, UUID studentId, boolean confirmed) {
        var other = new HashSet<>(courses.participantCourses(studentId));
        other.remove(courseId);
        if (other.isEmpty()) return;
        var all = new HashSet<>(other);
        all.add(courseId);
        var snapshot = snapshot(all);
        var dates = new TreeSet<LocalDate>();
        Instant now = clock.instant();
        LocalDate today = now.atZone(CalendarService.ZONE).toLocalDate();
        dates.add(today);
        for (var rule : snapshot.rules()) {
            dates.add(rule.startDate());
            if (rule.stopFromDate() != null) dates.add(rule.stopFromDate());
        }
        for (var change : snapshot.changes()) {
            dates.add(change.originalDate());
            dates.add(change.originalDate().plusDays(1));
        }
        var fixed = new ArrayList<>(snapshot.singles());
        for (var change : snapshot.changes()) {
            if (!change.rescheduled()) continue;
            var rule = snapshot.rules().stream().filter(item -> item.id().equals(change.seriesId())).findFirst().orElseThrow();
            fixed.add(change.dto(rule));
        }
        for (var lesson : fixed) dates.add(lesson.startsAt().atZone(CalendarService.ZONE).toLocalDate());
        var found = new LinkedHashSet<Conflict>();
        // Between finite rule/exception boundaries the future schedule repeats weekly: one full week is sufficient.
        for (LocalDate date : dates.tailSet(today, true)) {
            Instant start = date.minusDays(1).atStartOfDay(CalendarService.ZONE).toInstant();
            if (start.isBefore(now)) start = now;
            var window = new TimeInterval(start, date.plusDays(8).atStartOfDay(CalendarService.ZONE).toInstant());
            var target = snapshot.window(Set.of(courseId), window, null);
            var existing = snapshot.window(other, window, null);
            compare(new Subject("STUDENT", studentId), target, existing, found, now);
        }
        requireConfirmation(found, confirmed);
    }

    private void check(UUID courseId, List<LessonDto> candidates, LessonDto excluded, boolean confirmed) {
        var owner = courses.findByIdAndDeletionState(courseId, Course.DeletionState.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Course not found"));
        var scopes = new LinkedHashMap<Subject, Set<UUID>>();
        scopes.put(new Subject("TEACHER", owner.teacherId()), new HashSet<>(courses.participantCourses(owner.teacherId())));
        for (UUID id : students.studentIds(courseId)) {
            if (!id.equals(owner.teacherId())) scopes.put(new Subject("STUDENT", id), new HashSet<>(courses.participantCourses(id)));
        }
        var all = new HashSet<UUID>();
        scopes.values().forEach(all::addAll);
        var snapshot = snapshot(all);
        var found = new LinkedHashSet<Conflict>();
        for (var entry : scopes.entrySet()) {
            for (var candidate : candidates) {
                var window = new TimeInterval(candidate.startsAt(), candidate.endsAt());
                compare(entry.getKey(), List.of(candidate), snapshot.window(entry.getValue(), window, excluded), found, null);
            }
        }
        requireConfirmation(found, confirmed);
    }

    private Snapshot snapshot(Set<UUID> ids) {
        if (ids.isEmpty()) return new Snapshot(List.of(), List.of(), List.of());
        var rules = series.findByCourseIdIn(ids);
        var changes = rules.isEmpty() ? List.<LessonException>of() : exceptions.findBySeriesIdIn(rules.stream().map(LessonSeries::id).toList());
        return new Snapshot(singles.findByCourseIdIn(ids).stream().map(SingleLesson::dto).toList(), rules, changes);
    }

    private static void compare(Subject subject, List<LessonDto> candidates, List<LessonDto> existing, Set<Conflict> found, Instant earliest) {
        for (var candidate : candidates) {
            for (var other : existing) {
                Instant start = candidate.startsAt().isAfter(other.startsAt()) ? candidate.startsAt() : other.startsAt();
                Instant end = candidate.endsAt().isBefore(other.endsAt()) ? candidate.endsAt() : other.endsAt();
                if (earliest != null && start.isBefore(earliest)) start = earliest;
                if (start.isBefore(end)) found.add(new Conflict(subject.type(), subject.id(), start, end));
            }
        }
    }

    private static boolean same(LessonDto lesson, LessonDto excluded) {
        return excluded != null && lesson.kind().equals(excluded.kind()) && Objects.equals(lesson.singleLessonId(), excluded.singleLessonId())
                && Objects.equals(lesson.seriesId(), excluded.seriesId()) && Objects.equals(lesson.originalDate(), excluded.originalDate());
    }

    private static void requireConfirmation(Set<Conflict> found, boolean confirmed) {
        if (!confirmed && !found.isEmpty()) throw new ScheduleConflictException(found.stream()
                .sorted(Comparator.comparing(Conflict::subjectType).thenComparing(item -> item.subjectId().toString())
                        .thenComparing(Conflict::startsAt).thenComparing(Conflict::endsAt)).toList());
    }
}
