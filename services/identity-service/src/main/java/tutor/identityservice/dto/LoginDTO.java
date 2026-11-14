package tutor.identityservice.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginDTO(
        @NotBlank(message = "Логин обязателен") String login,
        @NotBlank(message = "Пароль обязателен") String password
) {
}
