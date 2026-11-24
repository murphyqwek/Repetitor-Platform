package tutor.educationservice.cleanup;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.cleanup.CleanupHandler.ResourceType;

@Repository
public class CleanupJobs {
    record Job(UUID id, ResourceType type, UUID resourceId, int attempts, UUID token) {}

    private final JdbcTemplate jdbc;

    public CleanupJobs(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(ResourceType type, UUID resourceId) {
        return enqueue(type, resourceId, Duration.ZERO);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(ResourceType type, UUID resourceId, Duration delay) {
        return jdbc.queryForObject("""
                insert into cleanup_job(id, resource_type, resource_id, next_attempt_at)
                values (?, ?, ?, clock_timestamp() + ? * interval '1 millisecond')
                on conflict (resource_type, resource_id) where status in ('PENDING', 'PROCESSING', 'FAILED')
                do update set resource_id = excluded.resource_id
                returning id
                """, UUID.class, UUID.randomUUID(), type.name(), resourceId, delay.toMillis());
    }

    List<Job> claim(Duration lease, int limit, List<String> types) {
        return jdbc.query("""
                with ready as (
                    select id from cleanup_job
                    where resource_type = any (?) and (
                        (status = 'PENDING' and next_attempt_at <= clock_timestamp())
                        or (status = 'PROCESSING' and locked_until <= clock_timestamp())
                    )
                    order by case when status = 'PENDING' then next_attempt_at else locked_until end, id
                    limit ? for update skip locked
                )
                update cleanup_job job set status = 'PROCESSING', attempts = attempts + 1,
                    locked_until = clock_timestamp() + ? * interval '1 millisecond',
                    lease_token = gen_random_uuid(), updated_at = clock_timestamp()
                from ready where job.id = ready.id
                returning job.id, job.resource_type, job.resource_id, job.attempts, job.lease_token
                """, statement -> {
                    statement.setArray(1, statement.getConnection().createArrayOf("text", types.toArray()));
                    statement.setInt(2, limit);
                    statement.setLong(3, lease.toMillis());
                }, (row, index) -> new Job(row.getObject(1, UUID.class), ResourceType.valueOf(row.getString(2)),
                        row.getObject(3, UUID.class), row.getInt(4), row.getObject(5, UUID.class)));
    }

    @Transactional
    public boolean complete(Job job, CleanupHandler handler) {
        var rows = jdbc.queryForList("""
                select id from cleanup_job where id = ? and lease_token = ? and locked_until > clock_timestamp()
                for update
                """, UUID.class, job.id(), job.token());
        if (rows.isEmpty()) return false;
        handler.removeRows(job.resourceId());
        jdbc.update("""
                update cleanup_job set status = 'COMPLETED', locked_until = null, lease_token = null,
                    last_error = null, updated_at = clock_timestamp() where id = ?
                """, job.id());
        return true;
    }

    boolean fail(Job job, boolean terminal, Duration backoff, String error) {
        return jdbc.update("""
                update cleanup_job set status = ?, locked_until = null, lease_token = null,
                    next_attempt_at = clock_timestamp() + ? * interval '1 millisecond',
                    last_error = ?, updated_at = clock_timestamp()
                where id = ? and lease_token = ? and locked_until > clock_timestamp()
                """, terminal ? "FAILED" : "PENDING", backoff.toMillis(), error, job.id(), job.token()) == 1;
    }

    long count(String status) {
        return jdbc.queryForObject("select count(*) from cleanup_job where status = ?", Long.class, status);
    }

    double oldestPendingSeconds() {
        return jdbc.queryForObject("""
                select coalesce(extract(epoch from clock_timestamp() - min(created_at)), 0)
                from cleanup_job where status in ('PENDING', 'PROCESSING')
                """, Double.class);
    }
}
