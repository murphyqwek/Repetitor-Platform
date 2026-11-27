package tutor.assignmentservice.storage;

public final class StorageException extends RuntimeException {
    public enum Reason { NOT_FOUND, UNAVAILABLE }

    private final Reason reason;

    StorageException(Reason reason) {
        super(reason == Reason.NOT_FOUND ? "Stored object not found" : "Object storage unavailable");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
