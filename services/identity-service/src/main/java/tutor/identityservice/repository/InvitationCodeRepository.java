package tutor.identityservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tutor.identityservice.entity.InvitationCode;

import java.util.Optional;

public interface InvitationCodeRepository extends JpaRepository<InvitationCode, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InvitationCode> findByCodeHash(String codeHash);
}
