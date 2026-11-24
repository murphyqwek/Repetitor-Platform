package tutor.educationservice.cleanup;

import java.util.UUID;

public interface CleanupHandler {
    enum ResourceType { COURSE, CHAT_MESSAGE, MATERIAL_FILE, MATERIAL_FOLDER, PENDING_UPLOAD, CHAT_ATTACHMENT }

    ResourceType type();
    void removeObjects(UUID resourceId);
    void removeRows(UUID resourceId);
}
