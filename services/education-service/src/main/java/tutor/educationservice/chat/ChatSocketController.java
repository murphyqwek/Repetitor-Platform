package tutor.educationservice.chat;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.common.error.ApiException;

@Controller
class ChatSocketController {
    private static final Logger log = LoggerFactory.getLogger(ChatSocketController.class);
    record Accepted(String type, UUID courseId, UUID clientMessageId, UUID messageId) {}
    record Rejected(String type, UUID courseId, UUID clientMessageId, String code, String message) {}
    record Created(String type, UUID courseId, ChatMessage message) {}

    private final ChatSocketSessions sessions;
    private final ChatSender sender;
    private final ChatEvents events;
    private final ObjectMapper mapper;

    ChatSocketController(ChatSocketSessions sessions, ChatSender sender, ChatEvents events, ObjectMapper mapper) {
        this.sessions = sessions;
        this.sender = sender;
        this.events = events;
        this.mapper = mapper;
    }

    @MessageMapping("/courses/{courseId}/chat.send")
    public void send(@DestinationVariable UUID courseId, @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String session,
            @Payload(required = false) byte[] payload) {
        ChatSender.Command command;
        try {
            command = mapper.readValue(payload, ChatSender.Command.class);
        } catch (RuntimeException exception) {
            reject(session, courseId, null, "INVALID_MESSAGE", "Invalid chat message");
            return;
        }
        UUID clientId = command == null ? null : command.clientMessageId();
        ChatSender.Result result;
        try {
            result = sessions.asUser(session, () -> sender.send(courseId, command));
        } catch (ApiException exception) {
            String code = exception.status() == HttpStatus.NOT_FOUND ? "COURSE_NOT_FOUND" : exception.code();
            reject(session, courseId, clientId, code, exception.getMessage());
            return;
        } catch (RuntimeException exception) {
            log.warn("Chat send failed for course {} (error={})", courseId, exception.getClass().getSimpleName());
            reject(session, courseId, clientId, "INTERNAL_ERROR", "Unable to save chat message");
            return;
        }
        events.response(session, new Accepted("MESSAGE_ACCEPTED", courseId, clientId, result.messageId()));
        if (result.created() != null) events.course(courseId, new Created("MESSAGE_CREATED", courseId, result.created()));
    }

    private void reject(String session, UUID course, UUID client, String code, String message) {
        events.response(session, new Rejected("MESSAGE_REJECTED", course, client, code, message));
    }
}
