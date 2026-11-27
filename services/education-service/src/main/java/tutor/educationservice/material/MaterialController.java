package tutor.educationservice.material;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tutor.educationservice.cleanup.DeletionReceipt;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.integration.storage.FileDownload;

@RestController
@RequestMapping("/api/v1/materials")
public class MaterialController {
    public record NewFolder(@NotBlank String name, UUID parentId, UUID teacherId) {}
    public record Rename(@NotBlank String name) {}
    public record FilePatch(String name, JsonNode folderId) {}

    private final MaterialService materials;
    private final FileDownload downloads;
    private final MaterialDeletionService deletions;

    public MaterialController(MaterialService materials, FileDownload downloads, MaterialDeletionService deletions) {
        this.materials = materials;
        this.downloads = downloads;
        this.deletions = deletions;
    }

    @GetMapping
    public ResponseEntity<MaterialService.Contents> list(@RequestParam(defaultValue = "root") String parentId,
            @RequestParam(required = false) UUID teacherId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(materials.list(parentId.equals("root") ? null : uuid(parentId), teacherId));
    }

    @PostMapping("/folders")
    public ResponseEntity<MaterialFolder> createFolder(@Valid @RequestBody NewFolder body, @AuthenticationPrincipal Jwt jwt) {
        var folder = materials.createFolder(body.name(), body.parentId(), body.teacherId(), jwt.getTokenValue());
        return ResponseEntity.created(URI.create("/api/v1/materials?parentId=" + folder.id())).cacheControl(CacheControl.noStore()).body(folder);
    }

    @PatchMapping("/folders/{folderId}")
    public ResponseEntity<MaterialFolder> renameFolder(@PathVariable UUID folderId, @Valid @RequestBody Rename body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(materials.renameFolder(folderId, body.name()));
    }

    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MaterialFile> upload(@RequestParam MultipartFile file, @RequestParam(required = false) UUID folderId,
            @RequestParam(required = false) UUID teacherId, @AuthenticationPrincipal Jwt jwt) {
        var result = materials.upload(file, folderId, teacherId, jwt.getTokenValue());
        return ResponseEntity.created(URI.create("/api/v1/materials/files/" + result.id() + "/content"))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @PatchMapping("/files/{fileId}")
    public ResponseEntity<MaterialFile> patch(@PathVariable UUID fileId, @RequestBody FilePatch body) {
        JsonNode folder = body.folderId();
        UUID folderId = folder == null || folder.isNull() ? null : uuid(folder.asString());
        if (body.name() == null && folder == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", "At least one field is required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(materials.updateFile(fileId, body.name(), folder != null, folderId));
    }

    @GetMapping("/files/{fileId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID fileId) {
        var file = materials.ownedFile(fileId, false);
        return downloads.response(file.key(), file.dto().name(), file.dto().contentType(), file.dto().sizeBytes());
    }

    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<DeletionReceipt> deleteFile(@PathVariable UUID fileId) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(deletions.file(fileId));
    }

    @DeleteMapping("/folders/{folderId}")
    public ResponseEntity<DeletionReceipt> deleteFolder(@PathVariable UUID folderId) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(deletions.folder(folderId));
    }

    private static UUID uuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (result.toString().equalsIgnoreCase(value)) return result;
        } catch (IllegalArgumentException ignored) {}
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid folder UUID");
    }
}
