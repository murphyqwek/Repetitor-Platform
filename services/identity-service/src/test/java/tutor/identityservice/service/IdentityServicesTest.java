package tutor.identityservice.service;

import org.junit.jupiter.api.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.dto.*;
import tutor.identityservice.entity.*;
import tutor.identityservice.repository.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdentityServicesTest {
    final AppUserRepository users = mock(AppUserRepository.class);
    final UserRoleRepository roles = mock(UserRoleRepository.class);
    final StudentProfileRepository profiles = mock(StudentProfileRepository.class);
    final ParentStudentRepository links = mock(ParentStudentRepository.class);
    final StudentRegRequestRepository requests = mock(StudentRegRequestRepository.class);
    final ProfileService profileService = new ProfileService(users, roles, profiles, links);
    final StudentCredentialCipher cipher = new StudentCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    final CreateUserService createUsers = mock(CreateUserService.class);
    final StudentRegistrationService registration = new StudentRegistrationService(requests, users, profiles, links,
            createUsers, new RandomGenerationService(), cipher, profileService);
    AppUser parent;

    @BeforeEach
    void setUp() {
        parent = user("parent");
        when(users.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(users.findForUpdate(parent.getId())).thenReturn(Optional.of(parent));
    }

    @Test
    void credentialsAreRandomlyEncryptedAndBoundToStudent() {
        UUID id = UUID.randomUUID();
        String first = cipher.encrypt(id, "password123");
        assertNotEquals(first, cipher.encrypt(id, "password123"));
        assertEquals("password123", cipher.decrypt(id, first));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(UUID.randomUUID(), first));
        byte[] modified = Base64.getDecoder().decode(first);
        modified[modified.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(id, Base64.getEncoder().encodeToString(modified)));
    }

    @Test
    void credentialsRequireParentLinkBeforeLoadingPassword() {
        assertThrows(AccessDeniedException.class, () -> registration.credentials(parent.getId(), UUID.randomUUID()));
        verifyNoInteractions(profiles);
    }

    @Test
    void linkedParentGetsDecryptedPassword() {
        var child = user("st-ABCDEFGH");
        when(links.existsById_ParentIdAndId_StudentId(parent.getId(), child.getId())).thenReturn(true);
        when(profiles.findById(child.getId())).thenReturn(Optional.of(new StudentProfile(child,
                cipher.encrypt(child.getId(), "secret123"), (short) 5)));
        assertEquals("secret123", registration.credentials(parent.getId(), child.getId()).password());
    }

    @Test
    void approvalCreatesProfileLinkAndRejectsSecondDecision() {
        var request = request();
        when(requests.findForUpdate(1L)).thenReturn(Optional.of(request));
        when(users.findByLogin(anyString())).thenReturn(Optional.empty());
        when(createUsers.saveUser(anyString(), anyString(), eq(Role.STUDENT), eq(true), eq("v1")))
                .thenAnswer(invocation -> user(invocation.getArgument(0)));
        var result = registration.decide(1L, true);
        assertEquals(StudentRegistrationStatus.APPROVED, result.status());
        assertNotNull(result.studentId());
        var saved = org.mockito.ArgumentCaptor.forClass(StudentProfile.class);
        verify(profiles).save(saved.capture());
        assertEquals((short) 5, saved.getValue().getSchoolGrade());
        assertEquals("Иван", saved.getValue().getUser().getFirstName());
        assertFalse(cipher.decrypt(result.studentId(), saved.getValue().getPassword()).isBlank());
        verify(links).save(any(ParentStudent.class));
        assertThrows(ResponseStatusException.class, () -> registration.decide(1L, true));
        verify(createUsers, times(1)).saveUser(anyString(), anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void rejectionCreatesNoAccountAndCannotBeApprovedLater() {
        when(requests.findForUpdate(1L)).thenReturn(Optional.of(request()));
        assertEquals(StudentRegistrationStatus.REJECTED, registration.decide(1L, false).status());
        assertThrows(ResponseStatusException.class, () -> registration.decide(1L, true));
        verifyNoInteractions(createUsers, profiles, links);
    }

    @Test
    void repeatedSubmissionReturnsExistingRequestAndRejectsChangedPayload() {
        when(requests.findByParent_IdAndIdempotencyKey(parent.getId(), "key")).thenReturn(Optional.of(request()));
        var body = new CreateStudentRequestDTO("Иван", "Иванов", null, (short) 5, "v1");
        assertEquals("Иван", registration.create(parent.getId(), "key", body).firstName());
        assertThrows(ResponseStatusException.class, () -> registration.create(parent.getId(), "key",
                new CreateStudentRequestDTO("Пётр", "Иванов", null, (short) 5, "v1")));
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void loginCollisionIsRetriedWithBoundedAttempts() {
        when(requests.findForUpdate(1L)).thenReturn(Optional.of(request()));
        when(users.findByLogin(anyString())).thenReturn(Optional.of(parent));
        assertThrows(IllegalStateException.class, () -> registration.decide(1L, true));
        verify(users, times(10)).findByLogin(matches("st-[A-Z0-9]{8}"));
        verifyNoInteractions(createUsers);
    }

    @Test
    void blockedParentsRequestCannotBeApproved() {
        parent.setBlocked(true);
        when(requests.findForUpdate(1L)).thenReturn(Optional.of(request()));
        assertThrows(ResponseStatusException.class, () -> registration.decide(1L, true));
        verifyNoInteractions(createUsers);
    }

    @Test
    void profileCompletesNameAndRejectsGradeForParent() {
        when(roles.findAllById_UserId(parent.getId())).thenReturn(List.of(new UserRole(parent, Role.PARENT)));
        when(profiles.findById(parent.getId())).thenReturn(Optional.empty());
        var result = profileService.update(parent.getId(), new ProfileUpdateDTO(" Иван ", " Иванов ", "", null));
        assertTrue(result.nameRequested());
        assertEquals("Иван", result.firstName());
        assertThrows(ResponseStatusException.class, () -> profileService.update(parent.getId(),
                new ProfileUpdateDTO(null, null, null, (short) 3)));
    }

    @Test
    void registrationPersistsUserBeforeConstructingRole() {
        var codes = mock(InvitationCodeService.class);
        when(codes.consumeInvitationCode("CODE")).thenReturn(true);
        when(users.saveAndFlush(any())).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        var service = new CreateUserService(users, new BCryptPasswordEncoder(), roles, codes, mock(TeacherRegistrationCodeService.class));
        service.registerParent(new RegistrateDTO("parent1", "password123", "CODE", "v1"));
        var saved = org.mockito.ArgumentCaptor.forClass(UserRole.class);
        verify(roles).saveAndFlush(saved.capture());
        assertNotNull(saved.getValue().getId().getUserId());
        assertEquals(Role.PARENT, saved.getValue().getId().getRole());
        assertEquals("v1", saved.getValue().getUser().getPolicyVersion());
    }

    @Test
    void invitationListsApplyOwnershipAndOnlyReturnMetadata() {
        var codes = mock(InvitationCodeRepository.class);
        var service = new InvitationQueryService(codes, mock(TeacherRegistrationCodeRepository.class));
        var code = new InvitationCode("hash", Instant.now(), Instant.now().plusSeconds(60), parent.getId());
        when(codes.findByTeacherIdAndExpiredAtAfter(eq(parent.getId()), any(), any())).thenReturn(new PageImpl<>(List.of(code)));
        assertEquals(1, service.parents(parent.getId(), false, 0, 10).content().size());
        verify(codes, never()).findByExpiredAtAfter(any(), any());
        when(codes.findByExpiredAtAfter(any(), any())).thenReturn(Page.empty());
        assertTrue(service.parents(parent.getId(), true, 0, 10).content().isEmpty());
    }

    StudentRegRequest request() {
        var request = new StudentRegRequest(parent, "Иван", "Иванов", null);
        request.setSchoolGrade((short) 5);
        request.setPolicyVersion("v1");
        return request;
    }

    static AppUser user(String login) {
        var user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setLogin(login);
        return user;
    }
}
