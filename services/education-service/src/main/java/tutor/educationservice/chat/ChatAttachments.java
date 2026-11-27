package tutor.educationservice.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tutor.educationservice.cleanup.*;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.integration.storage.*;
import tutor.educationservice.security.CurrentUser;

@Service
class ChatAttachments implements CleanupHandler {
    record Ready(UUID attachmentId, String fileName, String contentType, long sizeBytes, String state, Instant expiresAt) {}
    record Stored(UUID id, UUID message, String key, String name, String type, long size) {}

    private final JdbcTemplate jdbc;
    private final ChatAccess access;
    private final CourseService courses;
    private final CleanupJobs jobs;
    private final PendingUploads uploads;
    private final TransactionTemplate transaction;

    ChatAttachments(JdbcTemplate jdbc, ChatAccess access, CourseService courses, CleanupJobs jobs,
            PendingUploads uploads, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.access = access;
        this.courses = courses;
        this.jobs = jobs;
        this.uploads = uploads;
        this.transaction = new TransactionTemplate(manager);
    }

    Ready upload(UUID course, MultipartFile file) {
        var format = ChatFileFormat.check(file);
        UUID id = UUID.randomUUID();
        String key = ObjectKeys.chat(course);
        transaction.executeWithoutResult(status -> {
            access.writer(course);
            jdbc.update("""
                    insert into chat_attachment(id, course_id, uploader_id, object_key, original_name, mime_type, size_bytes, upload_state, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'UPLOADING', clock_timestamp() + interval '24 hours')
                    """, id, course, CurrentUser.get().id(), key, format.name(), format.contentType(), file.getSize());
            jobs.enqueue(type(), id, Duration.ofHours(24));
        });
        return uploads.store(key, file, format.contentType(), () -> {
            access.writer(course);
            var ready = jdbc.query("""
                    update chat_attachment set upload_state = 'READY'
                    where id = ? and upload_state = 'UPLOADING' and expires_at > clock_timestamp()
                    returning expires_at
                    """, (row, index) -> new Ready(id, format.name(), format.contentType(), file.getSize(), "READY",
                    row.getObject(1, OffsetDateTime.class).toInstant()), id);
            if (ready.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED", "Upload expired");
            return ready.get(0);
        });
    }

    @Transactional(readOnly = true)
    public Stored download(UUID course, UUID message, UUID id) {
        courses.get(course);
        return jdbc.query("""
                select a.* from chat_attachment a join chat_message m on m.id = a.message_id and m.course_id = a.course_id
                where a.id = ? and a.course_id = ? and a.message_id = ? and a.upload_state = 'ATTACHED' and m.state = 'ACTIVE'
                """, (row, index) -> stored(row), id, course, message).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Attachment not found"));
    }

    List<ChatMessage> populate(UUID course, List<ChatMessage> messages) {
        if (messages.isEmpty()) return messages;
        var ids = messages.stream().map(ChatMessage::id).toArray();
        var files = jdbc.query("""
                select * from chat_attachment where course_id = ? and message_id = any (?) and upload_state = 'ATTACHED'
                order by created_at, id
                """, statement -> {
                    statement.setObject(1, course);
                    statement.setArray(2, statement.getConnection().createArrayOf("uuid", ids));
                }, (row, index) -> stored(row));
        var byMessage = new HashMap<UUID, List<ChatMessage.Attachment>>();
        for (var file : files) {
            String url = "/api/v1/courses/" + course + "/chat/messages/" + file.message() + "/attachments/" + file.id() + "/content";
            byMessage.computeIfAbsent(file.message(), id -> new ArrayList<>()).add(new ChatMessage.Attachment(
                    file.id(), file.name(), file.type(), file.size(), file.type().startsWith("image/") ? "IMAGE" : "FILE", url));
        }
        return messages.stream().map(message -> new ChatMessage(message.id(), message.author(), message.text(), message.createdAt(),
                byMessage.getOrDefault(message.id(), List.of()))).toList();
    }

    @Override
    public ResourceType type() { return ResourceType.CHAT_ATTACHMENT; }

    @Override
    public void removeObjects(UUID id, Consumer<String> delete) {
        jdbc.queryForList("""
                update chat_attachment set upload_state = 'DELETING'
                where id = ? and message_id is null and expires_at <= clock_timestamp() returning object_key
                """, String.class, id).forEach(delete);
    }

    @Override
    public void removeRows(UUID id) {
        jdbc.update("delete from chat_attachment where id = ? and upload_state = 'DELETING'", id);
    }

    private static Stored stored(ResultSet row) throws SQLException {
        return new Stored(row.getObject("id", UUID.class), row.getObject("message_id", UUID.class), row.getString("object_key"),
                row.getString("original_name"), row.getString("mime_type"), row.getLong("size_bytes"));
    }
}
