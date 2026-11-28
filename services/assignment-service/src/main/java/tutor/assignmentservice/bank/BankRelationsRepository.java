package tutor.assignmentservice.bank;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
class BankRelationsRepository {
    private final EntityManager entities;

    BankRelationsRepository(EntityManager entities) {
        this.entities = entities;
    }

    boolean subjectExists(int id) {
        return !entities.createNativeQuery("select id from subject where id=:id")
                .setParameter("id", id).getResultList().isEmpty();
    }

    boolean activeTopics(int subject, Set<Integer> ids) {
        return entities.createNativeQuery("""
                select id from topic where id in (:ids) and subject_id=:subject and is_active order by id for share
                """).setParameter("ids", ids).setParameter("subject", subject).getResultList().size() == ids.size();
    }

    boolean compatiblePositions(int subject, String mode, Set<UUID> ids) {
        return entities.createNativeQuery("""
                select p.id from exam_position p
                join exam_structure_version v on v.id=p.structure_version_id
                join exam_definition e on e.id=v.exam_definition_id
                join exam_type et on et.id=e.exam_type_id
                where p.id in (:ids) and et.subject_id=:subject and p.check_mode=:mode
                """).setParameter("ids", ids).setParameter("subject", subject).setParameter("mode", mode)
                .getResultList().size() == ids.size();
    }

    void lockImportIdentity(int source, String key) {
        entities.createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(:identity,0))")
                .setParameter("identity", "imported-task:" + source + ":" + key).getSingleResult();
    }

    void rememberDeletion(BankTask task, UUID actor, Instant now) {
        entities.createNativeQuery("""
                insert into deleted_imported_task values (:source,:key,:actor,:now) on conflict do nothing
                """).setParameter("source", task.sourceId).setParameter("key", task.externalKey)
                .setParameter("actor", actor).setParameter("now", now).executeUpdate();
    }

    Map<UUID, UUID> transferCandidates(Set<UUID> positions) {
        List<Object[]> rows = entities.unwrap(Session.class).createNativeQuery("""
                select t.id,t.current_version_id from task t where t.deleted_at is null and exists
                    (select 1 from task_version_exam_position m
                        where m.task_version_id=t.current_version_id and m.exam_position_id in (:positions))
                order by t.id
                """, Object[].class).setParameter("positions", positions).getResultList();
        var result = new java.util.LinkedHashMap<UUID, UUID>();
        for (var row : rows) result.put((UUID) row[0], (UUID) row[1]);
        return result;
    }

    Set<Integer> topics(UUID version) {
        return Set.copyOf(entities.unwrap(Session.class).createNativeQuery(
                "select topic_id from task_version_topic where task_version_id=:id", Integer.class)
                .setParameter("id", version).getResultList());
    }

    Set<UUID> positions(UUID version) {
        return Set.copyOf(entities.unwrap(Session.class).createNativeQuery(
                "select exam_position_id from task_version_exam_position where task_version_id=:id", UUID.class)
                .setParameter("id", version).getResultList());
    }

    void classify(UUID version, Set<Integer> topics, Set<UUID> positions) {
        for (int topic : topics) {
            entities.createNativeQuery("insert into task_version_topic values (:version,:topic)")
                    .setParameter("version", version).setParameter("topic", topic).executeUpdate();
        }
        for (UUID position : positions) {
            entities.createNativeQuery("insert into task_version_exam_position values (:version,:position)")
                    .setParameter("version", version).setParameter("position", position).executeUpdate();
        }
    }

    void attachImage(UUID version, UUID file, TaskMarkdown.Usage usage) {
        entities.createNativeQuery("insert into task_version_file values (:version,:file,:statement,:explanation)")
                .setParameter("version", version).setParameter("file", file)
                .setParameter("statement", usage.statement()).setParameter("explanation", usage.explanation()).executeUpdate();
    }

    List<UUID> images(UUID version) {
        return entities.unwrap(Session.class).createNativeQuery(
                "select file_id from task_version_file where task_version_id=:version", UUID.class)
                .setParameter("version", version).getResultList();
    }

    List<UUID> taskImages(UUID task) {
        return entities.unwrap(Session.class).createNativeQuery("""
                select distinct f.file_id from task_version v join task_version_file f on f.task_version_id=v.id
                where v.task_id=:task
                """, UUID.class).setParameter("task", task).getResultList();
    }

    void shareImages(UUID source, UUID target) {
        entities.createNativeQuery("""
                insert into task_version_file
                select :target,file_id,used_in_statement,used_in_explanation from task_version_file where task_version_id=:source
                """).setParameter("target", target).setParameter("source", source).executeUpdate();
    }

    Map<UUID, String> imageHashes(UUID version) {
        List<Object[]> rows = entities.unwrap(Session.class).createNativeQuery("""
                select f.id,f.content_hash from stored_file f join task_version_file l on l.file_id=f.id
                where l.task_version_id=:version
                """, Object[].class).setParameter("version", version).getResultList();
        var result = new HashMap<UUID, String>();
        for (var row : rows) result.put((UUID) row[0], (String) row[1]);
        return result;
    }

    void clearCopyProvenance(UUID source) {
        entities.createNativeQuery("update task set copied_from_task_id=null where copied_from_task_id=:source")
                .setParameter("source", source).executeUpdate();
    }
}
