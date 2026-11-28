package tutor.assignmentservice.bank;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BankTaskVersionRepository extends JpaRepository<BankTaskVersion, UUID> {
    Optional<BankTaskVersion> findByTaskIdAndId(UUID taskId, UUID id);
    Optional<BankTaskVersion> findByTaskIdAndIdAndPublicationState(UUID taskId, UUID id, String state);
    boolean existsByTaskIdAndPublicationStateNot(UUID taskId, String state);

    @Modifying
    @Query("delete from BankTaskVersion v where v.taskId=:taskId")
    void deleteVersions(UUID taskId);
}
