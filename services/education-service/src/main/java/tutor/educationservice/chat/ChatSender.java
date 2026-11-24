package tutor.educationservice.chat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.CursorProperties;
import tutor.educationservice.security.CurrentUser;

@Service
class ChatSender {
    record Command(UUID clientMessageId, String text, List<UUID> attachmentIds) {}
    record Result(UUID messageId, ChatMessage created) {}
    private record Content(String text, List<UUID> attachmentIds) {}
    private record Receipt(UUID messageId, String hash) {}

    private final ChatAccess access;
    private final ChatAttachments attachments;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecretKeySpec key;

    ChatSender(ChatAccess access, ChatAttachments attachments, JdbcTemplate jdbc, ObjectMapper mapper, CursorProperties properties) {
        this.access = access;
        this.attachments = attachments;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.key()), "HmacSHA256");
    }

    @Transactional
    public Result send(UUID course, Command command) {
        var role = access.writer(course);
        var user = CurrentUser.get();
        Content content = content(command);
        UUID clientId = command.clientMessageId();
        String hash = hash(content);
        var receipts = jdbc.query("""
                select message_id, request_hash from chat_send_receipt where course_id = ? and author_id = ? and client_message_id = ?
                """, (row, index) -> new Receipt(row.getObject(1, UUID.class), row.getString(2)), course, user.id(), clientId);
        if (!receipts.isEmpty()) {
            var receipt = receipts.get(0);
            if (!receipt.hash().equals(hash)) throw conflict("CLIENT_MESSAGE_ID_CONFLICT", "Client ID has a different payload");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from chat_message where id = ? and course_id = ? and state = 'ACTIVE')
                    """, Boolean.class, receipt.messageId(), course))) {
                throw conflict("MESSAGE_ALREADY_DELETED", "Message has been deleted");
            }
            return new Result(receipt.messageId(), null);
        }
        UUID id = UUID.randomUUID();
        var message = jdbc.queryForObject("""
                insert into chat_message(id, course_id, author_id, author_role, author_display_name, body, client_message_id)
                values (?, ?, ?, ?, ?, ?, ?) returning *
                """, (row, index) -> ChatHistory.message(row), id, course, user.id(), role.name(), user.displayName(), content.text(), clientId);
        if (!content.attachmentIds().isEmpty()) attach(course, user.id(), id, content.attachmentIds());
        jdbc.update("""
                insert into chat_send_receipt(course_id, author_id, client_message_id, message_id, request_hash) values (?, ?, ?, ?, ?)
                """, course, user.id(), clientId, id, hash);
        return new Result(id, attachments.populate(course, List.of(message)).get(0));
    }

    private void attach(UUID course, UUID user, UUID message, List<UUID> ids) {
        for (UUID id : ids) {
            if (jdbc.update("""
                    update chat_attachment set upload_state = 'ATTACHED', message_id = ?, expires_at = null
                    where id = ? and course_id = ? and uploader_id = ? and upload_state = 'READY' and expires_at > clock_timestamp()
                    """, message, id, course, user) != 1) throw invalidAttachment();
        }
        long size = jdbc.queryForObject("select sum(size_bytes) from chat_attachment where message_id = ?", Long.class, message);
        if (size > 50_000_000) throw invalidAttachment();
    }

    private static Content content(Command command) {
        if (command == null || command.clientMessageId() == null) throw invalidMessage();
        String text = command.text();
        if (text != null && (text.codePointCount(0, text.length()) > 4000 || text.indexOf(0) >= 0)) throw invalidMessage();
        if (text != null && text.isBlank()) text = null;
        List<UUID> files = command.attachmentIds() == null ? List.of() : command.attachmentIds();
        if (files.size() > 5 || files.stream().anyMatch(Objects::isNull) || new HashSet<>(files).size() != files.size()) {
            throw invalidAttachment();
        }
        if (text == null && files.isEmpty()) throw invalidMessage();
        return new Content(text, List.copyOf(files));
    }

    private String hash(Content content) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update("education.chat.send.v1\n".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(mapper.writeValueAsBytes(content)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot hash chat command", exception);
        }
    }

    private static ApiException conflict(String code, String detail) { return new ApiException(HttpStatus.CONFLICT, code, detail); }
    private static ApiException invalidMessage() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", "Invalid chat message"); }
    private static ApiException invalidAttachment() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT", "Invalid chat attachment"); }
}
