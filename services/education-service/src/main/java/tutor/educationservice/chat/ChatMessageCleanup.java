package tutor.educationservice.chat;

import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tutor.educationservice.cleanup.CleanupHandler;

@Component
class ChatMessageCleanup implements CleanupHandler {
    private final JdbcTemplate jdbc;
    ChatMessageCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResourceType type() { return ResourceType.CHAT_MESSAGE; }

    @Override
    public void removeObjects(UUID id, Consumer<String> delete) {
        jdbc.queryForList("""
                select a.object_key from chat_attachment a join chat_message m on m.id = a.message_id
                where m.id = ? and m.state = 'DELETING'
                """, String.class, id).forEach(delete);
    }

    @Override
    public void removeRows(UUID id) {
        jdbc.update("delete from chat_message where id = ? and state = 'DELETING'", id);
    }
}
