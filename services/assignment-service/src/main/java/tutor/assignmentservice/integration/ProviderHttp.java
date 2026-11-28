package tutor.assignmentservice.integration;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tutor.assignmentservice.common.error.ApiException;

/** A fixed provider origin, audience and scope. Each call gets its own short-lived service credential. */
public final class ProviderHttp {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final URI origin;
    private final String audience, scope, errorCode;
    private final MACSigner signer;
    private final Clock clock;

    public ProviderHttp(URI origin, String audience, String scope, String key, String errorCode, Clock clock) {
        if (!Set.of("http", "https").contains(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null || !Set.of("", "/").contains(origin.getPath())) {
            throw new IllegalArgumentException("Invalid " + audience + " origin");
        }
        this.origin = origin;
        this.audience = audience;
        this.scope = scope;
        this.errorCode = errorCode;
        this.clock = clock;
        try {
            byte[] bytes = Base64.getDecoder().decode(key);
            if (bytes.length != 32) throw new IllegalArgumentException();
            signer = new MACSigner(bytes);
        } catch (IllegalArgumentException | JOSEException e) {
            throw new IllegalArgumentException(audience + " signing key requires 32 Base64-encoded bytes");
        }
    }

    public <T> T request(String path, Object body, Class<T> type) {
        var now = clock.instant();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("rp-assignment").subject("assignment-service").audience(audience)
                .claim("tokenType", "SERVICE").claim("scope", List.of(scope))
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        try {
            token.sign(signer);
            var req = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token.serialize()).header("Accept", "application/json");
            if (body != null) req.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            var res = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 404 && body == null && audience.equals("identity-service"))
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Identity user no longer exists");
            if (res.statusCode() == 422 && audience.equals("education-service") && body != null)
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "RECIPIENT_LIMIT", "Recipient selection exceeds limit");
            if (res.statusCode() != 200) throw unavailable();
            return Objects.requireNonNull(json.readValue(res.body(), type));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | JOSEException | tools.jackson.core.JacksonException | NullPointerException ex) {
            throw unavailable();
        }
    }

    public ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, errorCode, audience + " is unavailable");
    }
}
