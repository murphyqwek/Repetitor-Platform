package tutor.assignmentservice.storage;

import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tutor.assignmentservice.security.CurrentUser;

@RestController
@RequestMapping("/api/v1/tasks/images")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class ImageController {
    private final FileService service;
    private final StoredFiles files;
    private final FileDownload downloads;

    public ImageController(FileService service, StoredFiles files, FileDownload downloads) {
        this.service = service;
        this.files = files;
        this.downloads = downloads;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<FileService.Uploaded> upload(@RequestPart("file") MultipartFile file) {
        var result = service.upload(file, CurrentUser.get());
        return ResponseEntity.created(URI.create("/api/v1/tasks/images/" + result.id() + "/content")).body(result);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> preview(@PathVariable UUID id) {
        var file = files.preview(id, CurrentUser.get());
        return downloads.response(file.key(), file.name(), file.type(), file.size());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        files.delete(id, CurrentUser.get());
        return ResponseEntity.noContent().build();
    }
}
