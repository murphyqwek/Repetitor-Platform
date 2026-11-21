package tutor.educationservice.integration.storage;

public class StorageException extends RuntimeException {
    public enum Reason { NOT_FOUND, UNAVAILABLE }

    private final Reason reason;

    public StorageException(Reason reason) {
        // SDK messages/causes may contain object keys, server responses or credentials.
        super(reason == Reason.NOT_FOUND ? "Stored object not found" : "Object storage operation failed");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
