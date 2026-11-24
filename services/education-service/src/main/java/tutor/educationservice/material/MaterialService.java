package tutor.educationservice.material;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.integration.identity.IdentityClient;
import tutor.educationservice.integration.storage.ObjectKeys;
import tutor.educationservice.integration.storage.PendingUploads;
import tutor.educationservice.security.*;

@Service
class MaterialService {
    record Contents(UUID parentId, List<MaterialFolder> folders, List<MaterialFile> files) {}

    private final MaterialRepository materials;
    private final PendingUploads uploads;
    private final IdentityClient identity;
    private final PermissionService permissions;

    MaterialService(MaterialRepository materials, PendingUploads uploads, IdentityClient identity, PermissionService permissions) {
        this.materials = materials;
        this.uploads = uploads;
        this.identity = identity;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public Contents list(UUID parent, UUID teacherId) {
        UUID owner = owner(teacherId);
        requireParent(parent, owner);
        return new Contents(parent, materials.folders(owner, parent), materials.files(owner, parent));
    }

    @Transactional
    public MaterialFolder createFolder(String name, UUID parent, UUID teacherId, String token) {
        UUID owner = checkedOwner(teacherId, token);
        materials.lockLibrary(owner);
        requireParent(parent, owner);
        return materials.createFolder(owner, parent, name(name));
    }

    @Transactional
    public MaterialFolder renameFolder(UUID id, String name) {
        ownedFolder(id, true);
        return materials.renameFolder(id, name(name));
    }

    public MaterialFile upload(MultipartFile body, UUID parent, UUID teacherId, String token) {
        UUID owner = checkedOwner(teacherId, token);
        requireParent(parent, owner);
        String name = name(body.getOriginalFilename());
        String type = PendingUploads.contentType(body.getContentType());
        String key = ObjectKeys.material();
        return uploads.store(key, body, type, () -> {
            materials.lockLibrary(owner);
            requireParent(parent, owner);
            return materials.createFile(owner, parent, name, key, type, body.getSize());
        });
    }

    @Transactional
    public MaterialFile updateFile(UUID id, String name, boolean move, UUID folder) {
        var file = ownedFile(id, true);
        UUID destination = move ? folder : file.dto().folderId();
        requireParent(destination, file.owner());
        return materials.updateFile(id, destination, name == null ? file.dto().name() : name(name));
    }

    MaterialRepository.Folder ownedFolder(UUID id, boolean lock) {
        permissions.requireTeacherOrAdmin();
        var folder = materials.folder(id);
        if (folder == null) throw notFound();
        permissions.requireOwnerOrAdmin(folder.owner());
        if (lock) {
            materials.lockLibrary(folder.owner());
            folder = materials.folder(id);
        }
        if (folder == null || !folder.state().equals("ACTIVE")) throw notFound();
        return folder;
    }

    MaterialRepository.File ownedFile(UUID id, boolean lock) {
        permissions.requireTeacherOrAdmin();
        var file = materials.file(id);
        if (file == null) throw notFound();
        permissions.requireOwnerOrAdmin(file.owner());
        if (lock) {
            materials.lockLibrary(file.owner());
            file = materials.file(id);
        }
        if (file == null || !file.state().equals("ACTIVE")) throw notFound();
        return file;
    }

    private void requireParent(UUID parent, UUID owner) {
        if (parent == null) return;
        var folder = materials.folder(parent);
        if (folder == null || !folder.owner().equals(owner) || !folder.state().equals("ACTIVE")) throw notFound();
    }

    private UUID checkedOwner(UUID teacherId, String token) {
        UUID owner = owner(teacherId);
        if (teacherId != null && !identity.user(token, owner).roles().contains(Role.TEACHER)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_TEACHER", "Library owner must be a teacher");
        }
        return owner;
    }

    private UUID owner(UUID teacherId) {
        if (teacherId != null) {
            permissions.requireRole(Role.ADMIN);
            return teacherId;
        }
        var user = CurrentUser.get();
        permissions.requireTeacherOrAdmin();
        if (!user.roles().contains(Role.TEACHER)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", "teacherId is required for an administrator");
        }
        return user.id();
    }

    static String name(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > 255 || name.codePoints().anyMatch(Character::isISOControl)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.equals(".") || value.equals("..")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", "Invalid material name");
        }
        return value;
    }

    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Material not found"); }
}
