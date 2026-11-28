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
        registry.add("ASSIGNMENT_EDUCATION_SIGNING_KEY", AssignmentCredential::encoded);
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
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("show timezone", String.class)).isEqualTo("UTC");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema='public' order by table_name", String.class))
                .containsExactly("chat_attachment", "chat_message", "chat_send_receipt", "cleanup_job", "course", "course_student",
                        "file_course_access", "flyway_schema_history", "folder_course_access",
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
    void upgradesExistingScheduleDatabaseThroughV12() {
        String schema = "education_upgrade";
        var old = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("6").load();
        try {
            assertThat(old.migrate().migrationsExecuted).isEqualTo(6);
            var course = java.util.UUID.randomUUID();
            jdbc.update("insert into education_upgrade.course(id, teacher_id, title, created_at) values (?, ?, 'Existing', clock_timestamp())",
                    course, java.util.UUID.randomUUID());
            jdbc.update("insert into education_upgrade.course_student(course_id, student_id, enrolled_at) values (?, ?, clock_timestamp())",
                    course, java.util.UUID.randomUUID());
            var latest = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
            assertThat(latest.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select title from education_upgrade.course where id = ?", String.class, course)).isEqualTo("Existing");
            assertThat(jdbc.queryForObject("select count(*) from education_upgrade.course_student where course_id = ?", Integer.class, course)).isEqualTo(1);
        } finally {
            jdbc.execute("drop schema if exists education_upgrade cascade");
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

    @Test
    void assignmentResolveAndMembershipUseCurrentOwnedActiveCourses() throws Exception {
        var teacher = java.util.UUID.randomUUID();
        var student = java.util.UUID.randomUUID();
        var stranger = java.util.UUID.randomUUID();
        var c1 = java.util.UUID.randomUUID();
        var c2 = java.util.UUID.randomUUID();
        var deleted = java.util.UUID.randomUUID();
        var foreign = java.util.UUID.randomUUID();
        for (var id : java.util.List.of(c1,c2,deleted,foreign)) {
            jdbc.update("insert into course(id,teacher_id,title,deletion_state,created_at) values(?,?,'Math',?,now())",
                    id, id.equals(foreign) ? stranger : teacher, id.equals(deleted) ? "DELETING" : "ACTIVE");
            jdbc.update("insert into course_student(course_id,student_id,enrolled_at) values(?,?,now())", id, student);
        }
        var json = new tools.jackson.databind.json.JsonMapper();
        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/assignment-recipients/resolve"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        java.util.Map.of("teacherId", teacher, "studentIds", java.util.List.of(student,stranger),
                        "courseIds", java.util.List.of(c1,c2,deleted,foreign)))));
        var response = client.send(request.setHeader("Authorization","Bearer " + AssignmentCredential.valid()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var snapshot = json.readTree(response.body());
        assertThat(snapshot.path("recipients").size()).isEqualTo(1);
        assertThat(snapshot.path("recipients").get(0).path("courseIds").size()).isEqualTo(2);
        assertThat(snapshot.path("recipients").get(0).path("selectedIndividually").asBoolean()).isTrue();
        assertThat(snapshot.path("ineligibleStudentIds").toString()).contains(stranger.toString());
        assertThat(snapshot.path("invalidCourseIds").toString()).contains(deleted.toString(),foreign.toString());
        var membership = URI.create("http://localhost:" + port + "/internal/v1/teachers/" + teacher + "/students/" + student + "/membership");
        request.GET().uri(membership);
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()).contains("\"enrolled\":true");
        jdbc.update("delete from course_student where course_id in (?,?)",c1,c2);
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()).contains("\"enrolled\":false");
        jdbc.update("insert into course_student(course_id,student_id,enrolled_at) values(?,?,now())", c2, student);
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()).contains("\"enrolled\":true");
        jdbc.update("update course set deletion_state='DELETING' where id=?",c2);
        assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()).contains("\"enrolled\":false");
        assertThat(client.send(request.setHeader("Authorization","Bearer " + AssignmentCredential.token(
                "education-service","users:read","SERVICE")).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(client.send(request.setHeader("Authorization","Bearer " + TestJwt.token(java.util.List.of("ADMIN"))).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> http(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
