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
class ScheduleHttpTest {
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
    void createReturnsExactSingleDtoAndNormalizesOffsetToUtc() throws Exception {
        UUID course = course(TEACHER);
        var response = request("POST", lessons(course), TEACHER, interval("2026-12-01T17:00:00+03:00", "2026-12-01T17:05:00+03:00"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var body = json(response);
        assertThat(body.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "courseId", "kind", "singleLessonId", "seriesId", "originalDate", "startsAt", "endsAt", "rescheduled");
        assertThat(body.path("courseId").asString()).isEqualTo(course.toString());
        assertThat(body.path("kind").asString()).isEqualTo("SINGLE");
        assertThat(body.path("startsAt").asString()).isEqualTo("2026-12-01T14:00:00Z");
        assertThat(body.path("endsAt").asString()).isEqualTo("2026-12-01T14:05:00Z");
        assertThat(body.path("seriesId").isNull()).isTrue();
        assertThat(body.path("originalDate").isNull()).isTrue();
        assertThat(body.path("rescheduled").asBoolean()).isFalse();
        assertThat(response.headers().firstValue("Location")).contains(lessons(course) + "/" + body.path("singleLessonId").asString());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json(get(TEACHER, schedule(course))).path("lessons").get(0)).isEqualTo(body);
        var same = json(request("POST", lessons(course), TEACHER, interval("2026-12-01T14:00:00Z", "2026-12-01T14:05:00Z")));
        assertThat(same.path("startsAt")).isEqualTo(body.path("startsAt"));
        assertThat(jdbc.queryForObject("select created_at from single_lesson limit 1", OffsetDateTime.class).toInstant()).isEqualTo(NOW);
    }

    @Test
    void durationBoundsAreExactAndPostgresPrecisionDoesNotChangeResponse() throws Exception {
        UUID course = course(TEACHER);
        var start = Instant.parse("2026-12-01T14:00:00.123456789Z");
        for (long seconds : new long[]{300, 18000}) {
            var response = request("POST", lessons(course), TEACHER, interval(start.toString(), start.plusSeconds(seconds).toString()));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            assertThat(json(response).path("startsAt").asString()).isEqualTo("2026-12-01T14:00:00.123456Z");
        }
        for (var duration : List.of(Duration.ofMinutes(4), Duration.ofMinutes(301), Duration.ZERO,
                Duration.ofMinutes(-5), Duration.ofMinutes(5).minusNanos(1), Duration.ofMinutes(300).plusNanos(1))) {
            problem(request("POST", lessons(course), TEACHER, interval(start.toString(), start.plus(duration).toString())), 422, "INVALID_LESSON_TIME");
        }
        assertThat(lessonCount()).isEqualTo(2);
    }

    @Test
    void missingTimesLocalDatesMalformedUuidAndUnknownFieldsAreRejected() throws Exception {
        UUID course = course(TEACHER);
        problem(request("POST", lessons(course), TEACHER, Map.of()), 422, "INVALID_REQUEST");
        problem(request("POST", lessons(course), TEACHER, interval("2026-12-01T14:00:00", "2026-12-01T15:00:00")), 400, "INVALID_REQUEST");
        problem(request("POST", lessons(course), TEACHER, interval("bad", "2026-12-01T15:00:00Z")), 400, "INVALID_REQUEST");
        var body = new HashMap<>(interval("2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z"));
        body.put("status", "PLANNED");
        problem(request("POST", lessons(course), TEACHER, body), 400, "INVALID_REQUEST");
        problem(request("POST", "/api/v1/courses/bad/lessons", TEACHER, Map.of()), 400, "INVALID_REQUEST");
        assertThat(lessonCount()).isZero();
    }

    @Test
    void moveChangesOnlyOneLessonAndPreservesItsIdentityAndCreationTime() throws Exception {
        UUID course = course(TEACHER);
        UUID first = seed(course, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        UUID second = seed(course, "2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z");
        var body = new HashMap<>(interval("2027-01-03T17:00:00+03:00", "2027-01-03T18:00:00+03:00"));
        body.put("reason", "Перенос");
        body.put("confirmConflicts", true);
        var response = request("PATCH", lessons(course) + "/" + first, TEACHER, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("singleLessonId").asString()).isEqualTo(first.toString());
        assertThat(json(response).path("startsAt").asString()).isEqualTo("2027-01-03T14:00:00Z");
        assertThat(ids(json(get(TEACHER, schedule(course))))).containsExactly(second);
        assertThat(ids(json(get(TEACHER, schedule(course).replace("2026-12", "2027-01"))))).containsExactly(first);
        assertThat(jdbc.queryForObject("select created_at from single_lesson where id=?", OffsetDateTime.class, first).toInstant()).isEqualTo(NOW);
        problem(request("PATCH", lessons(course) + "/" + first, TEACHER, Map.of("startsAt", "2027-01-04T14:00:00Z")), 422, "INVALID_REQUEST");
        problem(request("PATCH", lessons(course) + "/" + first, TEACHER, interval("2027-01-04T15:00:00Z", "2027-01-04T14:00:00Z")), 422, "INVALID_LESSON_TIME");
        assertThat(jdbc.queryForObject("select starts_at from single_lesson where id=?", OffsetDateTime.class, first).toInstant()).isEqualTo(Instant.parse("2027-01-03T14:00:00Z"));
    }

    @Test
    void cancelPhysicallyDeletesAndCannotBeRestoredOrMoved() throws Exception {
        UUID course = course(TEACHER);
        UUID lesson = seed(course, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        var response = request("POST", lessons(course) + "/" + lesson + "/cancel", TEACHER, Map.of("reason", "Отмена"));
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(lessonCount()).isZero();
        assertThat(ids(json(get(TEACHER, schedule(course))))).isEmpty();
        problem(request("POST", lessons(course) + "/" + lesson + "/cancel", TEACHER, Map.of()), 404, "NOT_FOUND");
        problem(request("PATCH", lessons(course) + "/" + lesson, TEACHER, interval("2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z")), 404, "NOT_FOUND");
    }

    @Test
    void currentStudentCanReadButCannotManageEvenWithTeacherRole() throws Exception {
        UUID course = course(TEACHER);
        UUID lesson = seed(course, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        member(course, STUDENT);
        member(course, MULTI);
        for (UUID actor : List.of(STUDENT, MULTI, OTHER)) {
            if (!actor.equals(OTHER)) assertThat(ids(json(get(actor, schedule(course))))).containsExactly(lesson);
            else problem(get(actor, schedule(course)), 404, "NOT_FOUND");
            int status = actor.equals(STUDENT) ? 403 : 404;
            String code = status == 403 ? "ACCESS_DENIED" : "NOT_FOUND";
            var body = interval("2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
            problem(request("POST", lessons(course), actor, body), status, code);
            problem(request("PATCH", lessons(course) + "/" + lesson, actor, body), status, code);
            problem(request("POST", lessons(course) + "/" + lesson + "/cancel", actor, Map.of()), status, code);
        }
        jdbc.update("delete from course_student where course_id=? and student_id=?", course, STUDENT);
        problem(get(STUDENT, schedule(course)), 404, "NOT_FOUND");
        assertThat(ids(json(get(STUDENT, "/api/v1/me/schedule?month=2026-12")))).isEmpty();
        IDENTITY.people.put(MULTI, new Person(MULTI, List.of("TEACHER", "STUDENT"), true));
        problem(get(MULTI, schedule(course)), 403, "ACCOUNT_UNAVAILABLE");
    }

    @Test
    void adminManagesAnyCourseButPersonalCalendarDoesNotBecomeGlobal() throws Exception {
        UUID course = course(TEACHER);
        var body = interval("2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        var created = request("POST", lessons(course), ADMIN, body);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String path = lessons(course) + "/" + json(created).path("singleLessonId").asString();
        assertThat(ids(json(get(ADMIN, schedule(course))))).hasSize(1);
        assertThat(ids(json(get(ADMIN, "/api/v1/me/schedule?month=2026-12")))).isEmpty();
        assertThat(request("PATCH", path, ADMIN, body).statusCode()).isEqualTo(200);
        assertThat(request("POST", path + "/cancel", ADMIN, Map.of()).statusCode()).isEqualTo(204);
    }

    @Test
    void lessonIdCannotBeUsedWithAnotherCourse() throws Exception {
        UUID first = course(TEACHER);
        UUID second = course(TEACHER);
        UUID lesson = seed(first, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        problem(request("PATCH", lessons(second) + "/" + lesson, TEACHER, interval("2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z")), 404, "NOT_FOUND");
        problem(request("POST", lessons(second) + "/" + lesson + "/cancel", ADMIN, Map.of()), 404, "NOT_FOUND");
        assertThat(lessonCount()).isEqualTo(1);
    }

    @Test
    void calendarUsesMoscowHalfOpenMonthAndIncludesCrossingLessonsInStartOrder() throws Exception {
        UUID course = course(TEACHER);
        UUID late = seed(course, "2026-12-31T20:58:00Z", "2026-12-31T21:03:00Z");
        seed(course, "2026-12-31T21:00:00Z", "2026-12-31T21:05:00Z");
        UUID middle = seed(course, "2026-12-10T14:00:00Z", "2026-12-10T15:00:00Z");
        UUID crossing = seed(course, "2026-11-30T20:58:00Z", "2026-11-30T21:03:00Z");
        UUID boundary = seed(course, "2026-11-30T21:00:00Z", "2026-11-30T21:05:00Z");
        seed(course, "2026-11-30T20:55:00Z", "2026-11-30T21:00:00Z");
        var response = get(TEACHER, schedule(course));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var page = json(response);
        assertThat(page.path("month").asString()).isEqualTo("2026-12");
        assertThat(page.path("timeZone").asString()).isEqualTo("Europe/Moscow");
        assertThat(page.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("month", "timeZone", "lessons");
        assertThat(ids(page)).containsExactly(crossing, boundary, middle, late);
        assertThat(page.path("lessons").get(0).path("startsAt").asString()).isEqualTo("2026-11-30T20:58:00Z");
    }

    @Test
    void personalCalendarUnionsOwnedAndCurrentEnrolledCoursesWithoutDuplicates() throws Exception {
        UUID owned = course(MULTI);
        UUID enrolled = course(TEACHER);
        UUID foreign = course(OTHER);
        UUID deleting = course(MULTI);
        member(owned, MULTI);
        member(enrolled, MULTI);
        UUID first = seed(owned, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        UUID second = seed(enrolled, "2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z");
        seed(foreign, "2026-12-03T14:00:00Z", "2026-12-03T15:00:00Z");
        seed(deleting, "2026-12-04T14:00:00Z", "2026-12-04T15:00:00Z");
        jdbc.update("update course set deletion_state='DELETING' where id=?", deleting);
        jdbc.update("update course_student set can_send_messages=false where student_id=?", MULTI);
        assertThat(ids(json(get(MULTI, "/api/v1/me/schedule?month=2026-12")))).containsExactly(first, second).doesNotHaveDuplicates();
        assertThat(ids(json(get(TEACHER, "/api/v1/me/schedule?month=2026-12")))).containsExactly(second);
        jdbc.update("delete from course_student where course_id=? and student_id=?", enrolled, MULTI);
        assertThat(ids(json(get(MULTI, "/api/v1/me/schedule?month=2026-12")))).containsExactly(first);
    }

    @Test
    void monthIsRequiredAndStrictForBothCalendars() throws Exception {
        UUID course = course(TEACHER);
        for (String base : List.of("/api/v1/courses/" + course + "/schedule", "/api/v1/me/schedule")) {
            problem(get(TEACHER, base), 400, "INVALID_REQUEST");
            for (String month : List.of("2026-1", "2026-00", "2026-13", "2026-12-01", "0000-01", "bad", "")) {
                problem(get(TEACHER, base + "?month=" + month), 400, "INVALID_MONTH");
            }
            for (String month : List.of("2024-02", "2026-02", "2026-12", "2027-01")) {
                assertThat(get(TEACHER, base + "?month=" + month).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void deletingCourseIsAbsentAndEveryMutationIsHidden() throws Exception {
        UUID course = course(TEACHER);
        UUID lesson = seed(course, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
        member(course, STUDENT);
        jdbc.update("update course set deletion_state='DELETING' where id=?", course);
        for (UUID actor : List.of(TEACHER, ADMIN, STUDENT)) problem(get(actor, schedule(course)), 404, "NOT_FOUND");
        for (UUID actor : List.of(TEACHER, ADMIN)) {
            problem(request("POST", lessons(course), actor, interval("2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z")), 404, "NOT_FOUND");
            problem(request("PATCH", lessons(course) + "/" + lesson, actor, interval("2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z")), 404, "NOT_FOUND");
            problem(request("POST", lessons(course) + "/" + lesson + "/cancel", actor, Map.of()), 404, "NOT_FOUND");
        }
        assertThat(ids(json(get(STUDENT, "/api/v1/me/schedule?month=2026-12")))).isEmpty();
        assertThat(lessonCount()).isEqualTo(1);
    }

    @Test
    void createMoveAndCancelWaitingForDeletingCannotCommit() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            for (String operation : List.of("create", "move", "cancel")) {
                UUID course = course(TEACHER);
                UUID lesson = seed(course, "2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z");
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("update course set deletion_state='DELETING' where id=?")) {
                        statement.setObject(1, course);
                        statement.executeUpdate();
                    }
                    String path = lessons(course) + (operation.equals("create") ? "" : "/" + lesson) + (operation.equals("cancel") ? "/cancel" : "");
                    var response = pool.submit(() -> request(operation.equals("move") ? "PATCH" : "POST", path, TEACHER,
                            operation.equals("cancel") ? Map.of() : interval("2026-12-02T14:00:00Z", "2026-12-02T15:00:00Z")));
                    await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname=current_database() and wait_event_type='Lock' and query ilike '%course%'
                            """, Integer.class) > 0);
                    connection.commit();
                    problem(response.get(5, TimeUnit.SECONDS), 404, "NOT_FOUND");
                    assertThat(jdbc.queryForObject("select starts_at from single_lesson where id=?", OffsetDateTime.class, lesson).toInstant())
                            .isEqualTo(Instant.parse("2026-12-01T14:00:00Z"));
                    assertThat(jdbc.queryForObject("select count(*) from single_lesson where course_id=?", Integer.class, course)).isEqualTo(1);
                }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void idempotencyHeaderIsOptionalAndDoesNotChangeCreationSemantics() throws Exception {
        UUID course = course(TEACHER);
        var body = new HashMap<>(interval("2026-12-01T14:00:00Z", "2026-12-01T15:00:00Z"));
        body.put("confirmConflicts", true);
        var first = request("POST", lessons(course), TEACHER, body);
        var second = request("POST", lessons(course), TEACHER, body, "same-key");
        var third = request("POST", lessons(course), TEACHER, body, "same-key");
        for (var response : List.of(first, second, third)) assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(List.of(json(first).path("singleLessonId"), json(second).path("singleLessonId"), json(third).path("singleLessonId")))
                .doesNotHaveDuplicates();
        assertThat(lessonCount()).isEqualTo(3);
    }

    @Test
    void v3UpgradePreservesCourseMembershipAndAddsStrictLessonConstraints() {
        String schema = "stage5_upgrade";
        UUID course = UUID.randomUUID();
        try {
            var previous = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("3").load();
            assertThat(previous.migrate().migrationsExecuted).isEqualTo(3);
            jdbc.update("insert into stage5_upgrade.course(id,teacher_id,title,created_at) values (?,?,'Сохранить',now())", course, TEACHER);
            jdbc.update("insert into stage5_upgrade.course_student values (?,?,false,now())", course, STUDENT);
            var current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("4").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select title from stage5_upgrade.course", String.class)).isEqualTo("Сохранить");
            assertThat(jdbc.queryForObject("select can_send_messages from stage5_upgrade.course_student", Boolean.class)).isFalse();
            for (int minutes : new int[]{4, 0, -1, 301}) {
                assertThatThrownBy(() -> jdbc.update("insert into stage5_upgrade.single_lesson values (?,?,now(),now()+make_interval(mins => ?),now())",
                        UUID.randomUUID(), course, minutes)).isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> jdbc.update("insert into stage5_upgrade.single_lesson values (?,?,now(),now()+interval '5 minutes',now())",
                    UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("insert into stage5_upgrade.single_lesson values (?,?,now(),now()+interval '5 hours',now())", UUID.randomUUID(), course);
            assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name='single_lesson'", String.class, schema))
                    .containsExactlyInAnyOrder("id", "course_id", "starts_at", "ends_at", "created_at");
            assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=? and tablename='single_lesson'", String.class, schema))
                    .contains("idx_single_lesson_course_starts");
        } finally { jdbc.execute("drop schema if exists stage5_upgrade cascade"); }
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
    private Map<String, Object> interval(String start, String end) { return Map.of("startsAt", start, "endsAt", end, "confirmConflicts", true); }
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

    private List<UUID> ids(JsonNode page) {
        var result = new ArrayList<UUID>();
        for (var lesson : page.path("lessons")) result.add(UUID.fromString(lesson.path("singleLessonId").asString()));
        return result;
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json(response).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("stackTrace", "private-upstream-diagnostic", "\"startsAt\":", "\"courseId\":");
    }
}
