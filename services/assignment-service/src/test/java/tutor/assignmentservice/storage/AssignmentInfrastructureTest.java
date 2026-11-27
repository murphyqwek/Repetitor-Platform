package tutor.assignmentservice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssignmentInfrastructureTest {
    private static final String BUCKET = "assignment-private";
    private static final String ACCESS = "assignment-test";
    private static final String SECRET = "assignment-test-password";
    private static final DockerImageName SILO = DockerImageName.parse(
            "pgsty/silo:RELEASE.2026-11-12T00-00-00Z@sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329");

    @Container
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("assignment");

    @Container
    private static final GenericContainer<?> s3 = new GenericContainer<>(SILO)
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        try (var client = MinioClient.builder().endpoint(s3Endpoint()).credentials(ACCESS, SECRET).build()) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("assignment.storage.endpoint", AssignmentInfrastructureTest::s3Endpoint);
        registry.add("assignment.storage.bucket", () -> BUCKET);
        registry.add("assignment.storage.access-key", () -> ACCESS);
        registry.add("assignment.storage.secret-key", () -> SECRET);
    }

    @Test
    void migratesItsOwnDatabaseAndExposesOnlyHealthProbes() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("assignment");
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class))
                .isTrue();
        var http = HttpClient.newHttpClient();
        assertStatus(http, baseUrl() + "/actuator/health/liveness", 200);
        assertStatus(http, baseUrl() + "/actuator/health/readiness", 200);
        assertStatus(http, baseUrl() + "/internal/v1/test", 403);
        assertStatus(http, baseUrl() + "/api/v1/assignments", 403);
    }

    @Test
    void streamsExactBytesAndKeepsObjectsPrivate() throws Exception {
        byte[] payload = new byte[11 * 1024 * 1024];
        new Random(1).nextBytes(payload);
        String key = ObjectStorage.newKey();
        try {
            storage.put(key, new ByteArrayInputStream(payload), -1, "application/pdf");
            assertThat(storage.head(key)).isEqualTo(new ObjectStorage.Metadata(payload.length, "application/pdf"));
            try (var body = storage.get(key)) {
                assertThat(body.readAllBytes()).isEqualTo(payload);
            }
            assertStatus(HttpClient.newHttpClient(), s3Endpoint() + "/" + BUCKET + "/" + key, 403);
        } finally {
            storage.delete(key);
        }
        storage.delete(key);
        assertThatThrownBy(() -> storage.get(key)).isInstanceOfSatisfying(StorageException.class,
                exception -> assertThat(exception.reason()).isEqualTo(StorageException.Reason.NOT_FOUND));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static String s3Endpoint() {
        return "http://" + s3.getHost() + ":" + s3.getMappedPort(9000);
    }

    private static void assertStatus(HttpClient client, String url, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(status);
    }
}
