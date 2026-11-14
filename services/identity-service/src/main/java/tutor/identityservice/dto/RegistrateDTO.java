package tutor.identityservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrateDTO(
        @NotBlank(message = "Логин обязателен")
        @Size(min = 5, max = 25, message = "Логин должен содержать от 5 до 25 символов")
        String login,

        @NotBlank(message = "Пароль обязателен")
        @Size(min = 8, max = 25, message = "Пароль должен содержать от 8 до 25 символов")
        String password,

        @NotBlank(message = "Код приглашения обязателен")
        String invitationCode
) {
}
