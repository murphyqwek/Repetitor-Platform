package tutor.identityservice.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import tutor.identityservice.entity.AppUser;
import tutor.identityservice.entity.Role;

import java.util.Set;
import java.util.UUID;

public class IdentityUserDetails extends User {

    private final UUID userId;
    private final Set<Role> roles;

    public IdentityUserDetails(AppUser user, Set<Role> roles) {
        super(user.getLogin(), user.getPasswordHash(), true, true, true,
                !user.isBlocked(), roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                        .toList());
        this.userId = user.getId();
        this.roles = Set.copyOf(roles);
    }

    public UUID getUserId() {
        return userId;
    }

    public Set<Role> getRoles() {
        return roles;
    }
}
