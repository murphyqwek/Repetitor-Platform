package tutor.identityservice.dto;

import java.time.Instant;

public record InvitationDTO(Long id, Instant createdAt, Instant expiresAt) {}
