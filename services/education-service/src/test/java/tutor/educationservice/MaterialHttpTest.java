package tutor.educationservice;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tutor.educationservice.integration.storage.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaterialHttpTest {
    static final UUID TEACHER = UUID.randomUUID(), OTHER = UUID.randomUUID(), STUDENT = UUID.randomUUID();
    static final IdentityHttpStub identity = new IdentityHttpStub();
    static final String assignmentToken = UUID.randomUUID().toString();
    static final HttpClient http = HttpClient.newHttpClient();
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("education.identity.base-url", identity::baseUrl);
        registry.add("education.assignment.token", () -> assignmentToken);
        registry.add("education.storage.endpoint", () -> "http://localhost:1");
        registry.add("education.storage.bucket", () -> "material-test");
        registry.add("education.storage.access-key", () -> "material-test-access");
        registry.add("education.storage.secret-key", () -> "material-test-secret");
    }

    @MockitoBean ObjectStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        identity.people.put(TEACHER, new IdentityHttpStub.Person(TEACHER, List.of("TEACHER"), false));
        identity.people.put(OTHER, new IdentityHttpStub.Person(OTHER, List.of("TEACHER"), false));
        identity.people.put(STUDENT, new IdentityHttpStub.Person(STUDENT, List.of("STUDENT"), false));
        jdbc.update("delete from cleanup_job");
        jdbc.update("delete from pending_upload");
        jdbc.update("delete from material_reference");
        jdbc.update("delete from material_file");
        jdbc.update("delete from material_folder");
        jdbc.update("delete from course_student");
        jdbc.update("delete from course");
        doAnswer(call -> {
            try (InputStream stream = call.getArgument(1)) {
                objects.put(call.getArgument(0), stream.readAllBytes());
            }
            return null;
        }).when(storage).put(anyString(), any(), anyLong(), anyString());
        doAnswer(call -> { objects.put(call.getArgument(1), objects.get(call.getArgument(0))); return null; })
                .when(storage).copy(anyString(), anyString());
        when(storage.get(anyString())).thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(0))));
        doAnswer(call -> { objects.remove(call.getArgument(0)); return null; }).when(storage).delete(anyString());
    }

    @AfterAll
    static void closeIdentity() { identity.close(); }

    @Test
    void teacherCanMoveAndDownloadAFileWithoutExposingItToOtherUsers() throws Exception {
        String parent = id(request("POST", "/materials/folders", TEACHER, "{\"name\":\"Folder\"}"), 201);
        var contents = request("GET", "/materials?parentId=" + parent, TEACHER, null);
        assertThat(contents.statusCode()).as(contents.body()).isEqualTo(200);
        assertThat(mapper.readTree(contents.body()).path("files").size()).isZero();
        var response = upload(TEACHER, parent);
        String id = id(response, 201);
        assertThat(response.body()).doesNotContain("objectKey", "materials/");
        String path = "/materials/files/" + id;
        assertThat(request("GET", path + "/content", OTHER, null).statusCode()).isEqualTo(403);
        assertThat(request("GET", path + "/content", STUDENT, null).statusCode()).isEqualTo(403);
        var moved = request("PATCH", path, TEACHER, "{\"folderId\":null,\"name\":\"renamed.txt\"}");
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(mapper.readTree(moved.body()).path("folderId").isNull()).isTrue();
        var downloaded = request("GET", path + "/content", TEACHER, null);
        assertThat(downloaded.body()).isEqualTo("file bytes");
        assertThat(downloaded.headers().firstValue("Content-Disposition")).hasValueSatisfying(value -> assertThat(value).contains("attachment", "renamed.txt"));
    }

    @Test
    void failedCopyKeepsCleanupKeysAndDoesNotPublishFile() throws Exception {
        doThrow(new StorageException(StorageException.Reason.UNAVAILABLE)).when(storage).copy(anyString(), anyString());
        assertThat(upload(TEACHER, null).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("select count(*) from material_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pending_upload where not published", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select resource_type from cleanup_job", String.class)).isEqualTo("PENDING_UPLOAD");
    }

    @Test
    void parentGrantsAndFileRulesApplyOnlyThroughTheSelectedCourse() throws Exception {
        String parent = id(request("POST", "/materials/folders", TEACHER, "{\"name\":\"Parent\"}"), 201);
        String child = id(request("POST", "/materials/folders", TEACHER, "{\"name\":\"Child\",\"parentId\":\"" + parent + "\"}"), 201);
        String file = id(upload(TEACHER, child), 201);
        String first = id(request("POST", "/courses", TEACHER, "{\"title\":\"First\"}"), 201);
        String second = id(request("POST", "/courses", TEACHER, "{\"title\":\"Second\"}"), 201);
        for (String course : List.of(first, second)) {
            assertThat(request("POST", "/courses/" + course + "/students", TEACHER, "{\"studentId\":\"" + STUDENT + "\"}").statusCode()).isEqualTo(201);
            assertThat(request("PUT", "/materials/folders/" + parent + "/course-access/" + course, TEACHER, null).statusCode()).isEqualTo(204);
        }
        String list = "/courses/" + first + "/materials";
        var visible = request("GET", list + "?limit=1", STUDENT, null);
        assertThat(visible.statusCode()).as(visible.body()).isEqualTo(200);
        assertThat(mapper.readTree(visible.body()).path("items").get(0).path("id").asString()).isEqualTo(file);
        String path = list + "/files/" + file + "/content";
        assertThat(request("GET", path, STUDENT, null).statusCode()).isEqualTo(200);
        String rule = "/materials/files/" + file + "/course-access/" + first;
        assertThat(request("PUT", rule, TEACHER, "{\"allowRead\":false}").statusCode()).isEqualTo(204);
        assertThat(request("GET", path, STUDENT, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/courses/" + second + "/materials/files/" + file + "/content", STUDENT, null).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", rule, TEACHER, null).statusCode()).isEqualTo(204);
        assertThat(request("GET", path, STUDENT, null).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", "/courses/" + first + "/students/" + STUDENT, TEACHER, null).statusCode()).isEqualTo(204);
        assertThat(request("GET", path, STUDENT, null).statusCode()).isEqualTo(404);
    }

    @Test
    void assignmentReferencesBlockDeletionOfAFileAndItsFolderUntilReleased() throws Exception {
        String folder = id(request("POST", "/materials/folders", TEACHER, "{\"name\":\"Folder\"}"), 201);
        String file = id(upload(TEACHER, folder), 201);
        UUID reference = UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of("referenceId", reference,
                "consumerService", "ASSIGNMENT", "consumerEntityId", UUID.randomUUID()));
        String path = "/internal/v1/materials/" + file + "/references";
        String userToken = TestJwt.token(List.of("TEACHER"), claims -> claims.subject(TEACHER.toString()), false);
        assertThat(internal("POST", path, userToken, body).statusCode()).isEqualTo(401);
        assertThat(internal("POST", path, assignmentToken, body).statusCode()).isEqualTo(201);
        assertThat(internal("POST", path, assignmentToken, body).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", "/materials/files/" + file, TEACHER, null).statusCode()).isEqualTo(409);
        assertThat(request("DELETE", "/materials/folders/" + folder, TEACHER, null).statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select state from material_file", String.class)).isEqualTo("ACTIVE");
        assertThat(internal("DELETE", path + "/" + reference, assignmentToken, null).statusCode()).isEqualTo(204);
        var first = request("DELETE", "/materials/folders/" + folder, TEACHER, null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        assertThat(request("DELETE", "/materials/folders/" + folder, TEACHER, null).body()).isEqualTo(first.body());
        assertThat(request("GET", "/materials/files/" + file + "/content", TEACHER, null).statusCode()).isEqualTo(404);
        assertThat(internal("POST", path, assignmentToken, body).statusCode()).isEqualTo(409);
    }

    private HttpResponse<String> internal(String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String id(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return mapper.readTree(response.body()).path("id").asString();
    }

    HttpResponse<String> upload(UUID user, String folder) throws Exception {
        String parent = folder == null ? "" : "--test-boundary\r\nContent-Disposition: form-data; name=\"folderId\"\r\n\r\n" + folder + "\r\n";
        String body = parent + "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nfile bytes\r\n--test-boundary--\r\n";
        return http.send(builder("/materials/files", user).header("Content-Type", "multipart/form-data; boundary=test-boundary")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> request(String method, String path, UUID user, String body) throws Exception {
        return http.send(builder(path, user).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder builder(String path, UUID user) {
        String token = TestJwt.token(identity.people.get(user).roles(), claims -> claims.subject(user.toString()), false);
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
    }
}
