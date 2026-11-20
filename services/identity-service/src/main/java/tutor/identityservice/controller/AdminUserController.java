package tutor.identityservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tutor.identityservice.dto.UserStatusUpdateDTO;
import tutor.identityservice.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final UserService users;

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(@PathVariable UUID id,
            @Valid @RequestBody UserStatusUpdateDTO body) {
        users.setBlocked(id, body.blocked());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
