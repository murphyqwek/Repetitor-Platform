package tutor.assignmentservice.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FileCleanup {
    private static final int BATCH_SIZE = 10;
    private final StoredFiles files;
    private final ObjectStorage storage;
    private String objectCursor;

    public FileCleanup(StoredFiles files, ObjectStorage storage) {
        this.files = files;
        this.storage = storage;
    }

    public void runOnce() {
        files.expire(100);
        for (int i = 0; i < BATCH_SIZE; i++) {
            var job = files.claim();
            if (job == null) return;
            try {
                if (!files.mayDelete(job)) {
                    files.fail(job, "RESOURCE_IS_ACTIVE");
                    continue;
                }
                var multipart = storage.listMultipart(job.key(), null, null, 100);
                for (var upload : multipart.uploads()) storage.abortMultipart(upload.key(), upload.uploadId());
                storage.delete(job.key());
                if (multipart.nextKey() != null || multipart.nextUpload() != null) {
                    files.retry(job, "MULTIPART_PAGE_PENDING");
                    continue;
                }
                files.complete(job);
            } catch (StorageException ex) {
                files.retry(job, "STORAGE_UNAVAILABLE");
                log.warn("File cleanup dependency unavailable, jobId={}", job.id());
            } catch (RuntimeException ex) {
                files.retry(job, "CLEANUP_ERROR");
                log.warn("File cleanup failed, jobId={}, error={}", job.id(), ex.getClass().getSimpleName());
            }
        }
    }

    public synchronized void reconcileOnce() {
        var page = storage.list(objectCursor, 100);
        page.keys().forEach(files::reconcile);
        objectCursor = page.nextAfter();
    }
}
