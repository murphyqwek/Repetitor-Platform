package tutor.assignmentservice.bank;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "task")
class BankTask {
    @Id UUID id;
    UUID teacherId;
    short subjectId;
    Integer sourceId;
    String externalKey;
    boolean manualOverride;
    UUID currentVersionId;
    UUID copiedFromTaskId;
    Instant createdAt;
    Instant deletedAt;

    protected BankTask() {}

    BankTask(UUID owner, int subject, UUID copiedFrom, Instant now) {
        id = UUID.randomUUID();
        teacherId = owner;
        subjectId = (short) subject;
        copiedFromTaskId = copiedFrom;
        createdAt = now;
    }

    boolean imported() { return sourceId != null; }
    boolean deleted() { return deletedAt != null; }
}
