package tutor.educationservice.integration.storage;

import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class StorageUnitTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    static class PropertiesConfiguration {}

    @Test
    void keysAreUniqueAndHaveSeparatePrefixes() {
        var keys = new HashSet<String>();
        UUID course = UUID.randomUUID();
        for (int i = 0; i < 1000; i++) {
            assertThat(keys.add(ObjectKeys.material())).isTrue();
            assertThat(keys.add(ObjectKeys.chat(course))).isTrue();
            assertThat(keys.add(ObjectKeys.temporary())).isTrue();
        }
        assertThat(ObjectKeys.material()).matches("materials/[a-f0-9-]{36}");
        assertThat(ObjectKeys.chat(course)).startsWith("chat/" + course + "/");
        assertThat(ObjectKeys.temporary()).matches("tmp/[a-f0-9-]{36}");
        assertThatNullPointerException().isThrownBy(() -> ObjectKeys.chat(null));
    }

    @Test
    void rejectsMissingSecretsAtContextStartupWithoutLeakingOtherCredentials() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues("education.storage.endpoint=http://localhost:9000",
                        "education.storage.bucket=education-private", "education.storage.access-key=private-access")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("education.storage.secret-key is required");
                    var trace = new java.io.StringWriter();
                    context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                    assertThat(trace.toString()).doesNotContain("private-access");
                });
    }

    @Test
    void validatesConfigurationAndRedactsCredentials() {
        var valid = new StorageProperties("http://localhost:9000", "education-private", "access", "secret");
        assertThat(valid.toString()).doesNotContain("access", "secret");
        for (String endpoint : new String[]{"file:///tmp", "http://user:password@localhost:9000", "invalid", "http://localhost/path"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new StorageProperties(endpoint, "bucket", "access", "secret"))
                    .withMessage("education.storage.endpoint must be an HTTP(S) origin without credentials");
        }
        for (String bucket : new String[]{"A", "a..b", "192.168.0.1", "a/b"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new StorageProperties("http://localhost:9000", bucket, "access", "secret"));
        }
    }

    @Test
    void validatesSafeMetadataWithoutApplyingChatLimitsToMaterials() {
        assertThat(new ObjectMetadata(30_000_000, "application/pdf").size()).isEqualTo(30_000_000);
        assertThat(new ObjectMetadata(-1, "application/octet-stream").size()).isEqualTo(-1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ObjectMetadata(-2, "text/plain"));
        for (String type : new String[]{"", "bogus", "*/*", "text/plain\r\nInjected: true"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new ObjectMetadata(0, type));
        }
    }

    @Test
    void closesUploadStreamEvenWhenValidationFails() {
        var properties = new StorageProperties("http://localhost:9000", "bucket", "access", "secret");
        var storage = new MinioObjectStorage(MinioClient.builder().endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey()).build(), properties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var body = new ByteArrayInputStream(new byte[1]) {
            boolean closed;
            @Override
            public void close() {
                closed = true;
            }
        };
        assertThatIllegalArgumentException().isThrownBy(() -> storage.put("../unsafe", body, 1, "text/plain"));
        assertThat(body.closed).isTrue();
    }
}
