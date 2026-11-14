package tutor.identityservice.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class RandomGenerationService {
    private final SecureRandom random = new SecureRandom();

    public String createRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        return token;
    }

}
