package tutor.educationservice;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.ServerSocket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Random;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.cleanup.*;
import tutor.educationservice.integration.storage.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EducationInfrastructureTest {
    private static final String BUCKET = "education-private";
    private static final String ROOT_PASSWORD = UUID.randomUUID().toString();
    private static final String APP_SECRET = UUID.randomUUID().toString();
    private static final Network NETWORK = Network.newNetwork();
    private static final int S3_PORT = availablePort();
    private static final IdentityHttpStub identity = new IdentityHttpStub();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID teacher = UUID.randomUUID();
    private static final UUID student = UUID.randomUUID();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("pgsty/silo:RELEASE.2026-11-12T00-00-00Z@sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329")
            .withNetwork(NETWORK).withNetworkAliases("minio").withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "integration-root").withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", S3_PORT), new ExposedPort(9000)))
                    .withMounts(List.of(new Mount().withType(MountType.VOLUME).withTarget("/data"))))
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Container
    static final GenericContainer<?> BOOTSTRAP = new GenericContainer<>("pgsty/mc:RELEASE.2026-11-12T00-00-00Z@sha256:b532278b7a3747dbab6fe45eebb40a653efa704e6758e24e9d9ec54fc96c1800")
            .withNetwork(NETWORK).dependsOn(MINIO)
            .withEnv("MINIO_ROOT_USER", "integration-root").withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("EDUCATION_S3_BUCKET", BUCKET).withEnv("EDUCATION_S3_ACCESS_KEY", "education-test")
            .withEnv("EDUCATION_S3_SECRET_KEY", APP_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../Docker/local/minio/bootstrap.sh").toAbsolutePath()), "/bootstrap.sh")
            .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/sh", "/bootstrap.sh"))
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))
            .waitingFor(Wait.forLogMessage(".*restricted S3 user initialized.*", 1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("education.storage.endpoint", EducationInfrastructureTest::endpoint);
        registry.add("education.storage.bucket", () -> BUCKET);
        registry.add("education.storage.access-key", () -> "education-test");
        registry.add("education.storage.secret-key", () -> APP_SECRET);
        registry.add("education.identity.base-url", identity::baseUrl);
    }

    @Autowired ObjectStorage storage;
    @Autowired MinioClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CleanupJobs cleanupJobs;
    @Autowired List<CleanupHandler> cleanupHandlers;
    @Autowired MeterRegistry meters;
    @LocalServerPort int port;

    @AfterAll
    static void closeIdentity() { identity.close(); }

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static int availablePort() {
        // Docker reassigns random published ports on restart; the application endpoint must remain stable.
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    void contextLoadsAndFlywayRunsOnRealPostgres() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema='public' order by table_name", String.class))
                .containsExactly("chat_attachment", "chat_message", "chat_send_receipt", "cleanup_job", "course", "course_student",
                        "file_course_access", "flyway_schema_history", "folder_course_access",
                        "lesson_exception", "lesson_series", "material_file", "material_folder", "material_reference", "outbox_event",
                        "pending_upload", "single_lesson");
    }

    @Test
    void healthIsPublicAndOtherPathsAreClosed() throws Exception {
        var liveness = http("/actuator/health/liveness");
        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(liveness.headers().firstValue("X-Request-Id")).hasValueSatisfying(value -> assertThat(UUID.fromString(value)).isNotNull());
        assertThat(http("/actuator/health/readiness").body()).contains("UP").doesNotContain("secret", "components");
        assertThat(http("/actuator/env").statusCode()).isEqualTo(401);
        assertThat(http("/api/v1/courses").statusCode()).isEqualTo(401);
    }

    @Test
    void courseLifecycleUsesRealPostgresAndPrivateS3ThroughPublicApi() throws Exception {
        identity.people.put(teacher, new IdentityHttpStub.Person(teacher, List.of("TEACHER"), false));
        identity.people.put(student, new IdentityHttpStub.Person(student, List.of("STUDENT"), false));
        String owner = TestJwt.token(List.of("TEACHER"), claims -> claims.subject(teacher.toString()), false);
        String pupil = TestJwt.token(List.of("STUDENT"), claims -> claims.subject(student.toString()), false);
        String course = body(json("POST", "/courses", owner, Map.of("title", "Integration course")), 201).path("id").asString();
        String path = "/courses/" + course;
        body(json("POST", path + "/students", owner, Map.of("studentId", student)), 201);
        var today = LocalDate.now(ZoneId.of("Europe/Moscow"));
        var lesson = today.plusDays(1);
        body(json("POST", path + "/lessons", owner, Map.of("startsAt", lesson + "T09:00:00+03:00", "endsAt", lesson + "T10:00:00+03:00")), 201);
        body(json("POST", path + "/lesson-series", owner, Map.of("startDate", lesson.toString(),
                "weekdays", List.of(lesson.getDayOfWeek().getValue()), "startTime", "17:00", "durationMinutes", 45)), 201);
        assertThat(body(json("GET", path + "/schedule?month=" + YearMonth.from(lesson), pupil, null), 200).path("lessons").size()).isGreaterThanOrEqualTo(2);

        String folder = body(json("POST", "/materials/folders", owner, Map.of("name", "Handouts")), 201).path("id").asString();
        String file = body(upload("/materials/files", owner, folder, "library data"), 201).path("id").asString();
        assertThat(json("PUT", "/materials/folders/" + folder + "/course-access/" + course, owner, null).statusCode()).isEqualTo(204);
        String content = path + "/materials/files/" + file + "/content";
        assertThat(json("GET", content, pupil, null).body()).isEqualTo("library data");
        String attachment = body(upload(path + "/chat/attachments", pupil, null, "chat data"), 201).path("attachmentId").asString();
        String attachmentKey = jdbc.queryForObject("select object_key from chat_attachment where id = ?", String.class, UUID.fromString(attachment));
        try (var author = new TestStompClient(port, pupil, mapper); var moderator = new TestStompClient(port, owner, mapper)) {
            author.subscribe("response", "/user/queue/chat.responses");
            moderator.subscribe("response", "/user/queue/chat.responses");
            author.send(course, mapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "attachmentIds", List.of(attachment))));
            var received = author.event();
            assertThat(received.path("type").asString()).isEqualTo("MESSAGE_ACCEPTED");
            String message = received.path("messageId").asString();
            String download = path + "/chat/messages/" + message + "/attachments/" + attachment + "/content";
            var bytes = json("GET", download, pupil, null);
            assertThat(bytes.statusCode()).isEqualTo(200);
            assertThat(bytes.body()).isEqualTo("chat data");
            assertThat(bytes.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("private", "no-store"));
            moderator.send(course, mapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "text", "Notice")));
            String notice = moderator.event().path("messageId").asString();
            assertThat(json("DELETE", path + "/chat/messages/" + notice, owner, null).statusCode()).isEqualTo(204);
            assertThat(body(json("GET", path + "/chat/messages", pupil, null), 200).path("messages").size()).isEqualTo(1);
            UUID job = UUID.fromString(body(json("DELETE", path, owner, null), 202).path("cleanupJobId").asString());
            assertThat(json("GET", download, pupil, null).statusCode()).isEqualTo(404);
            var worker = new CleanupWorker(cleanupJobs, storage, cleanupHandlers,
                    new CleanupProperties(Duration.ofMinutes(1), 10, 3, Duration.ofSeconds(1), Duration.ofMinutes(1)), meters);
            worker.poll();
            assertThat(jdbc.queryForObject("select status from cleanup_job where id = ?", String.class, job)).isEqualTo("COMPLETED");
            assertThat(storage.exists(attachmentKey)).isFalse();
            assertThat(json("GET", "/materials/files/" + file + "/content", owner, null).body()).isEqualTo("library data");
            assertThat(body(json("GET", "/materials/files/" + file + "/course-access", owner, null), 200).size()).isZero();
        }
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> json(String method, String path, String token, Object value) throws Exception {
        return HTTP.send(api(path, token).header("Content-Type", "application/json")
                .method(method, value == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, String token, String folder, String data) throws Exception {
        String parent = folder == null ? "" : "--test-boundary\r\nContent-Disposition: form-data; name=\"folderId\"\r\n\r\n" + folder + "\r\n";
        String body = parent + "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + data + "\r\n--test-boundary--\r\n";
        return HTTP.send(api(path, token).header("Content-Type", "multipart/form-data; boundary=test-boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder api(String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token);
    }

    @Test
    void streamsBinaryObjectsWithMetadataAndPrivateAccess() throws Exception {
        byte[] bytes = new byte[6 * 1024 * 1024 + 17];
        new Random(17).nextBytes(bytes);
        String key = ObjectKeys.material();
        try {
            storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
            assertThat(storage.exists(key)).isTrue();
            var stat = client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            assertThat(stat.size()).isEqualTo(bytes.length);
            assertThat(stat.contentType()).isEqualTo("application/octet-stream");
            try (var body = storage.get(key)) {
                assertThat(body.readAllBytes()).isEqualTo(bytes); // Fixed, bounded test fixture only.
            }
            var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint() + "/" + BUCKET + "/" + key))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isEqualTo(403);
            assertThat(anonymous.body()).contains("AccessDenied");
            assertThatThrownBy(() -> client.putObject(PutObjectArgs.builder().bucket(BUCKET).object("outside-prefix")
                    .stream(new ByteArrayInputStream(new byte[1]), 1, -1).build())).isInstanceOf(ErrorResponseException.class);
        } finally {
            storage.delete(key);
        }
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
        assertThatThrownBy(() -> storage.get(key)).isInstanceOfSatisfying(StorageException.class,
                error -> assertThat(error.reason()).isEqualTo(StorageException.Reason.NOT_FOUND));
    }

    @Test
    void unknownLengthUploadUsesMultipart() throws Exception {
        byte[] bytes = new byte[11 * 1024 * 1024];
        new Random(18).nextBytes(bytes);
        String key = ObjectKeys.temporary();
        try {
            storage.put(key, new ByteArrayInputStream(bytes), -1, "application/octet-stream");
            try (var body = storage.get(key)) {
                assertThat(body.readAllBytes()).isEqualTo(bytes);
            }
        } finally {
            storage.delete(key);
        }
    }

    @Test
    void restartKeepsObjectAndReadinessRecoversAfterOutage() throws Exception {
        assertThat(MINIO.getDockerClient().inspectContainerCmd(MINIO.getContainerId()).exec().getMounts())
                .anySatisfy(mount -> {
                    assertThat(mount.getDestination().getPath()).isEqualTo("/data");
                    assertThat(mount.getName()).isNotBlank();
                });
        String key = ObjectKeys.chat(UUID.randomUUID());
        byte[] bytes = {0, 1, 2, -1};
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream");
        try {
            MINIO.getDockerClient().stopContainerCmd(MINIO.getContainerId()).withTimeout(5).exec();
            assertThat(http("/actuator/health/liveness").statusCode()).isEqualTo(200);
            assertThat(http("/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThatThrownBy(() -> storage.get(key)).isInstanceOfSatisfying(StorageException.class,
                    error -> assertThat(error.reason()).isEqualTo(StorageException.Reason.UNAVAILABLE));
        } finally {
            MINIO.getDockerClient().startContainerCmd(MINIO.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(http("/actuator/health/readiness").statusCode()).isEqualTo(200));
        try (var body = storage.get(key)) {
            assertThat(body.readAllBytes()).isEqualTo(bytes);
        } finally {
            storage.delete(key);
        }
    }

    @Test
    void badCredentialsAreUnavailableAndDoNotLeakSecretsOrKeys() {
        var properties = new StorageProperties(endpoint(), BUCKET, "education-test", "deliberately-wrong-secret");
        var badClient = MinioClient.builder().endpoint(endpoint()).credentials(properties.accessKey(), properties.secretKey()).build();
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var badStorage = new MinioObjectStorage(badClient, properties, meters);
        String key = ObjectKeys.material();
        assertThatThrownBy(() -> badStorage.exists(key)).isInstanceOfSatisfying(StorageException.class, error -> {
            assertThat(error.reason()).isEqualTo(StorageException.Reason.UNAVAILABLE);
            assertThat(error.getMessage()).doesNotContain(key, properties.secretKey());
            assertThat(error.getCause()).isNull();
        });
        assertThat(meters.get("education.storage.errors").tag("operation", "stat").counter().count()).isEqualTo(1);
    }

    @Test
    void bootstrapCanRunTwice() throws Exception {
        BOOTSTRAP.getDockerClient().startContainerCmd(BOOTSTRAP.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            var state = BOOTSTRAP.getDockerClient().inspectContainerCmd(BOOTSTRAP.getContainerId()).exec().getState();
            assertThat(state.getRunning()).isFalse();
            assertThat(state.getExitCodeLong()).isZero();
        });
        assertThat(http("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> http(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
