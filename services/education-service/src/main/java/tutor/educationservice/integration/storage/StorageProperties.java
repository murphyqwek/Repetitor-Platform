package tutor.educationservice.integration.storage;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("education.storage")
public record StorageProperties(String endpoint, String bucket, String accessKey, String secretKey) {
    public StorageProperties {
        requireText(endpoint, "endpoint");
        requireText(bucket, "bucket");
        requireText(accessKey, "access-key");
        requireText(secretKey, "secret-key");
        try {
            URI uri = URI.create(endpoint);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("education.storage.endpoint must be an HTTP(S) origin without credentials");
        }
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
                || bucket.contains(".-") || bucket.contains("-.") || bucket.matches("[0-9]+(\\.[0-9]+){3}")) {
            throw new IllegalArgumentException("education.storage.bucket must be a valid S3 bucket name");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw new IllegalArgumentException("education.storage." + name + " is required");
        }
    }

    @Override
    public String toString() {
        return "StorageProperties[redacted]";
    }
}
