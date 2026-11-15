package tutor.identityservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import tutor.identityservice.dto.LoginDTO;
import tutor.identityservice.dto.RegistrateDTO;
import tutor.identityservice.dto.TeacherRegistrationDTO;
import tutor.identityservice.service.AuthService;
import tutor.identityservice.service.CookieService;
import tutor.identityservice.service.CreateUserService;
import tutor.identityservice.service.TeacherRegistrationCodeService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final CreateUserService createUserService;
    private final TeacherRegistrationCodeService teacherCodes;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final CookieService cookieService;

    public AuthController(
            AuthService authService,
            CreateUserService createUserService,
            TeacherRegistrationCodeService teacherCodes,
            CookieCsrfTokenRepository csrfTokenRepository,
            CookieService cookieService) {
        this.authService = authService;
        this.createUserService = createUserService;
        this.teacherCodes = teacherCodes;
        this.csrfTokenRepository = csrfTokenRepository;
        this.cookieService = cookieService;
    }

    @PostMapping("/register/parent")
    public ResponseEntity<Void> registerParent(@Valid @RequestBody RegistrateDTO body,
                                               HttpServletRequest request, HttpServletResponse response) {
        createUserService.registerParent(body);
        return registrationTokens(body.login(), body.password(), request, response);
    }

    @GetMapping("/register/teacher/{code}")
    public ResponseEntity<Void> checkTeacherCode(@PathVariable String code) {
        teacherCodes.checkInvitationCode(code);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/register/teacher/{code}")
    public ResponseEntity<Void> registerTeacher(@PathVariable String code,
            @Valid @RequestBody TeacherRegistrationDTO body,
            HttpServletRequest request, HttpServletResponse response) {
        createUserService.registerTeacher(new RegistrateDTO(
                body.login(), body.password(), code, body.policyVersion()));
        return registrationTokens(body.login(), body.password(), request, response);
    }

    private ResponseEntity<Void> registrationTokens(String login, String password,
            HttpServletRequest request, HttpServletResponse response) {
        cookieService.addTokensToResponse(authService.login(new LoginDTO(login, password)), response);
        csrfTokenRepository.saveToken(null, request, response);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(
            @Valid @RequestBody LoginDTO credentials,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        var tokens = authService.login(credentials);

        cookieService.addTokensToResponse(tokens, response);

        csrfTokenRepository.saveToken(null, request, response);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@CookieValue(name = "refresh_token", required = false) String refreshToken,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        if(refreshToken == null || refreshToken.isBlank()) {
            throw new BadCredentialsException("Refresh-токен отсутствует");
        }

        var tokens = authService.refreshToken(refreshToken);

        cookieService.addTokensToResponse(tokens, response);

        csrfTokenRepository.saveToken(null, request, response);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "refresh_token", required = false) String refreshToken,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        authService.logout(refreshToken);

        cookieService.clearTokens(response);

        csrfTokenRepository.saveToken(null, request, response);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
