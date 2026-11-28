package tutor.assignmentservice.common.error;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tutor.assignmentservice.storage.StorageException;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> applicationError(ApiException exception) {
        var problem = Problems.body(exception.status(), exception.code(), exception.getMessage());
        exception.properties().forEach(problem::setProperty);
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden() {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .cacheControl(CacheControl.noStore()).body(Problems.body(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> invalidParameters() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request parameters");
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<Object> storageError(StorageException exception) {
        if (exception.reason() == StorageException.Reason.ALREADY_EXISTS) return response(HttpStatus.CONFLICT, "OBJECT_EXISTS", "Object already exists");
        return exception.reason() == StorageException.Reason.NOT_FOUND
                ? response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found")
                : response(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "Object storage is unavailable");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Object> integrityConflict() {
        return response(HttpStatus.CONFLICT, "CONFLICT", "Resource conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpectedError(Exception exception) {
        log.atError().setMessage("Unhandled API error")
                .addKeyValue("requestId", MDC.get("requestId")).addKeyValue("error", exception.getClass().getSimpleName()).log();
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
