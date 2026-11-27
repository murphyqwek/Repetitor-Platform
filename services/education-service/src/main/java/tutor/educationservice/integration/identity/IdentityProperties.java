package tutor.educationservice.integration.identity;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("education.identity")
public record IdentityProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    public IdentityProperties {
        try {
            URI uri = URI.create(baseUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException();
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                    || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0 || readTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("education.identity requires an HTTP(S) origin without credentials and timeouts in (0, 30s]");
        }
    }
}
