package tutor.educationservice.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tutor.educationservice.course.Course.ChatMode;
import tutor.educationservice.cleanup.DeletionReceipt;
import tutor.educationservice.integration.storage.FileDownload;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/chat")
public class ChatController {
    public record SettingsRequest(@NotNull ChatMode mode) {}

    private final ChatHistory history;
    private final ChatAttachments attachments;
    private final FileDownload downloads;
    private final ChatModeration moderation;

    public ChatController(ChatHistory history, ChatAttachments attachments, FileDownload downloads, ChatModeration moderation) {
        this.history = history;
        this.attachments = attachments;
        this.downloads = downloads;
        this.moderation = moderation;
    }

    @GetMapping("/settings")
    public ResponseEntity<ChatHistory.Settings> settings(@PathVariable UUID courseId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.settings(courseId));
    }

    @PatchMapping("/settings")
    public ResponseEntity<ChatHistory.Settings> settings(@PathVariable UUID courseId, @Valid @RequestBody SettingsRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.changeMode(courseId, body.mode()));
    }

    @GetMapping("/messages")
    public ResponseEntity<ChatHistory.Page> messages(@PathVariable UUID courseId,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String before) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.messages(courseId, limit, before));
    }

    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatAttachments.Ready> upload(@PathVariable UUID courseId, @RequestParam MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(attachments.upload(courseId, file));
    }

    @GetMapping("/messages/{messageId}/attachments/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID courseId, @PathVariable UUID messageId, @PathVariable UUID attachmentId) {
        var file = attachments.download(courseId, messageId, attachmentId);
        return downloads.response(file.key(), file.name(), file.type(), file.size());
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<DeletionReceipt> delete(@PathVariable UUID courseId, @PathVariable UUID messageId) {
        var receipt = moderation.delete(courseId, messageId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(receipt);
    }
}
