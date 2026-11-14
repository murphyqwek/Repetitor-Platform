package tutor.identityservice.exception;

public class InvitationCodeExcpetion extends RuntimeException {

    public InvitationCodeExcpetion() {
        super("Код приглашения не существует или истёк");
    }

    public InvitationCodeExcpetion(String message) {
        super(message);
    }
}
