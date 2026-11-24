package tutor.educationservice.material;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.cleanup.*;
import tutor.educationservice.cleanup.CleanupHandler.ResourceType;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.security.PermissionService;

@Service
class MaterialDeletionService {
    static final String TREE = """
            with recursive tree(id) as (
                select id from material_folder where id = ?
                union all
                select f.id from material_folder f join tree t on f.parent_id = t.id
            )
            """;

    private final JdbcTemplate jdbc;
    private final MaterialRepository materials;
    private final PermissionService permissions;
    private final CleanupJobs jobs;

    MaterialDeletionService(JdbcTemplate jdbc, MaterialRepository materials, PermissionService permissions, CleanupJobs jobs) {
        this.jdbc = jdbc;
        this.materials = materials;
        this.permissions = permissions;
        this.jobs = jobs;
    }

    @Transactional
    public DeletionReceipt file(UUID id) {
        permissions.requireTeacherOrAdmin();
        var file = materials.file(id);
        if (file == null) throw MaterialService.notFound();
        permissions.requireOwnerOrAdmin(file.owner());
        materials.lockLibrary(file.owner());
        var rows = jdbc.query("select cleanup_job_id from material_file where id = ? for update",
                (row, index) -> row.getObject(1, UUID.class), id);
        if (rows.isEmpty()) throw MaterialService.notFound();
        if (rows.get(0) != null) return new DeletionReceipt(id, rows.get(0));
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from material_reference where file_id = ?)", Boolean.class, id))) {
            throw inUse();
        }
        UUID job = jobs.enqueue(ResourceType.MATERIAL_FILE, id);
        jdbc.update("update material_file set state = 'DELETING', cleanup_job_id = ? where id = ?", job, id);
        return new DeletionReceipt(id, job);
    }

    @Transactional
    public DeletionReceipt folder(UUID id) {
        permissions.requireTeacherOrAdmin();
        var folder = materials.folder(id);
        if (folder == null) throw MaterialService.notFound();
        permissions.requireOwnerOrAdmin(folder.owner());
        materials.lockLibrary(folder.owner());
        var rows = jdbc.query("select cleanup_job_id from material_folder where id = ? for update",
                (row, index) -> row.getObject(1, UUID.class), id);
        if (rows.isEmpty()) throw MaterialService.notFound();
        if (rows.get(0) != null) return new DeletionReceipt(id, rows.get(0));
        jdbc.queryForList(TREE + "select f.id from material_file f join tree t on f.folder_id = t.id order by f.id for update of f", UUID.class, id);
        if (Boolean.TRUE.equals(jdbc.queryForObject(TREE + """
                select exists(select 1 from material_reference r join material_file f on f.id = r.file_id join tree t on f.folder_id = t.id)
                """, Boolean.class, id))) throw inUse();
        UUID job = jobs.enqueue(ResourceType.MATERIAL_FOLDER, id);
        jdbc.update(TREE + "update material_file set state = 'DELETING', cleanup_job_id = ? where folder_id in (select id from tree)", id, job);
        jdbc.update(TREE + "update material_folder set state = 'DELETING', cleanup_job_id = ? where id in (select id from tree)", id, job);
        return new DeletionReceipt(id, job);
    }

    private static ApiException inUse() {
        return new ApiException(HttpStatus.CONFLICT, "MATERIAL_IN_USE", "Material is referenced by an assignment");
    }
}
