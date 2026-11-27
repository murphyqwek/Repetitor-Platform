package tutor.identityservice.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tutor.identityservice.dto.InvitationDTO;
import tutor.identityservice.dto.PageDTO;
import tutor.identityservice.entity.Role;
import tutor.identityservice.exception.InvitationCodeExcpetion;
import tutor.identityservice.service.InvitationCodeService;
import tutor.identityservice.service.InvitationIdempotencyService.Operation;
import tutor.identityservice.service.InvitationIdempotencyService;
import tutor.identityservice.service.InvitationQueryService;
import tutor.identityservice.service.TeacherRegistrationCodeService;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {
    private final InvitationQueryService queries;
    private final InvitationIdempotencyService invitationIdempotencyService;
    private final InvitationCodeService invitationCodeService;
    private final TeacherRegistrationCodeService teacherRegistrationCodeService;

    public InvitationController(InvitationQueryService queries, InvitationIdempotencyService invitationIdempotencyService, InvitationCodeService invitationCodeService, TeacherRegistrationCodeService teacherRegistrationCodeService) {
        this.queries = queries;
        this.invitationIdempotencyService = invitationIdempotencyService;
        this.invitationCodeService = invitationCodeService;
        this.teacherRegistrationCodeService = teacherRegistrationCodeService;
    }

    @GetMapping("/parents")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<PageDTO<InvitationDTO>> parents(
            @AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.parents(UUID.fromString(jwt.getSubject()), admin, page, size));
    }

    @GetMapping("/teacher")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageDTO<InvitationDTO>> teachers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.teachers(page, size));
    }

    @PostMapping("/parents/{id}/revoke")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<Void> revokeInvitationCode(
            @PathVariable("id") @Positive Long id,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        UUID teacherId = UUID.fromString(jwt.getSubject());
        Role role = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))
                ? Role.ADMIN : Role.TEACHER;

        if (!invitationCodeService.revokeInvitationCode(id, teacherId, role)) {
            throw new InvitationCodeExcpetion("Приглашение не существует");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/teacher/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> revokeTeacherrInvitationCode(@PathVariable("id") @Positive Long id) {
        if (!teacherRegistrationCodeService.revokeInvitationCode(id)) {
            throw new InvitationCodeExcpetion("Приглашение не существует");
        }
        
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/parents")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public ResponseEntity<String> createParentInvitation(
                                                 @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String code = invitationIdempotencyService.create(idempotencyKey, userId, Operation.PARENT_INVITATION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(code);
    }

    @PostMapping("/teacher")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> createTeacherInvitation(
                                                          @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String code = invitationIdempotencyService.create(idempotencyKey, userId, Operation.TEACHER_REGISTRATION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(code);
    }
}
