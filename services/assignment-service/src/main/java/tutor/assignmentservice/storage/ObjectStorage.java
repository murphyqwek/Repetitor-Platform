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


    // Each scan returns at most limit entries from the private files/ namespace.
    ObjectPage list(String after, int limit);
    MultipartPage listMultipart(String key, String keyMarker, String uploadMarker, int limit);
    void abortMultipart(String key, String uploadId);

    record ObjectPage(java.util.List<String> keys, String nextAfter) {}
    record Multipart(String key, String uploadId) {}
    record MultipartPage(java.util.List<Multipart> uploads, String nextKey, String nextUpload) {}

    record Metadata(long size, String contentType) {}
}
