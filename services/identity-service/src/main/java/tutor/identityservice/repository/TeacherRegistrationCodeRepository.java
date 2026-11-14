package tutor.identityservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import tutor.identityservice.entity.TeacherRegistrationCode;

import java.util.Optional;

public interface TeacherRegistrationCodeRepository extends JpaRepository<TeacherRegistrationCode, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TeacherRegistrationCode> findByCodeHash(String codeHash);
}
