package tutor.assignmentservice.bank;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tutor.assignmentservice.exam.CatalogDtos.Page;
import tutor.assignmentservice.security.CurrentUser;
import tutor.assignmentservice.storage.FileDownload;
import static tutor.assignmentservice.bank.BankDtos.*;

@RestController
@RequestMapping("/api/v1/tasks")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN','STUDENT')")
public class TaskController {
    private final TaskBank bank;
    private final TaskQueries queries;
    private final FileDownload downloads;

    public TaskController(TaskBank bank, TaskQueries queries, FileDownload downloads) {
        this.bank = bank;
        this.queries = queries;
        this.downloads = downloads;
    }

    @GetMapping
    public ResponseEntity<Page<Summary>> list(@Valid @ModelAttribute Filter filter,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String cursor) {
        return read(queries.list(filter, limit, cursor, CurrentUser.get()));
    }

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<Receipt> create(@Valid @RequestBody Write input) {
        return created(bank.create(input, CurrentUser.get()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskDetail> detail(@PathVariable UUID id) {
        return read(queries.detail(id, null, CurrentUser.get()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<Receipt> update(@PathVariable UUID id, @Valid @RequestBody Write input) {
        var updated = bank.update(id, input, CurrentUser.get());
        return updated.changed() ? created(updated.receipt()) : read(updated.receipt());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<Deletion> delete(@PathVariable UUID id) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(bank.delete(id, CurrentUser.get()));
    }

    @PostMapping("/{id}/copy")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<Receipt> copy(@PathVariable UUID id, @Valid @RequestBody Copy input) {
        return created(bank.copy(id, input, CurrentUser.get()));
    }

    @PatchMapping("/{id}/import-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImportSettings> importSettings(@PathVariable UUID id, @Valid @RequestBody ImportSettings input) {
        return read(bank.importSettings(id, input, CurrentUser.get()));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<Page<Version>> history(@PathVariable UUID id, @RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) String cursor) {
        return read(queries.history(id, limit, cursor, CurrentUser.get()));
    }

    @GetMapping("/{id}/versions/{version}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<TaskDetail> version(@PathVariable UUID id, @PathVariable UUID version) {
        return read(queries.detail(id, version, CurrentUser.get()));
    }

    @GetMapping("/{id}/versions/{version}/images/{file}/content")
    public ResponseEntity<InputStreamResource> image(@PathVariable UUID id, @PathVariable UUID version, @PathVariable UUID file) {
        var image = queries.image(id, version, file, CurrentUser.get());
        return downloads.response(image.key(), image.name(), image.mimeType(), image.size());
    }

    @GetMapping("/{id}/versions/{version}/publication")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ResponseEntity<Publication> publication(@PathVariable UUID id, @PathVariable UUID version) {
        return read(queries.publication(id, version, CurrentUser.get()));
    }

    private static ResponseEntity<Receipt> created(Receipt receipt) {
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + receipt.id()))
                .cacheControl(CacheControl.noStore()).body(receipt);
    }

    private static <T> ResponseEntity<T> read(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
