package tutor.identityservice.controller;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tutor.identityservice.config.*;
import tutor.identityservice.entity.AppUser;
import tutor.identityservice.service.*;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthController.class, ProfileController.class, StudentRequestController.class, DirectoryController.class, InvitationController.class})
@Import({SecurityConfig.class, ProfileAccessConfig.class})
class IdentityApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean ProfileService profiles;
    @MockitoBean StudentRegistrationService students;
    @MockitoBean DirectoryService directory;
    @MockitoBean InvitationQueryService invitations;
    @MockitoBean InvitationIdempotencyService idempotency;
    @MockitoBean InvitationCodeService parentCodes;
    @MockitoBean TeacherRegistrationCodeService teacherCodes;
    @MockitoBean UserDetailsService userDetails;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean CreateUserService createUsers;
    @MockitoBean AuthService auth;
    @MockitoBean CookieService cookies;
    final UUID actor = UUID.randomUUID();
    AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setId(actor);
        user.setNameRequested(true);
        when(profiles.user(actor)).thenReturn(user);
    }

    @Test
    void anonymousCannotReadProfilesOrSearch() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/directory/users?type=STUDENT&fio=Иван")).andExpect(status().isUnauthorized());
    }

    @Test
    void parentCannotReviewRequestsOrSearchDirectory() throws Exception {
        mvc.perform(get("/api/v1/student-requests").with(actor("PARENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/directory/users?type=STUDENT&fio=Иван").with(actor("PARENT")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(students, directory);
    }

    @Test
    void teacherCannotReadChildCredentials() throws Exception {
        mvc.perform(get("/api/v1/students/" + UUID.randomUUID() + "/credentials").with(actor("TEACHER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(students);
    }

    @Test
    void unsafeRequestsRequireCsrfEvenWithCookieAuthentication() throws Exception {
        var token = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test-token")
                .header("alg", "RS256").subject(actor.toString()).claim("roles", java.util.List.of("TEACHER")).build();
        when(jwtDecoder.decode("test-token")).thenReturn(token);
        var cookie = new jakarta.servlet.http.Cookie("access_token", "test-token");
        mvc.perform(post("/api/v1/student-requests/1/approve").cookie(cookie))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/student-requests/1/approve").cookie(cookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(students).decide(1L, true);
    }

    @Test
    void paginationAndGradeHaveBounds() throws Exception {
        mvc.perform(get("/api/v1/invitations/parents?size=101").with(actor("TEACHER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/student-requests/my?page=-1").with(actor("PARENT")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/users/me").with(actor("PARENT")).with(csrf())
                .contentType("application/json").content("{\"firstName\":\"  \",\"grade\":12}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incompleteNameAllowsOwnProfileButBlocksPlatformOperations() throws Exception {
        user.setNameRequested(false);
        mvc.perform(get("/api/v1/users/me").with(actor("PARENT"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/student-requests/my").with(actor("PARENT"))).andExpect(status().isForbidden());
    }

    @Test
    void blockedAccountCannotReadOwnProfileWithExistingJwt() throws Exception {
        user.setBlocked(true);
        mvc.perform(get("/api/v1/users/me").with(actor("PARENT"))).andExpect(status().isForbidden());
    }

    @Test
    void parentIdentityComesFromJwtAndCredentialsAreNotCached() throws Exception {
        UUID child = UUID.randomUUID();
        when(students.credentials(actor, child)).thenReturn(new StudentRegistrationService.Credentials(child, "st-ABCDEFGH", "secret"));
        mvc.perform(get("/api/v1/students/" + child + "/credentials").with(actor("PARENT")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.login").value("st-ABCDEFGH"));
        verify(students).credentials(actor, child);
    }

    @Test
    void parentRegistrationIsPublicAndIssuesTokensAfterCreation() throws Exception {
        var tokens = new tutor.identityservice.dto.TokensDTO("access", "refresh");
        var body = new tutor.identityservice.dto.RegistrateDTO("parent1", "password123", "CODE", "v1");
        when(auth.login(new tutor.identityservice.dto.LoginDTO("parent1", "password123"))).thenReturn(tokens);
        mvc.perform(post("/api/v1/auth/register/parent").with(csrf()).contentType("application/json")
                .content("{\"login\":\"parent1\",\"password\":\"password123\",\"invitationCode\":\"CODE\",\"policyVersion\":\"v1\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"));
        var order = inOrder(createUsers, auth, cookies);
        order.verify(createUsers).registerParent(body);
        order.verify(auth).login(any());
        order.verify(cookies).addTokensToResponse(eq(tokens), any());
    }

    @Test
    void teacherRegistrationTakesInvitationFromPathAndRequiresConsent() throws Exception {
        mvc.perform(get("/api/v1/auth/register/teacher/CODE")).andExpect(status().isOk());
        verify(teacherCodes).checkInvitationCode("CODE");
        mvc.perform(post("/api/v1/auth/register/teacher/CODE").with(csrf()).contentType("application/json")
                .content("{\"login\":\"teacher1\",\"password\":\"password123\",\"policyVersion\":\"v1\"}"))
                .andExpect(status().isCreated());
        verify(createUsers).registerTeacher(new tutor.identityservice.dto.RegistrateDTO("teacher1", "password123", "CODE", "v1"));
        mvc.perform(post("/api/v1/auth/register/parent").with(csrf()).contentType("application/json")
                .content("{\"login\":\"parent1\",\"password\":\"password123\",\"invitationCode\":\"CODE\"}"))
                .andExpect(status().isBadRequest());
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor actor(String role) {
        return jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
