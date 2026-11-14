package tutor.identityservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tutor.identityservice.entity.InvitationCode;

import java.util.Optional;
import java.time.Instant;

public interface InvitationCodeRepository extends JpaRepository<InvitationCode, Long> {
    boolean existsByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InvitationCode c where c.id = :id")
    Optional<InvitationCode> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Query("delete from InvitationCode c where c.expiredAt <= :now")
    int deleteExpiredCodes(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InvitationCode> findByCodeHash(String codeHash);
}
