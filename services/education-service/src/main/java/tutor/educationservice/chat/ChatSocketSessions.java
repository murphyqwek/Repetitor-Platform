package tutor.educationservice.chat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.CourseDeletion;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.course.EnrollmentService;
import tutor.educationservice.security.*;

@Component
class ChatSocketSessions {
    private static final class Session {
        private final WebSocketSession socket;
        private final ConcurrentHashMap<String, UUID> courses = new ConcurrentHashMap<>();
        private volatile Jwt jwt;

        private Session(WebSocketSession socket) { this.socket = socket; }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final IdentityAuthentication identity;
    private final CourseService courses;

    ChatSocketSessions(IdentityAuthentication identity, CourseService courses, MeterRegistry meters) {
        this.identity = identity;
        this.courses = courses;
        Gauge.builder("education.chat.websocket.sessions", this, ChatSocketSessions::size).register(meters);
    }

    void opened(WebSocketSession socket) { sessions.put(socket.getId(), new Session(socket)); }
    void ended(String id) { sessions.remove(id); }
    int size() { return sessions.size(); }

    JwtAuthenticationToken connect(String id, Jwt jwt) {
        var session = session(id);
        var user = checked(jwt);
        if (session.jwt != null) throw unauthorized();
        session.jwt = jwt;
        return user;
    }

    void subscribe(String id, String subscription, UUID course) {
        if (subscription == null || subscription.isBlank()) throw unauthorized();
        var session = session(id);
        if (course == null) asUser(id, () -> null);
        else {
            session.courses.put(subscription, course);
            try {
                asUser(id, () -> courses.get(course));
            } catch (RuntimeException exception) {
                session.courses.remove(subscription, course);
                throw exception;
            }
        }
    }

    void unsubscribe(String id, String subscription) {
        if (subscription != null) session(id).courses.remove(subscription);
    }

    void requireConnected(String id) {
        if (session(id).jwt == null) throw unauthorized();
    }

    <T> T asUser(String id, Supplier<T> operation) {
        var user = checked(session(id).jwt);
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(user);
        try {
            SecurityContextHolder.setContext(context);
            return operation.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    boolean canDeliver(String id, String subscription, UUID course) {
        try {
            if (id == null || subscription == null || !course.equals(session(id).courses.get(subscription))) return false;
            asUser(id, () -> courses.get(course));
            return true;
        } catch (ApiException exception) {
            // A disconnected client reloads history after it reconnects.
            close(id);
            return false;
        }
    }

    @TransactionalEventListener
    public void membershipRemoved(EnrollmentService.Excluded event) {
        sessions.forEach((id, session) -> {
            if (session.jwt != null && session.jwt.getSubject().equals(event.studentId().toString())
                    && session.courses.containsValue(event.courseId())) close(id);
        });
    }

    @TransactionalEventListener
    public void courseDeleted(CourseDeletion.Started event) {
        sessions.forEach((id, session) -> {
            if (session.courses.containsValue(event.courseId())) close(id);
        });
    }

    private void close(String id) {
        var session = sessions.remove(id);
        if (session == null) return;
        try {
            session.socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {}
    }

    private Session session(String id) {
        var session = id == null ? null : sessions.get(id);
        if (session == null) throw unauthorized();
        return session;
    }

    private JwtAuthenticationToken checked(Jwt jwt) {
        if (jwt == null || !Instant.now().isBefore(jwt.getExpiresAt())) throw unauthorized();
        var token = identity.current(jwt);
        var user = (CurrentUser) token.getDetails();
        if (!user.roles().contains(Role.TEACHER) && !user.roles().contains(Role.ADMIN) && !user.roles().contains(Role.STUDENT)) {
            throw unauthorized();
        }
        return token;
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Chat authentication required");
    }
}
