package tutor.educationservice;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.integration.identity.*;
import tutor.educationservice.security.Role;
import static org.assertj.core.api.Assertions.*;

class IdentityLookupTest {
    private final UUID id = UUID.randomUUID();
    private final String token = TestJwt.token(List.of("TEACHER"));
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final AtomicReference<String> cookie = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
        var thread = new Thread(task, "lookup-test-server");
        thread.setDaemon(true);
        return thread;
    });
    private HttpServer server;
    private IdentityClient client;

    private record Reply(int status, String body, long delayMillis) {}

    @BeforeEach
    void startServer() throws Exception {
        reply.set(new Reply(200, body(id, "STUDENT", false), 0));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/v1/directory/users/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            var response = reply.get();
            try {
                if (response.delayMillis() > 0) Thread.sleep(response.delayMillis());
                if (response.status() == 302) exchange.getResponseHeaders().set("Location", "/not-followed");
                byte[] bytes = response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new IdentityClient(new IdentityProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(300), Duration.ofMillis(300)), RestClient.builder(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void lookupReturnsStudentNamesAndUsesCallerCookieAndExactUuid() {
        var user = client.user(token, id);
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.firstName()).isEqualTo("Иван");
        assertThat(user.surname()).isEqualTo("Иванов");
        assertThat(user.roles()).containsExactly(Role.STUDENT);
        assertThat(user.blocked()).isFalse();
        assertThat(path.get()).isEqualTo("/api/v1/directory/users/" + id);
        assertThat(cookie.get()).isEqualTo("access_token=" + token);
    }

    @Test
    void blockedTargetAndNonStudentRemainDistinguishableFromMissingUser() {
        reply.set(new Reply(200, body(id, "STUDENT", true), 0));
        var blocked = client.user(token, id);
        assertThat(blocked.blocked()).isTrue();
        assertThat(blocked.roles()).contains(Role.STUDENT);
        reply.set(new Reply(200, body(id, "TEACHER", false), 0));
        assertThat(client.user(token, id).roles()).doesNotContain(Role.STUDENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 503, 302})
    void lookupPreservesTarget404AndSeparatesDependencyFailures(int upstreamStatus) {
        reply.set(new Reply(upstreamStatus, "private-upstream-diagnostic", 0));
        int status = upstreamStatus == 401 || upstreamStatus == 403 || upstreamStatus == 404 ? upstreamStatus : 503;
        String code = switch (status) {
            case 401 -> "UNAUTHORIZED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "USER_NOT_FOUND";
            default -> "IDENTITY_UNAVAILABLE";
        };
        assertError(status, code);
    }

    @Test
    void malformedIncompleteAndMismatchedProfilesAre503() {
        for (String value : new String[]{"{broken", "{}", body(UUID.randomUUID(), "STUDENT", false),
                body(id, "STUDENT", false).replace("\"blocked\":false", "\"blocked\":null"),
                body(id, "STUDENT", false).replace("[\"STUDENT\"]", "[null]"), body(id, "UNKNOWN_ROLE", false)}) {
            reply.set(new Reply(200, value, 0));
            assertError(503, "IDENTITY_UNAVAILABLE");
        }
    }

    @Test
    void timeoutAndConnectionFailureAre503RatherThan404() {
        reply.set(new Reply(200, body(id, "STUDENT", false), 1000));
        assertError(503, "IDENTITY_UNAVAILABLE");
        server.stop(0);
        assertError(503, "IDENTITY_UNAVAILABLE");
    }

    private void assertError(int status, String code) {
        assertThatThrownBy(() -> client.user(token, id)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status().value()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("private-upstream-diagnostic", token, id.toString());
            assertThat(error.getCause()).isNull();
        });
    }

    private static String body(UUID id, String role, boolean blocked) {
        return "{\"id\":\"" + id + "\",\"firstName\":\"Иван\",\"surname\":\"Иванов\","
                + "\"patronymic\":null,\"roles\":[\"" + role + "\"],\"blocked\":" + blocked + "}";
    }
}
