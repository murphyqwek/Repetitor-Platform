package tutor.assignmentservice.storage;

public final class StorageException extends RuntimeException {
    public enum Reason { NOT_FOUND, ALREADY_EXISTS, UNAVAILABLE }

    private final Reason reason;

    StorageException(Reason reason) {
        super(switch (reason) {
            case NOT_FOUND -> "Stored object not found";
            case ALREADY_EXISTS -> "Stored object already exists";
            case UNAVAILABLE -> "Object storage unavailable";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
