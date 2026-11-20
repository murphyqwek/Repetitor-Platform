package tutor.identityservice.dto;

import jakarta.validation.constraints.NotNull;

public record UserStatusUpdateDTO(@NotNull Boolean blocked) {}
