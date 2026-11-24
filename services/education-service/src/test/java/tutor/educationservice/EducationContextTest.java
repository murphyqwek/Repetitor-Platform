package tutor.educationservice;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.flywaydb.core.Flyway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Context/HTTP checks remain independent of registry access for MinIO.
// Actual S3 behavior is verified separately by EducationInfrastructureTest.
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EducationContextTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("education.storage.endpoint", () -> "http://localhost:1");
        registry.add("education.storage.bucket", () -> "education-context");
        registry.add("education.storage.access-key", () -> "isolated-test-access");
        registry.add("education.storage.secret-key", () -> "isolated-test-secret");
    }

    @MockitoBean MinioClient minio;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired tutor.educationservice.common.pagination.CursorCodec cursors;
    @LocalServerPort int port;

    @BeforeEach
    void storageAvailable() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }

    @Test
    void contextLoadsAndFlywayInitializesRealPostgresInUtc() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema='public' order by table_name", String.class))
                .containsExactly("chat_message", "chat_send_receipt", "cleanup_job", "course", "course_student", "file_course_access", "flyway_schema_history", "folder_course_access",
                        "lesson_exception", "lesson_series", "material_file", "material_folder", "material_reference", "outbox_event",
                        "pending_upload", "single_lesson");
    }

    @Test
    void probesArePublicAndOtherPathsAreClosed() throws Exception {
        assertThat(http("/actuator/health/liveness").statusCode()).isEqualTo(200);
        var readiness = http("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("UP").doesNotContain("components", "secret");
        assertThat(http("/actuator/env").statusCode()).isEqualTo(401);
        assertThat(http("/api/v1/courses").statusCode()).isEqualTo(401);
    }

    @Test
    void repeatedMigrationsPreserveExistingData() {
        jdbc.execute("create table stage2_migration_fixture (id integer primary key)");
        try {
            jdbc.update("insert into stage2_migration_fixture values (1)");
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select count(*) from stage2_migration_fixture", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop table stage2_migration_fixture");
        }
    }

    @Test
    void keysetCursorSurvivesDeletedAnchorAndEqualTimestamps() {
        jdbc.execute("create table stage2_cursor_fixture (id uuid primary key, created_at timestamptz not null)");
        try {
            var instant = java.time.Instant.parse("2026-01-01T00:00:00.123456Z");
            var time = java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
            for (int i = 1; i <= 4; i++) {
                jdbc.update("insert into stage2_cursor_fixture values (?, ?)", new java.util.UUID(0, i), time);
            }
            var first = jdbc.query("select id from stage2_cursor_fixture order by created_at desc, id desc limit 2",
                    (row, index) -> row.getObject(1, java.util.UUID.class));
            var encoded = cursors.encode(new tutor.educationservice.common.pagination.CursorCodec.Position(instant, first.get(1)), "owned:test-user");
            jdbc.update("delete from stage2_cursor_fixture where id = ?", first.get(1));
            var position = cursors.decode(encoded, "owned:test-user");
            var next = jdbc.query("select id from stage2_cursor_fixture where (created_at, id) < (?, ?) order by created_at desc, id desc limit 2",
                    (row, index) -> row.getObject(1, java.util.UUID.class), time, position.id());
            assertThat(first).containsExactly(new java.util.UUID(0, 4), new java.util.UUID(0, 3));
            assertThat(next).containsExactly(new java.util.UUID(0, 2), new java.util.UUID(0, 1));
            assertThat(position.createdAt()).isEqualTo(instant);
        } finally {
            jdbc.execute("drop table stage2_cursor_fixture");
        }
    }

    @Test
    void unavailableS3LowersReadinessWithoutExposingException() throws Exception {
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IllegalStateException("private-test-secret"));
        var readiness = http("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).contains("DOWN").doesNotContain("private-test-secret");
        assertThat(http("/actuator/health/liveness").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> http(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
