package tutor.identityservice.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Service;
import tutor.identityservice.dto.TokensDTO;

import java.time.Duration;

@Service
public class CookieService {
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final boolean secureCookies;

    public CookieService(@Value("${identity.security.jwt.ttl}") Duration accessTokenTtl,
                         @Value("${identity.security.refreshTtl}") Duration refreshTokenTtl,
                         @Value("${identity.security.cookies.secure:true}") boolean secureCookies
    ) {
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
        this.secureCookies = secureCookies;
    }

    public void addTokensToResponse(TokensDTO tokens, HttpServletResponse response) {
        var accessTokenCookie = ResponseCookie.from("access_token", tokens.accessToken())
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/")
                .maxAge(accessTokenTtl)
                .build();

        var refreshTokenCookie = ResponseCookie.from("refresh_token", tokens.refreshToken())
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path("/api/v1/auth/")
                .maxAge(refreshTokenTtl)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString());
    }

    public void clearTokens(HttpServletResponse response) {
        clearCookie("access_token", "/", response);
        clearCookie("refresh_token", "/api/v1/auth/", response);
    }

    private void clearCookie(
            String name,
            String path,
            HttpServletResponse response
    ) {
        var cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ZERO)
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
