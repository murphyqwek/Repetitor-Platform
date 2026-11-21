package tutor.educationservice.integration.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import tutor.educationservice.security.Role;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityUser(UUID id, String firstName, String surname, String patronymic, Set<Role> roles, Boolean blocked) {
    public String displayName() {
        return Stream.of(surname, firstName, patronymic).filter(name -> name != null && !name.isBlank())
                .map(String::strip).collect(Collectors.joining(" "));
    }
}
