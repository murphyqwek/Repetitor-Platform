package tutor.identityservice.config;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import tutor.identityservice.service.ProfileService;

@Configuration
@RequiredArgsConstructor
public class ProfileAccessConfig implements WebMvcConfigurer {
    private final ProfileService profiles;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication instanceof JwtAuthenticationToken token) {
                    var user = profiles.user(UUID.fromString(token.getToken().getSubject()));
                    if (user.isBlocked()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Пользователь заблокирован");
                    boolean needsName = authentication.getAuthorities().stream().anyMatch(a ->
                            a.getAuthority().equals("ROLE_PARENT") || a.getAuthority().equals("ROLE_TEACHER"));
                    boolean ownProfile = request.getRequestURI().equals(request.getContextPath() + "/api/v1/users/me");
                    if (!ownProfile && needsName && !user.isNameRequested()) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Сначала заполните ФИО в профиле");
                    }
                }
                return true;
            }
        }).addPathPatterns("/api/v1/**").excludePathPatterns("/api/v1/auth/**");
    }
}
