package tutor.educationservice.course;

import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.cleanup.*;

@Service
public class CourseDeletion implements CleanupHandler {
    public record Started(UUID courseId) {}

    private final CourseService courses;
    private final CleanupJobs jobs;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public CourseDeletion(CourseService courses, CleanupJobs jobs, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.courses = courses;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public DeletionReceipt start(UUID id) {
        var course = courses.managedForDeletion(id);
        if (course.deletionState() == Course.DeletionState.DELETING) return new DeletionReceipt(id, jobs.open(type(), id));
        course.beginDeletion();
        var job = jobs.enqueue(type(), id);
        events.publishEvent(new Started(id));
        return new DeletionReceipt(id, job);
    }

    @Override
    public ResourceType type() { return ResourceType.COURSE; }

    @Override
    public void removeObjects(UUID id, Consumer<String> delete) {
        jdbc.queryForList("""
                select a.object_key from chat_attachment a join course c on c.id = a.course_id
                where c.id = ? and c.deletion_state = 'DELETING'
                """, String.class, id).forEach(delete);
    }

    @Override
    public void removeRows(UUID id) {
        if (jdbc.queryForList("select id from course where id = ? and deletion_state = 'DELETING' for update", UUID.class, id).isEmpty()) return;
        jdbc.update("delete from lesson_exception where series_id in (select id from lesson_series where course_id = ?)", id);
        jdbc.update("delete from lesson_series where course_id = ?", id);
        jdbc.update("delete from single_lesson where course_id = ?", id);
        jdbc.update("delete from course_student where course_id = ?", id);
        // Course FKs cascade chat rows and material grants, while the library remains independently owned.
        jdbc.update("delete from course where id = ?", id);
    }
}
