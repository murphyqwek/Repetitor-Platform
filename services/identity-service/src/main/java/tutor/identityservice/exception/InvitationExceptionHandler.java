package tutor.identityservice.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tutor.identityservice.controller.InvitationController;

import java.util.Map;

@RestControllerAdvice(assignableTypes = InvitationController.class)
public class InvitationExceptionHandler {

    @ExceptionHandler(InvitationCodeExcpetion.class)
    public ResponseEntity<Map<String, Object>> handleInvitationCodeFailure(InvitationCodeExcpetion exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("status", 400, "error", exception.getMessage()));
    }
}
