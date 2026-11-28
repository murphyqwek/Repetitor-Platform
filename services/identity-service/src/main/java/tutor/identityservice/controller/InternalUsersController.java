package tutor.identityservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.entity.Role;
import tutor.identityservice.repository.AppUserRepository;
import tutor.identityservice.repository.UserRoleRepository;

@RestController
@RequestMapping("/internal/v1/users")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InternalUsersController {
    private final AppUserRepository users;
    private final UserRoleRepository roles;

    public InternalUsersController(AppUserRepository users, UserRoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    public record User(UUID id, String firstName, String surname, String patronymic, Set<Role> roles,
                       boolean blocked, boolean profileCompleted) {}
    public record Request(@NotNull Set<@NotNull UUID> userIds) {}
    public record Result(List<User> users, Set<UUID> missingUserIds) {}

    @GetMapping("/{id}")
    public User get(@PathVariable UUID id) {
        return lookup(Set.of(id)).users().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
    }

    @PostMapping("/batch")
    public Result batch(@Valid @RequestBody Request request) {
        if (request.userIds().size() > 500) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Maximum 500 distinct users");
        return lookup(request.userIds());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ProblemDetail problem(ResponseStatusException exception) {
        var body = exception.getBody();
        body.setProperty("code", exception.getStatusCode().value() == 404 ? "NOT_FOUND" : "USER_LIMIT");
        return body;
    }

    private Result lookup(Set<UUID> ids) {
        if (ids.isEmpty()) return new Result(List.of(), Set.of());
        var byUser = roles.findAllById_UserIdIn(List.copyOf(ids)).stream().collect(Collectors.groupingBy(
                row -> row.getId().getUserId(), Collectors.mapping(row -> row.getId().getRole(), Collectors.toSet())));
        var missing = new LinkedHashSet<>(ids);
        var found = users.findAllById(ids).stream().map(u -> {
            missing.remove(u.getId());
            var current = byUser.getOrDefault(u.getId(), Set.of());
            boolean needsName = current.contains(Role.TEACHER) || current.contains(Role.PARENT);
            return new User(u.getId(), u.getFirstName(), u.getSurname(), u.getPatronymic(), current,
                    u.isBlocked(), !needsName || u.isNameRequested());
        }).toList();
        return new Result(found, missing);
    }
}
