package tutor.educationservice.material;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MaterialRepository {
    record Folder(MaterialFolder dto, UUID owner, String state) {}
    record File(MaterialFile dto, UUID owner, String key, String state) {}

    private final JdbcTemplate jdbc;

    MaterialRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lockLibrary(UUID owner) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?::text, 105))", row -> {}, owner);
    }

    Folder folder(UUID id) {
        return jdbc.query("select * from material_folder where id = ?", (row, index) -> folder(row), id)
                .stream().findFirst().orElse(null);
    }

    File file(UUID id) {
        return jdbc.query("select * from material_file where id = ?", (row, index) -> file(row), id)
                .stream().findFirst().orElse(null);
    }

    List<MaterialFolder> folders(UUID owner, UUID parent) {
        return jdbc.query("""
                select * from material_folder where teacher_id = ? and parent_id is not distinct from cast(? as uuid)
                    and state = 'ACTIVE' order by name, id
                """, (row, index) -> folder(row).dto(), owner, parent);
    }

    List<MaterialFile> files(UUID owner, UUID folder) {
        return jdbc.query("""
                select * from material_file where teacher_id = ? and folder_id is not distinct from cast(? as uuid)
                    and state = 'ACTIVE' order by name, id
                """, (row, index) -> file(row).dto(), owner, folder);
    }

    MaterialFolder createFolder(UUID owner, UUID parent, String name) {
        return jdbc.queryForObject("""
                insert into material_folder(id, teacher_id, parent_id, name) values (?, ?, ?, ?) returning *
                """, (row, index) -> folder(row).dto(), UUID.randomUUID(), owner, parent, name);
    }

    MaterialFolder renameFolder(UUID id, String name) {
        return jdbc.queryForObject("update material_folder set name = ? where id = ? returning *",
                (row, index) -> folder(row).dto(), name, id);
    }

    MaterialFile createFile(UUID owner, UUID folder, String name, String key, String type, long size) {
        return jdbc.queryForObject("""
                insert into material_file(id, teacher_id, folder_id, name, object_key, mime_type, size_bytes)
                values (?, ?, ?, ?, ?, ?, ?) returning *
                """, (row, index) -> file(row).dto(), UUID.randomUUID(), owner, folder, name, key, type, size);
    }

    MaterialFile updateFile(UUID id, UUID folder, String name) {
        return jdbc.queryForObject("update material_file set folder_id = ?, name = ? where id = ? returning *",
                (row, index) -> file(row).dto(), folder, name, id);
    }

    static Folder folder(ResultSet row) throws SQLException {
        var dto = new MaterialFolder(row.getObject("id", UUID.class), row.getString("name"), row.getObject("parent_id", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
        return new Folder(dto, row.getObject("teacher_id", UUID.class), row.getString("state"));
    }

    static File file(ResultSet row) throws SQLException {
        var dto = new MaterialFile(row.getObject("id", UUID.class), row.getString("name"), row.getObject("folder_id", UUID.class),
                row.getString("mime_type"), row.getLong("size_bytes"), row.getObject("created_at", OffsetDateTime.class).toInstant());
        return new File(dto, row.getObject("teacher_id", UUID.class), row.getString("object_key"), row.getString("state"));
    }
}
