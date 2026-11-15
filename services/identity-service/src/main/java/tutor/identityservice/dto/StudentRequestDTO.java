package tutor.identityservice.dto;

import tutor.identityservice.entity.StudentRegistrationStatus;
import java.util.UUID;

public record StudentRequestDTO(Long id, UUID parentId, String firstName, String surname, String patronymic,
                                Short grade, StudentRegistrationStatus status, UUID studentId) {}
