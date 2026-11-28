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
    OkHttpClient storageHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(60))
                .retryOnConnectionFailure(false)
                .build();
    }

    @Bean
    MinioClient minioClient(StorageProperties properties, OkHttpClient storageHttpClient) {
        return MinioClient.builder().endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .httpClient(storageHttpClient).build();
    }

    @Bean
    MultipartStorageClient storageMaintenanceClient(StorageProperties properties, OkHttpClient storageHttpClient) {
        return new MultipartStorageClient(io.minio.MinioAsyncClient.builder().endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey()).httpClient(storageHttpClient).build());
    }

    @Bean
    ObjectStorage objectStorage(MinioClient client, MultipartStorageClient maintenance, StorageProperties properties) {
        return new MinioObjectStorage(client, maintenance, properties.bucket());
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
