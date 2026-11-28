package tutor.assignmentservice.bank;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
class BankCleanupRepository {
    record Job(UUID id, UUID task) {}

    private final EntityManager entities;

    BankCleanupRepository(EntityManager entities) {
        this.entities = entities;
    }

    Optional<UUID> find(UUID task) {
        return entities.unwrap(Session.class).createNativeQuery(
                "select id from cleanup_job where resource_type='TASK' and resource_id=:task", UUID.class)
                .setParameter("task", task).getResultList().stream().findFirst();
    }

    UUID enqueue(UUID task, Instant now) {
        UUID job = UUID.randomUUID();
        entities.createNativeQuery("""
                insert into cleanup_job(id,resource_type,resource_id,status,next_attempt_at,created_at,updated_at)
                values (:job,'TASK',:task,'PENDING',:now,:now,:now)
                """).setParameter("job", job).setParameter("task", task).setParameter("now", now).executeUpdate();
        return job;
    }

    Optional<Job> claim(Instant now) {
        return entities.unwrap(Session.class).createNativeQuery("""
                select id,resource_id from cleanup_job
                where resource_type='TASK' and status='PENDING' and next_attempt_at<=:now
                order by next_attempt_at,id limit 1 for update skip locked
                """, Object[].class).setParameter("now", now).getResultList().stream()
                .map(row -> new Job((UUID) row[0], (UUID) row[1])).findFirst();
    }

    void complete(UUID job, Instant now) {
        entities.createNativeQuery("""
                update cleanup_job set status='COMPLETED',attempts=attempts+1,updated_at=:now where id=:job
                """).setParameter("job", job).setParameter("now", now).executeUpdate();
    }

    void fail(UUID job, Instant now) {
        entities.createNativeQuery("""
                update cleanup_job set status='FAILED',last_error='TASK_NOT_DELETED',updated_at=:now where id=:job
                """).setParameter("job", job).setParameter("now", now).executeUpdate();
    }
}
