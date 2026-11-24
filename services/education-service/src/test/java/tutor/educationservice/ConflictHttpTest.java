package tutor.educationservice;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tutor.educationservice.IdentityHttpStub.Person;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConflictHttpTest {
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID MULTI = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00.123456Z");
    private static final IdentityHttpStub IDENTITY = new IdentityHttpStub();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("education.storage.endpoint", () -> "http://localhost:1");
        registry.add("education.storage.bucket", () -> "schedule-test");
        registry.add("education.storage.access-key", () -> "schedule-test-access");
        registry.add("education.storage.secret-key", () -> "schedule-test-secret");
        registry.add("education.identity.base-url", IDENTITY::baseUrl);
    }

    @MockitoBean MinioClient minio;
    @MockitoBean Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from lesson_exception");
        jdbc.update("delete from lesson_series");
        jdbc.update("delete from single_lesson");
        jdbc.update("delete from course_student");
        jdbc.update("delete from course");
        IDENTITY.people.clear();
        IDENTITY.people.put(TEACHER, new Person(TEACHER, List.of("TEACHER"), false));
        IDENTITY.people.put(OTHER, new Person(OTHER, List.of("TEACHER"), false));
        IDENTITY.people.put(STUDENT, new Person(STUDENT, List.of("STUDENT"), false));
        IDENTITY.people.put(ADMIN, new Person(ADMIN, List.of("ADMIN"), false));
        IDENTITY.people.put(MULTI, new Person(MULTI, List.of("TEACHER", "STUDENT"), false));
        when(clock.instant()).thenReturn(NOW);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }

    @AfterAll
    static void closeIdentity() { IDENTITY.close(); }

    @Test
    void teacherConflictIsPrivateAndRequiresConfirmationWithoutWritingLessonOrOutbox() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(TEACHER);
        jdbc.update("update course set title='private-other-title' where id=?", other);
        UUID privateLesson = seed(other, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        var body = interval("2026-12-01T14:30:00Z", "2026-12-01T15:30:00Z");
        var response = request("POST", lessons(target), TEACHER, body);
        conflict(response, "TEACHER", TEACHER);
        assertThat(response.body()).doesNotContain(other.toString(), privateLesson.toString(), "private-other-title");
        assertThat(json(response).path("conflicts").get(0).path("startsAt").asString()).isEqualTo("2026-12-01T14:30:00Z");
        assertThat(json(response).path("conflicts").get(0).path("endsAt").asString()).isEqualTo("2026-12-01T15:00:00Z");
        assertThat(lessonCount()).isEqualTo(1);
        assertThat(outboxCount()).isZero();
        var confirmed = new HashMap<>(body);
        confirmed.put("confirmConflicts", true);
        seed(other, "2026-12-01T15:00:00Z", "2026-12-01T16:00:00Z");
        assertThat(request("POST", lessons(target), TEACHER, confirmed).statusCode()).isEqualTo(201);
        assertThat(lessonCount()).isEqualTo(3);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void enrolledStudentConflictIsFoundAcrossOtherTeachersCourses() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(OTHER);
        member(target, STUDENT);
        member(other, STUDENT);
        seed(other, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        conflict(request("POST", lessons(target), TEACHER, interval("2026-12-01T14:30:00Z", "2026-12-01T15:30:00Z")), "STUDENT", STUDENT);
        assertThat(lessonCount()).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void touchingIntervalsAreAllowedAndMoveExcludesItself() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(TEACHER);
        seed(other, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        var response = request("POST", lessons(target), TEACHER, interval("2026-12-01T15:00:00Z", "2026-12-01T16:00:00Z"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String path = lessons(target) + "/" + json(response).path("singleLessonId").asString();
        var moved = request("PATCH", path, TEACHER, interval("2026-12-01T15:15:00Z", "2026-12-01T16:15:00Z"));
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(outboxCount()).isEqualTo(1);
        var event = event("LessonRescheduled");
        assertThat(event.path("oldStartsAt").asString()).isEqualTo("2026-12-01T15:00:00Z");
        assertThat(event.path("newStartsAt").asString()).isEqualTo("2026-12-01T15:15:00Z");
        assertThat(event.path("lesson").path("singleLessonId")).isEqualTo(json(response).path("singleLessonId"));
    }

    @Test
    void rejectedMoveChangesNeitherScheduleNorOutboxAndConfirmedMovePreservesOldTime() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(TEACHER);
        UUID id = seed(target, "2026-12-01T13:00:00Z", "2026-12-01T14:00:00Z");
        seed(other, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        var body = new HashMap<>(interval("2026-12-01T14:30:00Z", "2026-12-01T15:30:00Z"));
        String path = lessons(target) + "/" + id;
        conflict(request("PATCH", path, TEACHER, body), "TEACHER", TEACHER);
        assertThat(jdbc.queryForObject("select starts_at from single_lesson where id=?", OffsetDateTime.class, id).toInstant())
                .isEqualTo(Instant.parse("2026-12-01T13:00:00Z"));
        assertThat(outboxCount()).isZero();
        body.put("confirmConflicts", true);
        body.put("reason", "Подтверждённый перенос");
        assertThat(request("PATCH", path, TEACHER, body).statusCode()).isEqualTo(200);
        var event = event("LessonRescheduled");
        assertThat(event.path("oldStartsAt").asString()).isEqualTo("2026-12-01T13:00:00Z");
        assertThat(event.path("reason").asString()).isEqualTo("Подтверждённый перенос");
    }

    @Test
    void weeklyExceptionsSuppressOriginalAndExposeActualMovedTimeIncludingStoppedRules() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(TEACHER);
        UUID rule = seedSeries(other, "2026-12-01", "{2}");
        seedException(rule, "2026-12-01", "2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z");
        seedException(rule, "2026-12-08", null, null);
        assertThat(request("POST", lessons(target), TEACHER, interval("2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z")).statusCode()).isEqualTo(201);
        assertThat(request("POST", lessons(target), TEACHER, interval("2026-12-08T14:00:00Z", "2026-12-08T15:00:00Z")).statusCode()).isEqualTo(201);
        conflict(request("POST", lessons(target), TEACHER, interval("2026-12-02T14:30:00Z", "2026-12-02T15:30:00Z")), "TEACHER", TEACHER);
        seedException(rule, "2026-12-15", "2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z");
        jdbc.update("update lesson_series set stop_from_date='2026-12-20' where id=?", rule);
        conflict(request("POST", lessons(target), TEACHER, interval("2026-12-25T14:30:00Z", "2026-12-25T15:30:00Z")), "TEACHER", TEACHER);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void newSeriesChecksOnlyFirstThirtyMoscowDaysAndLeavesNoRuleOn409() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(TEACHER);
        UUID busy = seed(other, "2026-12-15T14:00:00Z", "2026-12-15T15:00:00Z");
        conflict(request("POST", series(target), TEACHER, rule(List.of(2))), "TEACHER", TEACHER);
        assertThat(jdbc.queryForObject("select count(*) from lesson_series", Integer.class)).isZero();
        assertThat(outboxCount()).isZero();
        jdbc.update("delete from single_lesson where id=?", busy);
        seed(other, "2026-12-31T14:00:00Z", "2026-12-31T15:00:00Z");
        var created = request("POST", series(target), TEACHER, rule(List.of(4)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void recurringMoveRequiresConfirmationAndExcludesStableIdentityOnSubsequentMoves() throws Exception {
        UUID target = course(TEACHER);
        UUID id = seedSeries(target, "2026-12-01", "{2}");
        String path = occurrence(target, id, "2026-12-15") + "/reschedule";
        var body = new HashMap<>(interval("2026-12-22T14:00:00Z", "2026-12-22T15:00:00Z"));
        conflict(request("POST", path, TEACHER, body), "TEACHER", TEACHER);
        assertThat(jdbc.queryForObject("select count(*) from lesson_exception", Integer.class)).isZero();
        assertThat(outboxCount()).isZero();
        body.put("confirmConflicts", true);
        assertThat(request("POST", path, TEACHER, body).statusCode()).isEqualTo(200);
        assertThat(request("POST", path, TEACHER, interval("2026-12-22T16:00:00Z", "2026-12-22T17:00:00Z")).statusCode()).isEqualTo(200);
        assertThat(request("POST", path, TEACHER, interval("2026-12-22T16:30:00Z", "2026-12-22T17:30:00Z")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from lesson_exception", Integer.class)).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(3);
    }

    @Test
    void enrollmentChecksFarFutureWeeklyPatternsBeyondThirtyDays() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(OTHER);
        member(other, STUDENT);
        seedSeries(target, "2099-01-01", "{2}");
        seedSeries(other, "2098-01-01", "{2}");
        var response = request("POST", students(target), TEACHER, Map.of("studentId", STUDENT));
        conflict(response, "STUDENT", STUDENT);
        assertThat(response.body()).contains("2099-");
        assertThat(jdbc.queryForObject("select count(*) from course_student where course_id=?", Integer.class, target)).isZero();
        assertThat(outboxCount()).isZero();
        assertThat(request("POST", students(target), TEACHER, Map.of("studentId", STUDENT, "confirmConflicts", true)).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from course_student", Integer.class)).isEqualTo(2);
    }

    @Test
    void enrollmentDoesNotMissWeeklyConflictAfterCancelledFirstWeeks() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(OTHER);
        member(other, STUDENT);
        seedSeries(target, "2026-12-01", "{2}");
        UUID rule = seedSeries(other, "2026-12-01", "{2}");
        for (String date : List.of("2026-12-01", "2026-12-08", "2026-12-15")) seedException(rule, date, null, null);
        var response = request("POST", students(target), TEACHER, Map.of("studentId", STUDENT));
        conflict(response, "STUDENT", STUDENT);
        assertThat(response.body()).contains("2026-12-22T14:00:00Z");
    }

    @Test
    void enrollmentFindsFarFutureMoveFromStoppedSeriesAndIgnoresNonoverlappingStoppedRules() throws Exception {
        UUID target = course(TEACHER);
        UUID other = course(OTHER);
        member(other, STUDENT);
        UUID rule = seedSeries(target, "2026-12-01", "{2}");
        jdbc.update("update lesson_series set stop_from_date='2026-12-20' where id=?", rule);
        seedException(rule, "2026-12-15", "2080-01-01T14:00:00Z", "2080-01-01T15:00:00Z");
        UUID busy = seed(other, "2080-01-01T14:30:00Z", "2080-01-01T15:30:00Z");
        conflict(request("POST", students(target), TEACHER, Map.of("studentId", STUDENT)), "STUDENT", STUDENT);
        jdbc.update("delete from single_lesson where id=?", busy);
        seedSeries(other, "2027-01-01", "{2}");
        assertThat(request("POST", students(target), TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
    }

    @Test
    void failedOutboxInsertRollsBackLessonUpdate() throws Exception {
        UUID target = course(TEACHER);
        UUID id = seed(target, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        jdbc.execute("create function stage7_reject_event() returns trigger language plpgsql as $$ begin raise exception 'test rollback'; end $$");
        jdbc.execute("create trigger stage7_reject_event before insert on outbox_event for each row execute function stage7_reject_event()");
        try {
            problem(request("PATCH", lessons(target) + "/" + id, TEACHER, interval("2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z")), 500, "INTERNAL_ERROR");
            assertThat(jdbc.queryForObject("select starts_at from single_lesson where id=?", OffsetDateTime.class, id).toInstant())
                    .isEqualTo(Instant.parse("2026-12-01T14:00:00Z"));
            assertThat(outboxCount()).isZero();
        } finally {
            jdbc.execute("drop trigger stage7_reject_event on outbox_event");
            jdbc.execute("drop function stage7_reject_event()");
        }
    }

    @Test
    void cancelledAndStoppedEventsUseActualOldTimesAndDoNotMultiplyOnRetries() throws Exception {
        UUID target = course(TEACHER);
        UUID single = seed(target, "2026-12-03T14:00:00Z", "2026-12-03T15:00:00Z");
        assertThat(request("POST", lessons(target) + "/" + single + "/cancel", TEACHER, Map.of("reason", "Разовая отмена")).statusCode()).isEqualTo(204);
        var cancelled = event("LessonCancelled");
        assertThat(cancelled.path("oldStartsAt").asString()).isEqualTo("2026-12-03T14:00:00Z");
        assertThat(cancelled.path("newStartsAt").isNull()).isTrue();
        jdbc.update("delete from outbox_event");
        UUID id = seedSeries(target, "2026-12-01", "{2}");
        String path = occurrence(target, id, "2026-12-15");
        assertThat(request("POST", path + "/reschedule", TEACHER, interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z")).statusCode()).isEqualTo(200);
        for (int i = 0; i < 2; i++) assertThat(request("POST", path + "/cancel", TEACHER, Map.of("reason", "Отмена переноса")).statusCode()).isEqualTo(204);
        assertThat(outboxCount()).isEqualTo(2);
        assertThat(event("LessonCancelled").path("oldStartsAt").asString()).isEqualTo("2026-12-25T14:00:00Z");
        for (int i = 0; i < 2; i++) assertThat(request("POST", series(target) + "/" + id + "/stop", TEACHER, Map.of("stopFromDate", "2026-12-20")).statusCode()).isEqualTo(200);
        assertThat(outboxCount()).isEqualTo(3);
        var stopped = event("LessonSeriesStopped");
        assertThat(stopped.path("stopFromDate").asString()).isEqualTo("2026-12-20");
        assertThat(stopped.path("lesson").path("seriesId").asString()).isEqualTo(id.toString());
        assertThat(stopped.path("lesson").path("originalDate").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where published_at is not null", Integer.class)).isZero();
    }

    @Test
    void v5UpgradePreservesExistingDataAndAddsOutboxWithoutResourceForeignKeys() {
        String schema = "stage7_upgrade";
        try {
            var old = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("5").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(5);
            jdbc.execute("create table stage7_upgrade.marker(value integer)");
            jdbc.update("insert into stage7_upgrade.marker values (42)");
            var current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("6").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select value from stage7_upgrade.marker", Integer.class)).isEqualTo(42);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=? and table_name='outbox_event' and constraint_type='FOREIGN KEY'", Integer.class, schema)).isZero();
            assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=? and tablename='outbox_event'", String.class, schema)).contains("idx_outbox_event_pending");
        } finally { jdbc.execute("drop schema if exists stage7_upgrade cascade"); }
    }

    private String series(UUID course) { return "/api/v1/courses/" + course + "/lesson-series"; }
    private String students(UUID course) { return "/api/v1/courses/" + course + "/students"; }
    private String occurrence(UUID course, UUID rule, String date) { return series(course) + "/" + rule + "/occurrences/" + date; }
    private Map<String, Object> rule(List<Integer> days) {
        return Map.of("startDate", "2026-12-01", "weekdays", days, "startTime", "17:00", "durationMinutes", 90);
    }
    private UUID seedSeries(UUID course, String start, String days) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into lesson_series(id,course_id,start_date,weekdays,start_time,duration_minutes,created_at) values (?,?,cast(? as date),cast(? as smallint[]),'17:00',90,?)",
                id, course, start, days, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return id;
    }
    private void seedException(UUID rule, String original, String start, String end) {
        jdbc.update("insert into lesson_exception(id,series_id,original_date,kind,new_starts_at,new_ends_at,created_at) values (?,?,cast(? as date),?,cast(? as timestamptz),cast(? as timestamptz),?)",
                UUID.randomUUID(), rule, original, start == null ? "CANCELLED" : "RESCHEDULED", start, end, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }
    private int outboxCount() { return jdbc.queryForObject("select count(*) from outbox_event", Integer.class); }
    private JsonNode event(String type) {
        var body = mapper.readTree(jdbc.queryForObject("select payload::text from outbox_event where event_type=?", String.class, type));
        assertThat(body.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(body.path("eventId").asString())).isNotNull();
        return body;
    }
    private void conflict(HttpResponse<String> response, String type, UUID id) {
        problem(response, 409, "SCHEDULE_CONFLICTS");
        var items = json(response).path("conflicts");
        assertThat(items.size()).isPositive();
        for (var item : items) {
            assertThat(item.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("subjectType", "subjectId", "startsAt", "endsAt");
            assertThat(item.path("subjectType").asString()).isEqualTo(type);
            assertThat(item.path("subjectId").asString()).isEqualTo(id.toString());
        }
    }

    private UUID course(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into course(id,teacher_id,title,created_at) values (?,?,'Курс',?)", id, owner, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return id;
    }

    private void member(UUID course, UUID student) {
        jdbc.update("insert into course_student(course_id,student_id,enrolled_at) values (?,?,?)", course, student, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private UUID seed(UUID course, String start, String end) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into single_lesson values (?,?,?,?,?)", id, course, OffsetDateTime.parse(start), OffsetDateTime.parse(end), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return id;
    }

    private String lessons(UUID course) { return "/api/v1/courses/" + course + "/lessons"; }
    private String schedule(UUID course) { return "/api/v1/courses/" + course + "/schedule?month=2026-12"; }
    private int lessonCount() { return jdbc.queryForObject("select count(*) from single_lesson", Integer.class); }
    private Map<String, Object> interval(String start, String end) { return Map.of("startsAt", start, "endsAt", end); }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private HttpResponse<String> get(UUID actor, String path) throws Exception { return request("GET", path, actor, null); }

    private HttpResponse<String> request(String method, String path, UUID actor, Map<String, ?> body) throws Exception {
        return request(method, path, actor, body, null);
    }

    private HttpResponse<String> request(String method, String path, UUID actor, Map<String, ?> body, String idempotencyKey) throws Exception {
        String token = TestJwt.token(IDENTITY.people.get(actor).roles(), claims -> claims.subject(actor.toString()), false);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json(response).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("stackTrace", "private-upstream-diagnostic", "\"courseId\":");
        assertThat(json(response).has("startsAt")).isFalse();
    }
}
