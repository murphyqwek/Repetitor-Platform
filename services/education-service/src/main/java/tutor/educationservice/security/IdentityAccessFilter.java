package tutor.educationservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tutor.educationservice.common.error.*;
import tutor.educationservice.integration.identity.IdentityClient;

public class IdentityAccessFilter extends OncePerRequestFilter {
    private final IdentityClient identity;
    private final Problems problems;

    public IdentityAccessFilter(IdentityClient identity, Problems problems) {
        this.identity = identity;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getServletPath().startsWith("/api/v1/") && authentication instanceof JwtAuthenticationToken token) {
            try {
                UUID userId = UUID.fromString(token.getToken().getSubject());
                var profile = identity.currentUser(token.getToken().getTokenValue(), userId);
                if (profile.blocked()) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED", "Account access denied");
                var roles = new HashSet<Role>();
                token.getToken().getClaimAsStringList("roles").forEach(value -> roles.add(Role.valueOf(value)));
                roles.retainAll(profile.roles());
                if ((roles.contains(Role.TEACHER) || roles.contains(Role.PARENT)) && !profile.nameRequested()) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "PROFILE_INCOMPLETE", "Complete your Identity profile first");
                }
                var checked = new JwtAuthenticationToken(token.getToken(), roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
                checked.setDetails(new CurrentUser(userId, roles, profile.displayName()));
                SecurityContextHolder.getContext().setAuthentication(checked);
            } catch (ApiException exception) {
                problems.write(response, exception.status(), exception.code(), exception.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
