package tutor.identityservice.dto;

import jakarta.validation.constraints.*;

public record CreateStudentRequestDTO(@NotBlank @Size(max = 255) String firstName,
                                      @NotBlank @Size(max = 255) String surname,
                                      @Size(max = 255) String patronymic,
                                      @NotNull @Min(0) @Max(11) Short grade,
                                      @NotBlank @Size(max = 100) String policyVersion) {}
