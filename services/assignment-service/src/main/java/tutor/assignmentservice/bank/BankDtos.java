package tutor.assignmentservice.bank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tutor.assignmentservice.exam.CatalogDtos.CheckMode;
import tutor.assignmentservice.exam.CatalogDtos.TopicLabel;

public final class BankDtos {
    private BankDtos() {}

    public enum Scope { OWNED, IMPORTED, AVAILABLE, ALL }

    public record Filter(Scope scope, UUID teacherId, @Positive Integer subjectId, UUID examDefinitionId,
                         UUID examStructureVersionId, @Positive Integer position,
                         @Size(max = 20) Set<@NotNull @Positive Integer> topicIds, CheckMode checkMode,
                         AnswerType answerType, @Size(max = 1000) String search) {}

    public record Write(@NotNull @Positive Integer subjectId, @NotBlank @Size(max = 20000) String statement,
                        @Size(max = 20000) String explanation, @NotNull AnswerType answerType,
                        JsonNode answerConfig, JsonNode correctAnswer, Integer maxScore,
                        @Size(max = 20) Set<@NotNull @Positive Integer> topicIds,
                        @Size(max = 500) Set<@NotNull UUID> examPositionIds,
                        List<@NotNull UUID> materialIds, UUID expectedVersionId) {}

    public record Copy(@NotNull UUID sourceVersionId) {}
    public record ImportSettings(@NotNull Boolean manualOverride) {}
    public record Source(String code, String externalKey) {}
    public record Receipt(UUID id, UUID versionId, int version, String publicationState) {}
    public record Update(Receipt receipt, boolean changed) {}
    public record Deletion(UUID resourceId, UUID cleanupJobId) {}
    public record Publication(UUID taskId, UUID versionId, String publicationState, String errorCode) {}
    public record Version(UUID id, int version, boolean current, Instant createdAt) {}
    public record Position(UUID id, UUID examDefinitionId, UUID structureVersionId, int position) {}
    public record Image(UUID id, String fileName, String mimeType, long sizeBytes, String downloadUrl) {}
    public record Summary(UUID id, UUID versionId, int version, int subjectId, String statement, AnswerType answerType,
                          String checkMode, int maxScore, String origin, Source source, List<TopicLabel> topics,
                          Instant createdAt) {}

    public sealed interface TaskDetail permits Detail, TeacherDetail {}

    public record Detail(@JsonUnwrapped Summary summary, UUID teacherId, JsonNode answerConfig,
                         List<Position> examPositions, List<Image> images, List<Object> materials) implements TaskDetail {}

    public record TeacherDetail(@JsonUnwrapped Detail detail,
                                @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode correctAnswer,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String explanation) implements TaskDetail {}
}
