package tutor.educationservice.material;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tutor.educationservice.cleanup.CleanupHandler;
import tutor.educationservice.integration.storage.ObjectStorage;

@Configuration(proxyBeanMethods = false)
class MaterialCleanup {
    @Bean
    CleanupHandler materialFileCleanup(JdbcTemplate jdbc, ObjectStorage storage) {
        return new CleanupHandler() {
            public ResourceType type() { return ResourceType.MATERIAL_FILE; }
            public void removeObjects(UUID id) {
                jdbc.queryForList("select object_key from material_file where id = ? and state = 'DELETING'", String.class, id)
                        .forEach(storage::delete);
            }
            public void removeRows(UUID id) {
                jdbc.update("delete from material_file where id = ? and state = 'DELETING'", id);
            }
        };
    }

    @Bean
    CleanupHandler materialFolderCleanup(JdbcTemplate jdbc, ObjectStorage storage, MaterialRepository materials) {
        return new CleanupHandler() {
            public ResourceType type() { return ResourceType.MATERIAL_FOLDER; }
            public void removeObjects(UUID id) {
                jdbc.queryForList(MaterialDeletionService.TREE + """
                        select f.object_key from material_file f join tree t on f.folder_id = t.id where f.state = 'DELETING'
                        """, String.class, id).forEach(storage::delete);
            }
            public void removeRows(UUID id) {
                var folder = materials.folder(id);
                if (folder == null || !folder.state().equals("DELETING")) return;
                materials.lockLibrary(folder.owner());
                jdbc.update(MaterialDeletionService.TREE + "delete from material_file where folder_id in (select id from tree)", id);
                jdbc.update(MaterialDeletionService.TREE + "delete from material_folder where id in (select id from tree)", id);
            }
        };
    }
}
