package tutor.identityservice.controller;

import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tutor.identityservice.entity.Role;
import tutor.identityservice.service.DirectoryService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/directory/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
public class DirectoryController {
    private final DirectoryService directory;

    @GetMapping("/{id}")
    public ResponseEntity<DirectoryService.User> lookup(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.lookup(id));
    }

    @GetMapping
    public ResponseEntity<DirectoryService.Result> search(@RequestParam Role type,
            @RequestParam @NotBlank @Size(max = 255) String fio,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 4096) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.search(type, fio, limit, cursor));
    }
}
