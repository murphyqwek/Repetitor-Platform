package tutor.identityservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tutor.identityservice.dto.*;
import tutor.identityservice.entity.*;
import tutor.identityservice.repository.*;
import tutor.identityservice.service.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdentityServiceApplicationTests {
    @Autowired CreateUserService createUsers;
    @Autowired InvitationCodeService parentCodes;
    @Autowired TeacherRegistrationCodeService teacherCodes;
    @Autowired StudentRegistrationService registration;
    @Autowired ProfileService profiles;
    @Autowired DirectoryService directory;
    @Autowired AppUserRepository users;
    @Autowired StudentProfileRepository studentProfiles;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserService userService;

    @DynamicPropertySource
    static void testSettings(DynamicPropertyRegistry properties) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var folder = Path.of("build", "test-keys");
        Files.createDirectories(folder);
        var publicKey = folder.resolve("public.pem").toAbsolutePath();
        var privateKey = folder.resolve("private.pem").toAbsolutePath();
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        properties.add("identity.security.jwt.public-key-path", publicKey::toString);
        properties.add("identity.security.jwt.private-key-path", privateKey::toString);
        properties.add("identity.security.student-credentials-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        properties.add("spring.datasource.url", () -> System.getenv().getOrDefault("TEST_DATABASE_URL", "jdbc:postgresql://localhost:55439/identity_test"));
        properties.add("spring.datasource.username", () -> "identity_test");
        properties.add("spring.datasource.password", () -> "identity_test");
        properties.add("spring.data.redis.host", () -> "localhost");
        properties.add("spring.data.redis.port", () -> "6379");
        properties.add("spring.data.redis.password", () -> "");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void contextLoads() {}

    @Test
    void blockingAndUnblockingPersistAcrossTransactionsAndControlLogin() {
        var transaction = new TransactionTemplate(transactionManager);
        var user = transaction.execute(status -> account(Role.PARENT));
        for (boolean blocked : new boolean[]{true, true, false, false}) {
            userService.setBlocked(user.getId(), blocked);
            transaction.executeWithoutResult(status -> {
                assertEquals(blocked, users.findById(user.getId()).orElseThrow().isBlocked());
                assertEquals(!blocked, userService.loadUserByUsername(user.getLogin()).isAccountNonLocked());
            });
        }
    }

    @Test
    @Transactional
    void fullChildRegistrationAndCursorSearch() {
        var parent = account(Role.PARENT);
        var body = new CreateStudentRequestDTO("Иван", "Тестов", null, (short) 5, "v1");
        var request = registration.create(parent.getId(), "retry-key", body);
        assertEquals(request.id(), registration.create(parent.getId(), "retry-key", body).id());
        var approved = registration.decide(request.id(), true);
        var child = profiles.profile(approved.studentId());
        assertEquals((short) 5, child.grade());
        assertTrue(child.roles().contains(Role.STUDENT));
        assertEquals(1, profiles.children(parent.getId()).size());
        var credentials = registration.credentials(parent.getId(), child.id());
        assertTrue(credentials.login().matches("st-[A-Z0-9]{8}"));
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(credentials.password(), users.findById(child.id()).orElseThrow().getPasswordHash()));
        assertNotEquals(credentials.password(), studentProfiles.findById(child.id()).orElseThrow().getPassword());
        var another = registration.create(parent.getId(), "another-key", body);
        registration.decide(another.id(), true);
        var page = directory.search(Role.STUDENT, "Тестов", 1, null);
        assertTrue(page.hasNext());
        assertEquals(1, page.content().get(0).parents().size());
        var next = directory.search(Role.STUDENT, "Тестов", 1, page.nextCursor());
        assertFalse(next.hasNext());
        assertNotEquals(page.content().get(0).id(), next.content().get(0).id());
    }

    @Test
    @Transactional
    void registrationConsumesParentAndTeacherInvitationsOnce() {
        var teacher = account(Role.TEACHER);
        String parentCode = parentCodes.createInvitationCode(teacher.getId());
        String login = "p-" + UUID.randomUUID().toString().substring(0, 12);
        createUsers.registerParent(new RegistrateDTO(login, "password123", parentCode, "v1"));
        assertTrue(users.findByLogin(login).isPresent());
        assertFalse(parentCodes.consumeInvitationCode(parentCode));
        String teacherCode = teacherCodes.createTeacherRegistrationCode();
        teacherCodes.checkInvitationCode(teacherCode);
        createUsers.registerTeacher(new RegistrateDTO("t-" + login, "password123", teacherCode, "v1"));
        assertFalse(teacherCodes.consumeInvitationCode(teacherCode));
    }

    @Test
    void concurrentApprovalCreatesOneChild() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        Long requestId = transaction.execute(status -> {
            var parent = account(Role.PARENT);
            return registration.create(parent.getId(), "concurrent", new CreateStudentRequestDTO(
                    "Пётр", "Конкурентный", null, (short) 6, "v1")).id();
        });
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> approve = () -> {
            start.await();
            try {
                registration.decide(requestId, true);
                return true;
            } catch (org.springframework.web.server.ResponseStatusException exception) {
                assertEquals(409, exception.getStatusCode().value());
                return false;
            }
        };
        try {
            var first = executor.submit(approve);
            var second = executor.submit(approve);
            start.countDown();
            assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void takenLoginDoesNotConsumeInvitation() {
        var transaction = new TransactionTemplate(transactionManager);
        var existing = transaction.execute(status -> account(Role.PARENT));
        String code = transaction.execute(status -> parentCodes.createInvitationCode(account(Role.TEACHER).getId()));
        assertThrows(tutor.identityservice.exception.RegistrationLoginTakenException.class, () ->
                createUsers.registerParent(new RegistrateDTO(existing.getLogin(), "password123", code, "v1")));
        assertEquals(Boolean.TRUE, transaction.execute(status -> parentCodes.consumeInvitationCode(code)));
    }

    @Test
    void expiredInvitationIsExcludedAndCannotBeConsumed() {
        var transaction = new TransactionTemplate(transactionManager);
        String code = transaction.execute(status -> parentCodes.createInvitationCode(account(Role.TEACHER).getId()));
        transaction.executeWithoutResult(status -> {
            var invitation = invitationRepository.findByCodeHash(codeHasher.hash(code)).orElseThrow();
            invitation.setExpiredAt(java.time.Instant.now().minusSeconds(1));
        });
        assertEquals(Boolean.FALSE, transaction.execute(status -> parentCodes.consumeInvitationCode(code)));
    }

    @Autowired InvitationCodeRepository invitationRepository;
    @Autowired CodeHasher codeHasher;

    AppUser account(Role role) {
        String login = "u-" + UUID.randomUUID().toString().substring(0, 12);
        if (role == Role.TEACHER) {
            createUsers.registerTeacher(new RegistrateDTO(login, "password123", teacherCodes.createTeacherRegistrationCode(), "v1"));
        } else {
            var teacher = account(Role.TEACHER);
            createUsers.registerParent(new RegistrateDTO(login, "password123", parentCodes.createInvitationCode(teacher.getId()), "v1"));
        }
        var user = users.findByLogin(login).orElseThrow();
        profiles.update(user.getId(), new ProfileUpdateDTO("Имя", "Фамилия", null, null));
        return user;
    }
}
