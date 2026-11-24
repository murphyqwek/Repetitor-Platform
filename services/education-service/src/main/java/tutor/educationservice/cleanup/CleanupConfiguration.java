package tutor.educationservice.cleanup;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "education.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CleanupProperties.class)
@EnableScheduling
public class CleanupConfiguration {
    @Bean
    CleanupWorker cleanupWorker(CleanupJobs jobs, List<CleanupHandler> handlers, CleanupProperties properties, MeterRegistry meters) {
        return new CleanupWorker(jobs, handlers, properties, meters);
    }
}
