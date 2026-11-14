package tutor.identityservice.exception;

public class RefreshTokenException extends RuntimeException {

    public RefreshTokenException() {
        super("Refresh-токен недействителен или истёк");
    }

    public RefreshTokenException(Throwable cause) {
        super("Refresh-токен недействителен или истёк", cause);
    }
}
