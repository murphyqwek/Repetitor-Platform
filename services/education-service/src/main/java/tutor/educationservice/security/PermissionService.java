package tutor.educationservice.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {
    public void requireTeacherOrAdmin() {
        var user = CurrentUser.get();
        if (!user.roles().contains(Role.ADMIN) && !user.roles().contains(Role.TEACHER)) deny();
    }

    public void requireOwnerOrAdmin(UUID teacherId) {
        var user = CurrentUser.get();
        if (!user.roles().contains(Role.ADMIN) && !(user.roles().contains(Role.TEACHER) && user.id().equals(teacherId))) deny();
    }

    public void requireRole(Role role) {
        if (!CurrentUser.get().roles().contains(role)) deny();
    }

    private static void deny() { throw new AccessDeniedException("Access denied"); }
}
