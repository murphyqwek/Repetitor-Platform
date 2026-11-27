package tutor.identityservice.dto;

import tutor.identityservice.entity.Role;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ProfileDTO(UUID id, String login, String firstName, String surname, String patronymic,
                         Set<Role> roles, boolean blocked, Instant createdAt, boolean nameRequested, Short grade) {}
