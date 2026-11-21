package tutor.educationservice.integration.storage;

import java.io.InputStream;

public interface ObjectStorage {
    // put owns/closes body, including on failure. size >= 0 is exact; -1 uses 10 MiB multipart chunks.
    void put(String objectKey, InputStream body, long size, String contentType);
    // Caller owns and must close the returned stream.
    InputStream get(String objectKey);
    void delete(String objectKey);
    boolean exists(String objectKey);
}
