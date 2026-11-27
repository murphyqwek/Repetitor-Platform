package tutor.identityservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private final StringRedisTemplate redis;
    private final RandomGenerationService randomService;
    private final CodeHasher codeHasher;
    private final Duration refreshTokenTtl;

    public RefreshTokenService(StringRedisTemplate redis,
                               RandomGenerationService randomService,
                               CodeHasher codeHasher,
                               @Value("${identity.security.refreshTtl}")  Duration refreshTokenTtl) {
        this.redis = redis;
        this.randomService = randomService;
        this.codeHasher = codeHasher;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String createRefreshToken(UUID userId) {
        String token = randomService.createRefreshToken();

        String key = "identity:refresh:" + codeHasher.hash(token);

        redis.opsForValue().set(key, userId.toString(), refreshTokenTtl);

        return token;
    }

    public Optional<UUID> checkTokenAndRefresh(String token) {
        String key = "identity:refresh:" + codeHasher.hash(token);

        String userId = redis.opsForValue().getAndDelete(key);

        return Optional.ofNullable(userId).map(UUID::fromString);
    }

    public void deleteRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }

        String key = "identity:refresh:" + codeHasher.hash(refreshToken);

        redis.delete(key);
    }
}
