package tutor.assignmentservice.integration.identity;

import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.integration.ProviderHttp;
import tutor.assignmentservice.security.Role;

@Component
public class IdentityClient {
    private final ProviderHttp http;
    public IdentityClient(@Value("${assignment.identity.url}") URI url, @Value("${assignment.identity.key}") String key, Clock clock) {
        http = new ProviderHttp(url, "identity-service", "users:read", key, "IDENTITY_UNAVAILABLE", clock);
    }

    public record User(UUID id, String firstName, String surname, String patronymic, Set<Role> roles, boolean blocked, boolean profileCompleted) {
        public User {
            Objects.requireNonNull(id);
            roles = Set.copyOf(roles);
        }
        public String displayName() {
            return Stream.of(surname, firstName, patronymic).filter(Objects::nonNull).filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
        }
    }
    public record Batch(List<User> users, List<UUID> missingUserIds) {
        public Batch { users = List.copyOf(users); missingUserIds = List.copyOf(missingUserIds); }
    }

    public User user(UUID id) {
        var user = http.request("/internal/v1/users/" + id, null, User.class);
        if (!user.id().equals(id)) throw http.unavailable();
        return user;
    }

    public Batch batch(Collection<UUID> requested) {
        var ids = Set.copyOf(requested);
        if (ids.size() > 500) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "USER_LIMIT", "Maximum 500 distinct users");
        var response = http.request("/internal/v1/users/batch", Map.of("userIds", ids), Batch.class);
        var covered = new HashSet<UUID>();
        for (User user : response.users()) if (!covered.add(user.id())) throw http.unavailable();
        for (UUID id : response.missingUserIds()) if (!covered.add(id)) throw http.unavailable();
        if (!covered.equals(ids)) throw http.unavailable();
        return response;
    }
}
