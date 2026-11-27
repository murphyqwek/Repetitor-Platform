package tutor.assignmentservice.storage;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("assignment.storage")
record StorageProperties(URI endpoint, String bucket, String accessKey, String secretKey) {
    StorageProperties {
        if (endpoint == null || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("assignment.storage.endpoint must be an HTTP(S) origin");
        }
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("assignment.storage.bucket must be a valid S3 bucket name");
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("assignment.storage requires an access key and a secret key");
        }
    }

    @Override
    public String toString() {
        return "StorageProperties[redacted]";
    }
}
