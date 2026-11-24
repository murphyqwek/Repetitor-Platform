package tutor.educationservice.cleanup;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tutor.educationservice.cleanup.CleanupHandler.ResourceType;

public class CleanupWorker {
    private static final Logger log = LoggerFactory.getLogger(CleanupWorker.class);

    private final CleanupJobs jobs;
    private final Map<ResourceType, CleanupHandler> handlers;
    private final List<String> types;
    private final CleanupProperties properties;
    private final MeterRegistry meters;

    public CleanupWorker(CleanupJobs jobs, List<CleanupHandler> handlers, CleanupProperties properties, MeterRegistry meters) {
        this.jobs = jobs;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(CleanupHandler::type, Function.identity()));
        this.types = this.handlers.keySet().stream().map(Enum::name).toList();
        this.properties = properties;
        this.meters = meters;
        for (String status : List.of("PENDING", "PROCESSING", "FAILED")) {
            Gauge.builder("education.cleanup.jobs", jobs, queue -> queue.count(status)).tag("status", status).register(meters);
        }
        Gauge.builder("education.cleanup.oldest.seconds", jobs, CleanupJobs::oldestPendingSeconds).register(meters);
    }

    @Scheduled(fixedDelayString = "${education.cleanup.poll-delay:2s}")
    public void poll() {
        if (types.isEmpty()) return;
        for (int i = 0; i < properties.batchSize(); i++) {
            var claimed = jobs.claim(properties.lease(), 1, types);
            if (claimed.isEmpty()) return;
            var job = claimed.get(0);
            Timer.Sample timer = Timer.start(meters);
            try {
                var handler = handlers.get(job.type());
                handler.removeObjects(job.resourceId());
                jobs.complete(job, handler);
            } catch (RuntimeException exception) {
                boolean terminal = job.attempts() >= properties.maxAttempts();
                String error = exception.getClass().getSimpleName();
                if (jobs.fail(job, terminal, backoff(job.attempts()), error)) {
                    meters.counter("education.cleanup.errors", "terminal", Boolean.toString(terminal)).increment();
                    log.warn("Cleanup {} failed for {} {} on attempt {} (terminal={}, error={})",
                            job.id(), job.type(), job.resourceId(), job.attempts(), terminal, error);
                }
            } finally {
                timer.stop(meters.timer("education.cleanup.duration", "type", job.type().name()));
            }
        }
    }

    private Duration backoff(int attempts) {
        long cap = properties.maxRetryDelay().toMillis();
        long delay = properties.retryDelay().toMillis();
        for (int i = 1; i < attempts && delay < cap; i++) delay += Math.min(delay, cap - delay);
        return Duration.ofMillis(delay - ThreadLocalRandom.current().nextLong(delay / 4 + 1));
    }
}
