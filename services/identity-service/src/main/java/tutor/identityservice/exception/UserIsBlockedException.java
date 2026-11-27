package tutor.identityservice.exception;

public class UserIsBlockedException extends RuntimeException {

    public UserIsBlockedException() {
        super("Пользователь заблокирован");
    }

    public UserIsBlockedException(Throwable cause) {
        super("Пользователь заблокирован", cause);
    }
}
