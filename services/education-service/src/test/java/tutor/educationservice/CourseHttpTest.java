package tutor.educationservice;

import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.IdentityHttpStub.Person;
import tutor.educationservice.cleanup.*;
import tutor.educationservice.integration.storage.ObjectStorage;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CourseHttpTest {
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID MULTI = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00.123456789Z");
    private static final IdentityHttpStub IDENTITY = new IdentityHttpStub();
    private static final Map<UUID, Person> PEOPLE = IDENTITY.people;
    private static final AtomicInteger LOOKUPS = IDENTITY.lookups;
    private static final AtomicInteger LOOKUP_STATUS = IDENTITY.lookupStatus;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("education.storage.endpoint", () -> "http://localhost:1");
        registry.add("education.storage.bucket", () -> "course-test");
        registry.add("education.storage.access-key", () -> "course-test-access");
        registry.add("education.storage.secret-key", () -> "course-test-secret");
        registry.add("education.identity.base-url", IDENTITY::baseUrl);
    }

    @MockitoBean MinioClient minio;
    @MockitoBean Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired CleanupJobs cleanupJobs;
    @Autowired List<CleanupHandler> cleanupHandlers;
    @Autowired ObjectStorage storage;
    @LocalServerPort int port;

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("delete from cleanup_job");
        jdbc.update("delete from pending_upload");
        jdbc.update("delete from material_reference");
        jdbc.update("delete from material_file");
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from lesson_exception");
        jdbc.update("delete from lesson_series");
        jdbc.update("delete from single_lesson");
        jdbc.update("delete from course_student");
        jdbc.update("delete from course");
        PEOPLE.clear();
        PEOPLE.put(TEACHER, new Person(TEACHER, List.of("TEACHER"), false));
        PEOPLE.put(OTHER, new Person(OTHER, List.of("TEACHER"), false));
        PEOPLE.put(ADMIN, new Person(ADMIN, List.of("ADMIN"), false));
        PEOPLE.put(STUDENT, new Person(STUDENT, List.of("STUDENT"), false));
        PEOPLE.put(MULTI, new Person(MULTI, List.of("TEACHER", "STUDENT"), false));
        LOOKUPS.set(0);
        LOOKUP_STATUS.set(200);
        IDENTITY.beforeLookup = () -> {};
        IDENTITY.firstName = "Иван";
        when(clock.instant()).thenReturn(NOW);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    }

    @AfterAll
    static void closeIdentity() {
        IDENTITY.close();
    }

    @Test
    void chatHistoryUsesAuthorSnapshotAndCursorSurvivesDeletingItsAnchor() throws Exception {
        String course = json(create(TEACHER, Map.of("title", "Chat"))).path("id").asString();
        UUID id = UUID.fromString(course);
        String path = "/api/v1/courses/" + course;
        var update = request("PATCH", path + "/chat/settings", TEACHER, Map.of("mode", "ANNOUNCEMENTS"));
        assertThat(update.statusCode()).isEqualTo(200);
        var time = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        for (int i = 1; i <= 3; i++) {
            jdbc.update("""
                    insert into chat_message(id, course_id, author_id, author_role, author_display_name, body, client_message_id, created_at)
                    values (?, ?, ?, 'TEACHER', 'Snapshot', ?, ?, ?)
                    """, new UUID(0, i), id, TEACHER, "Message " + i, UUID.randomUUID(), time);
        }
        assertThat(request("POST", path + "/students", TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
        assertThat(json(get(STUDENT, path + "/chat/settings")).path("mode").asString()).isEqualTo("ANNOUNCEMENTS");
        var first = json(get(STUDENT, path + "/chat/messages?limit=1"));
        assertThat(first.path("messages").get(0).path("author").path("displayName").asString()).isEqualTo("Snapshot");
        assertThat(first.path("messages").get(0).path("text").asString()).isEqualTo("Message 3");
        jdbc.update("delete from chat_message where id = ?", new UUID(0, 3));
        var next = json(get(STUDENT, path + "/chat/messages?limit=1&before=" + first.path("nextCursor").asString()));
        assertThat(next.path("messages").get(0).path("text").asString()).isEqualTo("Message 2");
        assertThat(get(STUDENT, path + "/chat/messages?limit=101").statusCode()).isEqualTo(400);
        assertThat(get(OTHER, path + "/chat/messages").statusCode()).isEqualTo(404);
    }

    @Test
    void chatFilesArePrivateUntilAttachedAndAnnouncementsRejectStudents() throws Exception {
        String course = json(create(TEACHER, Map.of("title", "Chat files"))).path("id").asString();
        String path = "/api/v1/courses/" + course;
        assertThat(request("POST", path + "/students", TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
        var uploaded = uploadChatFile(path, STUDENT, "note.txt", "text/plain", "hello");
        assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(201);
        String id = json(uploaded).path("attachmentId").asString();
        assertThat(json(uploaded).path("state").asString()).isEqualTo("READY");
        assertThat(get(STUDENT, path + "/chat/messages/" + UUID.randomUUID() + "/attachments/" + id + "/content").statusCode()).isEqualTo(404);
        assertThat(uploadChatFile(path, STUDENT, "fake.png", "image/png", "not a png").statusCode()).isEqualTo(415);
        assertThat(request("PATCH", path + "/chat/settings", TEACHER, Map.of("mode", "ANNOUNCEMENTS")).statusCode()).isEqualTo(200);
        assertThat(uploadChatFile(path, STUDENT, "note.txt", "text/plain", "hello").statusCode()).isEqualTo(403);
        assertThat(uploadChatFile(path, ADMIN, "note.txt", "text/plain", "hello").statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> uploadChatFile(String path, UUID actor, String filename, String type, String content) throws Exception {
        String token = TestJwt.token(PEOPLE.get(actor).roles(), claims -> claims.subject(actor.toString()), false);
        String body = "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n" + content + "\r\n--test-boundary--\r\n";
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + "/chat/attachments")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=test-boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void chatWebSocketAcknowledgesCommittedSendsAndRevokesRemovedStudent() throws Exception {
        String course = json(create(TEACHER, Map.of("title", "Live chat"))).path("id").asString();
        String path = "/api/v1/courses/" + course;
        assertThat(request("POST", path + "/students", TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
        try (var author = stomp(TEACHER); var member = stomp(STUDENT)) {
            author.subscribe("response", "/user/queue/chat.responses");
            member.subscribe("course", "/topic/courses/" + course + "/chat");
            UUID clientId = UUID.randomUUID();
            String command = mapper.writeValueAsString(Map.of("clientMessageId", clientId, "text", "Hello", "attachmentIds", List.of()));
            // Wait for the student subscription to be processed by sending a command on that connection.
            member.subscribe("response", "/user/queue/chat.responses");
            member.send(course, "{}");
            assertThat(member.event().path("code").asString()).isEqualTo("INVALID_MESSAGE");
            author.send(course, command);
            var ack = author.event();
            assertThat(ack.path("type").asString()).isEqualTo("MESSAGE_ACCEPTED");
            String id = ack.path("messageId").asString();
            assertThat(jdbc.queryForObject("select body from chat_message where id = ?", String.class, UUID.fromString(id))).isEqualTo("Hello");
            var created = member.event();
            assertThat(created.path("type").asString()).isEqualTo("MESSAGE_CREATED");
            assertThat(created.path("message").path("id").asString()).isEqualTo(id);
            author.send(course, command);
            assertThat(author.event()).isEqualTo(ack);
            author.send(course, command.replace("Hello", "Changed"));
            assertThat(author.event().path("code").asString()).isEqualTo("CLIENT_MESSAGE_ID_CONFLICT");
            assertThat(jdbc.queryForObject("select count(*) from chat_message", Integer.class)).isEqualTo(1);
            assertThat(request("DELETE", path + "/students/" + STUDENT, TEACHER, null).statusCode()).isEqualTo(204);
            assertThat(member.closeStatus()).isEqualTo(1008);
        }
    }

    private TestStompClient stomp(UUID user) throws Exception {
        String token = TestJwt.token(PEOPLE.get(user).roles(), claims -> claims.subject(user.toString()), false);
        return new TestStompClient(port, token, mapper);
    }

    @Test
    void blockedStudentCannotReceiveOnAnExistingChatSession() throws Exception {
        String course = json(create(TEACHER, Map.of("title", "Blocked subscriber"))).path("id").asString();
        String path = "/api/v1/courses/" + course;
        assertThat(request("POST", path + "/students", TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
        try (var author = stomp(TEACHER); var member = stomp(STUDENT)) {
            author.subscribe("response", "/user/queue/chat.responses");
            member.subscribe("course", "/topic/courses/" + course + "/chat");
            member.subscribe("response", "/user/queue/chat.responses");
            member.send(course, "{}");
            assertThat(member.event().path("code").asString()).isEqualTo("INVALID_MESSAGE");
            PEOPLE.put(STUDENT, new Person(STUDENT, List.of("STUDENT"), true));
            author.send(course, mapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "text", "Private")));
            assertThat(author.event().path("type").asString()).isEqualTo("MESSAGE_ACCEPTED");
            assertThat(member.closeStatus()).isEqualTo(1008);
        }
    }

    @Test
    void courseDeletionDuringStagingPreventsFinalObjectPublication() throws Exception {
        String course = json(create(TEACHER, Map.of("title", "Upload race"))).path("id").asString();
        String path = "/api/v1/courses/" + course;
        var staging = new CountDownLatch(1);
        var continueUpload = new CountDownLatch(1);
        when(minio.putObject(any(PutObjectArgs.class))).thenAnswer(call -> {
            staging.countDown();
            if (!continueUpload.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Upload test timed out");
            return null;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var upload = executor.submit(() -> uploadChatFile(path, TEACHER, "note.txt", "text/plain", "buffered"));
            assertThat(staging.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(request("DELETE", path, TEACHER, null).statusCode()).isEqualTo(202);
            continueUpload.countDown();
            assertThat(upload.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(404);
            verify(minio, never()).copyObject(any(CopyObjectArgs.class));
            assertThat(jdbc.queryForObject("select count(*) from pending_upload where not published", Integer.class)).isEqualTo(1);
        } finally {
            continueUpload.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void chatModerationHidesAttachmentsAndRetainsTheSendReceipt() throws Exception {
        String course = json(create(TEACHER, Map.of("title", "Moderation"))).path("id").asString();
        String path = "/api/v1/courses/" + course;
        assertThat(request("POST", path + "/students", TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
        String attachment = json(uploadChatFile(path, STUDENT, "note.txt", "text/plain", "hello")).path("attachmentId").asString();
        try (var author = stomp(STUDENT); var teacher = stomp(TEACHER)) {
            author.subscribe("response", "/user/queue/chat.responses");
            teacher.subscribe("course", "/topic/courses/" + course + "/chat");
            teacher.subscribe("response", "/user/queue/chat.responses");
            teacher.send(course, "{}");
            assertThat(teacher.event().path("code").asString()).isEqualTo("INVALID_MESSAGE");
            UUID clientId = UUID.randomUUID();
            String command = mapper.writeValueAsString(Map.of("clientMessageId", clientId, "attachmentIds", List.of(attachment)));
            author.send(course, command);
            String message = author.event().path("messageId").asString();
            var created = teacher.event().path("message");
            assertThat(created.path("attachments").get(0).path("id").asString()).isEqualTo(attachment);
            String messagePath = path + "/chat/messages/" + message;
            assertThat(request("DELETE", messagePath, STUDENT, null).statusCode()).isEqualTo(403);
            var deleted = request("DELETE", messagePath, TEACHER, null);
            assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
            assertThat(teacher.event().path("type").asString()).isEqualTo("MESSAGE_DELETED");
            assertThat(request("DELETE", messagePath, TEACHER, null).body()).isEqualTo(deleted.body());
            assertThat(get(STUDENT, messagePath + "/attachments/" + attachment + "/content").statusCode()).isEqualTo(404);
            assertThat(json(get(STUDENT, path + "/chat/messages")).path("messages").size()).isZero();
            author.send(course, command);
            assertThat(author.event().path("code").asString()).isEqualTo("MESSAGE_ALREADY_DELETED");
            assertThat(jdbc.queryForObject("select count(*) from cleanup_job where resource_type = 'CHAT_MESSAGE'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from chat_send_receipt", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void courseDeletionRetriesStorageFailureAndKeepsTheLibrary() throws Exception {
        UUID course = UUID.fromString(json(create(TEACHER, Map.of("title", "Delete"))).path("id").asString());
        String path = "/api/v1/courses/" + course;
        assertThat(request("POST", path + "/students", TEACHER, Map.of("studentId", STUDENT)).statusCode()).isEqualTo(201);
        assertThat(uploadChatFile(path, STUDENT, "note.txt", "text/plain", "hello").statusCode()).isEqualTo(201);
        var starts = OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC);
        jdbc.update("insert into single_lesson values (?, ?, ?, ?, ?)", UUID.randomUUID(), course, starts, starts.plusMinutes(45), starts);
        UUID file = UUID.randomUUID();
        jdbc.update("""
                insert into material_file(id, teacher_id, name, object_key, mime_type, size_bytes)
                values (?, ?, 'Library', ?, 'text/plain', 1)
                """, file, TEACHER, "materials/" + file);
        jdbc.update("insert into file_course_access values (?, ?, true)", file, course);
        jdbc.update("insert into material_reference(id, file_id, consumer_service, consumer_entity_id) values (?, ?, 'ASSIGNMENT', ?)",
                UUID.randomUUID(), file, UUID.randomUUID());
        assertThat(request("DELETE", path, OTHER, null).statusCode()).isEqualTo(404);
        var deleted = request("DELETE", path, TEACHER, null);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(202);
        assertThat(get(STUDENT, path + "/chat/messages").statusCode()).isEqualTo(404);
        assertThat(request("DELETE", path, TEACHER, null).body()).isEqualTo(deleted.body());
        assertThat(uploadChatFile(path, STUDENT, "note.txt", "text/plain", "hello").statusCode()).isEqualTo(404);

        UUID job = UUID.fromString(json(deleted).path("cleanupJobId").asString());
        var worker = new CleanupWorker(cleanupJobs, storage, cleanupHandlers,
                new CleanupProperties(Duration.ofMinutes(1), 10, 3, Duration.ofSeconds(1), Duration.ofMinutes(1)), new SimpleMeterRegistry());
        doThrow(new java.io.IOException("Storage unavailable")).when(minio).removeObject(any(RemoveObjectArgs.class));
        worker.poll();
        assertThat(jdbc.queryForObject("select deletion_state from course where id = ?", String.class, course)).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("select count(*) from chat_attachment where course_id = ?", Integer.class, course)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from cleanup_job where id = ?", String.class, job)).isEqualTo("PENDING");
        jdbc.update("update cleanup_job set next_attempt_at = clock_timestamp() where id = ?", job);
        doNothing().when(minio).removeObject(any(RemoveObjectArgs.class));
        worker.poll();
        assertThat(jdbc.queryForObject("select status from cleanup_job where id = ?", String.class, job)).isEqualTo("COMPLETED");
        for (String table : List.of("chat_attachment", "course_student", "single_lesson", "file_course_access")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where course_id = ?", Integer.class, course)).isZero();
        }
        assertThat(request("DELETE", path, TEACHER, null).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from material_file where id = ?", Integer.class, file)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from material_reference where file_id = ?", Integer.class, file)).isEqualTo(1);
    }

    @Test
    void teacherCreatesOwnCourseWithoutIdempotencyHeaderAndGetsExactDto() throws Exception {
        var response = create(TEACHER, Map.of("title", "  Математика  "));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var body = json(response);
        UUID id = UUID.fromString(body.path("id").asString());
        assertThat(response.headers().firstValue("Location")).contains("/api/v1/courses/" + id);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(body.propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("id", "teacherId", "title", "meetingUrl", "chatMode", "createdAt");
        assertThat(body.path("teacherId").asString()).isEqualTo(TEACHER.toString());
        assertThat(body.path("title").asString()).isEqualTo("Математика");
        assertThat(body.path("meetingUrl").isNull()).isTrue();
        assertThat(body.path("chatMode").asString()).isEqualTo("NORMAL");
        assertThat(body.path("createdAt").asString()).isEqualTo("2026-11-20T10:00:00.123456Z");
        assertThat(json(get(TEACHER, "/api/v1/courses/" + id))).isEqualTo(body);
        assertThat(LOOKUPS.get()).isZero();
    }

    @Test
    void duplicateTitlesAreAllowedAndStudentRoleDoesNotCancelTeacherRole() throws Exception {
        var first = json(create(MULTI, Map.of("title", "Одинаковое")));
        var second = json(create(MULTI, Map.of("title", "Одинаковое")));
        assertThat(first.path("id").asString()).isNotEqualTo(second.path("id").asString());
        assertThat(first.path("teacherId").asString()).isEqualTo(MULTI.toString());
        assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(2);
        assertThat(json(get(MULTI, "/api/v1/courses?scope=OWNED")).path("items").size()).isEqualTo(2);
    }

    @Test
    void adminCreatesForExistingTeacherAndCanReadAndRenameOtherCourse() throws Exception {
        var response = create(ADMIN, Map.of("title", "Курс", "teacherId", OTHER));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID id = UUID.fromString(json(response).path("id").asString());
        assertThat(json(response).path("teacherId").asString()).isEqualTo(OTHER.toString());
        assertThat(LOOKUPS.get()).isEqualTo(1);
        assertThat(get(ADMIN, "/api/v1/courses/" + id).statusCode()).isEqualTo(200);
        assertThat(get(OTHER, "/api/v1/courses/" + id).statusCode()).isEqualTo(200);
        var renamed = request("PATCH", "/api/v1/courses/" + id, ADMIN, Map.of("title", "Новое"));
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(json(renamed).path("teacherId").asString()).isEqualTo(OTHER.toString());
        assertThat(json(renamed).path("title").asString()).isEqualTo("Новое");
    }

    @Test
    void adminTeacherCanCreateForSelfAndBlockedOwnerDoesNotBecomeAnInventedRestriction() throws Exception {
        PEOPLE.put(ADMIN, new Person(ADMIN, List.of("ADMIN", "TEACHER"), false));
        assertThat(create(ADMIN, Map.of("title", "Свой")).statusCode()).isEqualTo(201);
        PEOPLE.put(OTHER, new Person(OTHER, List.of("TEACHER"), true));
        assertThat(create(ADMIN, Map.of("title", "Для владельца", "teacherId", OTHER)).statusCode()).isEqualTo(201);
        assertProblem(get(OTHER, "/api/v1/courses?scope=OWNED"), 403, "ACCOUNT_UNAVAILABLE");
    }

    @Test
    void invalidAdminTeacherAndDependencyFailureNeverWriteCourse() throws Exception {
        assertProblem(create(ADMIN, Map.of("title", "Без владельца")), 422, "INVALID_REQUEST");
        assertProblem(create(ADMIN, Map.of("title", "Не преподаватель", "teacherId", STUDENT)), 422, "INVALID_TEACHER");
        assertProblem(create(ADMIN, Map.of("title", "Нет пользователя", "teacherId", UUID.randomUUID())), 404, "USER_NOT_FOUND");
        LOOKUP_STATUS.set(503);
        assertProblem(create(ADMIN, Map.of("title", "Dependency", "teacherId", OTHER)), 503, "IDENTITY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isZero();
    }

    @Test
    void studentsCannotCreateAndTeachersCannotSupplyOwnerEvenTheirOwn() throws Exception {
        assertProblem(create(STUDENT, Map.of("title", "Нельзя")), 403, "ACCESS_DENIED");
        assertProblem(create(TEACHER, Map.of("title", "Нельзя", "teacherId", OTHER)), 403, "ACCESS_DENIED");
        assertProblem(create(TEACHER, Map.of("title", "Нельзя", "teacherId", TEACHER)), 403, "ACCESS_DENIED");
        assertThat(LOOKUPS.get()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isZero();
    }

    @Test
    void hiddenMissingAndDeletingCoursesReturn404WithoutDto() throws Exception {
        UUID active = seed(TEACHER, "private-title", "ACTIVE");
        UUID deleting = seed(TEACHER, "private-title", "DELETING");
        for (UUID actor : List.of(OTHER, STUDENT)) {
            assertProblem(get(actor, "/api/v1/courses/" + active), 404, "NOT_FOUND");
            assertProblem(get(actor, "/api/v1/courses/" + UUID.randomUUID()), 404, "NOT_FOUND");
        }
        assertProblem(request("PATCH", "/api/v1/courses/" + active, OTHER, Map.of("title", "Нельзя")), 404, "NOT_FOUND");
        assertProblem(request("PATCH", "/api/v1/courses/" + active, STUDENT, Map.of("title", "Нельзя")), 403, "ACCESS_DENIED");
        for (UUID actor : List.of(TEACHER, ADMIN)) {
            assertProblem(get(actor, "/api/v1/courses/" + deleting), 404, "NOT_FOUND");
            assertProblem(request("PATCH", "/api/v1/courses/" + deleting, actor, Map.of("title", "Нельзя")), 404, "NOT_FOUND");
        }
    }

    @Test
    void renameChangesOnlyTitleAndRejectsImmutableFields() throws Exception {
        UUID id = seed(TEACHER, "Старое", "ACTIVE");
        jdbc.update("update course set meeting_url = ?, chat_mode = 'ANNOUNCEMENTS' where id = ?", "https://example.com/meeting", id);
        var before = json(get(TEACHER, "/api/v1/courses/" + id));
        var response = request("PATCH", "/api/v1/courses/" + id, TEACHER, Map.of("title", "  Новое  "));
        assertThat(response.statusCode()).isEqualTo(200);
        var after = json(response);
        for (String field : List.of("id", "teacherId", "meetingUrl", "chatMode", "createdAt")) {
            assertThat(after.path(field)).isEqualTo(before.path(field));
        }
        assertThat(after.path("title").asString()).isEqualTo("Новое");
        for (String field : List.of("teacherId", "meetingUrl", "chatMode", "deletionState", "id", "createdAt")) {
            assertProblem(request("PATCH", "/api/v1/courses/" + id, ADMIN, Map.of("title", "Нельзя", field, "untrusted")), 400, "INVALID_REQUEST");
        }
        assertThat(jdbc.queryForObject("select title from course where id = ?", String.class, id)).isEqualTo("Новое");
    }

    @Test
    void titlesAndUnknownCreateFieldsAreValidatedWithoutArbitraryLengthLimit() throws Exception {
        for (String title : List.of("", " ", "\t\n")) {
            assertProblem(create(TEACHER, Map.of("title", title)), 422, "INVALID_REQUEST");
        }
        assertProblem(create(TEACHER, Map.of()), 422, "INVALID_REQUEST");
        assertProblem(create(TEACHER, Map.of("title", "Курс", "meetingUrl", "https://example.com")), 400, "INVALID_REQUEST");
        String longTitle = "Я".repeat(2048);
        assertThat(json(create(TEACHER, Map.of("title", longTitle))).path("title").asString()).isEqualTo(longTitle);
    }

    @Test
    void listScopesFiltersAndRolesDoNotLeakOtherTeachers() throws Exception {
        UUID own = seed(TEACHER, "Свой", "ACTIVE");
        UUID other = seed(OTHER, "Другой", "ACTIVE");
        seed(TEACHER, "private-title", "DELETING");
        assertThat(ids(json(get(TEACHER, "/api/v1/courses?scope=OWNED")))).containsExactly(own);
        assertThat(ids(json(get(ADMIN, "/api/v1/courses?scope=OWNED&teacherId=" + OTHER)))).containsExactly(other);
        assertThat(ids(json(get(ADMIN, "/api/v1/courses?scope=ALL")))).containsExactlyInAnyOrder(own, other);
        assertThat(ids(json(get(ADMIN, "/api/v1/courses?scope=ALL&teacherId=" + TEACHER)))).containsExactly(own);
        assertProblem(get(ADMIN, "/api/v1/courses?scope=OWNED"), 400, "INVALID_REQUEST");
        assertProblem(get(TEACHER, "/api/v1/courses?scope=ALL"), 403, "ACCESS_DENIED");
        assertProblem(get(TEACHER, "/api/v1/courses?scope=OWNED&teacherId=" + OTHER), 403, "ACCESS_DENIED");
        assertProblem(get(STUDENT, "/api/v1/courses?scope=OWNED"), 403, "ACCESS_DENIED");
        assertProblem(get(STUDENT, "/api/v1/courses?scope=ALL"), 403, "ACCESS_DENIED");
        assertThat(ids(json(get(STUDENT, "/api/v1/courses?scope=ENROLLED")))).isEmpty();
        assertThat(ids(json(get(MULTI, "/api/v1/courses?scope=ENROLLED")))).isEmpty();
        assertProblem(get(TEACHER, "/api/v1/courses?scope=ENROLLED"), 403, "ACCESS_DENIED");
    }

    @Test
    void paginationUsesPostgresUuidOrderingAndSurvivesDeletedAnchor() throws Exception {
        for (int i = 0; i < 7; i++) seed(TEACHER, "Курс", "ACTIVE");
        seed(OTHER, "Другой", "ACTIVE");
        var expected = jdbc.queryForList("select id from course where teacher_id = ? order by created_at desc, id desc", UUID.class, TEACHER);
        var first = json(get(TEACHER, "/api/v1/courses?scope=OWNED&limit=2"));
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 2));
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var collected = new ArrayList<>(ids(first));
        jdbc.update("delete from course where id = ?", collected.get(1));
        var page = first;
        while (page.path("hasMore").asBoolean()) {
            page = json(get(TEACHER, "/api/v1/courses?scope=OWNED&limit=2&cursor=" + page.path("nextCursor").asString()));
            collected.addAll(ids(page));
        }
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(collected).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void cursorCannotBeReusedByAnotherActorScopeOrOwnerFilter() throws Exception {
        for (int i = 0; i < 3; i++) seed(TEACHER, "Курс", "ACTIVE");
        var ownerCursor = json(get(TEACHER, "/api/v1/courses?scope=OWNED&limit=1")).path("nextCursor").asString();
        assertProblem(get(OTHER, "/api/v1/courses?scope=OWNED&cursor=" + ownerCursor), 400, "INVALID_CURSOR");
        var adminCursor = json(get(ADMIN, "/api/v1/courses?scope=ALL&limit=1")).path("nextCursor").asString();
        assertProblem(get(ADMIN, "/api/v1/courses?scope=OWNED&teacherId=" + TEACHER + "&cursor=" + adminCursor), 400, "INVALID_CURSOR");
        assertProblem(get(ADMIN, "/api/v1/courses?scope=ALL&teacherId=" + OTHER + "&cursor=" + adminCursor), 400, "INVALID_CURSOR");
        assertProblem(get(TEACHER, "/api/v1/courses?scope=OWNED&cursor=broken"), 400, "INVALID_CURSOR");
    }

    @Test
    void requiredScopeUuidLimitAndDefaultPageSizeAreEnforced() throws Exception {
        for (String path : List.of("/api/v1/courses", "/api/v1/courses?scope=UNKNOWN", "/api/v1/courses/not-a-uuid",
                "/api/v1/courses?scope=ALL&teacherId=bad", "/api/v1/courses?scope=ALL&limit=abc")) {
            assertProblem(get(ADMIN, path), 400, "INVALID_REQUEST");
        }
        for (int limit : new int[]{0, -1, 101}) assertProblem(get(TEACHER, "/api/v1/courses?scope=OWNED&limit=" + limit), 400, "INVALID_LIMIT");
        for (int i = 0; i < 31; i++) seed(TEACHER, "Курс", "ACTIVE");
        assertThat(ids(json(get(TEACHER, "/api/v1/courses?scope=OWNED")))).hasSize(30);
        assertThat(ids(json(get(TEACHER, "/api/v1/courses?scope=OWNED&limit=100")))).hasSize(31);
        assertThat(ids(json(get(OTHER, "/api/v1/courses?scope=OWNED")))).isEmpty();
    }

    @Test
    void concurrentCreatesWithSameTitleAreSeparateResources() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<HttpResponse<String>> create = () -> { start.await(); return create(TEACHER, Map.of("title", "Параллельный")); };
        try {
            var one = pool.submit(create);
            var two = pool.submit(create);
            start.countDown();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(second.statusCode()).isEqualTo(201);
            assertThat(json(first).path("id").asString()).isNotEqualTo(json(second).path("id").asString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void renameWaitingForDeleteTransitionCannotCommitAfterDeleting() throws Exception {
        UUID id = seed(TEACHER, "Исходное", "ACTIVE");
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("update course set deletion_state = 'DELETING' where id = ?")) {
                statement.setObject(1, id);
                statement.executeUpdate();
            }
            var response = pool.submit(() -> request("PATCH", "/api/v1/courses/" + id, TEACHER, Map.of("title", "Не записывать")));
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and wait_event_type = 'Lock' and query ilike '%course%'
                    """, Integer.class) > 0);
            connection.commit();
            assertProblem(response.get(5, TimeUnit.SECONDS), 404, "NOT_FOUND");
            assertThat(jdbc.queryForObject("select title from course where id = ?", String.class, id)).isEqualTo("Исходное");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void schemaHasNoIdentityForeignKeyAndRejectsInvalidStatesAndEmptyTitles() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema='public' and table_name='course' and constraint_type='FOREIGN KEY'", Integer.class)).isZero();
        for (String sql : List.of("insert into course (id,teacher_id,title,created_at) values (?,?,'   ',now())",
                "insert into course (id,teacher_id,title,chat_mode,created_at) values (?,?,'Курс','UNKNOWN',now())",
                "insert into course (id,teacher_id,title,deletion_state,created_at) values (?,?,'Курс','UNKNOWN',now())")) {
            assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), TEACHER)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void v1UpgradeAppliesV2WithoutLosingExistingData() {
        String schema = "stage3_upgrade";
        try {
            var v1 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("1").load();
            assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
            jdbc.execute("create table stage3_upgrade.marker (value integer)");
            jdbc.update("insert into stage3_upgrade.marker values (42)");
            var current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("2").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select value from stage3_upgrade.marker", Integer.class)).isEqualTo(42);
            assertThat(jdbc.queryForObject("select count(*) from stage3_upgrade.course", Integer.class)).isZero();
        } finally {
            jdbc.execute("drop schema if exists stage3_upgrade cascade");
        }
    }

    @Test
    void enrollmentGrantsReadAndExclusionRemovesItImmediately() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        UUID other = seed(OTHER, "Другой", "ACTIVE");
        var response = enroll(course, STUDENT, TEACHER);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains(studentsPath(course) + "/" + STUDENT);
        var student = json(response);
        assertThat(student.propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
                "studentId", "displayName", "blocked", "canSendMessages", "enrolledAt");
        assertThat(student.path("studentId").asString()).isEqualTo(STUDENT.toString());
        assertThat(student.path("displayName").asString()).isEqualTo("Иванов Иван");
        assertThat(student.path("blocked").asBoolean()).isFalse();
        assertThat(student.path("canSendMessages").asBoolean()).isTrue();
        assertThat(student.path("enrolledAt").asString()).isEqualTo("2026-11-20T10:00:00.123456Z");
        assertThat(get(STUDENT, "/api/v1/courses/" + course).statusCode()).isEqualTo(200);
        assertProblem(get(STUDENT, "/api/v1/courses/" + other), 404, "NOT_FOUND");
        assertThat(ids(json(get(STUDENT, "/api/v1/courses?scope=ENROLLED")))).containsExactly(course);
        assertThat(request("DELETE", studentsPath(course) + "/" + STUDENT, TEACHER, null).statusCode()).isEqualTo(204);
        assertThat(request("DELETE", studentsPath(course) + "/" + STUDENT, ADMIN, null).statusCode()).isEqualTo(204);
        assertProblem(get(STUDENT, "/api/v1/courses/" + course), 404, "NOT_FOUND");
        assertThat(ids(json(get(STUDENT, "/api/v1/courses?scope=ENROLLED")))).isEmpty();
        assertThat(memberCount()).isZero();
    }

    @Test
    void localChatBanKeepsReadAndReenrollmentResetsFlagAndTimestamp() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        assertThat(enroll(course, STUDENT, ADMIN).statusCode()).isEqualTo(201);
        var path = studentsPath(course) + "/" + STUDENT;
        var banned = request("PATCH", path + "/chat-permissions", TEACHER, Map.of("canSendMessages", false));
        assertThat(banned.statusCode()).as(banned.body()).isEqualTo(200);
        assertThat(json(banned).propertyStream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder("studentId", "canSendMessages");
        assertThat(json(banned).path("canSendMessages").asBoolean()).isFalse();
        assertThat(json(get(TEACHER, studentsPath(course))).path("items").get(0).path("canSendMessages").asBoolean()).isFalse();
        assertThat(get(STUDENT, "/api/v1/courses/" + course).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", path, TEACHER, null).statusCode()).isEqualTo(204);
        assertProblem(request("PATCH", path + "/chat-permissions", ADMIN, Map.of("canSendMessages", true)), 404, "NOT_FOUND");
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        var again = enroll(course, STUDENT, TEACHER);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(json(again).path("canSendMessages").asBoolean()).isTrue();
        assertThat(json(again).path("enrolledAt").asString()).isEqualTo("2026-11-20T10:00:10.123456Z");
    }

    @Test
    void enrollmentValidatesIdentityWithoutLeavingRows() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        assertProblem(enroll(course, UUID.randomUUID(), TEACHER), 404, "USER_NOT_FOUND");
        assertProblem(enroll(course, OTHER, TEACHER), 422, "INVALID_STUDENT");
        PEOPLE.put(STUDENT, new Person(STUDENT, List.of("STUDENT"), true));
        assertProblem(enroll(course, STUDENT, TEACHER), 409, "STUDENT_BLOCKED");
        LOOKUP_STATUS.set(503);
        assertProblem(enroll(course, MULTI, TEACHER), 503, "IDENTITY_UNAVAILABLE");
        assertThat(memberCount()).isZero();
    }

    @Test
    void membershipNeverGrantsManagementEvenWithTeacherRole() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        assertThat(enroll(course, STUDENT, TEACHER).statusCode()).isEqualTo(201);
        assertThat(enroll(course, MULTI, TEACHER).statusCode()).isEqualTo(201);
        assertThat(get(MULTI, "/api/v1/courses/" + course).statusCode()).isEqualTo(200);
        assertProblem(request("PATCH", "/api/v1/courses/" + course, MULTI, Map.of("title", "Нельзя")), 404, "NOT_FOUND");
        for (UUID actor : List.of(STUDENT, MULTI, OTHER)) {
            int status = actor.equals(STUDENT) ? 403 : 404;
            String code = status == 403 ? "ACCESS_DENIED" : "NOT_FOUND";
            assertProblem(get(actor, studentsPath(course)), status, code);
            assertProblem(enroll(course, UUID.randomUUID(), actor), status, code);
            assertProblem(request("DELETE", studentsPath(course) + "/" + STUDENT, actor, null), status, code);
            assertProblem(request("PATCH", studentsPath(course) + "/" + STUDENT + "/chat-permissions", actor,
                    Map.of("canSendMessages", false)), status, code);
        }
        assertThat(get(ADMIN, studentsPath(course)).statusCode()).isEqualTo(200);
        assertThat(memberCount()).isEqualTo(2);
    }

    @Test
    void blockedMemberRemainsInRosterWithFreshProfileButCannotRead() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        assertThat(enroll(course, STUDENT, TEACHER).statusCode()).isEqualTo(201);
        PEOPLE.put(STUDENT, new Person(STUDENT, List.of("STUDENT"), true));
        IDENTITY.firstName = "Пётр";
        var item = json(get(TEACHER, studentsPath(course))).path("items").get(0);
        assertThat(item.path("blocked").asBoolean()).isTrue();
        assertThat(item.path("displayName").asString()).isEqualTo("Иванов Пётр");
        assertProblem(get(STUDENT, "/api/v1/courses/" + course), 403, "ACCOUNT_UNAVAILABLE");
        assertThat(memberCount()).isEqualTo(1);
        LOOKUP_STATUS.set(503);
        assertProblem(get(TEACHER, studentsPath(course)), 503, "IDENTITY_UNAVAILABLE");
        LOOKUP_STATUS.set(200);
        PEOPLE.remove(STUDENT);
        assertProblem(get(TEACHER, studentsPath(course)), 503, "IDENTITY_UNAVAILABLE");
        assertThat(memberCount()).isEqualTo(1);
    }

    @Test
    void duplicateAndConcurrentEnrollmentProduceExactlyOneRow() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(2);
        IDENTITY.beforeLookup = () -> {
            entered.countDown();
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException exception) { throw new IllegalStateException(exception); }
        };
        try {
            var one = pool.submit(() -> enroll(course, STUDENT, TEACHER));
            var two = pool.submit(() -> enroll(course, STUDENT, ADMIN));
            assertThat(List.of(one.get(10, TimeUnit.SECONDS).statusCode(), two.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(memberCount()).isEqualTo(1);
            IDENTITY.beforeLookup = () -> {};
            assertProblem(enroll(course, STUDENT, TEACHER), 409, "ALREADY_ENROLLED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingDuringIdentityLookupPreventsEnrollmentWithoutHoldingSqlLock() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        IDENTITY.beforeLookup = () -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException exception) { throw new IllegalStateException(exception); }
        };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var response = pool.submit(() -> enroll(course, STUDENT, TEACHER));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("set statement_timeout = '2s'");
                statement.executeUpdate("update course set deletion_state='DELETING' where id='" + course + "'");
            }
            release.countDown();
            assertProblem(response.get(5, TimeUnit.SECONDS), 404, "NOT_FOUND");
            assertThat(memberCount()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void rosterRechecksActiveCourseAfterIdentityLookup() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        seedMember(course, STUDENT);
        IDENTITY.beforeLookup = () -> jdbc.update("update course set deletion_state='DELETING' where id=?", course);
        assertProblem(get(TEACHER, studentsPath(course)), 404, "NOT_FOUND");
        assertThat(memberCount()).isEqualTo(1);
    }

    @Test
    void exclusionAndPermissionsWaitingForDeletingCannotModifyMembership() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            for (String method : List.of("DELETE", "PATCH")) {
                UUID course = seed(TEACHER, "Курс", "ACTIVE");
                seedMember(course, STUDENT);
                String path = studentsPath(course) + "/" + STUDENT + (method.equals("PATCH") ? "/chat-permissions" : "");
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement("update course set deletion_state='DELETING' where id=?")) {
                        statement.setObject(1, course);
                        statement.executeUpdate();
                    }
                    var response = pool.submit(() -> request(method, path, TEACHER,
                            method.equals("PATCH") ? Map.of("canSendMessages", false) : null));
                    await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname=current_database() and wait_event_type='Lock' and query ilike '%course%'
                            """, Integer.class) > 0);
                    connection.commit();
                    assertProblem(response.get(5, TimeUnit.SECONDS), 404, "NOT_FOUND");
                    assertThat(jdbc.queryForObject("select can_send_messages from course_student where course_id=? and student_id=?",
                            Boolean.class, course, STUDENT)).isTrue();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rosterUsesBoundKeysetAndSurvivesRemovedAnchor() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        UUID other = seed(TEACHER, "Другой", "ACTIVE");
        for (int i = 0; i < 7; i++) seedMember(course, UUID.randomUUID());
        var expected = jdbc.queryForList("select student_id from course_student where course_id=? order by enrolled_at desc,student_id desc", UUID.class, course);
        var first = json(get(TEACHER, studentsPath(course) + "?limit=2"));
        var collected = new ArrayList<>(studentIds(first));
        assertThat(collected).containsExactlyElementsOf(expected.subList(0, 2));
        String cursor = first.path("nextCursor").asString();
        assertProblem(get(ADMIN, studentsPath(course) + "?cursor=" + cursor), 400, "INVALID_CURSOR");
        assertProblem(get(TEACHER, studentsPath(other) + "?cursor=" + cursor), 400, "INVALID_CURSOR");
        jdbc.update("delete from course_student where course_id=? and student_id=?", course, collected.get(1));
        var page = first;
        while (page.path("hasMore").asBoolean()) {
            page = json(get(TEACHER, studentsPath(course) + "?limit=2&cursor=" + page.path("nextCursor").asString()));
            collected.addAll(studentIds(page));
        }
        assertThat(collected).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void enrolledCoursePaginationUsesCurrentActiveMemberships() throws Exception {
        for (int i = 0; i < 5; i++) seedMember(seed(TEACHER, "Курс", "ACTIVE"), STUDENT);
        UUID deleting = seed(TEACHER, "Удаляется", "DELETING");
        seedMember(deleting, STUDENT);
        seed(TEACHER, "Без членства", "ACTIVE");
        var expected = jdbc.queryForList("select id from course where deletion_state='ACTIVE' and id in (select course_id from course_student where student_id=?) order by created_at desc,id desc", UUID.class, STUDENT);
        var page = json(get(STUDENT, "/api/v1/courses?scope=ENROLLED&limit=2"));
        var collected = new ArrayList<>(ids(page));
        jdbc.update("delete from course_student where course_id=? and student_id=?", collected.get(1), STUDENT);
        while (page.path("hasMore").asBoolean()) {
            page = json(get(STUDENT, "/api/v1/courses?scope=ENROLLED&limit=2&cursor=" + page.path("nextCursor").asString()));
            collected.addAll(ids(page));
        }
        assertThat(collected).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertProblem(get(STUDENT, "/api/v1/courses?scope=ENROLLED&teacherId=" + TEACHER), 400, "INVALID_REQUEST");
        assertProblem(get(STUDENT, "/api/v1/courses/" + deleting), 404, "NOT_FOUND");
    }

    @Test
    void rosterLimitsAndEnrollmentBodiesAreValidated() throws Exception {
        UUID course = seed(TEACHER, "Курс", "ACTIVE");
        String path = studentsPath(course);
        assertProblem(request("POST", path, TEACHER, Map.of()), 422, "INVALID_REQUEST");
        assertProblem(request("POST", path, TEACHER, Map.of("studentId", "bad")), 400, "INVALID_REQUEST");
        assertProblem(request("POST", path, TEACHER, Map.of("studentId", STUDENT, "unknown", true)), 400, "INVALID_REQUEST");
        assertProblem(request("PATCH", path + "/" + STUDENT + "/chat-permissions", TEACHER, Map.of()), 422, "INVALID_REQUEST");
        assertProblem(request("PATCH", path + "/bad/chat-permissions", TEACHER, Map.of("canSendMessages", true)), 400, "INVALID_REQUEST");
        for (int limit : new int[]{0, -1, 101}) assertProblem(get(TEACHER, path + "?limit=" + limit), 400, "INVALID_LIMIT");
        assertProblem(get(TEACHER, path + "?cursor=broken"), 400, "INVALID_CURSOR");
        for (int i = 0; i < 31; i++) seedMember(course, UUID.randomUUID());
        assertThat(studentIds(json(get(TEACHER, path)))).hasSize(30);
        assertThat(studentIds(json(get(TEACHER, path + "?limit=100")))).hasSize(31);
        // confirmConflicts is accepted; the actual schedule checker belongs to stage 7.
        assertThat(request("POST", path, TEACHER, Map.of("studentId", STUDENT, "confirmConflicts", true)).statusCode()).isEqualTo(201);
    }

    @Test
    void deletingCourseRejectsEveryEnrollmentOperation() throws Exception {
        UUID course = seed(TEACHER, "Курс", "DELETING");
        seedMember(course, STUDENT);
        for (UUID actor : List.of(TEACHER, ADMIN)) {
            assertProblem(get(actor, studentsPath(course)), 404, "NOT_FOUND");
            assertProblem(enroll(course, MULTI, actor), 404, "NOT_FOUND");
            assertProblem(request("DELETE", studentsPath(course) + "/" + STUDENT, actor, null), 404, "NOT_FOUND");
            assertProblem(request("PATCH", studentsPath(course) + "/" + STUDENT + "/chat-permissions", actor,
                    Map.of("canSendMessages", false)), 404, "NOT_FOUND");
        }
        assertThat(memberCount()).isEqualTo(1);
        assertThat(LOOKUPS.get()).isZero();
    }

    @Test
    void v2UpgradePreservesCourseAndCreatesMembershipConstraintsAndDefaults() {
        String schema = "stage4_upgrade";
        UUID course = UUID.randomUUID();
        try {
            var v2 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("2").load();
            assertThat(v2.migrate().migrationsExecuted).isEqualTo(2);
            jdbc.update("insert into stage4_upgrade.course(id,teacher_id,title,created_at) values (?,?,'Сохранить',now())", course, TEACHER);
            var current = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).target("3").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select title from stage4_upgrade.course where id=?", String.class, course)).isEqualTo("Сохранить");
            jdbc.update("insert into stage4_upgrade.course_student(course_id,student_id,enrolled_at) values (?,?,now())", course, STUDENT);
            assertThat(jdbc.queryForObject("select can_send_messages from stage4_upgrade.course_student", Boolean.class)).isTrue();
            assertThatThrownBy(() -> jdbc.update("insert into stage4_upgrade.course_student values (?,?,false,now())", course, STUDENT))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into stage4_upgrade.course_student values (?,?,true,now())", UUID.randomUUID(), STUDENT))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForList("select column_name from information_schema.key_column_usage where table_schema=? and table_name='course_student' and constraint_name in (select constraint_name from information_schema.table_constraints where table_schema=? and constraint_type='FOREIGN KEY')", String.class, schema, schema))
                    .containsExactly("course_id");
            assertThat(jdbc.queryForList("select indexname from pg_indexes where schemaname=? and tablename='course_student'", String.class, schema))
                    .contains("idx_course_student_student_course", "idx_course_student_page");
        } finally {
            jdbc.execute("drop schema if exists stage4_upgrade cascade");
        }
    }

    private String studentsPath(UUID course) { return "/api/v1/courses/" + course + "/students"; }

    private HttpResponse<String> enroll(UUID course, UUID student, UUID actor) throws Exception {
        return request("POST", studentsPath(course), actor, Map.of("studentId", student));
    }

    private int memberCount() { return jdbc.queryForObject("select count(*) from course_student", Integer.class); }

    private void seedMember(UUID course, UUID student) {
        PEOPLE.putIfAbsent(student, new Person(student, List.of("STUDENT"), false));
        jdbc.update("insert into course_student(course_id,student_id,enrolled_at) values (?,?,?)", course, student,
                OffsetDateTime.ofInstant(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC));
    }

    private List<UUID> studentIds(JsonNode page) {
        var ids = new ArrayList<UUID>();
        for (var item : page.path("items")) ids.add(UUID.fromString(item.path("studentId").asString()));
        return ids;
    }

    private UUID seed(UUID teacher, String title, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into course (id,teacher_id,title,deletion_state,created_at) values (?,?,?,?,?)",
                id, teacher, title, state, OffsetDateTime.ofInstant(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC));
        return id;
    }

    private HttpResponse<String> create(UUID actor, Map<String, ?> body) throws Exception {
        return request("POST", "/api/v1/courses", actor, body);
    }

    private HttpResponse<String> get(UUID actor, String path) throws Exception { return request("GET", path, actor, null); }

    private HttpResponse<String> request(String method, String path, UUID actor, Map<String, ?> body) throws Exception {
        var person = PEOPLE.get(actor);
        String token = TestJwt.token(person.roles(), claims -> claims.subject(actor.toString()), false);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return HTTP.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }

    private List<UUID> ids(JsonNode page) {
        var ids = new ArrayList<UUID>();
        for (var item : page.path("items")) ids.add(UUID.fromString(item.path("id").asString()));
        return ids;
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json(response).path("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("private-title", "private-upstream-diagnostic", "\"teacherId\":", "\"meetingUrl\":", "stackTrace");
    }

}
