package tutor.assignmentservice.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class StorageConfiguration {
    @Bean
    MinioClient minioClient(StorageProperties properties) {
        var httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(60))
                .retryOnConnectionFailure(false)
                .build();
        return MinioClient.builder().endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .httpClient(httpClient).build();
    }

    @Bean
    ObjectStorage objectStorage(MinioClient client, StorageProperties properties) {
        return new MinioObjectStorage(client, properties.bucket());
    }

    @Bean
    HealthIndicator objectStorageHealthIndicator(MinioClient client, StorageProperties properties) {
        return () -> {
            try {
                boolean present = client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
                return present ? Health.up().build() : Health.down().build();
            } catch (Exception exception) {
                return Health.down().build();
            }
        };
    }
}
