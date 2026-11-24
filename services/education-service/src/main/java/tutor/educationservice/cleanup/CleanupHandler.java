package tutor.educationservice.cleanup;

import java.util.UUID;
import java.util.function.Consumer;

public interface CleanupHandler {
    enum ResourceType { COURSE, CHAT_MESSAGE, MATERIAL_FILE, MATERIAL_FOLDER, PENDING_UPLOAD, CHAT_ATTACHMENT }

    ResourceType type();
    void removeObjects(UUID resourceId, Consumer<String> delete);
    void removeRows(UUID resourceId);
}
