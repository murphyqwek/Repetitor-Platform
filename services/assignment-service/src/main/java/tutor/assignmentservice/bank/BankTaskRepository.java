package tutor.assignmentservice.bank;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface BankTaskRepository extends JpaRepository<BankTask, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BankTask t where t.id=:id")
    Optional<BankTask> lock(UUID id);

    @Query(value = "select * from task where id=:id for update skip locked", nativeQuery = true)
    Optional<BankTask> tryLock(UUID id);
}
