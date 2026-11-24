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
import java.util.Random;
import java.util.List;
import java.util.UUID;
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
    }

    @Autowired ObjectStorage storage;
    @Autowired MinioClient client;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

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
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema='public' order by table_name", String.class))
                .containsExactly("cleanup_job", "course", "course_student", "file_course_access", "flyway_schema_history", "folder_course_access",
                        "lesson_exception", "lesson_series", "material_file", "material_folder", "material_reference", "outbox_event",
                        "pending_upload", "single_lesson");
    }

    @Test
    void healthIsPublicAndOtherPathsAreClosed() throws Exception {
        assertThat(http("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(http("/actuator/health/readiness").body()).contains("UP").doesNotContain("secret", "components");
        assertThat(http("/actuator/env").statusCode()).isEqualTo(401);
        assertThat(http("/api/v1/courses").statusCode()).isEqualTo(401);
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
        var badStorage = new MinioObjectStorage(badClient, properties);
        String key = ObjectKeys.material();
        assertThatThrownBy(() -> badStorage.exists(key)).isInstanceOfSatisfying(StorageException.class, error -> {
            assertThat(error.reason()).isEqualTo(StorageException.Reason.UNAVAILABLE);
            assertThat(error.getMessage()).doesNotContain(key, properties.secretKey());
            assertThat(error.getCause()).isNull();
        });
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
