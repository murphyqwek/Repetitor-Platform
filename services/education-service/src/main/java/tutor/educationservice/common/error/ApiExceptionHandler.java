package tutor.educationservice.common.error;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tutor.educationservice.integration.storage.StorageException;
import tutor.educationservice.schedule.ScheduleConflictException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ScheduleConflictException.class)
    ResponseEntity<Object> conflicts(ScheduleConflictException exception) {
        var body = Problems.body(exception.status(), exception.code(), exception.getMessage());
        body.setProperty("conflicts", exception.conflicts());
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> applicationError(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden() {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .cacheControl(CacheControl.noStore()).body(Problems.body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> invalidParameters() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request parameters");
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<Object> storageError(StorageException exception) {
        return exception.reason() == StorageException.Reason.NOT_FOUND
                ? response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found")
                : response(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Object storage is unavailable");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedError() {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return response(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", "Request validation failed");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus httpStatus = HttpStatus.valueOf(status.value());
        String code = switch (httpStatus) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case CONTENT_TOO_LARGE -> "FILE_TOO_LARGE";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case SERVICE_UNAVAILABLE -> "DEPENDENCY_UNAVAILABLE";
            default -> "HTTP_" + status.value();
        };
        return ResponseEntity.status(status).headers(headers).cacheControl(CacheControl.noStore())
                .body(Problems.body(httpStatus, code, httpStatus.getReasonPhrase()));
    }

    private static ResponseEntity<Object> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Problems.body(status, code, message));
    }
}
