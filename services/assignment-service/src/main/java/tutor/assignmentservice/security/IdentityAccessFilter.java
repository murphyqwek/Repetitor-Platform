package tutor.assignmentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tutor.assignmentservice.common.error.*;

public class IdentityAccessFilter extends OncePerRequestFilter {
    private final IdentityAuthentication identity;
    private final Problems problems;

    public IdentityAccessFilter(IdentityAuthentication identity, Problems problems) {
        this.identity = identity;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (request.getServletPath().startsWith("/api/v1/") && authentication instanceof JwtAuthenticationToken token) {
            try {
                SecurityContextHolder.getContext().setAuthentication(identity.current(token.getToken()));
            } catch (ApiException exception) {
                problems.write(response, exception.status(), exception.code(), exception.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
