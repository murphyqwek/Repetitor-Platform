package tutor.assignmentservice.security;

import java.util.HashSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.integration.identity.IdentityClient;

@Component
public class IdentityAuthentication {
    private final IdentityClient identity;

    public IdentityAuthentication(IdentityClient identity) { this.identity = identity; }

    public JwtAuthenticationToken current(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        var profile = identity.user(userId);
        if (profile.blocked()) throw new ApiException(HttpStatus.FORBIDDEN, "USER_BLOCKED", "Account access denied");
        var roles = new HashSet<Role>();
        jwt.getClaimAsStringList("roles").forEach(role -> roles.add(Role.valueOf(role)));
        roles.retainAll(profile.roles());
        if (!profile.profileCompleted()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROFILE_INCOMPLETE", "Complete your Identity profile first");
        }
        var checked = new JwtAuthenticationToken(jwt, roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
        checked.setDetails(new CurrentUser(userId, roles, profile.displayName()));
        return checked;
    }
}
