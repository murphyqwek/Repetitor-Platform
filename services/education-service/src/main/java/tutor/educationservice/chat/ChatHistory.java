package tutor.educationservice.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.common.pagination.CursorCodec;
import tutor.educationservice.course.Course.ChatMode;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.security.Role;

@Service
class ChatHistory {
    record Settings(ChatMode mode) {}
    record Page(List<ChatMessage> messages, String nextCursor, boolean hasMore) {}

    private final CourseService courses;
    private final JdbcTemplate jdbc;
    private final CursorCodec cursors;
    private final ChatAttachments attachments;

    ChatHistory(CourseService courses, JdbcTemplate jdbc, CursorCodec cursors, ChatAttachments attachments) {
        this.courses = courses;
        this.jdbc = jdbc;
        this.cursors = cursors;
        this.attachments = attachments;
    }

    public Settings settings(UUID course) { return new Settings(courses.get(course).chatMode()); }

    @Transactional
    public Settings changeMode(UUID course, ChatMode mode) {
        courses.managedForUpdate(course).changeChatMode(mode);
        return new Settings(mode);
    }

    @Transactional(readOnly = true)
    public Page messages(UUID course, Integer requestedLimit, String before) {
        courses.get(course);
        int limit = CursorCodec.limit(requestedLimit, 50);
        String binding = "chat:v1:" + course;
        var position = before == null ? null : cursors.decode(before, binding);
        var time = position == null ? null : OffsetDateTime.ofInstant(position.createdAt(), ZoneOffset.UTC);
        var rows = jdbc.query("""
                select * from chat_message where course_id = ? and state = 'ACTIVE'
                and (?::timestamptz is null or (created_at, id) < (?, ?))
                order by created_at desc, id desc limit ?
                """, (row, index) -> message(row), course, time, time, position == null ? null : position.id(), limit + 1);
        boolean more = rows.size() > limit;
        var messages = rows.subList(0, Math.min(limit, rows.size()));
        var last = more ? messages.get(messages.size() - 1) : null;
        String next = last == null ? null : cursors.encode(new CursorCodec.Position(last.createdAt(), last.id()), binding);
        return new Page(attachments.populate(course, messages), next, more);
    }

    static ChatMessage message(ResultSet row) throws SQLException {
        var author = new ChatMessage.Author(row.getObject("author_id", UUID.class), row.getString("author_display_name"),
                Role.valueOf(row.getString("author_role")));
        return new ChatMessage(row.getObject("id", UUID.class), author, row.getString("body"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), List.of());
    }
}
