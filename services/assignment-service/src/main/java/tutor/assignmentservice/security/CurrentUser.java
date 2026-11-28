package tutor.assignmentservice.security;

import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

public record CurrentUser(UUID id, Set<Role> roles, String displayName) {
    public CurrentUser { roles = Set.copyOf(roles); }

    public static CurrentUser get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getDetails() instanceof CurrentUser user)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return user;
    }
}
