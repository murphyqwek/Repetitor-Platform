package tutor.assignmentservice.bank;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "task_version")
class BankTaskVersion {
    @Id UUID id;
    UUID taskId;
    int version;
    String statement;
    String explanation;
    @Enumerated(EnumType.STRING) AnswerType answerType;
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    String answerConfig;
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    String correctAnswer;
    int maxScore;
    String contentHash;
    UUID createdBy;
    Instant createdAt;
    String publicationState = "ACTIVE";
    String publicationErrorCode;

    protected BankTaskVersion() {}
}
