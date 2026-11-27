package tutor.identityservice.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tutor.identityservice.controller.AuthController;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler({BadCredentialsException.class, AccountStatusException.class})
    public ResponseEntity<Map<String, Object>> handleAuthenticationFailure() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "status", 401,
                        "error", "Неверный логин или пароль, либо аккаунт недоступен"
                ));
    }

    @ExceptionHandler(UserIsBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleUserIsBlocked() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "status", 403,
                        "error", "Пользователь заблокирован"
                ));
    }

    @ExceptionHandler({RefreshTokenException.class})
    public ResponseEntity<Void> handleRefreshTokenFailure() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
