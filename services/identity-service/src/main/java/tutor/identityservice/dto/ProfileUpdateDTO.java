package tutor.identityservice.dto;

import jakarta.validation.constraints.*;

public record ProfileUpdateDTO(@Size(max = 255) @Pattern(regexp = ".*\\S.*") String firstName,
                               @Size(max = 255) @Pattern(regexp = ".*\\S.*") String surname,
                               @Size(max = 255) String patronymic,
                               @Min(0) @Max(11) Short grade) {}
