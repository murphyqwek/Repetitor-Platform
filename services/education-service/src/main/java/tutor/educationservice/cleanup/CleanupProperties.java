package tutor.educationservice.cleanup;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("education.cleanup")
public record CleanupProperties(@DefaultValue("3m") Duration lease, @DefaultValue("20") @Min(1) int batchSize,
                                @DefaultValue("10") @Min(1) int maxAttempts,
                                @DefaultValue("1s") Duration retryDelay, @DefaultValue("5m") Duration maxRetryDelay) {
    public CleanupProperties {
        if (lease.toMillis() < 1 || retryDelay.toMillis() < 1 || maxRetryDelay.compareTo(retryDelay) < 0) {
            throw new IllegalArgumentException("Invalid cleanup delay configuration");
        }
    }
}
