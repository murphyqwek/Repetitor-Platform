package tutor.assignmentservice.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tutor.assignmentservice.bank.TaskCleanup;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "assignment.files.cleanup-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
class FileMaintenanceScheduler {
    private final FileCleanup cleanup;
    private final TaskCleanup tasks;
    FileMaintenanceScheduler(FileCleanup cleanup, TaskCleanup tasks) {
        this.cleanup = cleanup;
        this.tasks = tasks;
    }

    @Scheduled(fixedDelayString = "${assignment.files.cleanup-delay-ms:10000}", initialDelay = 10000)
    void deleteExpired() { execute(cleanup::runOnce); }

    @Scheduled(fixedDelayString = "${assignment.files.cleanup-delay-ms:10000}", initialDelay = 10000)
    void deleteTasks() {
        execute(() -> {
            for (int count = 0; count < 10 && tasks.runOne(); count++) {
                // Each job commits separately.
            }
        });
    }

    @Scheduled(fixedDelayString = "${assignment.files.reconciliation-delay-ms:60000}", initialDelay = 30000)
    void reconcile() { execute(cleanup::reconcileOnce); }

    private static void execute(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("File maintenance pass failed, error={}", ex.getClass().getSimpleName());
        }
    }
}
