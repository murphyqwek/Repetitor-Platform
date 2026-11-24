package tutor.educationservice.material;

import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tutor.educationservice.cleanup.CleanupHandler;

@Configuration(proxyBeanMethods = false)
class MaterialCleanup {
    @Bean
    CleanupHandler materialFileCleanup(JdbcTemplate jdbc) {
        return new CleanupHandler() {
            public ResourceType type() { return ResourceType.MATERIAL_FILE; }
            public void removeObjects(UUID id, Consumer<String> delete) {
                jdbc.queryForList("select object_key from material_file where id = ? and state = 'DELETING'", String.class, id)
                        .forEach(delete);
            }
            public void removeRows(UUID id) {
                jdbc.update("delete from material_file where id = ? and state = 'DELETING'", id);
            }
        };
    }

    @Bean
    CleanupHandler materialFolderCleanup(JdbcTemplate jdbc, MaterialRepository materials) {
        return new CleanupHandler() {
            public ResourceType type() { return ResourceType.MATERIAL_FOLDER; }
            public void removeObjects(UUID id, Consumer<String> delete) {
                jdbc.queryForList(MaterialDeletionService.TREE + """
                        select f.object_key from material_file f join tree t on f.folder_id = t.id where f.state = 'DELETING'
                        """, String.class, id).forEach(delete);
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
