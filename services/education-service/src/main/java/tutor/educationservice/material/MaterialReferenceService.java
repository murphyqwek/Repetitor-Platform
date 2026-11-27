package tutor.educationservice.material;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.common.error.ApiException;

@Service
class MaterialReferenceService {
    record Registration(UUID referenceId, UUID fileId, boolean registered) {}
    record Result(Registration body, boolean created) {}
    private record Reference(UUID file, UUID entity) {}

    private final JdbcTemplate jdbc;

    MaterialReferenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Result register(UUID file, UUID id, UUID entity) {
        var state = jdbc.queryForList("select state from material_file where id = ? for update", String.class, file);
        if (state.isEmpty()) throw MaterialService.notFound();
        if (!state.get(0).equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "MATERIAL_DELETING", "Material is being deleted");
        }
        int inserted = jdbc.update("""
                insert into material_reference(id, file_id, consumer_service, consumer_entity_id)
                values (?, ?, 'ASSIGNMENT', ?) on conflict do nothing
                """, id, file, entity);
        if (inserted == 1) return new Result(new Registration(id, file, true), true);
        var byId = jdbc.query("select file_id, consumer_entity_id from material_reference where id = ?",
                (row, index) -> new Reference(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), id);
        if (!byId.isEmpty() && !byId.get(0).equals(new Reference(file, entity))) {
            throw new ApiException(HttpStatus.CONFLICT, "REFERENCE_ID_CONFLICT", "Reference ID already has a different assignment");
        }
        UUID registered = jdbc.queryForObject("""
                select id from material_reference where file_id = ? and consumer_service = 'ASSIGNMENT' and consumer_entity_id = ?
                """, UUID.class, file, entity);
        return new Result(new Registration(registered, file, true), false);
    }

    @Transactional
    public void release(UUID file, UUID id) {
        jdbc.queryForList("select id from material_file where id = ? for update", UUID.class, file);
        jdbc.update("delete from material_reference where file_id = ? and id = ? and consumer_service = 'ASSIGNMENT'", file, id);
    }
}
