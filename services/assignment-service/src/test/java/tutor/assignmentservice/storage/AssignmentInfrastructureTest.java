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
import tutor.assignmentservice.bank.BankDtos;
import tutor.assignmentservice.bank.AnswerType;
import tutor.assignmentservice.bank.TaskBank;
import tutor.assignmentservice.bank.TaskCleanup;
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

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
            try { catalog.createStructure(id,input,TestJwt.USER); return true; }
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
        assertThat(db.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(19);
        var old=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("catalog_upgrade").defaultSchema("catalog_upgrade").target("1").load();
        old.migrate();
        db.execute("create table catalog_upgrade.preserved(id integer primary key)");
        db.update("insert into catalog_upgrade.preserved values(1)");
        var latest=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("catalog_upgrade").defaultSchema("catalog_upgrade").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select count(*) from catalog_upgrade.preserved",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->db.update("insert into exam_definition values(?,1,2028,?,now())",UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->db.update("insert into topic(subject_id,name) values(1,' ')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Autowired io.minio.MinioClient imageStorageClient;
    @Autowired MultipartStorageClient imageMaintenanceClient;
    @Autowired StoredFiles storedFiles;
    @Autowired FileService fileService;
    @Autowired FileCleanup fileCleanup;
    @Autowired TaskBank bank;
    @Autowired TaskCleanup taskCleanup;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aM4sAAAAASUVORK5CYII=");

    @Test
    void taskBankKeepsVersionsAndScopesItsCursors() throws Exception {
        UUID teacher = UUID.randomUUID();
        UUID colleague = UUID.randomUUID();
        String marker = UUID.randomUUID().toString();
        var first = bankApi("POST", "/tasks", teacher, "TEACHER", numberTask(marker + " first"), 201);
        var second = bankApi("POST", "/tasks", teacher, "TEACHER", numberTask(marker + " second"), 201);
        String task = "/tasks/" + first.path("id").asString();
        String previous = first.path("versionId").asString();
        bankApi("POST", "/tasks", teacher, "ADMIN", numberTask(marker), 403);
        bankApi("GET", task, colleague, "TEACHER", null, 404);
        bankApi("GET", task, colleague, "STUDENT", null, 404);

        db.update("update task set created_at='2026-11-28 08:00:00+00' where id in (?,?)",
                UUID.fromString(first.path("id").asString()), UUID.fromString(second.path("id").asString()));
        String list = "/tasks?scope=OWNED&limit=1&answerType=NUMBER&search=" + marker;
        var page = bankApi("GET", list, teacher, "TEACHER", null, 200);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        String cursor = page.path("nextCursor").asString();
        var more = bankApi("GET", list + "&cursor=" + cursor, teacher, "TEACHER", null, 200);
        assertThat(more.path("items").get(0).path("id").asString())
                .isNotEqualTo(page.path("items").get(0).path("id").asString());
        bankApi("GET", list + "&cursor=" + cursor, colleague, "TEACHER", null, 400);

        var write = numberTask(marker + " first");
        write.put("expectedVersionId", previous);
        write.put("correctAnswer", Map.of("acceptedAnswers", List.of("+2.50")));
        assertThat(bankApi("PUT", task, teacher, "TEACHER", write, 200).path("versionId").asString()).isEqualTo(previous);
        write.put("statement", marker + " changed");
        var updated = bankApi("PUT", task, teacher, "TEACHER", write, 201);
        assertThat(bankApi("PUT", task, teacher, "TEACHER", write, 409).path("currentVersionId").asString())
                .isEqualTo(updated.path("versionId").asString());
        var detail = bankApi("GET", task + "/versions/" + previous, teacher, "TEACHER", null, 200);
        assertThat(detail.path("statement").asString()).isEqualTo(marker + " first");
        assertThat(detail.path("correctAnswer").path("acceptedAnswers").get(0).asString()).isEqualTo("2.5");
        var versions = bankApi("GET", task + "/versions?limit=1", teacher, "TEACHER", null, 200);
        assertThat(versions.path("items").get(0).path("current").asBoolean()).isTrue();
        var oldVersions = bankApi("GET", task + "/versions?cursor=" + versions.path("nextCursor").asString(),
                teacher, "TEACHER", null, 200);
        assertThat(oldVersions.path("items").get(0).path("id").asString()).isEqualTo(previous);
    }

    @Test
    void onlyOneConcurrentTaskUpdateCanReplaceTheExpectedVersion() throws Exception {
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Teacher");
        var original = bank.create(taskWrite("Original", null), actor);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> update = () -> {
            start.await();
            try {
                return bank.update(original.id(), taskWrite(UUID.randomUUID().toString(), original.versionId()), actor)
                        .receipt().version();
            } catch (ApiException conflict) {
                return conflict.status().value();
            }
        };
        try {
            var first = pool.submit(update);
            var second = pool.submit(update);
            start.countDown();
            assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(2, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void classificationTransferCreatesANewVersionOnlyForCompatiblePositions() throws Exception {
        int topic = api("POST", "/topics", Map.of("subjectId", 1, "name", "Transfer-" + UUID.randomUUID()), 201)
                .path("id").asInt();
        var positions = List.of(
                Map.of("position", 1, "maxScore", 1, "checkMode", "AUTOMATIC", "topicIds", List.of(topic)),
                Map.of("position", 2, "maxScore", 1, "checkMode", "AUTOMATIC", "topicIds", List.of(topic)));
        var exam = api("POST", "/exams", Map.of("examTypeId", 3, "year", 2199,
                "structure", Map.of("durationMinutes", 90, "positions", positions)), 201);
        String old = exam.path("currentStructureVersionId").asString();
        var oldPositions = exam.path("structure").path("positions");
        UUID teacher = UUID.randomUUID();
        var body = numberTask("Compatible");
        body.put("examPositionIds", List.of(oldPositions.get(0).path("id").asString()));
        body.put("topicIds", List.of(topic));
        var compatible = bankApi("POST", "/tasks", teacher, "TEACHER", body, 201);
        body.put("statement", "Incompatible");
        body.put("examPositionIds", List.of(oldPositions.get(1).path("id").asString()));
        var incompatible = bankApi("POST", "/tasks", teacher, "TEACHER", body, 201);

        String examId = exam.path("id").asString();
        var next = api("POST", "/exams/" + examId + "/structures", Map.of("expectedStructureVersionId", old,
                "structure", Map.of("durationMinutes", 120, "positions", List.of(positions.get(0),
                        Map.of("position", 2, "maxScore", 2, "checkMode", "AUTOMATIC", "topicIds", List.of(topic))))), 201);
        var filter = "/tasks?examDefinitionId=" + examId + "&examStructureVersionId=" + next.path("id").asString()
                + "&position=1&topicIds=" + topic + ",2147483647";
        var matches = bankApi("GET", filter, teacher, "TEACHER", null, 200).path("items");
        assertThat(matches.size()).isOne();
        assertThat(matches.get(0).path("id").asString()).isEqualTo(compatible.path("id").asString());
        assertThat(matches.get(0).path("version").asInt()).isEqualTo(2);
        var history = bankApi("GET", "/tasks/" + compatible.path("id").asString() + "/versions/"
                + compatible.path("versionId").asString(), teacher, "TEACHER", null, 200);
        assertThat(history.path("examPositions").size()).isOne();
        assertThat(bankApi("GET", "/tasks/" + incompatible.path("id").asString(), teacher, "TEACHER", null, 200)
                .path("version").asInt()).isOne();
    }

    @Test
    void importedCopySharesImagesAndStudentCannotReadExplanation() throws Exception {
        UUID teacher = UUID.randomUUID(), student = UUID.randomUUID(), admin = UUID.randomUUID();
        UUID statement = UUID.fromString(json.readTree(uploadImage(PNG, "image/png", teacher, "TEACHER").body())
                .path("id").asString());
        UUID explanation = UUID.fromString(json.readTree(uploadImage(PNG, "image/png", teacher, "TEACHER").body())
                .path("id").asString());
        var write = numberTask("Condition ![figure](attachment:" + statement + ")");
        write.put("explanation", "Solution ![figure](attachment:" + explanation + ")");
        int topic = api("POST", "/topics", Map.of("subjectId", 1, "name", "Imported-" + UUID.randomUUID()), 201)
                .path("id").asInt();
        var exam = api("POST", "/exams", Map.of("examTypeId", 3, "year", 2198, "structure", layout(topic, 1)), 201);
        write.put("examPositionIds", List.of(exam.path("structure").path("positions").get(0).path("id").asString()));
        bankApi("POST", "/tasks", admin, "TEACHER", write, 404);
        var unsafe = numberTask("![remote](https://example.com/figure.png)");
        bankApi("POST", "/tasks", teacher, "TEACHER", unsafe, 422);
        bankApi("POST", "/tasks", teacher, "TEACHER", numberTask("<img src='x'>"), 422);
        var original = bankApi("POST", "/tasks", teacher, "TEACHER", write, 201);
        String id = original.path("id").asString();
        String version = original.path("versionId").asString();
        UUID identical = UUID.fromString(json.readTree(uploadImage(PNG, "image/png", teacher, "TEACHER").body())
                .path("id").asString());
        write.put("statement", "Condition ![figure](attachment:" + identical + ")");
        write.put("expectedVersionId", version);
        assertThat(bankApi("PUT", "/tasks/" + id, teacher, "TEACHER", write, 200).path("versionId").asString())
                .isEqualTo(version);
        assertThat(db.queryForObject("select state from stored_file where id=?", String.class, identical)).isEqualTo("READY");
        String externalKey = "fixture-" + id;
        int source = db.queryForObject("select id from import_source where code='RESHUEGE'", Integer.class);
        db.update("update task set teacher_id=null,source_id=?,external_key=? where id=?", source, externalKey, UUID.fromString(id));

        var visible = bankApi("GET", "/tasks/" + id, student, "STUDENT", null, 200);
        assertThat(visible.has("correctAnswer")).isFalse();
        assertThat(visible.has("explanation")).isFalse();
        assertThat(visible.path("images").size()).isOne();
        assertThat(bankApi("GET", "/tasks?scope=IMPORTED", student, "STUDENT", null, 200)
                .path("items").get(0).path("id").asString()).isEqualTo(id);
        bankApi("GET", "/tasks/" + id + "/versions/" + version, student, "STUDENT", null, 403);
        var imagePath = "/api/v1/tasks/" + id + "/versions/" + version + "/images/";
        assertThat(assignmentHttp("GET", imagePath + statement + "/content", student, "STUDENT", null, null).body())
                .isEqualTo(PNG);
        assertThat(assignmentHttp("GET", imagePath + explanation + "/content", student, "STUDENT", null, null).statusCode())
                .isEqualTo(404);
        assertThat(imageHttp("DELETE", "/" + statement, teacher, "TEACHER", null, null).statusCode())
                .isEqualTo(409);
        UUID copyOwner = UUID.randomUUID();
        var copy = bankApi("POST", "/tasks/" + id + "/copy", copyOwner, "TEACHER", Map.of("sourceVersionId", version), 201);
        var copyId = copy.path("id").asString();
        String copyImage = "/api/v1/tasks/" + copyId + "/versions/" + copy.path("versionId").asString()
                + "/images/" + statement + "/content";
        write.put("statement", "Changed copy ![figure](attachment:" + statement + ")");
        write.put("expectedVersionId", copy.path("versionId").asString());
        assertThat(bankApi("PUT", "/tasks/" + copyId, copyOwner, "TEACHER", write, 201).path("version").asInt()).isEqualTo(2);
        assertThat(bankApi("GET", "/tasks/" + id, student, "STUDENT", null, 200).path("versionId").asString())
                .isEqualTo(version);

        bankApi("PATCH", "/tasks/" + id + "/import-settings", admin, "ADMIN", Map.of("manualOverride", true), 200);
        assertThat(db.queryForObject("select manual_override from task where id=?", Boolean.class, UUID.fromString(id))).isTrue();
        var job = bankApi("DELETE", "/tasks/" + id, admin, "ADMIN", null, 202).path("cleanupJobId").asString();
        assertThat(bankApi("DELETE", "/tasks/" + id, admin, "ADMIN", null, 202).path("cleanupJobId").asString()).isEqualTo(job);
        assertThat(taskCleanup.runOne()).isTrue();
        bankApi("GET", "/tasks/" + id, student, "STUDENT", null, 404);
        assertThat(db.queryForObject("select count(*) from deleted_imported_task where source_id=? and external_key=?",
                Integer.class, source, externalKey)).isOne();
        assertThat(assignmentHttp("GET", copyImage, copyOwner, "TEACHER", null, null).body()).isEqualTo(PNG);
        bankApi("DELETE", "/tasks/" + copyId, copyOwner, "TEACHER", null, 202);
        assertThat(taskCleanup.runOne()).isTrue();
        assertThat(db.queryForObject("select state from stored_file where id=?", String.class, statement)).isEqualTo("DELETING");
    }

    private BankDtos.Write taskWrite(String statement, UUID expected) {
        return new BankDtos.Write(1, statement, null, AnswerType.NUMBER, json.createObjectNode(),
                json.valueToTree(Map.of("acceptedAnswers", List.of("2.5"))), null, Set.of(), Set.of(), List.of(), expected);
    }

    private java.util.HashMap<String, Object> numberTask(String statement) {
        return new java.util.HashMap<>(Map.of("subjectId", 1, "statement", statement, "answerType", "NUMBER",
                "answerConfig", Map.of(), "correctAnswer", Map.of("acceptedAnswers", List.of("2.5"))));
    }

    private tools.jackson.databind.JsonNode bankApi(String method, String path, UUID actor, String role, Object body,
                                                   int status) throws Exception {
        var result = assignmentHttp(method, "/api/v1" + path, actor, role,
                body == null ? null : json.writeValueAsBytes(body), "application/json");
        assertThat(result.statusCode()).as(new String(result.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(status);
        return json.readTree(result.body());
    }

    private HttpResponse<byte[]> imageHttp(String method, String path, UUID actor, String role, byte[] body, String type) throws Exception {
        return assignmentHttp(method, "/api/v1/tasks/images" + path, actor, role, body, type);
    }

    private HttpResponse<byte[]> assignmentHttp(String method, String path, UUID actor, String role, byte[] body, String type) throws Exception {
        identity.respond(200, user(false, true, role).replace(TestJwt.USER.toString(), actor.toString()));
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + TestJwt.token(List.of(role), c -> c.subject(actor.toString()), false))
                .timeout(java.time.Duration.ofSeconds(15));
        if (type != null) request.header("Content-Type", type);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> uploadImage(byte[] bytes, String mime, UUID actor, String role) throws Exception {
        String boundary = "assignment-test-boundary";
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: "
                + mime + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return imageHttp("POST", "", actor, role, body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    @Test
    void temporaryImagesValidateOwnershipMimeSizeAndStreamExactBytes() throws Exception {
        UUID owner = UUID.randomUUID();
        var uploaded = uploadImage(PNG, "image/png", owner, "TEACHER");
        assertThat(uploaded.statusCode()).as(new String(uploaded.body())).isEqualTo(201);
        var metadata = json.readTree(uploaded.body());
        UUID id = UUID.fromString(metadata.path("id").asString());
        assertThat(metadata.path("state").asString()).isEqualTo("READY");
        assertThat(metadata.path("markdownReference").asString()).isEqualTo("attachment:" + id);
        assertThat(metadata.has("objectKey")).isFalse();
        assertThat(java.time.Instant.parse(metadata.path("expiresAt").asString()))
                .isAfter(java.time.Instant.now().plus(java.time.Duration.ofHours(23)));
        assertThat(db.queryForObject("select content_hash from stored_file where id=?", String.class, id))
                .isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(PNG)));
        var preview = imageHttp("GET", "/" + id + "/content", owner, "TEACHER", null, null);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).isEqualTo(PNG);
        assertThat(preview.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(preview.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(preview.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store","private");
        assertThat(preview.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
        assertThat(imageHttp("GET", "/" + id + "/content", UUID.randomUUID(), "TEACHER", null, null).statusCode()).isEqualTo(404);
        assertThat(imageHttp("GET", "/" + id + "/content", owner, "STUDENT", null, null).statusCode()).isEqualTo(403);
        assertThat(imageHttp("DELETE", "/" + id, UUID.randomUUID(), "TEACHER", null, null).statusCode()).isEqualTo(404);
        assertThat(imageHttp("GET", "/" + id + "/content", UUID.randomUUID(), "ADMIN", null, null).statusCode()).isEqualTo(200);
        int beforeRejected = db.queryForObject("select count(*) from stored_file",Integer.class);
        assertThat(uploadImage(PNG, "image/jpeg", owner, "TEACHER").statusCode()).isEqualTo(415);
        assertThat(uploadImage("<svg/>".getBytes(), "image/svg+xml", owner, "TEACHER").statusCode()).isEqualTo(415);
        assertThat(uploadImage(new byte[10_000_001], "image/png", owner, "TEACHER").statusCode()).isEqualTo(413);
        assertThat(db.queryForObject("select count(*) from stored_file",Integer.class)).isEqualTo(beforeRejected);
        byte[] boundary = new byte[10_000_000];
        System.arraycopy(PNG, 0, boundary, 0, PNG.length);
        var exact = uploadImage(boundary, "image/png", owner, "TEACHER");
        assertThat(exact.statusCode()).as(new String(exact.body())).isEqualTo(201);
        UUID boundaryId = UUID.fromString(json.readTree(exact.body()).path("id").asString());
        assertThat(imageHttp("DELETE", "/" + boundaryId, owner, "TEACHER", null, null).statusCode()).isEqualTo(204);
        db.update("update stored_file set state='ATTACHED',expires_at=null where id=?", id);
        assertThat(imageHttp("DELETE", "/" + id, owner, "TEACHER", null, null).statusCode()).isEqualTo(409);
        db.update("update stored_file set state='READY',expires_at=now()+interval '1 hour' where id=?", id);
        assertThat(imageHttp("DELETE", "/" + id, owner, "TEACHER", null, null).statusCode()).isEqualTo(204);
        UUID cleanupId = db.queryForObject("select id from cleanup_job where resource_id=?", UUID.class, id);
        assertThat(imageHttp("DELETE", "/" + id, owner, "TEACHER", null, null).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("select id from cleanup_job where resource_id=?", UUID.class, id)).isEqualTo(cleanupId);
        assertThat(imageHttp("GET", "/" + id + "/content", owner, "TEACHER", null, null).statusCode()).isEqualTo(404);
        fileCleanup.runOnce();
        assertThat(db.queryForObject("select count(*) from stored_file where id=?", Integer.class, id)).isZero();
    }

    @Test
    void ttlCleanupRetriesStorageFailureAndExpiredLeasesWithoutIdentity() throws Exception {
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Owner");
        var result = fileService.upload(new org.springframework.mock.web.MockMultipartFile("file","ttl.png","image/png",PNG),actor);
        String key = db.queryForObject("select object_key from stored_file where id=?", String.class, result.id());
        db.update("update stored_file set expires_at=timestamp '2020-01-01' where id=?", result.id());
        assertThatThrownBy(() -> storedFiles.preview(result.id(), actor)).isInstanceOf(ApiException.class);
        org.mockito.Mockito.doThrow(new StorageException(StorageException.Reason.UNAVAILABLE)).doCallRealMethod().when(storage).delete(key);
        identity.respond(503,"{}");
        fileCleanup.runOnce();
        UUID job = db.queryForObject("select id from cleanup_job where resource_id=?", UUID.class, result.id());
        assertThat(db.queryForObject("select status from cleanup_job where id=?", String.class, job)).isEqualTo("PENDING");
        assertThat(db.queryForObject("select state from stored_file where id=?", String.class, result.id())).isEqualTo("DELETING");
        assertThat(db.queryForObject("select last_error from cleanup_job where id=?", String.class, job)).isEqualTo("STORAGE_UNAVAILABLE");
        db.update("update cleanup_job set status='PROCESSING',lease_token=?,locked_until=timestamp '2020-01-01' where id=?", UUID.randomUUID(), job);
        fileCleanup.runOnce();
        assertThat(db.queryForObject("select status from cleanup_job where id=?", String.class, job)).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> storage.head(key)).isInstanceOfSatisfying(StorageException.class,
                ex -> assertThat(ex.reason()).isEqualTo(StorageException.Reason.NOT_FOUND));
        fileCleanup.runOnce();
        assertThat(db.queryForObject("select count(*) from cleanup_job where resource_id=?", Integer.class, result.id())).isEqualTo(1);
    }

    @Test
    void latePutAfterDeleteCannotBecomeReadyAndReconciliationKeepsDeletionMarker() throws Exception {
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Owner");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delayed = org.mockito.Mockito.spy(new MinioObjectStorage(imageStorageClient, imageMaintenanceClient, BUCKET));
        var key = new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            key.set(invocation.getArgument(0));
            entered.countDown();
            assertThat(release.await(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(delayed).put(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        var service = new FileService(storedFiles, delayed);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending = executor.submit(() -> service.upload(new org.springframework.mock.web.MockMultipartFile("file","late.png","image/png",PNG),actor));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            UUID id = db.queryForObject("select id from stored_file where object_key=?", UUID.class, key.get());
            UUID job = storedFiles.delete(id,actor);
            fileCleanup.runOnce();
            assertThat(db.queryForObject("select state from stored_file where id=?", String.class, id)).isEqualTo("DELETING");
            release.countDown();
            assertThatThrownBy(() -> pending.get(15,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            assertThat(storage.head(key.get()).size()).isEqualTo(PNG.length);
            assertThatThrownBy(() -> storedFiles.preview(id,actor)).isInstanceOf(ApiException.class);
            db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where id=?",job);
            fileCleanup.runOnce();
            assertThat(db.queryForObject("select status from cleanup_job where id=?",String.class,job)).isEqualTo("COMPLETED");
            assertThat(db.queryForObject("select count(*) from stored_file where id=?",Integer.class,id)).isZero();
            storage.put(key.get(),new ByteArrayInputStream(PNG),PNG.length,"image/png");
            fileCleanup.reconcileOnce();
            fileCleanup.runOnce();
            assertThat(db.queryForObject("select id from cleanup_job where object_key=?",UUID.class,key.get())).isEqualTo(job);
            assertThatThrownBy(() -> storage.head(key.get())).isInstanceOf(StorageException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void uncertainUploadRemainsFencedAndOrphanMultipartIsAborted() throws Exception {
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Owner");
        var failed = org.mockito.Mockito.spy(new MinioObjectStorage(imageStorageClient, imageMaintenanceClient, BUCKET));
        String name = "unknown-" + UUID.randomUUID()+".png";
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new StorageException(StorageException.Reason.UNAVAILABLE);
        }).when(failed).put(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString());
        assertThatThrownBy(() -> new FileService(storedFiles,failed).upload(
                new org.springframework.mock.web.MockMultipartFile("file",name,"image/png",PNG),actor))
                .isInstanceOf(StorageException.class);
        UUID id = db.queryForObject("select id from stored_file where original_name=?",UUID.class,name);
        String key = db.queryForObject("select object_key from stored_file where id=?",String.class,id);
        UUID token = db.queryForObject("select upload_lease_token from stored_file where id=?",UUID.class,id);
        fileCleanup.runOnce();
        assertThat(db.queryForObject("select state from stored_file where id=?",String.class,id)).isEqualTo("DELETING");
        storage.put(key,new ByteArrayInputStream(PNG),PNG.length,"image/png");
        db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where resource_id=?",id);
        fileCleanup.runOnce();
        assertThatThrownBy(()->storage.head(key)).isInstanceOf(StorageException.class);
        storedFiles.acknowledgeUpload(id,token);
        db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where resource_id=?",id);
        fileCleanup.runOnce();
        try (var client = io.minio.MinioAsyncClient.builder().endpoint(s3Endpoint()).credentials(ACCESS,SECRET).build()) {
            var reserved = storedFiles.begin(actor,"multipart.png","image/png",PNG.length);
            String orphan = reserved.key();
            var upload = client.createMultipartUploadAsync(BUCKET,null,orphan,null,null).get();
            String uploadId = upload.result().uploadId();
            client.uploadPartAsync(BUCKET,null,orphan,PNG,PNG.length,uploadId,1,null,null).get();
            assertThat(storage.listMultipart(orphan,null,null,100).uploads()).contains(new ObjectStorage.Multipart(orphan,uploadId));
            storedFiles.delete(reserved.id(),actor);
            fileCleanup.runOnce();
            assertThat(storage.listMultipart(orphan,null,null,100).uploads()).doesNotContain(new ObjectStorage.Multipart(orphan,uploadId));
            storedFiles.acknowledgeUpload(reserved.id(),reserved.uploadToken());
            db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where resource_id=?",reserved.id());
            fileCleanup.runOnce();
        }
    }

    @Test
    void storageDownloadOutageUsesProblem503() throws Exception {
        UUID owner=UUID.randomUUID();
        var response=uploadImage(PNG,"image/png",owner,"TEACHER");
        UUID id=UUID.fromString(json.readTree(response.body()).path("id").asString());
        String key=db.queryForObject("select object_key from stored_file where id=?",String.class,id);
        org.mockito.Mockito.doThrow(new StorageException(StorageException.Reason.UNAVAILABLE)).when(storage).get(key);
        var failed=imageHttp("GET","/"+id+"/content",owner,"TEACHER",null,null);
        assertThat(failed.statusCode()).as(new String(failed.body())).isEqualTo(503);
        assertThat(new String(failed.body())).contains("STORAGE_UNAVAILABLE").doesNotContain(key);
        org.mockito.Mockito.doCallRealMethod().when(storage).get(key);
        imageHttp("DELETE","/"+id,owner,"TEACHER",null,null);
        fileCleanup.runOnce();
    }


    @Test
    void abandonedUploadLeaseAndStaleCleanupTokenCannotPublishOrFinalize() throws Exception {
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Owner");
        var abandoned = storedFiles.begin(actor,"abandoned.png","image/png",PNG.length);
        db.update("update stored_file set upload_locked_until=timestamp '2020-01-01' where id=?",abandoned.id());
        fileCleanup.runOnce();
        assertThat(db.queryForObject("select state from stored_file where id=?",String.class,abandoned.id())).isEqualTo("DELETING");
        storage.put(abandoned.key(),new ByteArrayInputStream(PNG),PNG.length,"image/png");
        assertThatThrownBy(()->storedFiles.ready(abandoned.id(),abandoned.uploadToken(),"0".repeat(64))).isInstanceOf(ApiException.class);
        db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where resource_id=?",abandoned.id());
        fileCleanup.runOnce();
        assertThatThrownBy(()->storage.head(abandoned.key())).isInstanceOf(StorageException.class);
        storedFiles.acknowledgeUpload(abandoned.id(),abandoned.uploadToken());
        db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where resource_id=?",abandoned.id());
        fileCleanup.runOnce();

        var ready = fileService.upload(new org.springframework.mock.web.MockMultipartFile("file","stale.png","image/png",PNG),actor);
        UUID jobId = storedFiles.delete(ready.id(),actor);
        var first = storedFiles.claim();
        assertThat(first.id()).isEqualTo(jobId);
        db.update("update cleanup_job set locked_until=timestamp '2020-01-01' where id=?",jobId);
        var second = storedFiles.claim();
        assertThat(second.id()).isEqualTo(jobId);
        assertThat(second.token()).isNotEqualTo(first.token());
        storedFiles.complete(first);
        assertThat(db.queryForObject("select state from stored_file where id=?",String.class,ready.id())).isEqualTo("DELETING");
        storage.delete(second.key());
        storedFiles.complete(second);
        assertThat(db.queryForObject("select status from cleanup_job where id=?",String.class,jobId)).isEqualTo("COMPLETED");
    }

    @Test
    void reconciliationIsPagedAndPreservesAnActiveUploadAndReadyImage() throws Exception {
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Owner");
        var ready = fileService.upload(new org.springframework.mock.web.MockMultipartFile("file","protected.png","image/png",PNG),actor);
        String readyKey=db.queryForObject("select object_key from stored_file where id=?",String.class,ready.id());
        var orphans=new java.util.HashSet<String>();
        for(int i=0;i<3;i++) {
            String key=ObjectStorage.newKey();
            storage.put(key,new ByteArrayInputStream(PNG),PNG.length,"image/png");
            orphans.add(key);
        }
        var all=new java.util.HashSet<String>();
        String after=null;
        do {
            var page=storage.list(after,2);
            assertThat(page.keys().size()).isLessThanOrEqualTo(2);
            all.addAll(page.keys());
            after=page.nextAfter();
        } while(after!=null);
        assertThat(all).containsAll(orphans).contains(readyKey);
        var uploading=storedFiles.begin(actor,"active.png","image/png",PNG.length);
        try(var client=io.minio.MinioAsyncClient.builder().endpoint(s3Endpoint()).credentials(ACCESS,SECRET).build()) {
            String uploadId=client.createMultipartUploadAsync(BUCKET,null,uploading.key(),null,null).get().result().uploadId();
            client.uploadPartAsync(BUCKET,null,uploading.key(),PNG,PNG.length,uploadId,1,null,null).get();
            fileCleanup.reconcileOnce();
            assertThat(storage.listMultipart(uploading.key(),null,null,100).uploads()).contains(new ObjectStorage.Multipart(uploading.key(),uploadId));
            fileCleanup.runOnce();
            assertThat(storage.head(readyKey).size()).isEqualTo(PNG.length);
            for(String key:orphans) assertThatThrownBy(()->storage.head(key)).isInstanceOf(StorageException.class);
            storedFiles.delete(uploading.id(),actor);
            fileCleanup.runOnce();
            assertThat(storage.listMultipart(uploading.key(),null,null,100).uploads()).doesNotContain(new ObjectStorage.Multipart(uploading.key(),uploadId));
            storedFiles.acknowledgeUpload(uploading.id(),uploading.uploadToken());
            db.update("update cleanup_job set next_attempt_at=timestamp '2020-01-01' where resource_id=?",uploading.id());
        }
        storedFiles.delete(ready.id(),actor);
        fileCleanup.runOnce();
    }


    @Test
    void stage4UpgradePreservesV2CatalogAndCreatesFileConstraints() {
        var old=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("files_upgrade").defaultSchema("files_upgrade").target("2").load();
        old.migrate();
        db.update("insert into files_upgrade.topic(subject_id,name) values(1,'Preserved topic')");
        var stage4=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("files_upgrade").defaultSchema("files_upgrade").target("3").load();
        assertThat(stage4.migrate().migrationsExecuted).isEqualTo(1);
        UUID image = UUID.randomUUID();
        db.update("""
                insert into files_upgrade.stored_file(id,object_key,original_name,mime_type,size_bytes,content_hash,
                    purpose,state,created_at,expires_at,updated_at)
                values(?,?,'preserved.png','image/png',12,repeat('a',64),'TASK_IMAGE','READY',now(),now()+interval '1 day',now())
                """, image, ObjectStorage.newKey());
        var current=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("files_upgrade").defaultSchema("files_upgrade").load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select count(*) from files_upgrade.topic where name='Preserved topic'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select state from files_upgrade.stored_file where id=?", String.class, image)).isEqualTo("READY");
        assertThatThrownBy(()->db.update("""
                insert into stored_file(id,object_key,original_name,mime_type,size_bytes,purpose,state,created_at,expires_at,updated_at)
                values(?,?,'bad.png','image/png',-1,'TASK_IMAGE','READY',now(),now(),now())
                """,UUID.randomUUID(),ObjectStorage.newKey())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }


    @Test
    void temporaryImageExpiresExactlyAt24HoursUsingInjectedClock() {
        var start = java.time.Instant.parse("2026-01-01T12:00:00Z");
        var initial = new StoredFiles(db,transactionManager,java.time.Clock.fixed(start,java.time.ZoneOffset.UTC));
        var actor = new CurrentUser(UUID.randomUUID(), Set.of(tutor.assignmentservice.security.Role.TEACHER), "Owner");
        var image = new FileService(initial,storage).upload(
                new org.springframework.mock.web.MockMultipartFile("file","boundary.png","image/png",PNG),actor);
        assertThat(image.expiresAt()).isEqualTo(start.plus(java.time.Duration.ofHours(24)));
        var before = new StoredFiles(db,transactionManager,java.time.Clock.fixed(image.expiresAt().minusNanos(1),java.time.ZoneOffset.UTC));
        assertThat(before.preview(image.id(),actor).id()).isEqualTo(image.id());
        var expired = new StoredFiles(db,transactionManager,java.time.Clock.fixed(image.expiresAt(),java.time.ZoneOffset.UTC));
        assertThatThrownBy(()->expired.preview(image.id(),actor)).isInstanceOf(ApiException.class);
        new FileCleanup(expired,storage).runOnce();
        assertThat(db.queryForObject("select count(*) from stored_file where id=?",Integer.class,image.id())).isZero();
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
