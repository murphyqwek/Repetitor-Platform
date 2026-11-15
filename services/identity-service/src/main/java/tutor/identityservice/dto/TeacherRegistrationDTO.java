package tutor.identityservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeacherRegistrationDTO(@NotBlank @Size(min = 5, max = 25) String login,
                                     @NotBlank @Size(min = 8, max = 25) String password,
                                     @NotBlank @Size(max = 100) String policyVersion) {}
