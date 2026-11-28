package tutor.assignmentservice.bank;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import tutor.assignmentservice.common.pagination.CursorCodec;
import tutor.assignmentservice.exam.CatalogDtos.TopicLabel;
import static tutor.assignmentservice.bank.BankDtos.*;

@Repository
class BankReadRepository {
    private static final String SUMMARIES = """
            select t.id,t.created_at,t.subject_id,t.external_key,s.code,
                v.id as version_id,v.version,v.statement,v.answer_type,v.max_score
            from task t join task_version v on v.task_id=t.id
            left join import_source s on s.id=t.source_id
            """;

    private final EntityManager entities;

    BankReadRepository(EntityManager entities) {
        this.entities = entities;
    }

    List<Summary> list(Filter filter, UUID actor, boolean admin, boolean teacher,
                       CursorCodec.Position from, int limit) {
        var query = new StringBuilder("""
                where t.deleted_at is null and v.id=t.current_version_id and v.publication_state='ACTIVE'
                """);
        var args = new HashMap<String, Object>();
        if (filter.scope() == Scope.OWNED) add(query, args, "t.teacher_id=:owner", "owner",
                filter.teacherId() == null ? actor : filter.teacherId());
        if (filter.scope() == Scope.IMPORTED || (!admin && !teacher)) query.append(" and t.source_id is not null");
        if (filter.scope() == Scope.AVAILABLE && teacher && !admin)
            add(query, args, "(t.source_id is not null or t.teacher_id=:owner)", "owner", actor);
        if (filter.teacherId() != null) add(query, args, "t.teacher_id=:teacher", "teacher", filter.teacherId());
        add(query, args, "t.subject_id=:subject", "subject", filter.subjectId());
        if (filter.answerType() != null) add(query, args, "v.answer_type=:answerType", "answerType", filter.answerType().name());
        if (filter.checkMode() != null) query.append(filter.checkMode().name().equals("MANUAL")
                ? " and v.answer_type='FILES'" : " and v.answer_type<>'FILES'");
        if (filter.search() != null && !filter.search().isBlank()) {
            add(query, args, "position(lower(:search) in lower(v.statement))>0", "search", filter.search().strip());
        }
        if (filter.topicIds() != null && !filter.topicIds().isEmpty())
            add(query, args, "exists(select 1 from task_version_topic x where x.task_version_id=v.id and x.topic_id in (:topics))",
                    "topics", filter.topicIds().stream().sorted().toList());
        if (filter.examDefinitionId() != null || filter.examStructureVersionId() != null || filter.position() != null) {
            query.append("""
                     and exists(select 1 from task_version_exam_position x join exam_position p on p.id=x.exam_position_id
                         join exam_structure_version st on st.id=p.structure_version_id where x.task_version_id=v.id
                    """);
            add(query, args, "st.exam_definition_id=:exam", "exam", filter.examDefinitionId());
            add(query, args, "st.id=:structure", "structure", filter.examStructureVersionId());
            add(query, args, "p.position=:position", "position", filter.position());
            query.append(")");
        }

        if (from != null) {
            add(query, args, "(t.created_at,t.id)<(:time,:id)", "time", from.createdAt());
            args.put("id", from.id());
        }
        var statement = session().createNativeQuery(SUMMARIES + query
                + " order by t.created_at desc,t.id desc", Tuple.class);
        args.forEach(statement::setParameter);
        return statement.setMaxResults(limit).getResultList().stream().map(BankReadRepository::summary).toList();
    }

    Summary summary(UUID task, UUID version) {
        return summary(session().createNativeQuery(SUMMARIES + " where t.id=:task and v.id=:version", Tuple.class)
                .setParameter("task", task).setParameter("version", version).getSingleResult());
    }

    List<Position> positions(UUID version) {
        return session().createNativeQuery("""
                select p.id,v.exam_definition_id,v.id as structure_id,p.position from task_version_exam_position l
                join exam_position p on p.id=l.exam_position_id join exam_structure_version v on v.id=p.structure_version_id
                where l.task_version_id=:version order by v.exam_definition_id,v.version,p.position
                """, Tuple.class).setParameter("version", version).getResultList().stream()
                .map(r -> new Position(id(r, "id"), id(r, "exam_definition_id"), id(r, "structure_id"), number(r, "position")))
                .toList();
    }

    List<Image> images(UUID task, UUID version, boolean showExplanation) {
        return session().createNativeQuery("""
                select f.id,f.original_name,f.mime_type,f.size_bytes from task_version_file l
                join stored_file f on f.id=l.file_id where l.task_version_id=:version and f.state='ATTACHED'
                and (:showExplanation or l.used_in_statement) order by f.id
                """, Tuple.class).setParameter("version", version).setParameter("showExplanation", showExplanation)
                .getResultList().stream().map(r -> {
                    UUID file = id(r, "id");
                    return new Image(file, r.get("original_name", String.class), r.get("mime_type", String.class),
                            r.get("size_bytes", Number.class).longValue(),
                            "/api/v1/tasks/" + task + "/versions/" + version + "/images/" + file + "/content");
                }).toList();
    }

    List<Version> history(BankTask task, Integer before, int limit) {
        String query = """
                select v from BankTaskVersion v where v.taskId=:task and v.publicationState='ACTIVE'
                """;
        if (before != null) query += " and v.version<:before";
        var history = entities.createQuery(query + " order by v.version desc", BankTaskVersion.class)
                .setParameter("task", task.id);
        if (before != null) history.setParameter("before", before);
        return history.setMaxResults(limit).getResultList().stream()
                .map(v -> new Version(v.id, v.version, v.id.equals(task.currentVersionId), v.createdAt)).toList();
    }

    Optional<TaskQueries.Download> image(UUID task, UUID version, UUID file, boolean showExplanation) {
        return session().createNativeQuery("""
                select f.object_key,f.original_name,f.mime_type,f.size_bytes from task_version_file l
                join task_version v on v.id=l.task_version_id join stored_file f on f.id=l.file_id
                where v.task_id=:task and v.id=:version and f.id=:file and v.publication_state='ACTIVE'
                    and f.state='ATTACHED' and f.purpose='TASK_IMAGE' and (:showExplanation or l.used_in_statement)
                """, Tuple.class).setParameter("task", task).setParameter("version", version).setParameter("file", file)
                .setParameter("showExplanation", showExplanation).getResultList().stream()
                .map(r -> new TaskQueries.Download(r.get("object_key", String.class), r.get("original_name", String.class),
                        r.get("mime_type", String.class), r.get("size_bytes", Number.class).longValue())).findFirst();
    }

    Map<UUID, List<TopicLabel>> topics(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = session().createNativeQuery("""
                select x.task_version_id,t.id,t.name from task_version_topic x join topic t on t.id=x.topic_id
                where x.task_version_id in (:ids) order by t.name,t.id
                """, Tuple.class).setParameter("ids", ids).getResultList();
        var result = new HashMap<UUID, List<TopicLabel>>();
        for (var row : rows) result.computeIfAbsent(id(row, "task_version_id"), ignored -> new ArrayList<>())
                .add(new TopicLabel(number(row, "id"), row.get("name", String.class)));
        return result;
    }

    private Session session() { return entities.unwrap(Session.class); }

    private static Summary summary(Tuple r) {
        var type = AnswerType.valueOf(r.get("answer_type", String.class));
        String source = r.get("code", String.class);
        return new Summary(id(r, "id"), id(r, "version_id"), number(r, "version"), number(r, "subject_id"),
                r.get("statement", String.class), type, type.checkMode(), number(r, "max_score"),
                source == null ? "PRIVATE" : "IMPORTED", source == null ? null : new Source(source, r.get("external_key", String.class)),
                List.of(), r.get("created_at", Instant.class));
    }

    private static UUID id(Tuple row, String alias) { return row.get(alias, UUID.class); }
    private static int number(Tuple row, String alias) { return row.get(alias, Number.class).intValue(); }

    private static void add(StringBuilder query, Map<String, Object> args, String condition, String key, Object value) {
        if (value != null) {
            query.append(" and ").append(condition);
            args.put(key, value);
        }
    }
}
