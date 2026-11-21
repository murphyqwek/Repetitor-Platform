package tutor.educationservice.outbox;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("education.outbox")
public record OutboxProperties(@NotBlank String bootstrapServers, @NotBlank String topic,
                               @Min(1) @Max(100) int batchSize, @Min(1000) @Max(30000) int sendTimeoutMs) {}
