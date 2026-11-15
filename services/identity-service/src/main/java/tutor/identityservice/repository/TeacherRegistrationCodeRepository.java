package tutor.identityservice.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tutor.identityservice.entity.TeacherRegistrationCode;

public interface TeacherRegistrationCodeRepository extends JpaRepository<TeacherRegistrationCode, Long> {
    Page<TeacherRegistrationCode> findByExpiredAtAfter(
            Instant now, Pageable pageable);

    boolean existsByCodeHash(String codeHash);
    boolean existsByCodeHashAndExpiredAtAfter(String codeHash, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TeacherRegistrationCode c where c.id = :id")
    Optional<TeacherRegistrationCode> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("delete from TeacherRegistrationCode c where c.expiredAt <= :now")
    int deleteExpiredCodes(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TeacherRegistrationCode> findByCodeHash(String codeHash);
}
