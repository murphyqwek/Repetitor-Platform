package tutor.identityservice.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tutor.identityservice.dto.*;
import tutor.identityservice.service.ProfileService;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProfileController {
    private final ProfileService profiles;

    @GetMapping("/users/me")
    public ResponseEntity<ProfileDTO> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.profile(UUID.fromString(jwt.getSubject())));
    }

    @PatchMapping("/users/me")
    public ResponseEntity<ProfileDTO> update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileUpdateDTO body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.update(UUID.fromString(jwt.getSubject()), body));
    }

    @GetMapping("/students/my")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<List<ProfileDTO>> children(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.children(UUID.fromString(jwt.getSubject())));
    }

    @GetMapping("/students/{id}")
    @PreAuthorize("hasRole('PARENT')")
    public ResponseEntity<ProfileDTO> child(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        profiles.requireParent(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.profile(id));
    }
}
