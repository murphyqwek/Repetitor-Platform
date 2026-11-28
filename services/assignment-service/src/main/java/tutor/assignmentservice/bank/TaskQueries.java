package tutor.assignmentservice.bank;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.common.pagination.CursorCodec;
import tutor.assignmentservice.exam.CatalogDtos.Page;
import tutor.assignmentservice.exam.CatalogDtos.TopicLabel;
import tutor.assignmentservice.security.CurrentUser;
import tutor.assignmentservice.security.Role;
import static tutor.assignmentservice.bank.BankDtos.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TaskQueries {
    public record Download(String key, String name, String mimeType, long size) {}

    private final BankReadRepository repository;
    private final BankTaskVersionRepository versionRows;
    private final TaskBank bank;
    private final TaskVersions versions;
    private final CursorCodec cursors;
    private final ObjectMapper json;

    TaskQueries(BankReadRepository repository, BankTaskVersionRepository versionRows, TaskBank bank,
                TaskVersions versions, CursorCodec cursors, ObjectMapper json) {
        this.repository = repository;
        this.versionRows = versionRows;
        this.bank = bank;
        this.versions = versions;
        this.cursors = cursors;
        this.json = json;
    }

    public Page<Summary> list(Filter filter, Integer requested, String cursor, CurrentUser actor) {
        boolean admin = actor.roles().contains(Role.ADMIN);
        boolean teacher = actor.roles().contains(Role.TEACHER);
        Scope scope = filter.scope() == null ? Scope.AVAILABLE : filter.scope();
        if (!admin && (filter.teacherId() != null || scope == Scope.ALL || (scope == Scope.OWNED && !teacher)))
            throw new AccessDeniedException("Scope is unavailable");
        if (scope == Scope.OWNED && !teacher && filter.teacherId() == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "OWNED requires teacherId for administrators");

        String search = filter.search() == null ? null : filter.search().strip();
        if (search != null && search.indexOf(0) >= 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid search");
        var normalized = new Filter(scope, filter.teacherId(), filter.subjectId(), filter.examDefinitionId(),
                filter.examStructureVersionId(), filter.position(),
                filter.topicIds() == null ? null : new java.util.TreeSet<>(filter.topicIds()),
                filter.checkMode(), filter.answerType(), search);
        var cursorScope = new CursorCodec.Scope(actor.id(), "tasks", "created-desc",
                json.writeValueAsString(List.of(normalized, admin, teacher)));
        int limit = CursorCodec.limit(requested);
        var from = cursor == null ? null : cursors.decode(cursor, cursorScope);
        var rows = repository.list(normalized, actor.id(), admin, teacher, from, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = page.get(page.size() - 1);
            next = cursors.encode(new CursorCodec.Position(last.createdAt(), last.id()), cursorScope);
        }
        var topics = repository.topics(page.stream().map(Summary::versionId).toList());
        return new Page<>(page.stream().map(row -> withTopics(row, topics)).toList(), next, more);
    }

    public TaskDetail detail(UUID id, UUID version, CurrentUser actor) {
        var task = bank.task(id, false);
        if (version == null) {
            TaskBank.available(task);
            readCurrent(task, actor);
            version = task.currentVersionId;
        } else {
            history(task, actor);
        }
        var row = versions.active(id, version);
        var summary = withTopics(repository.summary(id, version), repository.topics(List.of(version)));
        var positions = repository.positions(version);
        boolean solutions = teacherOrAdmin(actor);
        var images = repository.images(id, version, solutions);
        var detail = new Detail(summary, task.teacherId, row.content().config(), positions, images, List.of());
        return solutions ? new TeacherDetail(detail, row.content().correct(), row.content().explanation()) : detail;
    }

    public Page<Version> history(UUID id, Integer requested, String cursor, CurrentUser actor) {
        var task = bank.task(id, false);
        history(task, actor);
        var scope = new CursorCodec.Scope(actor.id(), "task-versions", "version-desc", id.toString());
        int limit = CursorCodec.limit(requested);
        Integer before = cursor == null ? null : Integer.parseInt(cursors.decodeCatalog(cursor, scope).sortKey());
        var rows = repository.history(task, before, limit + 1);
        boolean more = rows.size() > limit;
        String next = null;
        if (more) {
            var row = rows.get(limit - 1);
            next = cursors.encodeCatalog(new CursorCodec.CatalogPosition(Integer.toString(row.version()), row.id().toString()), scope);
        }
        return new Page<>(rows.subList(0, Math.min(limit, rows.size())), next, more);
    }

    public Publication publication(UUID id, UUID version, CurrentUser actor) {
        TaskBank.manage(bank.task(id, false), actor);
        return versionRows.findByTaskIdAndId(id, version)
                .map(row -> new Publication(id, version, row.publicationState, row.publicationErrorCode))
                .orElseThrow(TaskBank::missing);
    }

    public Download image(UUID id, UUID version, UUID file, CurrentUser actor) {
        var task = bank.task(id, false);
        boolean solutions = teacherOrAdmin(actor);
        if (solutions) {
            history(task, actor);
        } else if (!task.imported() || task.deleted() || !version.equals(task.currentVersionId)) {
            throw missingFile();
        }
        return repository.image(id, version, file, solutions).orElseThrow(TaskQueries::missingFile);
    }

    private static ApiException missingFile() {
        return new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "Image not found");
    }

    private static void readCurrent(BankTask task, CurrentUser user) {
        if (!task.imported()) TaskBank.manage(task, user);
    }

    private static void history(BankTask task, CurrentUser user) {
        if (task.imported() && !task.deleted() && user.roles().contains(Role.TEACHER)) return;
        TaskBank.manage(task, user);
    }

    private static boolean teacherOrAdmin(CurrentUser actor) {
        return actor.roles().contains(Role.TEACHER) || actor.roles().contains(Role.ADMIN);
    }

    private static Summary withTopics(Summary row, Map<UUID, List<TopicLabel>> topics) {
        return new Summary(row.id(), row.versionId(), row.version(), row.subjectId(), row.statement(), row.answerType(),
                row.checkMode(), row.maxScore(), row.origin(), row.source(), topics.getOrDefault(row.versionId(), List.of()), row.createdAt());
    }

}
