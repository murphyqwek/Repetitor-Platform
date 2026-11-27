package tutor.educationservice.schedule;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.outbox.OutboxWriter;

@Service
public class SingleLessonService {
    private final SingleLessonRepository lessons;
    private final CourseService courses;
    private final Clock clock;
    private final ScheduleConflictService conflicts;
    private final OutboxWriter outbox;

    public SingleLessonService(SingleLessonRepository lessons, CourseService courses, Clock clock, ScheduleConflictService conflicts, OutboxWriter outbox) {
        this.lessons = lessons;
        this.courses = courses;
        this.clock = clock;
        this.conflicts = conflicts;
        this.outbox = outbox;
    }

    @Transactional
    public LessonDto create(UUID courseId, OffsetDateTime startsAt, OffsetDateTime endsAt, boolean confirmed) {
        courses.managedForUpdate(courseId);
        var interval = TimeInterval.lesson(startsAt, endsAt);
        conflicts.lesson(courseId, interval, null, confirmed);
        return lessons.saveAndFlush(new SingleLesson(courseId, interval, clock.instant().truncatedTo(ChronoUnit.MICROS))).dto();
    }

    @Transactional
    public LessonDto move(UUID courseId, UUID lessonId, OffsetDateTime startsAt, OffsetDateTime endsAt, String reason, boolean confirmed) {
        courses.managedForUpdate(courseId);
        var lesson = find(courseId, lessonId);
        var old = lesson.dto();
        var interval = TimeInterval.lesson(startsAt, endsAt);
        conflicts.lesson(courseId, interval, old, confirmed);
        lesson.move(interval);
        outbox.moved(old, lesson.dto(), reason);
        return lesson.dto();
    }

    @Transactional
    public void cancel(UUID courseId, UUID lessonId, String reason) {
        courses.managedForUpdate(courseId);
        var lesson = find(courseId, lessonId);
        outbox.cancelled(lesson.dto(), reason);
        lessons.delete(lesson);
    }

    private SingleLesson find(UUID courseId, UUID lessonId) {
        return lessons.findByIdAndCourseId(lessonId, courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lesson not found"));
    }
}
