package tutor.educationservice.chat;

import java.util.UUID;
import org.springframework.messaging.simp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

@Component
class ChatEvents {
    private final SimpMessagingTemplate broker;
    private final ObjectMapper mapper;

    ChatEvents(SimpMessagingTemplate broker, ObjectMapper mapper) {
        this.broker = broker;
        this.mapper = mapper;
    }

    void response(String session, Object event) {
        send("/user/" + session + "/queue/chat.responses", session, event);
    }

    void course(UUID courseId, Object event) {
        send("/topic/courses/" + courseId + "/chat", null, event);
    }

    private void send(String destination, String session, Object event) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        if (session != null) headers.setSessionId(session);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        headers.setLeaveMutable(true);
        broker.send(destination, MessageBuilder.createMessage(mapper.writeValueAsBytes(event), headers.getMessageHeaders()));
    }
}
