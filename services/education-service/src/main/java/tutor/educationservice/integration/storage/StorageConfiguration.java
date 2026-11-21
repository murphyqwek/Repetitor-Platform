package tutor.educationservice.integration.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {
    @Bean
    MinioClient minioClient(StorageProperties properties) {
        OkHttpClient httpClient = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5)).writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(60)).retryOnConnectionFailure(false).build();
        return MinioClient.builder().endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey()).httpClient(httpClient).build();
    }

    @Bean
    ObjectStorage objectStorage(MinioClient client, StorageProperties properties) {
        return new MinioObjectStorage(client, properties);
    }

    @Bean
    HealthIndicator objectStorageHealthIndicator(MinioClient client, StorageProperties properties) {
        return () -> {
            try {
                return client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())
                        ? Health.up().build() : Health.down().build();
            } catch (Exception exception) {
                return Health.down().build();
            }
        };
    }
}
