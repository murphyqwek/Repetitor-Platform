package tutor.educationservice.chat;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
class ChatWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private static final String COURSE_ID = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
    private static final Pattern TOPIC = Pattern.compile("/topic/courses/" + COURSE_ID + "/chat");
    private static final Pattern SEND = Pattern.compile("/app/courses/" + COURSE_ID + "/chat\\.send");
    private static final String RESPONSES = "/user/queue/chat.responses";

    private final JwtDecoder decoder;
    private final ChatSocketSessions sessions;

    ChatWebSocketConfiguration(JwtDecoder decoder, ChatSocketSessions sessions) {
        this.decoder = decoder;
        this.sessions = sessions;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic", "/queue");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration transport) {
        transport.setMessageSizeLimit(32 * 1024).setSendBufferSizeLimit(256 * 1024).setSendTimeLimit(10_000);
        transport.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.opened(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.ended(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var event = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (event == null || event.getCommand() == null) return message;
                String id = event.getSessionId();
                String destination = event.getDestination();
                switch (event.getCommand()) {
                    case CONNECT, STOMP -> {
                        String header = event.getFirstNativeHeader("Authorization");
                        event.removeNativeHeader("Authorization");
                        if (header == null || !header.startsWith("Bearer ")) throw denied();
                        event.setUser(sessions.connect(id, decoder.decode(header.substring(7))));
                    }
                    case SUBSCRIBE -> {
                        if (RESPONSES.equals(destination)) sessions.subscribe(id, event.getSubscriptionId(), null);
                        else {
                            UUID course = topicCourse(destination);
                            if (course == null) throw denied();
                            sessions.subscribe(id, event.getSubscriptionId(), course);
                        }
                    }
                    case UNSUBSCRIBE -> sessions.unsubscribe(id, event.getSubscriptionId());
                    case SEND -> {
                        if (destination == null || !SEND.matcher(destination).matches()) throw denied();
                        sessions.requireConnected(id);
                    }
                    case DISCONNECT -> {}
                    default -> throw denied();
                }
                return message;
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = message.getHeaders();
                if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) return message;
                UUID course = topicCourse(SimpMessageHeaderAccessor.getDestination(headers));
                if (course == null) return message;
                String session = SimpMessageHeaderAccessor.getSessionId(headers);
                String subscription = SimpMessageHeaderAccessor.getSubscriptionId(headers);
                return sessions.canDeliver(session, subscription, course) ? message : null;
            }
        });
    }

    private static UUID topicCourse(String destination) {
        if (destination == null) return null;
        var matcher = TOPIC.matcher(destination);
        return matcher.matches() ? UUID.fromString(matcher.group(1)) : null;
    }

    private static AccessDeniedException denied() { return new AccessDeniedException("Chat destination not allowed"); }
}
