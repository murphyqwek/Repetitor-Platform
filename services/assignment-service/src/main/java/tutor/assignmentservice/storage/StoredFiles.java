package tutor.assignmentservice.storage;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.security.CurrentUser;
import tutor.assignmentservice.security.Role;

@Repository
public class StoredFiles {
    public record File(UUID id, UUID uploader, String key, String name, String type, long size, String hash,
                       String state, String purpose, Instant expiresAt, UUID uploadToken) {}
    public record Job(UUID id, UUID resource, String key, UUID token, int attempts) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StoredFiles(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    private static Timestamp time(Instant instant) { return Timestamp.from(instant); }
    private static File file(ResultSet row, int index) throws SQLException {
        Timestamp expires = row.getTimestamp("expires_at");
        return new File(row.getObject("id", UUID.class), row.getObject("uploader_id", UUID.class),
                row.getString("object_key"), row.getString("original_name"), row.getString("mime_type"),
                row.getLong("size_bytes"), row.getString("content_hash"), row.getString("state"), row.getString("purpose"),
                expires == null ? null : expires.toInstant(), row.getObject("upload_lease_token", UUID.class));
    }

    public File begin(CurrentUser actor, String name, String type, long size) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            UUID id = UUID.randomUUID(), token = UUID.randomUUID();
            jdbc.update("""
                    insert into stored_file(id,uploader_id,object_key,original_name,mime_type,size_bytes,purpose,state,
                        created_at,expires_at,updated_at,upload_locked_until,upload_lease_token)
                    values(?,?,?,?,?,?,'TASK_IMAGE','UPLOADING',?,?,?,?,?)
                    """, id, actor.id(), ObjectStorage.newKey(), name, type, size, time(now), time(now.plus(Duration.ofHours(24))),
                    time(now), time(now.plus(Duration.ofMinutes(5))), token);
            return load(id, false);
        });
    }

    public File ready(UUID id, UUID token, String hash) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            int changed = jdbc.update("""
                    update stored_file set state='READY',content_hash=?,updated_at=?
                    where id=? and upload_lease_token=? and state='UPLOADING' and upload_locked_until>? and expires_at>?
                    """, hash, time(now), id, token, time(now), time(now));
            if (changed != 1) throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED", "Upload is no longer active");
            return load(id, false);
        });
    }

    public void acknowledgeUpload(UUID id, UUID token) {
        transaction.executeWithoutResult(status -> jdbc.update("""
                update stored_file set upload_lease_token=null,upload_locked_until=null,updated_at=?
                where id=? and upload_lease_token=?
                """, time(clock.instant()), id, token));
    }

    public void cancel(UUID id, UUID token) {
        transaction.executeWithoutResult(status -> {
            File file = load(id, true);
            if (file != null && token.equals(file.uploadToken()) && !"ATTACHED".equals(file.state())) markDeleting(file);
        });
    }

    public File preview(UUID id, CurrentUser actor) {
        File file = load(id, false);
        authorize(file, actor);
        if (!"READY".equals(file.state()) || file.expiresAt() == null || !file.expiresAt().isAfter(clock.instant()))
            throw missing();
        return file;
    }

    public UUID delete(UUID id, CurrentUser actor) {
        return transaction.execute(status -> {
            File file = load(id, true);
            authorize(file, actor);
            if ("ATTACHED".equals(file.state()) || file.expiresAt() == null)
                throw new ApiException(HttpStatus.CONFLICT, "FILE_IN_USE", "Image is attached");
            return markDeleting(file);
        });
    }

    private void authorize(File file, CurrentUser actor) {
        if (file == null || (!actor.roles().contains(Role.ADMIN) && !actor.id().equals(file.uploader()))) throw missing();
        if (!"TASK_IMAGE".equals(file.purpose()))
            throw missing();
    }

    private File load(UUID id, boolean lock) {
        var rows = jdbc.query("select * from stored_file where id=?" + (lock ? " for update" : ""), StoredFiles::file, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID markDeleting(File file) {
        jdbc.update("update stored_file set state='DELETING',updated_at=? where id=?", time(clock.instant()), file.id());
        return enqueue(file.id(), file.key());
    }

    // All enqueue paths share this advisory lock, including scans after metadata has been removed.
    private UUID enqueue(UUID fileId, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", r -> {}, key);
        var jobs = jdbc.query("select id,status from cleanup_job where resource_type='STORED_FILE' and resource_id=? order by created_at desc",
                (r,i) -> Map.entry(r.getObject(1, UUID.class), r.getString(2)), fileId);
        Instant now = clock.instant();
        if (!jobs.isEmpty()) {
            var job = jobs.get(0);
            if ("COMPLETED".equals(job.getValue())) {
                jdbc.update("update cleanup_job set status='PENDING',next_attempt_at=?,updated_at=? where id=?",
                        time(now), time(now), job.getKey());
            }
            return job.getKey();
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cleanup_job(id,resource_type,resource_id,object_key,status,next_attempt_at,created_at,updated_at)
                values(?,'STORED_FILE',?,?,'PENDING',?,?,?)
                """, id, fileId, key, time(now), time(now), time(now));
        return id;
    }

    public int expire(int limit) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            var rows = jdbc.query("""
                    select * from stored_file where state in ('UPLOADING','READY') and
                        (expires_at<=? or (state='UPLOADING' and upload_locked_until<=?))
                    order by expires_at,id limit ? for update skip locked
                    """, StoredFiles::file, time(now), time(now), limit);
            rows.forEach(this::markDeleting);
            return rows.size();
        });
    }

    public Job claim() {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            var rows = jdbc.query("""
                    select id,resource_id,object_key,attempts from cleanup_job
                    where resource_type='STORED_FILE' and ((status='PENDING' and next_attempt_at<=?)
                        or (status='PROCESSING' and locked_until<=?))
                    order by next_attempt_at,id limit 1 for update skip locked
                    """, (r,i) -> new Job(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                    r.getString(3), UUID.randomUUID(), r.getInt(4)+1), time(now), time(now));
            if (rows.isEmpty()) return null;
            Job job = rows.get(0);
            jdbc.update("""
                    update cleanup_job set status='PROCESSING',lease_token=?,locked_until=?,attempts=?,updated_at=? where id=?
                    """, job.token(), time(now.plusSeconds(120)), job.attempts(), time(now), job.id());
            return job;
        });
    }

    public boolean mayDelete(Job job) {
        File file = load(job.resource(), false);
        return file == null || "DELETING".equals(file.state());
    }

    public void complete(Job job) {
        transaction.executeWithoutResult(status -> {
            File file = load(job.resource(), true);
            if (!ownsLease(job)) return;
            if (file != null && !"DELETING".equals(file.state())) {
                fail(job, "RESOURCE_IS_ACTIVE");
                return;
            }
            // An abandoned writer may still succeed. Retain its metadata and retry until an acknowledgement exists.
            if (file != null && file.uploadToken() != null) {
                retry(job, "UPLOAD_OUTCOME_UNKNOWN", 30);
                return;
            }
            if (file != null) jdbc.update("delete from stored_file where id=?", file.id());
            jdbc.update("""
                    update cleanup_job set status='COMPLETED',locked_until=null,lease_token=null,last_error=null,updated_at=?
                    where id=? and lease_token=?
                    """, time(clock.instant()), job.id(), job.token());
        });
    }

    private boolean ownsLease(Job job) {
        return !jdbc.query("""
                select id from cleanup_job where id=? and lease_token=? and status='PROCESSING' and locked_until>? for update
                """, (row, index) -> row.getObject(1, UUID.class), job.id(), job.token(), time(clock.instant())).isEmpty();
    }

    public void retry(Job job, String error) {
        long delay = Math.min(300, 1L << Math.min(job.attempts(), 8));
        transaction.executeWithoutResult(status -> retry(job, error, delay));
    }

    private void retry(Job job, String error, long delay) {
        Instant now = clock.instant();
        jdbc.update("""
                update cleanup_job set status='PENDING',next_attempt_at=?,locked_until=null,lease_token=null,last_error=?,updated_at=?
                where id=? and lease_token=? and status='PROCESSING'
                """, time(now.plusSeconds(delay)), error, time(now), job.id(), job.token());
    }

    public void fail(Job job, String error) {
        jdbc.update("""
                update cleanup_job set status='FAILED',locked_until=null,lease_token=null,last_error=?,updated_at=?
                where id=? and lease_token=? and status='PROCESSING'
                """, error, time(clock.instant()), job.id(), job.token());
    }

    public void reconcile(String key) {
        transaction.executeWithoutResult(status -> {
            var file = jdbc.query("select * from stored_file where object_key=?", StoredFiles::file, key);
            if (file.isEmpty()) {
                var markers = jdbc.query("select resource_id from cleanup_job where object_key=? order by created_at desc limit 1",
                        (r,i) -> r.getObject(1, UUID.class), key);
                enqueue(markers.isEmpty() ? UUID.randomUUID() : markers.get(0), key);
            } else if ("DELETING".equals(file.get(0).state())) {
                enqueue(file.get(0).id(), key);
            }
        });
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "Image not found");
    }
}
