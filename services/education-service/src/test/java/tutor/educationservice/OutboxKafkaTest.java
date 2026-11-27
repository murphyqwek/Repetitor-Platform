package tutor.educationservice;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tutor.educationservice.outbox.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OutboxKafkaTest {
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.1");
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    private final UUID course = UUID.randomUUID();
    private final String topic = "schedule-" + UUID.randomUUID();
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private Producer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private OutboxProperties properties;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate();
    }

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        jdbc.update("delete from outbox_event");
        properties = new OutboxProperties(kafka.getBootstrapServers(), topic, 20, 5000);
        try (var admin = Admin.create(Map.of("bootstrap.servers", properties.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        producer = OutboxConfiguration.producer(properties);
        consumer = new KafkaConsumer<>(Map.of("bootstrap.servers", properties.bootstrapServers(), "enable.auto.commit", false),
                new StringDeserializer(), new StringDeserializer());
        var partition = new TopicPartition(topic, 0);
        consumer.assign(List.of(partition));
        consumer.seek(partition, 0);
    }

    @AfterEach void close() {
        if (consumer != null) consumer.close(Duration.ofSeconds(3));
        if (producer != null) producer.close(Duration.ofSeconds(3));
    }

    @Test void brokerOutageLeavesPendingForRestartedPublisher() {
        UUID id = enqueue();
        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            assertThatThrownBy(() -> publisher().publishOne()).isInstanceOf(IllegalStateException.class);
            assertThat(pending()).isOne();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
        producer.close(Duration.ZERO);
        producer = OutboxConfiguration.producer(properties);
        assertThat(publisher().publishOne()).isTrue();
        assertThat(pending()).isZero();
        assertThat(receive().value()).contains(id.toString());
    }

    @Test void rollbackAfterAckRedeliversSameEventIdAndPayload() {
        UUID id = enqueue();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(publisher().publishOne()).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(pending()).isOne();
        var first = receive();
        assertThat(first.key()).isEqualTo(course.toString());
        assertThat(first.value()).contains(id.toString());
        assertThat(publisher().publishOne()).isTrue();
        var retry = receive();
        assertThat(retry.offset()).isGreaterThan(first.offset());
        assertThat(retry.value()).isEqualTo(first.value());
        assertThat(retry.key()).isEqualTo(first.key());
        assertThat(pending()).isZero();
    }

    @Test void twoPublishersSkipLockedEventAndDeliverBothRows() throws Exception {
        UUID first = enqueue();
        UUID second = enqueue();
        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                assertThat(publisher().publishOne()).isTrue();
                var concurrent = executor.submit(() -> publisher().publishOne());
                try { assertThat(concurrent.get(12, TimeUnit.SECONDS)).isTrue(); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
        } finally { executor.shutdownNow(); }
        assertThat(pending()).isZero();
        assertThat(publisher().publishOne()).isFalse();
        assertThat(consumer.endOffsets(List.of(new TopicPartition(topic, 0))).values()).containsExactly(2L);
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 2; i++) {
            var payload = new tools.jackson.databind.ObjectMapper().readTree(receive().value());
            ids.add(UUID.fromString(payload.path("eventId").asString()));
        }
        assertThat(ids).containsExactlyInAnyOrder(first, second);
    }

    @Test void rolledBackInsertNeverReachesKafka() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> { enqueue(); tx.setRollbackOnly(); });
        assertThat(publisher().publishOne()).isFalse();
        assertThat(consumer.endOffsets(List.of(new TopicPartition(topic, 0))).values()).containsExactly(0L);
    }

    private UUID enqueue() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into outbox_event(id,aggregate_id,event_type,payload,created_at)
                values (?,?,'LessonCancelled',jsonb_build_object('eventId',cast(? as uuid)),clock_timestamp())
                """, id, course, id);
        return id;
    }

    private OutboxPublisher publisher() { return new OutboxPublisher(jdbc, manager, producer, properties); }
    private int pending() { return jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Integer.class); }

    private ConsumerRecord<String, String> receive() {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(300));
            if (!records.isEmpty()) {
                var row = records.iterator().next();
                consumer.seek(new TopicPartition(topic, 0), row.offset() + 1);
                return row;
            }
        }
        throw new AssertionError("Timed out waiting for Kafka record");
    }
}
