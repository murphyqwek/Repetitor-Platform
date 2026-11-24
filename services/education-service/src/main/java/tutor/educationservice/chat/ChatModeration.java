package tutor.educationservice.chat;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.cleanup.*;
import tutor.educationservice.cleanup.CleanupHandler.ResourceType;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.security.*;

@Service
class ChatModeration {
    record Deleted(String type, UUID courseId, UUID messageId) {}
    private record Message(UUID author, Role role, String state) {}

    private final CourseService courses;
    private final JdbcTemplate jdbc;
    private final CleanupJobs jobs;
    private final ApplicationEventPublisher events;

    ChatModeration(CourseService courses, JdbcTemplate jdbc, CleanupJobs jobs, ApplicationEventPublisher events) {
        this.courses = courses;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.events = events;
    }

    @Transactional
    public DeletionReceipt delete(UUID course, UUID message) {
        courses.managedForUpdate(course);
        var rows = jdbc.query("""
                select author_id, author_role, state from chat_message where id = ? and course_id = ? for update
                """, (row, index) -> new Message(row.getObject(1, UUID.class), Role.valueOf(row.getString(2)), row.getString(3)), message, course);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Chat message not found");
        var row = rows.get(0);
        var user = CurrentUser.get();
        if (!user.roles().contains(Role.ADMIN) && row.role() != Role.STUDENT && !user.id().equals(row.author())) {
            throw new AccessDeniedException("Cannot delete this message");
        }
        if (row.state().equals("DELETING")) {
            return new DeletionReceipt(message, jobs.open(ResourceType.CHAT_MESSAGE, message));
        }
        boolean hasFiles = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from chat_attachment where message_id = ? and course_id = ?)
                """, Boolean.class, message, course));
        DeletionReceipt receipt = null;
        if (hasFiles) {
            jdbc.update("update chat_message set state = 'DELETING' where id = ?", message);
            receipt = new DeletionReceipt(message, jobs.enqueue(ResourceType.CHAT_MESSAGE, message));
        } else jdbc.update("delete from chat_message where id = ?", message);
        events.publishEvent(new Deleted("MESSAGE_DELETED", course, message));
        return receipt;
    }
}
