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
class SeriesHttpTest {
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
    void createsIndefiniteRuleWithSortedWeekdaysAndExactDtoWithoutGeneratingRows() throws Exception {
        UUID course = course(TEACHER);
        var response = request("POST", series(course), TEACHER, rule("2026-12-01", List.of(4, 1), 90));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var body = json(response);
        assertThat(body.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "id", "courseId", "startDate", "weekdays", "startTime", "durationMinutes", "stopFromDate", "timeZone");
        UUID id = UUID.fromString(body.path("id").asString());
        assertThat(response.headers().firstValue("Location")).contains(series(course) + "/" + id);
        assertThat(body.path("weekdays").toString()).isEqualTo("[1,4]");
        assertThat(body.path("startDate").asString()).isEqualTo("2026-12-01");
        assertThat(body.path("startTime").asString()).isEqualTo("17:00:00");
        assertThat(body.path("stopFromDate").isNull()).isTrue();
        assertThat(body.path("timeZone").asString()).isEqualTo("Europe/Moscow");
        assertThat(json(get(TEACHER, series(course))).path("items").get(0)).isEqualTo(body);
        var dates = items(course, TEACHER, "2026-12");
        assertThat(originalDates(dates)).containsExactly("2026-12-03", "2026-12-07", "2026-12-10", "2026-12-14",
                "2026-12-17", "2026-12-21", "2026-12-24", "2026-12-28", "2026-12-31");
        assertThat(dates.get(0).path("startsAt").asString()).isEqualTo("2026-12-03T14:00:00Z");
        assertThat(dates.get(0).path("endsAt").asString()).isEqualTo("2026-12-03T15:30:00Z");
        assertThat(dates.get(0).path("singleLessonId").isNull()).isTrue();
        assertThat(dates.get(0).path("seriesId").asString()).isEqualTo(id.toString());
        assertThat(dates.get(0).path("rescheduled").asBoolean()).isFalse();
        assertThat(items(course, TEACHER, "2099-01").size()).isBetween(8, 10);
        assertThat(jdbc.queryForObject("select count(*) from lesson_series", Integer.class)).isEqualTo(1);
        assertThat(exceptionCount()).isZero();
        assertThat(lessonCount()).isZero();
    }

    @Test
    void validatesWeekdaysDurationsRequiredFieldsAndStrictJson() throws Exception {
        UUID course = course(TEACHER);
        for (var days : List.of(List.<Integer>of(), List.of(0), List.of(8))) {
            problem(request("POST", series(course), TEACHER, rule("2026-12-01", days, 90)), 422, "INVALID_REQUEST");
        }
        problem(request("POST", series(course), TEACHER, rule("2026-12-01", List.of(2, 2), 90)), 422, "INVALID_WEEKDAYS");
        problem(request("POST", series(course), TEACHER, rule("2026-12-01", Arrays.asList(2, null), 90)), 422, "INVALID_REQUEST");
        for (int minutes : new int[]{4, 301}) problem(request("POST", series(course), TEACHER, rule("2026-12-01", List.of(2), minutes)), 422, "INVALID_REQUEST");
        problem(request("POST", series(course), TEACHER, Map.of()), 422, "INVALID_REQUEST");
        problem(request("POST", series(course), TEACHER, rule("2026-02-30", List.of(2), 90)), 400, "INVALID_REQUEST");
        var unknown = new HashMap<>(rule("2026-12-01", List.of(2), 90));
        unknown.put("stopFromDate", "2026-12-20");
        problem(request("POST", series(course), TEACHER, unknown), 400, "INVALID_REQUEST");
        assertThat(jdbc.queryForObject("select count(*) from lesson_series", Integer.class)).isZero();
        for (int minutes : new int[]{5, 300}) assertThat(request("POST", series(course), TEACHER, rule("2026-12-01", List.of(2), minutes)).statusCode()).isEqualTo(201);
    }

    @Test
    void crossesYearsAndLeapMonthWithoutPersistingOccurrences() throws Exception {
        UUID course = course(TEACHER);
        createSeries(course, "2026-12-31", List.of(4));
        assertThat(originalDates(items(course, TEACHER, "2026-12"))).containsExactly("2026-12-31");
        assertThat(originalDates(items(course, TEACHER, "2027-01"))).containsExactly("2027-01-07", "2027-01-14", "2027-01-21", "2027-01-28");
        UUID leap = course(TEACHER);
        var body = new HashMap<>(rule("2024-02-01", List.of(1, 2, 3, 4, 5, 6, 7), 5));
        body.put("startTime", "23:58");
        assertThat(request("POST", series(leap), TEACHER, body).statusCode()).isEqualTo(201);
        assertThat(items(leap, TEACHER, "2024-02")).hasSize(29);
        var march = items(leap, TEACHER, "2024-03");
        assertThat(march).hasSize(32);
        assertThat(march.get(0).path("originalDate").asString()).isEqualTo("2024-02-29");
        assertThat(march.get(0).path("startsAt").asString()).isEqualTo("2024-02-29T20:58:00Z");
        assertThat(march.get(0).path("endsAt").asString()).isEqualTo("2024-02-29T21:03:00Z");
        assertThat(lessonCount()).isZero();
    }

    @Test
    void repeatedMoveKeepsStableIdentityAndOneExceptionWithLatestReason() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        String path = occurrence(course, id, "2026-12-15") + "/reschedule";
        var first = request("POST", path, TEACHER, interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z"));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        UUID exceptionId = jdbc.queryForObject("select id from lesson_exception", UUID.class);
        var body = new HashMap<>(interval("2026-12-26T17:00:00+03:00", "2026-12-26T18:00:00+03:00"));
        body.put("reason", "Повторный перенос");
        body.put("confirmConflicts", true);
        var moved = request("POST", path, ADMIN, body);
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        var dto = json(moved);
        assertThat(dto.path("originalDate").asString()).isEqualTo("2026-12-15");
        assertThat(dto.path("seriesId").asString()).isEqualTo(id.toString());
        assertThat(dto.path("startsAt").asString()).isEqualTo("2026-12-26T14:00:00Z");
        assertThat(dto.path("rescheduled").asBoolean()).isTrue();
        assertThat(exceptionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from lesson_exception", UUID.class)).isEqualTo(exceptionId);
        assertThat(jdbc.queryForObject("select reason from lesson_exception", String.class)).isEqualTo("Повторный перенос");
        assertThat(jdbc.queryForObject("select created_at from lesson_exception", OffsetDateTime.class).toInstant()).isEqualTo(NOW);
        assertThat(items(course, TEACHER, "2026-12").stream().filter(item -> item.path("originalDate").asString().equals("2026-12-15")).toList())
                .containsExactly(dto);
    }

    @Test
    void movedOccurrenceTravelsBetweenMonthsWithoutOriginalDuplicate() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        String path = occurrence(course, id, "2026-12-15") + "/reschedule";
        assertThat(request("POST", path, TEACHER, interval("2027-01-05T14:00:00Z", "2027-01-05T15:00:00Z")).statusCode()).isEqualTo(200);
        assertThat(originalDates(items(course, TEACHER, "2026-12"))).doesNotContain("2026-12-15");
        assertThat(originalDates(items(course, TEACHER, "2027-01"))).containsOnlyOnce("2026-12-15");
        assertThat(request("POST", path, TEACHER, interval("2026-11-20T14:00:00Z", "2026-11-20T15:00:00Z")).statusCode()).isEqualTo(200);
        assertThat(originalDates(items(course, TEACHER, "2026-11"))).containsExactly("2026-12-15");
        assertThat(originalDates(items(course, TEACHER, "2027-01"))).doesNotContain("2026-12-15");
        assertThat(exceptionCount()).isEqualTo(1);
    }

    @Test
    void futureRuleCanMoveOriginalBackBeforeStartMonth() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2027-01-01", List.of(2));
        assertThat(request("POST", occurrence(course, id, "2027-01-05") + "/reschedule", TEACHER,
                interval("2026-12-10T14:00:00Z", "2026-12-10T15:00:00Z")).statusCode()).isEqualTo(200);
        assertThat(originalDates(items(course, TEACHER, "2026-12"))).containsExactly("2027-01-05");
        assertThat(originalDates(items(course, TEACHER, "2027-01"))).doesNotContain("2027-01-05");
    }

    @Test
    void cancelledOccurrenceStaysCancelledAndCannotBeMovedOrRestored() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        String path = occurrence(course, id, "2026-12-15");
        assertThat(request("POST", path + "/reschedule", TEACHER, interval("2027-01-05T14:00:00Z", "2027-01-05T15:00:00Z")).statusCode()).isEqualTo(200);
        for (UUID actor : List.of(TEACHER, ADMIN)) assertThat(request("POST", path + "/cancel", actor, Map.of("reason", "Отмена")).statusCode()).isEqualTo(204);
        problem(request("POST", path + "/reschedule", TEACHER, interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z")), 409, "LESSON_CANCELLED");
        assertThat(exceptionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select kind from lesson_exception", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select new_starts_at is null and new_ends_at is null from lesson_exception", Boolean.class)).isTrue();
        assertThat(originalDates(items(course, TEACHER, "2026-12"))).doesNotContain("2026-12-15");
        assertThat(originalDates(items(course, TEACHER, "2027-01"))).doesNotContain("2026-12-15");
        assertThat(request("POST", occurrence(course, id, "2026-12-22") + "/cancel", TEACHER, Map.of()).statusCode()).isEqualTo(204);
        assertThat(exceptionCount()).isEqualTo(2);
    }

    @Test
    void stop20DecemberPreserves15MovedTo25ButRemovesLaterOriginals() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2, 5));
        assertThat(request("POST", occurrence(course, id, "2026-12-15") + "/reschedule", TEACHER,
                interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z")).statusCode()).isEqualTo(200);
        assertThat(request("POST", occurrence(course, id, "2026-12-22") + "/reschedule", TEACHER,
                interval("2026-12-19T14:00:00Z", "2026-12-19T15:00:00Z")).statusCode()).isEqualTo(200);
        var stopped = request("POST", series(course) + "/" + id + "/stop", TEACHER, Map.of("stopFromDate", "2026-12-20"));
        assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
        assertThat(json(stopped).path("stopFromDate").asString()).isEqualTo("2026-12-20");
        var calendar = items(course, TEACHER, "2026-12");
        assertThat(originalDates(calendar)).containsExactly("2026-12-01", "2026-12-04", "2026-12-08", "2026-12-11", "2026-12-18", "2026-12-15")
                .doesNotContain("2026-12-25");
        assertThat(calendar.get(5).path("startsAt").asString()).isEqualTo("2026-12-25T14:00:00Z");
        assertThat(json(get(TEACHER, series(course))).path("items").get(0).path("stopFromDate").asString()).isEqualTo("2026-12-20");
        assertThat(exceptionCount()).isEqualTo(2);
    }

    @Test
    void stopIsInclusiveAndOldMovedOccurrenceSurvivesInLaterMonth() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        assertThat(request("POST", occurrence(course, id, "2026-12-08") + "/reschedule", TEACHER,
                interval("2027-01-02T14:00:00Z", "2027-01-02T15:00:00Z")).statusCode()).isEqualTo(200);
        String stop = series(course) + "/" + id + "/stop";
        for (int i = 0; i < 2; i++) assertThat(request("POST", stop, TEACHER, Map.of("stopFromDate", "2026-12-15")).statusCode()).isEqualTo(200);
        assertThat(originalDates(items(course, TEACHER, "2026-12"))).containsExactly("2026-12-01");
        assertThat(originalDates(items(course, TEACHER, "2027-01"))).containsExactly("2026-12-08");
        problem(request("POST", occurrence(course, id, "2026-12-15") + "/cancel", TEACHER, Map.of()), 409, "SERIES_STOPPED");
        problem(request("POST", occurrence(course, id, "2026-12-22") + "/reschedule", TEACHER,
                interval("2026-12-23T14:00:00Z", "2026-12-23T15:00:00Z")), 409, "SERIES_STOPPED");
        problem(request("POST", stop, ADMIN, Map.of("stopFromDate", "2026-12-30")), 409, "SERIES_ALREADY_STOPPED");
        UUID early = createSeries(course, "2027-02-01", List.of(1));
        assertThat(request("POST", series(course) + "/" + early + "/stop", TEACHER, Map.of("stopFromDate", "2027-01-01")).statusCode()).isEqualTo(200);
        assertThat(items(course, TEACHER, "2027-02")).isEmpty();
    }

    @Test
    void validatesOccurrenceDateIntervalsAndCourseBinding() throws Exception {
        UUID course = course(TEACHER);
        UUID other = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        for (String date : List.of("2026-11-24", "2026-12-02")) {
            problem(request("POST", occurrence(course, id, date) + "/cancel", TEACHER, Map.of()), 404, "NOT_FOUND");
        }
        String path = occurrence(course, id, "2026-12-15") + "/reschedule";
        problem(request("POST", path, TEACHER, interval("2026-12-25T14:00:00Z", "2026-12-25T14:04:00Z")), 422, "INVALID_LESSON_TIME");
        problem(request("POST", path, TEACHER, Map.of()), 422, "INVALID_REQUEST");
        problem(request("POST", occurrence(course, id, "2026-02-30") + "/cancel", TEACHER, Map.of()), 400, "INVALID_REQUEST");
        problem(request("POST", occurrence(other, id, "2026-12-15") + "/cancel", ADMIN, Map.of()), 404, "NOT_FOUND");
        problem(request("POST", series(other) + "/" + id + "/stop", TEACHER, Map.of("stopFromDate", "2026-12-20")), 404, "NOT_FOUND");
        assertThat(exceptionCount()).isZero();
    }

    @Test
    void rulesRemainPrivateWhileMembersReadMixedPersonalCalendar() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        member(course, STUDENT);
        member(course, MULTI);
        for (UUID actor : List.of(STUDENT, MULTI, OTHER)) {
            int status = actor.equals(STUDENT) ? 403 : 404;
            String code = status == 403 ? "ACCESS_DENIED" : "NOT_FOUND";
            problem(get(actor, series(course)), status, code);
            problem(request("POST", series(course), actor, rule("2026-12-01", List.of(2), 90)), status, code);
            problem(request("POST", series(course) + "/" + id + "/stop", actor, Map.of("stopFromDate", "2026-12-20")), status, code);
            problem(request("POST", occurrence(course, id, "2026-12-15") + "/cancel", actor, Map.of()), status, code);
            problem(request("POST", occurrence(course, id, "2026-12-15") + "/reschedule", actor,
                    interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z")), status, code);
        }
        assertThat(get(ADMIN, series(course)).statusCode()).isEqualTo(200);
        UUID single = seed(course, "2026-12-01T13:00:00Z", "2026-12-01T14:00:00Z");
        var memberCalendar = items(course, STUDENT, "2026-12");
        assertThat(memberCalendar).hasSize(6);
        assertThat(memberCalendar.get(0).path("singleLessonId").asString()).isEqualTo(single.toString());
        assertThat(json(get(STUDENT, "/api/v1/me/schedule?month=2026-12")).path("lessons").size()).isEqualTo(6);
        assertThat(json(get(ADMIN, "/api/v1/me/schedule?month=2026-12")).path("lessons").size()).isZero();
        jdbc.update("delete from course_student where course_id=? and student_id=?", course, STUDENT);
        problem(get(STUDENT, schedule(course)), 404, "NOT_FOUND");
        assertThat(json(get(STUDENT, "/api/v1/me/schedule?month=2026-12")).path("lessons").size()).isZero();
        UUID own = course(MULTI);
        assertThat(request("POST", series(own), ADMIN, rule("2026-12-01", List.of(2), 90)).statusCode()).isEqualTo(201);
        member(own, MULTI);
        assertThat(json(get(MULTI, "/api/v1/me/schedule?month=2026-12")).path("lessons").size()).isEqualTo(11);
        jdbc.update("delete from course_student where course_id=? and student_id=?", course, MULTI);
        assertThat(json(get(MULTI, "/api/v1/me/schedule?month=2026-12")).path("lessons").size()).isEqualTo(5);
        IDENTITY.people.put(MULTI, new Person(MULTI, List.of("TEACHER", "STUDENT"), true));
        problem(get(MULTI, schedule(course)), 403, "ACCOUNT_UNAVAILABLE");
    }

    @Test
    void adminCreatesForCourseAndDeletingHidesEveryRuleOperation() throws Exception {
        UUID course = course(TEACHER);
        var created = request("POST", series(course), ADMIN, rule("2026-12-01", List.of(2), 90));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id = UUID.fromString(json(created).path("id").asString());
        jdbc.update("update course set deletion_state='DELETING' where id=?", course);
        for (UUID actor : List.of(TEACHER, ADMIN)) {
            problem(get(actor, series(course)), 404, "NOT_FOUND");
            problem(get(actor, schedule(course)), 404, "NOT_FOUND");
            problem(request("POST", series(course), actor, rule("2026-12-01", List.of(2), 90)), 404, "NOT_FOUND");
            problem(request("POST", series(course) + "/" + id + "/stop", actor, Map.of("stopFromDate", "2026-12-20")), 404, "NOT_FOUND");
            problem(request("POST", occurrence(course, id, "2026-12-15") + "/cancel", actor, Map.of()), 404, "NOT_FOUND");
            problem(request("POST", occurrence(course, id, "2026-12-15") + "/reschedule", actor,
                    interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z")), 404, "NOT_FOUND");
        }
        assertThat(json(get(TEACHER, "/api/v1/me/schedule?month=2026-12")).path("lessons").size()).isZero();
    }

    @Test
    void concurrentMovesUpsertOneExceptionAndCancelRaceNeverRestoresIt() throws Exception {
        UUID course = course(TEACHER);
        UUID id = createSeries(course, "2026-12-01", List.of(2));
        var pool = Executors.newFixedThreadPool(2);
        try {
            String path = occurrence(course, id, "2026-12-15");
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return request("POST", path + "/reschedule", TEACHER, interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z")); });
            var two = pool.submit(() -> { start.await(); return request("POST", path + "/reschedule", ADMIN, interval("2026-12-26T14:00:00Z", "2026-12-26T15:00:00Z")); });
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(two.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(exceptionCount()).isEqualTo(1);
            var again = new CountDownLatch(1);
            var cancel = pool.submit(() -> { again.await(); return request("POST", path + "/cancel", TEACHER, Map.of()); });
            var move = pool.submit(() -> { again.await(); return request("POST", path + "/reschedule", ADMIN, interval("2026-12-27T14:00:00Z", "2026-12-27T15:00:00Z")); });
            again.countDown();
            assertThat(cancel.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(move.get(10, TimeUnit.SECONDS).statusCode()).isIn(200, 409);
            assertThat(exceptionCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select kind from lesson_exception", String.class)).isEqualTo("CANCELLED");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void everyMutationWaitingForDeletingCannotCommit() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            for (String operation : List.of("create", "stop", "reschedule", "cancel")) {
                UUID course = course(TEACHER);
                UUID id = createSeries(course, "2026-12-01", List.of(2));
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("update course set deletion_state='DELETING' where id=?")) {
                        statement.setObject(1, course);
                        statement.executeUpdate();
                    }
                    String path = operation.equals("create") ? series(course) : operation.equals("stop") ? series(course) + "/" + id + "/stop"
                            : occurrence(course, id, "2026-12-15") + "/" + operation;
                    Map<String, ?> body = operation.equals("create") ? rule("2026-12-01", List.of(2), 90)
                            : operation.equals("stop") ? Map.of("stopFromDate", "2026-12-20")
                            : operation.equals("cancel") ? Map.of() : interval("2026-12-25T14:00:00Z", "2026-12-25T15:00:00Z");
                    var response = pool.submit(() -> request("POST", path, TEACHER, body));
                    await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname=current_database() and wait_event_type='Lock' and query ilike '%course%'
                            """, Integer.class) > 0);
                    connection.commit();
                    problem(response.get(5, TimeUnit.SECONDS), 404, "NOT_FOUND");
                    assertThat(jdbc.queryForObject("select stop_from_date is null from lesson_series where id=?", Boolean.class, id)).isTrue();
                    assertThat(jdbc.queryForObject("select count(*) from lesson_series where course_id=?", Integer.class, course)).isEqualTo(1);
                    assertThat(exceptionCount()).isZero();
                }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void v4UpgradePreservesExistingDataAndEnforcesArraysAndExceptionsInPostgres() {
        String schema = "stage6_upgrade";
        UUID course = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        try {
            var old = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("4").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(4);
            jdbc.update("insert into stage6_upgrade.course(id,teacher_id,title,created_at) values (?,?,'Сохранить',now())", course, TEACHER);
            jdbc.update("insert into stage6_upgrade.course_student values (?,?,false,now())", course, STUDENT);
            jdbc.update("insert into stage6_upgrade.single_lesson values (?,?,now(),now()+interval '5 minutes',now())", UUID.randomUUID(), course);
            var current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("5").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select title from stage6_upgrade.course", String.class)).isEqualTo("Сохранить");
            assertThat(jdbc.queryForObject("select can_send_messages from stage6_upgrade.course_student", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from stage6_upgrade.single_lesson", Integer.class)).isEqualTo(1);
            String insertRule = "insert into stage6_upgrade.lesson_series(id,course_id,start_date,weekdays,start_time,duration_minutes,created_at) values (?,?,'2026-12-01',cast(? as smallint[]),'17:00',?,now())";
            for (String days : List.of("{}", "{1,1}", "{0}", "{8}", "{1,NULL}", "{{1,2},{3,4}}")) {
                assertThatThrownBy(() -> jdbc.update(insertRule, UUID.randomUUID(), course, days, 90)).isInstanceOf(DataIntegrityViolationException.class);
            }
            for (int minutes : new int[]{4, 301}) assertThatThrownBy(() -> jdbc.update(insertRule, UUID.randomUUID(), course, "{2}", minutes)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(insertRule, UUID.randomUUID(), UUID.randomUUID(), "{2}", 90)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(insertRule, ruleId, course, "{2}", 90);
            String insertException = "insert into stage6_upgrade.lesson_exception(id,series_id,original_date,kind,new_starts_at,new_ends_at,created_at) values (?,?,'2026-12-15',?,cast(? as timestamptz),cast(? as timestamptz),now())";
            Object[][] invalid = {{"RESCHEDULED", null, null}, {"RESCHEDULED", "2026-12-25T14:00Z", null},
                    {"CANCELLED", "2026-12-25T14:00Z", "2026-12-25T15:00Z"}, {"UNKNOWN", null, null},
                    {"RESCHEDULED", "2026-12-25T14:00Z", "2026-12-25T14:04Z"}, {"RESCHEDULED", "2026-12-25T14:00Z", "2026-12-25T19:01Z"}};
            for (var row : invalid) assertThatThrownBy(() -> jdbc.update(insertException, UUID.randomUUID(), ruleId, row[0], row[1], row[2])).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(insertException, UUID.randomUUID(), ruleId, "CANCELLED", null, null);
            assertThatThrownBy(() -> jdbc.update(insertException, UUID.randomUUID(), ruleId, "CANCELLED", null, null)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(insertException, UUID.randomUUID(), UUID.randomUUID(), "CANCELLED", null, null)).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("drop schema if exists stage6_upgrade cascade"); }
    }

    @Test
    void ruleListUsesCommonScopedCursorContractIncludingStoppedRules() throws Exception {
        UUID course = course(TEACHER);
        UUID other = course(TEACHER);
        for (int i = 0; i < 31; i++) {
            jdbc.update("insert into lesson_series(id,course_id,start_date,weekdays,start_time,duration_minutes,created_at) values (?,?,'2026-12-01','{2}','17:00',90,?)",
                    UUID.randomUUID(), course, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        }
        var expected = jdbc.queryForList("select id from lesson_series where course_id=? order by created_at desc,id desc", UUID.class, course);
        jdbc.update("update lesson_series set stop_from_date='2026-12-20' where id=?", expected.get(0));
        assertThat(json(get(TEACHER, series(course))).path("items").size()).isEqualTo(30);
        assertThat(json(get(TEACHER, series(course) + "?limit=100")).path("items").size()).isEqualTo(31);
        var first = json(get(TEACHER, series(course) + "?limit=2"));
        assertThat(first.path("items").get(0).path("stopFromDate").asString()).isEqualTo("2026-12-20");
        String cursor = first.path("nextCursor").asString();
        problem(get(ADMIN, series(course) + "?cursor=" + cursor), 400, "INVALID_CURSOR");
        problem(get(TEACHER, series(other) + "?cursor=" + cursor), 400, "INVALID_CURSOR");
        problem(get(TEACHER, series(course) + "?limit=101"), 400, "INVALID_LIMIT");
        jdbc.update("delete from lesson_series where id=?", expected.get(1));
        var collected = new ArrayList<UUID>();
        var page = first;
        while (true) {
            for (var item : page.path("items")) collected.add(UUID.fromString(item.path("id").asString()));
            if (!page.path("hasMore").asBoolean()) break;
            page = json(get(TEACHER, series(course) + "?limit=2&cursor=" + page.path("nextCursor").asString()));
        }
        assertThat(collected).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    private String series(UUID course) { return "/api/v1/courses/" + course + "/lesson-series"; }
    private String occurrence(UUID course, UUID id, String date) { return series(course) + "/" + id + "/occurrences/" + date; }
    private Map<String, Object> rule(String startDate, List<Integer> days, int minutes) {
        return Map.of("startDate", startDate, "weekdays", days, "startTime", "17:00", "durationMinutes", minutes, "confirmConflicts", true);
    }
    private UUID createSeries(UUID course, String startDate, List<Integer> days) throws Exception {
        var response = request("POST", series(course), TEACHER, rule(startDate, days, 90));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json(response).path("id").asString());
    }
    private int exceptionCount() { return jdbc.queryForObject("select count(*) from lesson_exception", Integer.class); }
    private List<JsonNode> items(UUID course, UUID actor, String month) throws Exception {
        var response = get(actor, "/api/v1/courses/" + course + "/schedule?month=" + month);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result = new ArrayList<JsonNode>();
        for (var item : json(response).path("lessons")) result.add(item);
        return result;
    }
    private List<String> originalDates(List<JsonNode> items) { return items.stream().map(item -> item.path("originalDate").asString()).toList(); }

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

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json(response).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("stackTrace", "private-upstream-diagnostic", "\"startsAt\":", "\"courseId\":");
    }
}
