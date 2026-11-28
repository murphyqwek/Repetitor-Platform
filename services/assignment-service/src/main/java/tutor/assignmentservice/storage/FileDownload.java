package tutor.assignmentservice.storage;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class FileDownload {
    private final ObjectStorage storage;

    public FileDownload(ObjectStorage storage) { this.storage = storage; }

    public ResponseEntity<InputStreamResource> response(String key, String name, String type, long size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(type)).contentLength(size)
                .body(new InputStreamResource(storage.get(key)));
    }
}
