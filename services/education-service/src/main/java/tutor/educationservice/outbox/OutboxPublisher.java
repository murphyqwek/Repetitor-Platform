package tutor.educationservice.outbox;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class OutboxPublisher {
    private record Pending(UUID id, UUID aggregateId, String payload) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Producer<String, String> producer;
    private final OutboxProperties properties;

    public OutboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager, Producer<String, String> producer, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.producer = producer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${education.outbox.poll-delay-ms:1000}")
    public void publishBatch() {
        try {
            for (int i = 0; i < properties.batchSize(); i++) if (!publishOne()) return;
        } catch (RuntimeException exception) {
            log.warn("Outbox publication failed; pending event will be retried");
        }
    }

    public boolean publishOne() {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var rows = jdbc.query("""
                    select id,aggregate_id,payload::text from outbox_event where published_at is null
                    order by created_at,id limit 1 for update skip locked
                    """, (row, index) -> new Pending(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getString(3)));
            if (rows.isEmpty()) return false;
            var event = rows.get(0);
            try {
                producer.send(new ProducerRecord<>(properties.topic(), event.aggregateId().toString(), event.payload()))
                        .get(properties.sendTimeoutMs() + 1000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox publication interrupted");
            } catch (Exception exception) {
                throw new IllegalStateException("Outbox publication not acknowledged");
            }
            jdbc.update("update outbox_event set published_at=clock_timestamp() where id=?", event.id());
            return true;
        }));
    }
}
