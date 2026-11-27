package tutor.educationservice.integration.identity;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tutor.educationservice.common.error.ApiException;

@Component
public class IdentityClient {
    private final RestClient client;
    private final MeterRegistry meters;

    public IdentityClient(IdentityProperties properties, RestClient.Builder builder, MeterRegistry meters) {
        this.meters = meters;
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        client = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    public IdentityProfile currentUser(String accessToken, UUID expectedId) {
        return observed("profile", () -> {
            // Identity currently resolves access_token cookies only; never forward other browser cookies.
            var profile = client.get().uri("/api/v1/users/me").header(HttpHeaders.COOKIE, "access_token=" + accessToken)
                    .retrieve().onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
                        if (status == 403) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "Account access denied");
                        if (status == 404) throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_UNAVAILABLE", "Account access denied");
                        throw unavailable();
                    }).body(IdentityProfile.class);
            if (profile == null || !expectedId.equals(profile.id()) || profile.roles() == null
                    || profile.roles().isEmpty() || profile.roles().stream().anyMatch(java.util.Objects::isNull)
                    || profile.blocked() == null || profile.nameRequested() == null) throw unavailable();
            return profile;
        });
    }

    public IdentityUser user(String accessToken, UUID id) {
        java.util.Objects.requireNonNull(id, "Target user ID is required");
        return observed("lookup", () -> {
            var user = client.get().uri("/api/v1/directory/users/{id}", id).header(HttpHeaders.COOKIE, "access_token=" + accessToken)
                    .retrieve().onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
                        if (status == 401) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
                        if (status == 403) throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied");
                        throw unavailable();
                    }).body(IdentityUser.class);
            if (user == null || !id.equals(user.id()) || user.roles() == null
                    || user.roles().stream().anyMatch(java.util.Objects::isNull) || user.blocked() == null) throw unavailable();
            return user;
        });
    }

    private <T> T observed(String operation, Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (RestClientException exception) {
            meters.counter("education.identity.errors", "operation", operation).increment();
            throw unavailable();
        } catch (ApiException exception) {
            if (exception.status().is5xxServerError()) meters.counter("education.identity.errors", "operation", operation).increment();
            throw exception;
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Identity service is unavailable");
    }
}
