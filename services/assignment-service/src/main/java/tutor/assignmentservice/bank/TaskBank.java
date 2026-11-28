package tutor.assignmentservice.bank;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.security.CurrentUser;
import tutor.assignmentservice.security.Role;
import tutor.assignmentservice.storage.StoredFiles;
import static tutor.assignmentservice.bank.BankDtos.*;

@Service
@Transactional
@Slf4j
public class TaskBank {
    private final BankTaskRepository tasks;
    private final BankTaskVersionRepository versionRows;
    private final BankRelationsRepository relations;
    private final BankCleanupRepository cleanup;
    private final TaskVersions versions;
    private final StoredFiles files;
    private final Clock clock;

    TaskBank(BankTaskRepository tasks, BankTaskVersionRepository versionRows, BankRelationsRepository relations,
             BankCleanupRepository cleanup, TaskVersions versions, StoredFiles files, Clock clock) {
        this.tasks = tasks;
        this.versionRows = versionRows;
        this.relations = relations;
        this.cleanup = cleanup;
        this.versions = versions;
        this.files = files;
        this.clock = clock;
    }

    public Receipt create(Write input, CurrentUser actor) {
        require(actor, Role.TEACHER);
        if (!relations.subjectExists(input.subjectId())) throw invalid("Unknown subject");
        var task = tasks.saveAndFlush(new BankTask(actor.id(), input.subjectId(), null, clock.instant()));
        return write(task, 1, null, input, actor).receipt();
    }

    public Update update(UUID id, Write input, CurrentUser actor) {
        BankTask task = task(id, true);
        manage(task, actor);
        available(task);
        if (input.expectedVersionId() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVISION_REQUIRED", "Expected version is required");
        if (!input.expectedVersionId().equals(task.currentVersionId))
            throw new ApiException(HttpStatus.CONFLICT, "TASK_VERSION_CONFLICT", "Current version changed",
                    Map.of("currentVersionId", task.currentVersionId));
        if (task.subjectId != input.subjectId()) throw invalid("Task subject cannot change");
        if (versionRows.existsByTaskIdAndPublicationStateNot(id, "ACTIVE"))
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_AVAILABLE", "Task has an unfinished version");
        var previous = versions.active(id, task.currentVersionId);
        var result = write(task, previous.number() + 1, previous, input, actor);
        if (task.imported() && result.changed()) task.manualOverride = true;
        return result;
    }

    private Update write(BankTask task, int number, TaskVersions.Row previous, Write input, CurrentUser actor) {
        if (input.materialIds() != null && !input.materialIds().isEmpty())
            throw invalid("Education materials are not supported yet");
        if (input.statement().indexOf(0) >= 0 || (input.explanation() != null && input.explanation().indexOf(0) >= 0))
            throw invalid("Task text contains an invalid character");
        var config = input.answerConfig() == null ? JsonNodeFactory.instance.objectNode() : input.answerConfig();
        var correct = input.correctAnswer() == null || input.correctAnswer().isNull() ? null : input.correctAnswer();
        var answer = AnswerValidator.definition(input.answerType(), config, correct, input.maxScore());
        Set<Integer> topics = input.topicIds() == null ? Set.of() : input.topicIds();
        Set<UUID> positions = input.examPositionIds() == null ? Set.of() : input.examPositionIds();
        var content = new TaskVersions.Content(input.statement(), input.explanation(), input.answerType(), config,
                correct, answer.maxScore(), topics, positions);
        var statement = TaskMarkdown.parse(content.statement());
        var explanation = TaskMarkdown.parse(content.explanation());
        var references = TaskMarkdown.usage(statement, explanation);
        var images = new HashMap<UUID, StoredFiles.File>();
        var hashes = new HashMap<UUID, String>();
        for (UUID id : references.keySet().stream().sorted().toList()) {
            var file = files.lockTaskImage(id, task.id, actor);
            images.put(id, file);
            hashes.put(id, file.hash());
        }
        if (images.values().stream().mapToLong(StoredFiles.File::size).sum() > 50_000_000)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ATTACHMENT_LIMIT_EXCEEDED", "Task images exceed 50 MB");
        String hash = versions.hash(content, statement, explanation, hashes);
        if (previous != null && previous.hash().equals(hash))
            return new Update(new Receipt(task.id, previous.id(), previous.number(), "ACTIVE"), false);
        classify(task, content, previous);
        var receipt = versions.write(task, number, content, hash, actor.id());
        versions.attach(receipt.versionId(), references, images);
        return new Update(receipt, true);
    }

    private void classify(BankTask task, TaskVersions.Content content, TaskVersions.Row previous) {
        var newTopics = new HashSet<>(content.topics());
        if (previous != null) newTopics.removeAll(previous.content().topics());
        if (!newTopics.isEmpty() && !relations.activeTopics(task.subjectId, newTopics))
            throw invalid("Select active topics of the task subject");
        if (content.positions().isEmpty()) {
            if (task.imported()) throw invalid("Imported tasks require an exam position");
            return;
        }
        if (!relations.compatiblePositions(task.subjectId, content.answerType().checkMode(), content.positions()))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "EXAM_POSITION_MISMATCH",
                    "Exam positions must match the subject and check mode");
    }

    public Receipt copy(UUID id, Copy input, CurrentUser actor) {
        require(actor, Role.TEACHER);
        BankTask source = task(id, true);
        available(source);
        if (!source.imported()) throw missing();
        var origin = versions.active(id, input.sourceVersionId());
        var copy = tasks.saveAndFlush(new BankTask(actor.id(), source.subjectId, id, clock.instant()));
        var result = versions.write(copy, 1, origin.content(), origin.hash(), actor.id());
        versions.shareImages(origin.id(), result.versionId());
        return result;
    }

    public ImportSettings importSettings(UUID id, ImportSettings input, CurrentUser actor) {
        require(actor, Role.ADMIN);
        BankTask task = task(id, true);
        available(task);
        if (!task.imported()) throw missing();
        task.manualOverride = input.manualOverride();
        return input;
    }

    public Deletion delete(UUID id, CurrentUser actor) {
        BankTask initial = task(id, false);
        manage(initial, actor);
        if (initial.imported()) relations.lockImportIdentity(initial.sourceId, initial.externalKey);
        BankTask task = task(id, true);
        var job = cleanup.find(id);
        if (job.isPresent()) return new Deletion(id, job.get());

        task.deletedAt = clock.instant();
        if (task.imported()) relations.rememberDeletion(task, actor.id(), task.deletedAt);
        return new Deletion(id, cleanup.enqueue(id, task.deletedAt));
    }

    public void transferClassification(Map<UUID, UUID> replacements, UUID actor) {
        if (replacements.isEmpty()) return;
        var candidates = relations.transferCandidates(replacements.keySet());
        for (var candidate : candidates.entrySet()) {
            UUID taskId = candidate.getKey();
            var task = tasks.tryLock(taskId).orElse(null);
            if (task == null || task.deleted() || !candidate.getValue().equals(task.currentVersionId)
                    || versionRows.existsByTaskIdAndPublicationStateNot(taskId, "ACTIVE")) {
                log.info("Skipped classification transfer for changed task {}", taskId);
                continue;
            }
            var previous = versions.active(taskId, candidate.getValue());
            var old = previous.content();
            var positions = new HashSet<>(old.positions());
            for (UUID position : old.positions()) {
                UUID replacement = replacements.get(position);
                if (replacement != null) positions.add(replacement);
            }
            if (positions.equals(old.positions())) continue;
            var content = new TaskVersions.Content(old.statement(), old.explanation(), old.answerType(), old.config(),
                    old.correct(), old.maxScore(), old.topics(), positions);
            var hash = versions.hash(content, TaskMarkdown.parse(content.statement()), TaskMarkdown.parse(content.explanation()),
                    versions.imageHashes(previous.id()));
            var receipt = versions.write(task, previous.number() + 1, content, hash, actor);
            versions.shareImages(previous.id(), receipt.versionId());
        }
    }

    BankTask task(UUID id, boolean lock) {
        return (lock ? tasks.lock(id) : tasks.findById(id)).orElseThrow(TaskBank::missing);
    }

    static void available(BankTask task) {
        if (task.deleted() || task.currentVersionId == null) throw missing();
    }

    static void manage(BankTask task, CurrentUser user) {
        if (!user.roles().contains(Role.ADMIN) && !(user.roles().contains(Role.TEACHER) && user.id().equals(task.teacherId)))
            throw missing();
    }

    static void require(CurrentUser user, Role role) {
        if (!user.roles().contains(role)) throw new AccessDeniedException("Access denied");
    }

    static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
    }

    static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", detail);
    }
}
