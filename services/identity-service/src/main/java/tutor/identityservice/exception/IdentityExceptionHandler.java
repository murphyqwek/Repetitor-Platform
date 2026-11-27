package tutor.identityservice.exception;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class IdentityExceptionHandler {
    @ExceptionHandler({InvitationCodeExcpetion.class, RegistrationLoginTakenException.class})
    public ResponseEntity<Map<String, Object>> registration(RuntimeException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(Map.of("status", 400, "error", exception.getMessage()));
    }
}
