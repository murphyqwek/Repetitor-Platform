package tutor.assignmentservice.storage;

import java.io.InputStream;
import java.util.UUID;

public interface ObjectStorage {
    // Generate once per file and persist the key so that retries use the same object.
    static String newKey() {
        return "files/" + UUID.randomUUID();
    }

    // The adapter closes body. A size of -1 uses streaming multipart upload.
    void put(String key, InputStream body, long size, String contentType);

    // The caller must close the returned stream.
    InputStream get(String key);

    Metadata head(String key);

    void delete(String key);

    record Metadata(long size, String contentType) {}
}
