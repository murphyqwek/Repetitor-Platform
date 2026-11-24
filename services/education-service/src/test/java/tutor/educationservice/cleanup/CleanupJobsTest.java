package tutor.educationservice.cleanup;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tutor.educationservice.cleanup.CleanupHandler.ResourceType;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CleanupJobsTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    static final Duration lease = Duration.ofMinutes(1);
    static final List<String> types = List.of(ResourceType.COURSE.name());
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static CleanupJobs jobs;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jobs = new CleanupJobs(jdbc);
    }

    @BeforeEach
    void reset() {
        jdbc.update("delete from cleanup_job");
        jdbc.execute("create table if not exists cleanup_fixture (id uuid primary key)");
        jdbc.update("delete from cleanup_fixture");
    }

    @Test
    void concurrentWorkersGetDisjointJobsAndDuplicateEnqueueReturnsSameId() throws Exception {
        UUID resource = UUID.randomUUID();
        UUID first = enqueue(resource);
        assertThat(enqueue(resource)).isEqualTo(first);
        UUID second = enqueue(UUID.randomUUID());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var claimed = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var left = executor.submit(() -> transaction.execute(status -> {
                var job = jobs.claim(lease, 1, types).get(0);
                claimed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Claim timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return job.id();
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            var right = executor.submit(() -> jobs.claim(lease, 1, types).get(0).id());
            UUID other = right.get(10, TimeUnit.SECONDS);
            release.countDown();
            assertThat(List.of(left.get(10, TimeUnit.SECONDS), other)).containsExactlyInAnyOrder(first, second);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredLeaseRecoversAfterObjectDeleteAndFencesOldWorker() {
        UUID resource = UUID.randomUUID();
        jdbc.update("insert into cleanup_fixture values (?)", resource);
        UUID id = enqueue(resource);
        var handler = new Handler();
        var old = jobs.claim(lease, 1, types).get(0);
        handler.removeObjects(resource);
        jdbc.update("update cleanup_job set locked_until = clock_timestamp() where id = ?", id);
        var current = jobs.claim(lease, 1, types).get(0);
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(transaction.<Boolean>execute(status -> jobs.complete(old, handler))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from cleanup_fixture", Integer.class)).isEqualTo(1);
        handler.removeObjects(resource);
        assertThat(transaction.<Boolean>execute(status -> jobs.complete(current, handler))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from cleanup_fixture", Integer.class)).isZero();
        assertThat(jobs.count("COMPLETED")).isEqualTo(1);
    }

    @Test
    void failurePreservesRowsAndFailedJobIsVisibleWithoutReenqueuing() {
        UUID resource = UUID.randomUUID();
        jdbc.update("insert into cleanup_fixture values (?)", resource);
        UUID id = enqueue(resource);
        var handler = new Handler();
        handler.available = false;
        var meters = new SimpleMeterRegistry();
        var worker = new CleanupWorker(jobs, List.of(handler),
                new CleanupProperties(lease, 1, 2, Duration.ofSeconds(1), Duration.ofMinutes(1)), meters);
        worker.poll();
        assertThat(jobs.count("PENDING")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from cleanup_fixture", Integer.class)).isEqualTo(1);
        jdbc.update("update cleanup_job set next_attempt_at = clock_timestamp() where id = ?", id);
        worker.poll();
        assertThat(meters.get("education.cleanup.jobs").tag("status", "FAILED").gauge().value()).isEqualTo(1);
        assertThat(enqueue(resource)).isEqualTo(id);
        assertThat(handler.removals.get()).isEqualTo(2);
    }

    private UUID enqueue(UUID resource) {
        return transaction.execute(status -> jobs.enqueue(ResourceType.COURSE, resource));
    }

    private static class Handler implements CleanupHandler {
        final AtomicInteger removals = new AtomicInteger();
        boolean available = true;

        public ResourceType type() { return ResourceType.COURSE; }
        public void removeObjects(UUID resourceId) {
            removals.incrementAndGet();
            if (!available) throw new IllegalStateException("storage unavailable");
        }
        public void removeRows(UUID resourceId) { jdbc.update("delete from cleanup_fixture where id = ?", resourceId); }
    }
}
