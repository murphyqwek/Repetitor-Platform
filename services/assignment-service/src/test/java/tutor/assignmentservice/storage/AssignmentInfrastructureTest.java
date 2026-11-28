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
import java.util.List;
import tutor.assignmentservice.exam.*;
import java.net.http.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tutor.assignmentservice.ProviderStub;
import tutor.assignmentservice.TestJwt;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.common.pagination.CursorCodec;
import tutor.assignmentservice.integration.identity.IdentityClient;
import tutor.assignmentservice.integration.education.EducationClient;
import tutor.assignmentservice.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
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

@Testcontainers
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(AssignmentInfrastructureTest.Probe.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssignmentInfrastructureTest {
    private static final ProviderStub identity = new ProviderStub();
    private static final ProviderStub education = new ProviderStub();

    @org.junit.jupiter.api.AfterAll
    static void stopStubs() { identity.close(); education.close(); }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/test/{id}")
        CurrentUser user(@PathVariable UUID id) { return CurrentUser.get(); }
        record Payload(UUID id) {}
        @PostMapping("/api/v1/test")
        CurrentUser post(@RequestBody Payload payload) { return CurrentUser.get(); }
    }

    @Autowired IdentityClient directory;
    @Autowired EducationClient recipients;
    @Autowired CursorCodec cursors;

    private static final String BUCKET = "assignment-private";
    private static final String ACCESS = "assignment-test";
    private static final String SECRET = java.util.UUID.randomUUID().toString();
    private static final String ROOT_SECRET = java.util.UUID.randomUUID().toString();
    private static final org.testcontainers.containers.Network network = org.testcontainers.containers.Network.newNetwork();
    private static final DockerImageName SILO = DockerImageName.parse(
            "pgsty/silo:RELEASE.2026-11-12T00-00-00Z@sha256:c20d112390b9a9ce8c11b8af95ad35bcaa62f4759663c09c936cd8d2b511f329");

    @Container
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("assignment");

    @Container
    private static final GenericContainer<?> s3 = new GenericContainer<>(SILO)
            .withNetwork(network).withNetworkAliases("minio")
            .withEnv("MINIO_ROOT_USER", "admin-test")
            .withEnv("MINIO_ROOT_PASSWORD", ROOT_SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @Container
    static final GenericContainer<?> bootstrap = new GenericContainer<>("pgsty/mc:RELEASE.2026-11-12T00-00-00Z@sha256:b532278b7a3747dbab6fe45eebb40a653efa704e6758e24e9d9ec54fc96c1800")
            .dependsOn(s3).withNetwork(network)
            .withEnv("MINIO_ROOT_USER", "admin-test").withEnv("MINIO_ROOT_PASSWORD", ROOT_SECRET)
            .withEnv("ASSIGNMENT_S3_BUCKET", BUCKET).withEnv("ASSIGNMENT_S3_ACCESS_KEY", ACCESS)
            .withEnv("ASSIGNMENT_S3_SECRET_KEY", SECRET).withEnv("EDUCATION_S3_BUCKET", "education-private")
            .withEnv("EDUCATION_S3_ACCESS_KEY", "education-test")
            .withCopyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(
                    java.nio.file.Path.of("../../Docker/local/minio/bootstrap.sh").toAbsolutePath()), "/bootstrap.sh")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-ec",
                    "sh /bootstrap.sh assignment && sh /bootstrap.sh assignment"))
            .withStartupCheckStrategy(new org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy())
            .waitingFor(Wait.forLogMessage(".*restricted S3 user initialized.*", 2));

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        try (var client = MinioClient.builder().endpoint(s3Endpoint()).credentials("admin-test", ROOT_SECRET).build()) {
            client.makeBucket(MakeBucketArgs.builder().bucket("education-private").build());
        }
        TestJwt.properties(registry);
        registry.add("assignment.identity.url", identity::url);
        registry.add("assignment.education.url", education::url);
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
        assertStatus(http, baseUrl() + "/internal/v1/test", 401);
        assertStatus(http, baseUrl() + "/api/v1/assignments", 401);
    }

    @Test
    void streamsExactBytesAndKeepsObjectsPrivate() throws Exception {
        byte[] payload = new byte[11 * 1024 * 1024];
        new Random(1).nextBytes(payload);
        String key = ObjectStorage.newKey();
        try {
            storage.put(key, new ByteArrayInputStream(payload), -1, "application/pdf");
            assertThatThrownBy(() -> storage.put(key, new ByteArrayInputStream(new byte[]{1}), 1, "application/pdf"))
                    .isInstanceOfSatisfying(StorageException.class, ex -> assertThat(ex.reason()).isEqualTo(StorageException.Reason.ALREADY_EXISTS));
            assertThatThrownBy(() -> storage.put(key, new ByteArrayInputStream(payload), -1, "application/pdf"))
                    .isInstanceOfSatisfying(StorageException.class, ex -> assertThat(ex.reason()).isEqualTo(StorageException.Reason.ALREADY_EXISTS));
            try (var client = MinioClient.builder().endpoint(s3Endpoint()).credentials(ACCESS, SECRET).build()) {
                assertThatThrownBy(() -> client.listObjects(io.minio.ListObjectsArgs.builder().bucket("education-private").build()).iterator().next().get())
                        .isInstanceOfSatisfying(io.minio.errors.ErrorResponseException.class, ex -> assertThat(ex.response().code()).isEqualTo(403));
            }
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

    private HttpResponse<String> call(String path, String token, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(baseUrl() + path)).header("X-User-Id", UUID.randomUUID().toString());
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String user(boolean blocked, boolean complete, String role) {
        return """
                {"id":"%s","roles":["%s"],"firstName":"A","surname":"B","patronymic":null,"blocked":%s,"profileCompleted":%s}
                """.formatted(TestJwt.USER,role,blocked,complete);
    }

    @Test
    void publicJwtAndLiveIdentityAreBothRequiredOnEveryRequest() throws Exception {
        String path = "/api/v1/test/" + UUID.randomUUID();
        var roles = List.of("ADMIN","TEACHER");
        String token = TestJwt.token(roles);
        identity.respond(200,user(false,true,"TEACHER"));
        var ok = call(path,token,null);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).contains(TestJwt.USER.toString(),"TEACHER").doesNotContain("ADMIN");
        var signed = com.nimbusds.jwt.SignedJWT.parse(identity.authorization.get().substring(7));
        assertThat(signed.verify(new com.nimbusds.jose.crypto.MACVerifier(java.util.Base64.getDecoder().decode(TestJwt.CURSOR_KEY)))).isTrue();
        assertThat(signed.getJWTClaimsSet().getAudience()).containsExactly("identity-service");
        assertThat(signed.getJWTClaimsSet().getClaim("tokenType")).isEqualTo("SERVICE");
        assertThat(call("/internal/v1/test",token,null).statusCode()).isEqualTo(403);
        assertThat(call(path,null,null).statusCode()).isEqualTo(401);
        for (String invalid : List.of(TestJwt.token(roles,c -> {},true),
                TestJwt.token(roles,c -> c.audience(List.of("education-service")),false),
                TestJwt.token(roles,c -> c.issuer("other"),false),
                TestJwt.token(roles,c -> c.issuedAt(java.time.Instant.now().minusSeconds(600)).expiresAt(java.time.Instant.now().minusSeconds(60)),false))) {
            assertThat(call(path,invalid,null).statusCode()).isEqualTo(401);
        }
        identity.respond(200,user(true,true,"TEACHER"));
        var blocked = call(path,token,null);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(blocked.body()).contains("USER_BLOCKED");
        identity.respond(200,user(false,false,"TEACHER"));
        assertThat(call(path,token,null).body()).contains("PROFILE_INCOMPLETE");
        identity.respond(200,user(false,true,"PARENT"));
        assertThat(call(path,TestJwt.token(List.of("PARENT")),null).statusCode()).isEqualTo(403);
        identity.respond(404,"{}");
        assertThat(call(path,token,null).statusCode()).isEqualTo(401);
        identity.respond(200,"{}");
        assertThat(call(path,token,null).body()).contains("IDENTITY_UNAVAILABLE");
        identity.respond(500,"{}");
        assertThat(call(path,token,null).statusCode()).isEqualTo(503);
        identity.reply.set(new ProviderStub.Reply(200,user(false,true,"TEACHER"),3400));
        assertThat(call(path,token,null).statusCode()).isEqualTo(503);
    }

    @Test
    void cursorAndMalformedInputsAreRejected() throws Exception {
        var id = TestJwt.USER;
        var position = new CursorCodec.Position(java.time.Instant.now(), id);
        var scope = new CursorCodec.Scope(id,"tasks","created","OWNED");
        var token = cursors.encode(position, scope);
        assertThat(cursors.decode(token, scope)).isEqualTo(position);
        for (var wrong : List.of(new CursorCodec.Scope(UUID.randomUUID(),"tasks","created","OWNED"),
                new CursorCodec.Scope(id,"tasks","other","OWNED"),new CursorCodec.Scope(id,"tasks","created","AVAILABLE"),
                new CursorCodec.Scope(id,"other","created","OWNED"))) {
            assertThatThrownBy(() -> cursors.decode(token,wrong)).isInstanceOf(ApiException.class);
        }
        for (int limit : List.of(0,101)) assertThatThrownBy(() -> CursorCodec.limit(limit)).isInstanceOf(ApiException.class);
        identity.respond(200,user(false,true,"TEACHER"));
        var jwt = TestJwt.token(List.of("TEACHER"));
        for (var res : List.of(call("/api/v1/test/bad",jwt,null),call("/api/v1/test",jwt,"{"))) {
            assertThat(res.statusCode()).as(res.body()).isEqualTo(400);
            assertThat(res.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
            assertThat(res.body()).contains("INVALID_REQUEST");
        }
    }

    @Test
    void clientsRejectMissingExtraOrMalformedProviderData() {
        var id = TestJwt.USER;
        identity.respond(200,"{\"users\":[],\"missingUserIds\":[\"" + id + "\"]}");
        assertThat(directory.batch(List.of(id,id)).missingUserIds()).containsExactly(id);
        identity.respond(200,"{\"users\":[],\"missingUserIds\":[]}");
        assertThatThrownBy(() -> directory.batch(List.of(id))).isInstanceOf(ApiException.class);
        education.respond(200,"{\"enrolled\":false}");
        assertThat(recipients.enrolled(id,id)).isFalse();
        education.respond(200,"{}");
        assertThatThrownBy(() -> recipients.enrolled(id,id)).isInstanceOf(ApiException.class);
        education.respond(200,"{\"resolvedAt\":\"2026-11-27T00:00:00Z\",\"courses\":[],\"recipients\":[],\"invalidCourseIds\":[],\"ineligibleStudentIds\":[]}");
        assertThat(recipients.resolve(id,Set.of(),Set.of()).recipients()).isEmpty();
        assertThatThrownBy(() -> recipients.resolve(id,Set.of(),Set.of(id))).isInstanceOf(ApiException.class);
    }

    @Autowired ExamCatalog catalog;
    @Autowired ExamScoreConverter converter;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired org.flywaydb.core.Flyway flyway;
    final tools.jackson.databind.json.JsonMapper json=new tools.jackson.databind.json.JsonMapper();

    tools.jackson.databind.JsonNode api(String method,String path,Object body,int expected) throws Exception {
        identity.respond(200,user(false,true,"ADMIN"));
        var req=HttpRequest.newBuilder(URI.create(baseUrl()+"/api/v1"+path))
                .header("Authorization","Bearer "+TestJwt.token(List.of("ADMIN"))).header("Content-Type","application/json")
                .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var res=HttpClient.newHttpClient().send(req.build(),HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expected);
        return json.readTree(res.body().isBlank() ? "{}" : res.body());
    }
    Map<String,Object> layout(int topic,int score) {
        return Map.of("durationMinutes",180,"positions",List.of(Map.of("position",1,"maxScore",score,"checkMode","AUTOMATIC","topicIds",List.of(topic))));
    }
    List<Map<String,Integer>> values(int score) {
        return List.of(Map.of("primaryScore",0,"testScore",0),Map.of("primaryScore",1,"testScore",score));
    }

    @Test void catalogAndPinnedVersions() throws Exception {
        assertThat(api("GET","/subjects",null,200).size()).isEqualTo(1);
        assertThat(api("GET","/exam-types",null,200).size()).isEqualTo(3);
        String name="Topic-"+UUID.randomUUID();
        int topic=api("POST","/topics",Map.of("subjectId",1,"name",name),201).path("id").asInt();
        api("POST","/topics",Map.of("subjectId",1,"name",name),409);
        api("PATCH","/topics/"+topic,Map.of("name",name+" renamed"),200);
        var e=api("POST","/exams",Map.of("examTypeId",1,"year",2051,"structure",layout(topic,1)),201);
        UUID exam=UUID.fromString(e.path("id").asString()), old=UUID.fromString(e.path("currentStructureVersionId").asString());
        var s=api("POST","/exams/"+exam+"/scales",Map.of("name","Synthetic","structureVersionId",old,"values",values(50)),201);
        UUID scale=UUID.fromString(s.path("id").asString()), version=UUID.fromString(s.path("currentVersionId").asString());
        var v=api("POST","/grading-scales/"+scale+"/versions",Map.of("expectedVersionId",version,"structureVersionId",old,"values",values(60)),201);
        assertThat(converter.convert(version,1).testScore()).isEqualTo(50);
        assertThat(converter.convert(UUID.fromString(v.path("id").asString()),1).testScore()).isEqualTo(60);
        assertThatThrownBy(()->converter.convert(version,2)).isInstanceOf(ApiException.class);
        api("POST","/grading-scales/"+scale+"/versions",Map.of("expectedVersionId",version,"structureVersionId",old,"values",values(60)),409);
        var next=api("POST","/exams/"+exam+"/structures",Map.of("expectedStructureVersionId",old,"structure",layout(topic,2)),201);
        api("POST","/exams/"+exam+"/structures",Map.of("expectedStructureVersionId",old,"structure",layout(topic,2)),409);
        assertThat(api("GET","/exams/"+exam+"/structures/"+old,null,200).path("maxScore").asInt()).isEqualTo(1);
        var before=catalog.structure(exam,old);
        assertThat(CompatiblePositions.between(before,before)).hasSize(1);
        assertThat(CompatiblePositions.between(before,catalog.structure(exam,UUID.fromString(next.path("id").asString())))).isEmpty();
        String cursor=api("GET","/exams/"+exam+"/structures?limit=1",null,200).path("nextCursor").asString();
        assertThat(api("GET","/exams/"+exam+"/structures?limit=1&cursor="+cursor,null,200).path("items").get(0).path("id").asString()).isEqualTo(old.toString());
        assertThat(api("GET","/grading-scales/"+scale+"/versions?limit=1",null,200).path("hasMore").asBoolean()).isTrue();
        api("GET","/grading-scales/"+scale+"/versions/"+version,null,200);
        api("GET","/exams/"+exam+"/scales",null,200);
        api("DELETE","/topics/"+topic,null,204);
        assertThat(api("GET","/topics?active=false&search="+name,null,200).path("items").size()).isEqualTo(1);
        api("POST","/exams",Map.of("examTypeId",1,"year",2052,"structure",layout(topic,1)),422);
        assertThat(api("GET","/exams?year=2052",null,200).path("items").size()).isZero();
    }

    @Test void rejectsBadStructuresScalesAndScopedCursors() throws Exception {
        int topic=api("POST","/topics",Map.of("subjectId",1,"name","Validation-"+UUID.randomUUID()),201).path("id").asInt();
        var e=api("POST","/exams",Map.of("examTypeId",2,"year",2053,"structure",layout(topic,1)),201);
        String exam=e.path("id").asString(), structure=e.path("currentStructureVersionId").asString();
        api("POST","/exams",Map.of("examTypeId",2,"year",2053,"structure",layout(topic,1)),409);
        for(var vals:List.of(values(50),List.of(Map.of("primaryScore",0,"schoolMark",2)),
                List.of(Map.of("primaryScore",0,"schoolMark",3),Map.of("primaryScore",1,"schoolMark",2)),
                List.of(Map.of("primaryScore",0,"schoolMark",2),Map.of("primaryScore",0,"schoolMark",5)))) {
            api("POST","/exams/"+exam+"/scales",Map.of("name","Invalid","structureVersionId",structure,"values",vals),422);
        }
        assertThat(api("GET","/exams/"+exam+"/scales",null,200).size()).isZero();
        var s=api("POST","/exams/"+exam+"/scales",Map.of("name","School","structureVersionId",structure,"values",
                List.of(Map.of("primaryScore",0,"schoolMark",2),Map.of("primaryScore",1,"schoolMark",5))),201);
        assertThat(converter.convert(UUID.fromString(s.path("currentVersionId").asString()),1).schoolMark()).isEqualTo(5);
        api("POST","/exams/"+exam+"/structures",Map.of("expectedStructureVersionId",structure,"structure",
                Map.of("durationMinutes",0,"positions",List.of())),422);
        api("POST","/exams/"+exam+"/structures",Map.of("expectedStructureVersionId",structure,"structure",
                Map.of("durationMinutes",10,"positions",List.of(Map.of("position",2,"maxScore",1,"checkMode","MANUAL","topicIds",List.of(topic))))),422);
        api("POST","/topics",Map.of("subjectId",1,"name","Second-"+UUID.randomUUID()),201);
        String cursor=api("GET","/topics?limit=1",null,200).path("nextCursor").asString();
        api("GET","/topics?limit=1&cursor="+cursor,null,200);
        api("GET","/topics?active=true&limit=1&cursor="+cursor,null,400);
        api("GET","/topics?limit=0",null,400);
        identity.respond(200,user(false,true,"TEACHER"));
        var req=HttpRequest.newBuilder(URI.create(baseUrl()+"/api/v1/topics")).header("Authorization","Bearer "+TestJwt.token(List.of("TEACHER")))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"subjectId\":1,\"name\":\"Forbidden\"}")).build();
        assertThat(HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
    }


    @Test void concurrentStructureUpdatesHaveOneWinnerAndForeignPointersAreRejected() throws Exception {
        int topic=api("POST","/topics",Map.of("subjectId",1,"name","Concurrent-"+UUID.randomUUID()),201).path("id").asInt();
        var e=api("POST","/exams",Map.of("examTypeId",3,"year",2054,"structure",layout(topic,1)),201);
        var other=api("POST","/exams",Map.of("examTypeId",3,"year",2055,"structure",layout(topic,1)),201);
        UUID id=UUID.fromString(e.path("id").asString()), old=UUID.fromString(e.path("currentStructureVersionId").asString());
        UUID foreign=UUID.fromString(other.path("currentStructureVersionId").asString());
        assertThatThrownBy(()->db.update("update exam_definition set current_structure_version_id=? where id=?",foreign,id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID scale=UUID.randomUUID();
        db.update("insert into grading_scale(id,exam_definition_id,name) values(?,?,'Foreign guard')",scale,id);
        assertThatThrownBy(()->db.update("insert into grading_scale_version(id,scale_id,structure_version_id,version,created_at) values(?,?,?,1,now())",
                UUID.randomUUID(),scale,foreign)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var input=new CatalogDtos.StructureCreate(old,new CatalogDtos.StructureInput(60,
                List.of(new CatalogDtos.PositionInput(1,1,CatalogDtos.CheckMode.AUTOMATIC,Set.of(topic)))));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> update=()->{
            start.await();
            try { catalog.createStructure(id,input); return true; }
            catch(ApiException ex) { assertThat(ex.status().value()).isEqualTo(409); return false; }
        };
        try {
            var first=executor.submit(update);
            var second=executor.submit(update);
            start.countDown();
            assertThat(first.get(15,java.util.concurrent.TimeUnit.SECONDS)).isNotEqualTo(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(db.queryForObject("select count(*) from exam_structure_version where exam_definition_id=?",Integer.class,id)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }

    @Test void migratesV1WithoutLosingDataAndChecksSqlConstraints() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(10);
        var old=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("catalog_upgrade").defaultSchema("catalog_upgrade").target("1").load();
        old.migrate();
        db.execute("create table catalog_upgrade.preserved(id integer primary key)");
        db.update("insert into catalog_upgrade.preserved values(1)");
        var latest=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("catalog_upgrade").defaultSchema("catalog_upgrade").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select count(*) from catalog_upgrade.preserved",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->db.update("insert into exam_definition values(?,1,2028,?,now())",UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("insert into topic(subject_id,name) values(1,' ')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
