package tutor.assignmentservice.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.security.CurrentUser;

@Service
public class FileService {
    public record Uploaded(UUID id, String fileName, String mimeType, long sizeBytes, String state,
                           String markdownReference, Instant expiresAt) {}

    private final StoredFiles files;
    private final ObjectStorage storage;

    public FileService(StoredFiles files, ObjectStorage storage) {
        this.files = files;
        this.storage = storage;
    }

    public Uploaded upload(MultipartFile part, CurrentUser actor) {
        try (var image = ImageUpload.prepare(part)) {
            StoredFiles.File file = files.begin(actor, image.name, image.type, image.size);
            boolean putStarted = false;
            boolean putConfirmed = false;
            try (var body = Files.newInputStream(image.path)) {
                putStarted = true;
                storage.put(file.key(), body, image.size, image.type);
                putConfirmed = true;
                var stored = storage.head(file.key());
                if (stored.size() != image.size || !image.type.equals(stored.contentType()))
                    throw new StorageException(StorageException.Reason.UNAVAILABLE);
                var ready = files.ready(file.id(), file.uploadToken(), image.hash);
                return new Uploaded(ready.id(), ready.name(), ready.type(), ready.size(), ready.state(),
                        "attachment:" + ready.id(), ready.expiresAt());
            } catch (IOException ex) {
                files.cancel(file.id(), file.uploadToken());
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Cannot read prepared image");
            } catch (RuntimeException ex) {
                files.cancel(file.id(), file.uploadToken());
                throw ex;
            } finally {
                if (!putStarted || putConfirmed) files.acknowledgeUpload(file.id(), file.uploadToken());
            }
        }
    }
}
