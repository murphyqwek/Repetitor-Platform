package tutor.educationservice.material;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/materials/{fileId}/references")
public class MaterialReferenceController {
    public enum ConsumerService { ASSIGNMENT }
    public record RegistrationRequest(@NotNull UUID referenceId, @NotNull ConsumerService consumerService, @NotNull UUID consumerEntityId) {}

    private final MaterialReferenceService references;

    public MaterialReferenceController(MaterialReferenceService references) { this.references = references; }

    @PostMapping
    public ResponseEntity<MaterialReferenceService.Registration> register(@PathVariable UUID fileId, @Valid @RequestBody RegistrationRequest body) {
        var result = references.register(fileId, body.referenceId(), body.consumerEntityId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore()).body(result.body());
    }

    @DeleteMapping("/{referenceId}")
    public ResponseEntity<Void> release(@PathVariable UUID fileId, @PathVariable UUID referenceId) {
        references.release(fileId, referenceId);
        return ResponseEntity.noContent().build();
    }
}
