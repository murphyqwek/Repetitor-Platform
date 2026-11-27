package tutor.identityservice.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tutor.identityservice.dto.*;
import tutor.identityservice.service.StudentRegistrationService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StudentRequestController {
    private final StudentRegistrationService students;

    @PostMapping("/student-requests")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<StudentRequestDTO> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            @Valid @RequestBody CreateStudentRequestDTO body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(students.create(UUID.fromString(jwt.getSubject()), key, body));
    }

    @GetMapping("/student-requests/my")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<PageDTO<StudentRequestDTO>> mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(students.mine(UUID.fromString(jwt.getSubject()), page, size));
    }

    @GetMapping("/student-requests")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<PageDTO<StudentRequestDTO>> pending(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(students.pending(page, size));
    }

    @PostMapping("/student-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<StudentRequestDTO> approve(@PathVariable @Positive Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(students.decide(id, true));
    }

    @PostMapping("/student-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<StudentRequestDTO> reject(@PathVariable @Positive Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(students.decide(id, false));
    }

    @GetMapping("/students/{id}/credentials")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<StudentRegistrationService.Credentials> credentials(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(students.credentials(UUID.fromString(jwt.getSubject()), id));
    }
}
