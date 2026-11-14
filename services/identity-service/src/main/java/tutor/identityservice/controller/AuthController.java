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
import tutor.identityservice.service.AuthService;
import tutor.identityservice.service.CookieService;
import tutor.identityservice.service.RefreshTokenService;
import tutor.identityservice.service.UserService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final CookieService cookieService;

    public AuthController(
            AuthService authService,
            CookieCsrfTokenRepository csrfTokenRepository,
            CookieService cookieService) {
        this.authService = authService;
        this.csrfTokenRepository = csrfTokenRepository;
        this.cookieService = cookieService;
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
