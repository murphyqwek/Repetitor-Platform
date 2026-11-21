package tutor.educationservice.integration.storage;

import java.util.Objects;
import java.util.UUID;

public final class ObjectKeys {
    private ObjectKeys() {}

    public static String material() {
        return "materials/" + UUID.randomUUID();
    }

    public static String chat(UUID courseId) {
        return "chat/" + Objects.requireNonNull(courseId) + "/" + UUID.randomUUID();
    }

    public static String temporary() {
        return "tmp/" + UUID.randomUUID();
    }
}
