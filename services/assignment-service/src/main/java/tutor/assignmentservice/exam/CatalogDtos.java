package tutor.assignmentservice.exam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CatalogDtos {
    private CatalogDtos() {}
    public enum CheckMode { AUTOMATIC, MANUAL }
    public record Subject(int id, String name) {}
    public record ExamType(int id, String code, String name, int subjectId) {}
    public record Topic(int id, int subjectId, String name, boolean isActive) {}
    public record TopicCreate(@NotNull @Positive Integer subjectId, @NotBlank @Size(max=200) String name) {}
    public record TopicPatch(@Size(min=1,max=200) String name, Boolean isActive) {}
    public record PositionInput(@Min(1) @Max(100) int position, @Min(1) @Max(100) int maxScore,
                                @NotNull CheckMode checkMode, @NotNull @Size(max=500) Set<@NotNull @Positive Integer> topicIds) {}
    public record StructureInput(@Min(1) @Max(1440) int durationMinutes,
                                 @NotNull @Size(min=1,max=100) List<@NotNull @Valid PositionInput> positions) {}
    public record ExamCreate(@NotNull @Positive Integer examTypeId, @Min(1) @Max(32767) int year, @NotNull @Valid StructureInput structure) {}
    public record StructureCreate(@NotNull UUID expectedStructureVersionId, @NotNull @Valid StructureInput structure) {}
    public record ExamSummary(UUID id, int examTypeId, int year, UUID currentStructureVersionId) {}
    public record ExamDetail(UUID id, int examTypeId, int year, UUID currentStructureVersionId, Structure structure) {}
    public record TopicLabel(int id, String name) {}
    public record Position(UUID id, int position, int maxScore, CheckMode checkMode, List<TopicLabel> topics) {}
    public record Structure(UUID id, UUID examDefinitionId, int version, int durationMinutes, int maxScore,
                            List<Position> positions, Instant createdAt) {}
    public record Value(@NotNull @Min(0) Integer primaryScore, @Min(0) @Max(100) Integer testScore, @Min(2) @Max(5) Integer schoolMark) {}
    public record ScaleCreate(@NotBlank @Size(max=200) String name, @NotNull UUID structureVersionId,
                              @NotNull @Size(min=1,max=10001) List<@NotNull @Valid Value> values) {}
    public record ScaleUpdate(@NotNull UUID expectedVersionId, @NotNull UUID structureVersionId,
                              @NotNull @Size(min=1,max=10001) List<@NotNull @Valid Value> values) {}
    public record ScaleSummary(UUID id, UUID examDefinitionId, String name, UUID currentVersionId) {}
    public record ScaleVersion(UUID id, UUID scaleId, UUID structureVersionId, int version, List<Value> values, Instant createdAt) {}
    public record ScaleDetail(UUID id, UUID examDefinitionId, String name, UUID currentVersionId, ScaleVersion version) {}
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}
}
