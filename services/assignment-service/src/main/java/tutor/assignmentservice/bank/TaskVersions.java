package tutor.assignmentservice.bank;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tutor.assignmentservice.storage.StoredFiles;
import static tutor.assignmentservice.bank.BankDtos.*;

@Repository
class TaskVersions {
    record Content(String statement, String explanation, AnswerType answerType, JsonNode config, JsonNode correct,
                   int maxScore, Set<Integer> topics, Set<UUID> positions) {}

    record Row(UUID id, int number, Content content, String hash) {}

    private final BankTaskVersionRepository repository;
    private final BankRelationsRepository relations;
    private final ObjectMapper json;
    private final StoredFiles files;
    private final Clock clock;

    TaskVersions(BankTaskVersionRepository repository, BankRelationsRepository relations,
                 ObjectMapper json, StoredFiles files, Clock clock) {
        this.repository = repository;
        this.relations = relations;
        this.json = json;
        this.files = files;
        this.clock = clock;
    }

    Row active(UUID taskId, UUID versionId) {
        var row = repository.findByTaskIdAndIdAndPublicationState(taskId, versionId, "ACTIVE")
                .orElseThrow(TaskBank::missing);
        var content = new Content(row.statement, row.explanation, row.answerType, json.readTree(row.answerConfig),
                row.correctAnswer == null ? null : json.readTree(row.correctAnswer), row.maxScore,
                relations.topics(row.id), relations.positions(row.id));
        return new Row(row.id, row.version, content, row.contentHash);
    }

    Receipt write(BankTask task, int number, Content content, String hash, UUID actor) {
        var row = new BankTaskVersion();
        row.id = UUID.randomUUID();
        row.taskId = task.id;
        row.version = number;
        row.statement = content.statement();
        row.explanation = content.explanation();
        row.answerType = content.answerType();
        row.answerConfig = json.writeValueAsString(content.config());
        row.correctAnswer = content.correct() == null ? null : json.writeValueAsString(content.correct());
        row.maxScore = content.maxScore();
        row.contentHash = hash;
        row.createdBy = actor;
        row.createdAt = clock.instant();
        repository.saveAndFlush(row);
        relations.classify(row.id, content.topics(), content.positions());
        task.currentVersionId = row.id;
        return new Receipt(task.id, row.id, number, row.publicationState);
    }

    void attach(UUID version, Map<UUID, TaskMarkdown.Usage> usage, Map<UUID, StoredFiles.File> images) {
        for (var entry : images.entrySet()) {
            var flags = usage.get(entry.getKey());
            files.attachTaskImage(entry.getValue());
            relations.attachImage(version, entry.getKey(), flags);
        }
    }

    void shareImages(UUID source, UUID target) {
        files.lockTaskImages(relations.images(source));
        relations.shareImages(source, target);
    }

    Map<UUID, String> imageHashes(UUID version) {
        return relations.imageHashes(version);
    }

    String hash(Content content, TaskMarkdown.Parsed statement, TaskMarkdown.Parsed explanation,
                Map<UUID, String> images) {
        var answer = AnswerValidator.definition(content.answerType(), content.config(), content.correct(), content.maxScore());
        var canonical = Arrays.asList(statement.canonical(images::get), explanation.canonical(images::get),
                content.answerType(), answer.canonicalConfig(), answer.canonicalAnswer(), content.maxScore(),
                content.topics().stream().sorted().toList(), content.positions().stream().sorted().toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
