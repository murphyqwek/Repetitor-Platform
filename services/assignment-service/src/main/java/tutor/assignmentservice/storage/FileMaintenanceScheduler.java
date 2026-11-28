package tutor.assignmentservice.storage;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "assignment.files.cleanup-enabled", havingValue = "true", matchIfMissing = true)
class FileMaintenanceScheduler {
    private final FileCleanup cleanup;
    FileMaintenanceScheduler(FileCleanup cleanup) { this.cleanup = cleanup; }

    @Scheduled(fixedDelayString = "${assignment.files.cleanup-delay-ms:10000}", initialDelay = 10000)
    void deleteExpired() { execute(cleanup::runOnce); }

    @Scheduled(fixedDelayString = "${assignment.files.reconciliation-delay-ms:60000}", initialDelay = 30000)
    void reconcile() { execute(cleanup::reconcileOnce); }

    private static void execute(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(FileMaintenanceScheduler.class).warn("File maintenance pass failed, error={}", ex.getClass().getSimpleName());
        }
    }
}
