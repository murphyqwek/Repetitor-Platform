package tutor.identityservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

@Service
public class StudentCredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public StudentCredentialCipher(@Value("${identity.security.student-credentials-key}") String encodedKey) {
        byte[] bytes = Base64.getDecoder().decode(encodedKey);
        if (bytes.length != 32) throw new IllegalArgumentException("Student credentials key must contain 32 bytes");
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(UUID studentId, String password) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, studentId, nonce, password.getBytes(StandardCharsets.UTF_8));
        byte[] result = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(result);
    }

    public String decrypt(UUID studentId, String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        if (bytes.length < 28) throw new IllegalStateException("Invalid encrypted credentials");
        return new String(crypt(Cipher.DECRYPT_MODE, studentId, Arrays.copyOf(bytes, 12),
                Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, UUID studentId, byte[] nonce, byte[] value) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(studentId.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Student credentials encryption failed", exception);
        }
    }
}
