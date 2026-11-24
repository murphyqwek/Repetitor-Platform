package tutor.educationservice.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tutor.educationservice.course.Course.ChatMode;

@RestController
@RequestMapping("/api/v1/courses/{courseId}/chat")
public class ChatController {
    public record SettingsRequest(@NotNull ChatMode mode) {}

    private final ChatHistory history;

    public ChatController(ChatHistory history) { this.history = history; }

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
}
