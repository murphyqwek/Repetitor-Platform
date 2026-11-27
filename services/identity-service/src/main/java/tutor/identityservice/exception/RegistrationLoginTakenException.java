package tutor.identityservice.exception;

public class RegistrationLoginTakenException extends RuntimeException {

    public RegistrationLoginTakenException() {
        super("Пользователь с таким логином уже существует");
    }

    public RegistrationLoginTakenException(Throwable cause) {
        super("Пользователь с таким логином уже существует", cause);
    }
}
