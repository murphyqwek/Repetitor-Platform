package tutor.identityservice.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class RandomGenerationService {
    private static final String INVITATION_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int INVITATION_LENGTH = 12;
    private final SecureRandom random = new SecureRandom();

    public String createInvitationCode() {
        var code = new StringBuilder(INVITATION_LENGTH);
        for (int index = 0; index < INVITATION_LENGTH; index++) {
            code.append(INVITATION_ALPHABET.charAt(random.nextInt(INVITATION_ALPHABET.length())));
        }
        return code.toString();
    }

    public String createTeacherRegistrationCode() {
        return UUID.randomUUID().toString();
    }

    public String createRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);

        return token;
    }

}
