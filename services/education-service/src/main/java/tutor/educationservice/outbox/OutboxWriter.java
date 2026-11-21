package tutor.educationservice.outbox;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.schedule.LessonDto;
import tutor.educationservice.schedule.SeriesDto;

@Service
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void moved(LessonDto old, LessonDto current, String reason) {
        append(new ScheduleEvent(UUID.randomUUID(), 1, "LessonRescheduled", clock.instant().truncatedTo(ChronoUnit.MICROS),
                old.courseId(), identity(old), old.startsAt(), old.endsAt(), current.startsAt(), current.endsAt(), null, reason));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(LessonDto old, String reason) {
        append(new ScheduleEvent(UUID.randomUUID(), 1, "LessonCancelled", clock.instant().truncatedTo(ChronoUnit.MICROS),
                old.courseId(), identity(old), old.startsAt(), old.endsAt(), null, null, null, reason));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void stopped(SeriesDto rule) {
        append(new ScheduleEvent(UUID.randomUUID(), 1, "LessonSeriesStopped", clock.instant().truncatedTo(ChronoUnit.MICROS),
                rule.courseId(), new ScheduleEvent.LessonIdentity("RECURRING", null, rule.id(), null),
                null, null, null, null, rule.stopFromDate(), null));
    }

    private ScheduleEvent.LessonIdentity identity(LessonDto lesson) {
        return new ScheduleEvent.LessonIdentity(lesson.kind(), lesson.singleLessonId(), lesson.seriesId(), lesson.originalDate());
    }

    private void append(ScheduleEvent event) {
        jdbc.update("insert into outbox_event(id,aggregate_id,event_type,payload,created_at) values (?,?,?,cast(? as jsonb),?)",
                event.eventId(), event.courseId(), event.type(), mapper.writeValueAsString(event),
                java.time.OffsetDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC));
    }
}
