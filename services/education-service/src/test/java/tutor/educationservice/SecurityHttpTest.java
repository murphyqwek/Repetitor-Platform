package tutor.educationservice;

import com.sun.net.httpserver.HttpServer;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.CursorCodec;
import tutor.educationservice.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.servlet.multipart.max-file-size=128B", "spring.servlet.multipart.max-request-size=1KB"})
@Import(SecurityHttpTest.TestRoutes.class)
class SecurityHttpTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final AtomicReference<Reply> REPLY = new AtomicReference<>();
    private static final AtomicReference<String> COOKIE = new AtomicReference<>();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        var thread = new Thread(task, "identity-test-server");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpServer IDENTITY = identityServer();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private record Reply(int status, String body, long delayMillis) {}

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("education.storage.endpoint", () -> "http://localhost:1");
        registry.add("education.storage.bucket", () -> "security-test");
        registry.add("education.storage.access-key", () -> "security-test-access");
        registry.add("education.storage.secret-key", () -> "security-test-secret");
        registry.add("education.identity.base-url", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        registry.add("education.identity.read-timeout", () -> "500ms");
    }

    @MockitoBean MinioClient minio;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @BeforeEach
    void resetIdentity() throws Exception {
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("TEACHER", "STUDENT"), false, true), 0));
        CALLS.set(0);
        COOKIE.set(null);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }

    @AfterAll
    static void closeServer() {
        IDENTITY.stop(0);
        EXECUTOR.shutdownNow();
    }

    @Test
    void missingTokenAndCookieOnlyAreUnauthorizedWithoutIdentityCall() throws Exception {
        var response = request("GET", "/api/v1/_test/current", null, null, null);
        assertProblem(response, 401, "UNAUTHORIZED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        var cookieOnly = HTTP.send(HttpRequest.newBuilder(uri("/api/v1/_test/current"))
                .header("Cookie", "access_token=" + TestJwt.token(List.of("TEACHER"))).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(cookieOnly, 401, "UNAUTHORIZED");
        assertThat(CALLS.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"issuer", "expired", "signature", "futureNbf", "futureIat", "subject", "rolesString", "emptyRoles", "unknownRole", "missingExpiry", "unsigned", "malformed"})
    void invalidTokensReturn401WithoutCallingIdentity(String scenario) throws Exception {
        String token = switch (scenario) {
            case "issuer" -> TestJwt.token(List.of("TEACHER"), claims -> claims.issuer("other-issuer"), false);
            case "expired" -> TestJwt.token(List.of("TEACHER"), claims -> claims
                    .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(120)), false);
            case "signature" -> TestJwt.token(List.of("TEACHER"), claims -> {}, true);
            case "futureNbf" -> TestJwt.token(List.of("TEACHER"), claims -> claims.notBefore(Instant.now().plusSeconds(120)), false);
            case "futureIat" -> TestJwt.token(List.of("TEACHER"), claims -> claims
                    .issuedAt(Instant.now().plusSeconds(120)).expiresAt(Instant.now().plusSeconds(600)), false);
            case "subject" -> TestJwt.token(List.of("TEACHER"), claims -> claims.subject("not-a-uuid"), false);
            case "rolesString" -> TestJwt.token(List.of("TEACHER"), claims -> claims.claim("roles", "ADMIN"), false);
            case "emptyRoles" -> TestJwt.token(List.of(), claims -> {}, false);
            case "unknownRole" -> TestJwt.token(List.of("SUPERUSER"));
            case "missingExpiry" -> TestJwt.token(List.of("TEACHER"), claims -> claims.claims(values -> values.remove("exp")), false);
            case "unsigned" -> Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                    + "." + TestJwt.token(List.of("TEACHER")).split("\\.")[1] + ".";
            default -> "not-a-jwt";
        };
        var response = request("GET", "/api/v1/_test/current", token, null, null);
        assertProblem(response, 401, "UNAUTHORIZED");
        assertThat(response.body()).doesNotContain(token);
        assertThat(CALLS.get()).isZero();
    }

    @Test
    void multipleRolesAndDisplayNameUseCheckedIdentityProfile() throws Exception {
        String token = TestJwt.token(List.of("TEACHER", "STUDENT"));
        var response = request("GET", "/api/v1/_test/current", token, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = mapper.readTree(response.body());
        assertThat(body.path("id").asString()).isEqualTo(TestJwt.USER.toString());
        assertThat(body.path("displayName").asString()).isEqualTo("Иванов Иван");
        assertThat(body.path("roles").toString()).contains("TEACHER", "STUDENT");
        assertThat(COOKIE.get()).isEqualTo("access_token=" + token);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void rolesDoNotGrantOwnershipOrAdministratorAccess() throws Exception {
        String teacher = TestJwt.token(List.of("TEACHER"));
        assertThat(request("GET", "/api/v1/_test/owner/" + TestJwt.USER, teacher, null, null).statusCode()).isEqualTo(200);
        assertProblem(request("GET", "/api/v1/_test/owner/" + UUID.randomUUID(), teacher, null, null), 403, "ACCESS_DENIED");
        assertProblem(request("GET", "/api/v1/_test/admin", teacher, null, null), 403, "ACCESS_DENIED");
        String student = TestJwt.token(List.of("STUDENT"));
        assertProblem(request("GET", "/api/v1/_test/owner/" + TestJwt.USER, student, null, null), 403, "ACCESS_DENIED");
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("ADMIN"), false, true), 0));
        assertThat(request("GET", "/api/v1/_test/owner/" + UUID.randomUUID(), TestJwt.token(List.of("ADMIN")), null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void identityCannotAddUnsignedRolesAndRevokedRolesStopWorking() throws Exception {
        String token = TestJwt.token(List.of("STUDENT"));
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("STUDENT", "ADMIN"), false, true), 0));
        assertProblem(request("GET", "/api/v1/_test/admin", token, null, null), 403, "ACCESS_DENIED");
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("STUDENT"), false, true), 0));
        assertProblem(request("GET", "/api/v1/_test/teacher", TestJwt.token(List.of("TEACHER", "STUDENT")), null, null), 403, "ACCESS_DENIED");
    }

    @Test
    void blockingTakesEffectOnNextRequestWithSameToken() throws Exception {
        String token = TestJwt.token(List.of("TEACHER"));
        assertThat(request("GET", "/api/v1/_test/current", token, null, null).statusCode()).isEqualTo(200);
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("TEACHER"), true, true), 0));
        assertProblem(request("GET", "/api/v1/_test/current", token, null, null), 403, "ACCOUNT_BLOCKED");
        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test
    void incompleteProfilesAndParentOnlyRoleCannotAccessEducation() throws Exception {
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("TEACHER"), false, false), 0));
        assertProblem(request("GET", "/api/v1/_test/current", TestJwt.token(List.of("TEACHER")), null, null), 403, "PROFILE_INCOMPLETE");
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("PARENT"), false, true), 0));
        assertProblem(request("GET", "/api/v1/_test/current", TestJwt.token(List.of("PARENT")), null, null), 403, "ACCESS_DENIED");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 503})
    void identityHttpFailuresDoNotBecomeUserNotFound(int identityStatus) throws Exception {
        REPLY.set(new Reply(identityStatus, "private-upstream-diagnostic", 0));
        int expected = identityStatus == 401 ? 401 : identityStatus == 403 || identityStatus == 404 ? 403 : 503;
        String code = expected == 401 ? "UNAUTHORIZED" : expected == 403 ? "ACCOUNT_UNAVAILABLE" : "IDENTITY_UNAVAILABLE";
        assertProblem(request("GET", "/api/v1/_test/current", TestJwt.token(List.of("TEACHER")), null, null), expected, code);
    }

    @Test
    void timeoutsAndMalformedOrMismatchedProfilesFailClosed() throws Exception {
        String token = TestJwt.token(List.of("TEACHER"));
        REPLY.set(new Reply(200, profile(TestJwt.USER, List.of("TEACHER"), false, true), 1500));
        assertProblem(request("GET", "/api/v1/_test/current", token, null, null), 503, "IDENTITY_UNAVAILABLE");
        for (String body : new String[]{"{broken", "{}", profile(UUID.randomUUID(), List.of("TEACHER"), false, true),
                profile(TestJwt.USER, List.of("TEACHER"), false, true).replace("\"blocked\":false,", "")}) {
            REPLY.set(new Reply(200, body, 0));
            assertProblem(request("GET", "/api/v1/_test/current", token, null, null), 503, "IDENTITY_UNAVAILABLE");
        }
    }

    @Test
    void invalidUuidLimitCursorAndJsonHaveSafeProblemResponses() throws Exception {
        String token = TestJwt.token(List.of("TEACHER"));
        assertProblem(request("GET", "/api/v1/_test/input/bad-uuid", token, null, null), 400, "INVALID_REQUEST");
        for (String limit : new String[]{"0", "101", "abc"}) {
            var response = request("GET", "/api/v1/_test/input/" + TestJwt.USER + "?limit=" + limit, token, null, null);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        }
        assertProblem(request("GET", "/api/v1/_test/input/" + TestJwt.USER + "?cursor=broken", token, null, null), 400, "INVALID_CURSOR");
        assertProblem(request("POST", "/api/v1/_test/input", token, "{broken", "application/json"), 400, "INVALID_REQUEST");
        assertProblem(request("POST", "/api/v1/_test/input", token, "{\"name\":\"\"}", "application/json"), 422, "INVALID_REQUEST");
        assertProblem(request("POST", "/api/v1/_test/input", token, "text", "text/plain"), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void conflictsMissingResourcesUploadsAndUnexpectedErrorsAreSanitized() throws Exception {
        String token = TestJwt.token(List.of("TEACHER"));
        assertProblem(request("GET", "/api/v1/_test/conflict", token, null, null), 409, "RESOURCE_IN_USE");
        assertProblem(request("GET", "/api/v1/nonexistent", token, null, null), 404, "NOT_FOUND");
        assertProblem(request("GET", "/api/v1/_test/unexpected", token, null, null), 500, "INTERNAL_ERROR");
        String multipart = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"file.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + "x".repeat(512) + "\r\n--boundary--\r\n";
        assertProblem(request("POST", "/api/v1/_test/upload", token, multipart, "multipart/form-data; boundary=boundary"), 413, "FILE_TOO_LARGE");
        assertProblem(request("POST", "/internal/v1/materials/" + UUID.randomUUID() + "/references", token, "{}", "application/json"),
                401, "UNAUTHORIZED");
    }

    @Test
    void healthDoesNotDependOnIdentityAvailability() throws Exception {
        REPLY.set(new Reply(503, "private-upstream-diagnostic", 0));
        assertThat(request("GET", "/actuator/health/liveness", null, null, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health/readiness", null, null, null).statusCode()).isEqualTo(200);
        assertThat(CALLS.get()).isZero();
    }

    private HttpResponse<String> request(String method, String path, String token, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (contentType != null) request.header("Content-Type", contentType);
        return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("private-upstream-diagnostic", "private-internal-detail", "stackTrace");
    }

    private static String profile(UUID id, List<String> roles, boolean blocked, boolean nameRequested) {
        return "{\"id\":\"" + id + "\",\"firstName\":\"Иван\",\"surname\":\"Иванов\",\"roles\":[\""
                + String.join("\",\"", roles) + "\"],\"blocked\":" + blocked + ",\"nameRequested\":" + nameRequested + "}";
    }

    private static HttpServer identityServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/api/v1/users/me", exchange -> {
                CALLS.incrementAndGet();
                COOKIE.set(exchange.getRequestHeaders().getFirst("Cookie"));
                Reply reply = REPLY.get();
                try {
                    if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestComponent
    @RestController
    @RequestMapping("/api/v1/_test")
    static class TestRoutes {
        private final PermissionService permissions;
        private final CursorCodec cursors;

        TestRoutes(PermissionService permissions, CursorCodec cursors) {
            this.permissions = permissions;
            this.cursors = cursors;
        }

        record Body(@NotBlank String name) {}
        @GetMapping("/current") CurrentUser current() { return CurrentUser.get(); }
        @GetMapping("/admin") @PreAuthorize("hasRole('ADMIN')") String admin() { return "ok"; }
        @GetMapping("/teacher") String teacher() { permissions.requireTeacherOrAdmin(); return "ok"; }
        @GetMapping("/owner/{owner}") String owner(@PathVariable UUID owner) { permissions.requireOwnerOrAdmin(owner); return "ok"; }
        @GetMapping("/input/{id}") UUID input(@PathVariable UUID id, @RequestParam(required = false) Integer limit,
                @RequestParam(required = false) String cursor) {
            CursorCodec.limit(limit);
            if (cursor != null) cursors.decode(cursor, "test-scope");
            return id;
        }
        @PostMapping(value = "/input", consumes = "application/json") Body input(@Valid @RequestBody Body body) { return body; }
        @PostMapping("/upload") long upload(@RequestParam MultipartFile file) { return file.getSize(); }
        @GetMapping("/conflict") void conflict() { throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_IN_USE", "Resource is in use"); }
        @GetMapping("/unexpected") void unexpected() { throw new IllegalStateException("private-internal-detail"); }
    }
}
