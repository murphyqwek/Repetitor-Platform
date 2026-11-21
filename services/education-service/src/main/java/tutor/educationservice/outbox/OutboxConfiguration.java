package tutor.educationservice.outbox;

import java.util.HashMap;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "education.outbox.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class OutboxConfiguration {
    public static Producer<String, String> producer(OutboxProperties properties) {
        var config = new HashMap<String, Object>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.min(2000, properties.sendTimeoutMs() / 2));
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, properties.sendTimeoutMs());
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.sendTimeoutMs());
        return new KafkaProducer<>(config);
    }

    @Bean(destroyMethod = "close")
    Producer<String, String> outboxProducer(OutboxProperties properties) { return producer(properties); }

    @Bean
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager, Producer<String, String> producer, OutboxProperties properties) {
        return new OutboxPublisher(jdbc, manager, producer, properties);
    }
}
