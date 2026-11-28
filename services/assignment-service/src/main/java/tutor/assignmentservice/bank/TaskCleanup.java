package tutor.assignmentservice.bank;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.assignmentservice.storage.StoredFiles;

@Service
public class TaskCleanup {
    private final BankTaskRepository tasks;
    private final BankTaskVersionRepository versions;
    private final BankRelationsRepository relations;
    private final BankCleanupRepository cleanup;
    private final StoredFiles files;
    private final Clock clock;

    TaskCleanup(BankTaskRepository tasks, BankTaskVersionRepository versions, BankRelationsRepository relations,
                BankCleanupRepository cleanup, StoredFiles files, Clock clock) {
        this.tasks = tasks;
        this.versions = versions;
        this.relations = relations;
        this.cleanup = cleanup;
        this.files = files;
        this.clock = clock;
    }

    @Transactional
    public boolean runOne() {
        var now = clock.instant();
        var job = cleanup.claim(now).orElse(null);
        if (job == null) return false;

        var task = tasks.lock(job.task()).orElse(null);
        if (task != null) {
            if (!task.deleted()) {
                cleanup.fail(job.id(), now);
                return true;
            }
            relations.clearCopyProvenance(task.id);
            var images = relations.taskImages(task.id);
            files.lockTaskImages(images);
            task.currentVersionId = null;
            tasks.flush();
            versions.deleteVersions(task.id);
            tasks.delete(task);
            files.deleteUnreferencedTaskImages(images);
        }
        cleanup.complete(job.id(), now);
        return true;
    }
}
