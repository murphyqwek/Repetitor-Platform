package tutor.educationservice.material;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tutor.educationservice.integration.storage.FileDownload;

@RestController
@RequestMapping("/api/v1")
public class MaterialAccessController {
    public record FileRule(@NotNull Boolean allowRead) {}

    private final MaterialAccessService access;
    private final FileDownload downloads;

    public MaterialAccessController(MaterialAccessService access, FileDownload downloads) {
        this.access = access;
        this.downloads = downloads;
    }

    @GetMapping("/materials/folders/{folderId}/course-access")
    public ResponseEntity<List<UUID>> folderGrants(@PathVariable UUID folderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.folderGrants(folderId));
    }

    @PutMapping("/materials/folders/{folderId}/course-access/{courseId}")
    public ResponseEntity<Void> grantFolder(@PathVariable UUID folderId, @PathVariable UUID courseId) {
        access.setFolderGrant(folderId, courseId, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/materials/folders/{folderId}/course-access/{courseId}")
    public ResponseEntity<Void> revokeFolder(@PathVariable UUID folderId, @PathVariable UUID courseId) {
        access.setFolderGrant(folderId, courseId, false);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/materials/files/{fileId}/course-access")
    public ResponseEntity<List<MaterialAccessService.Rule>> fileRules(@PathVariable UUID fileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.fileRules(fileId));
    }

    @PutMapping("/materials/files/{fileId}/course-access/{courseId}")
    public ResponseEntity<Void> setFileRule(@PathVariable UUID fileId, @PathVariable UUID courseId, @Valid @RequestBody FileRule body) {
        access.setFileRule(fileId, courseId, body.allowRead());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/materials/files/{fileId}/course-access/{courseId}")
    public ResponseEntity<Void> clearFileRule(@PathVariable UUID fileId, @PathVariable UUID courseId) {
        access.setFileRule(fileId, courseId, null);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/courses/{courseId}/materials")
    public ResponseEntity<MaterialAccessService.Page> list(@PathVariable UUID courseId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.list(courseId, limit, cursor));
    }

    @GetMapping("/courses/{courseId}/materials/files/{fileId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID courseId, @PathVariable UUID fileId) {
        var file = access.download(courseId, fileId);
        return downloads.response(file.key(), file.dto().name(), file.dto().contentType(), file.dto().sizeBytes());
    }
}
