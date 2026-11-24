package tutor.educationservice.integration.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tutor.educationservice.cleanup.CleanupHandler;
import tutor.educationservice.cleanup.CleanupJobs;
import tutor.educationservice.common.error.ApiException;

@Service
public class PendingUploads implements CleanupHandler {
    private record Upload(String stagingKey, String objectKey, boolean published) {}

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final CleanupJobs jobs;
    private final TransactionTemplate transaction;

    public PendingUploads(JdbcTemplate jdbc, ObjectStorage storage, CleanupJobs jobs, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(manager);
    }

    public <T> T store(String key, MultipartFile body, String contentType, Supplier<T> publish) {
        UUID id = UUID.randomUUID();
        String staging = ObjectKeys.temporary();
        transaction.executeWithoutResult(status -> {
            jdbc.update("insert into pending_upload(id, staging_key, object_key) values (?, ?, ?)", id, staging, key);
            jobs.enqueue(type(), id, Duration.ofHours(1));
        });
        return transaction.execute(status -> {
            // Lock only the upload record until S3 finishes, then check and lock the destination.
            var active = jdbc.queryForList("select id from pending_upload where id = ? and not cleaning for update", UUID.class, id);
            if (active.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED", "Upload expired");
            try {
                storage.put(staging, body.getInputStream(), body.getSize(), contentType);
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Cannot read file");
            }
            storage.copy(staging, key);
            T result = publish.get();
            jdbc.update("update pending_upload set published = true where id = ?", id);
            return result;
        });
    }

    public static String contentType(String type) {
        if (type == null || type.isBlank()) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        try {
            return new ObjectMetadata(0, type).contentType();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Invalid file type");
        }
    }

    @Override
    public ResourceType type() { return ResourceType.PENDING_UPLOAD; }

    @Override
    public void removeObjects(UUID id) {
        var uploads = jdbc.query("""
                update pending_upload set cleaning = true where id = ? returning staging_key, object_key, published
                """, (row, index) -> new Upload(row.getString(1), row.getString(2), row.getBoolean(3)), id);
        if (uploads.isEmpty()) return;
        var upload = uploads.get(0);
        storage.delete(upload.stagingKey());
        if (!upload.published()) storage.delete(upload.objectKey());
    }

    @Override
    public void removeRows(UUID id) { jdbc.update("delete from pending_upload where id = ?", id); }
}
