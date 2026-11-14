package tutor.identityservice.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.identityservice.entity.TeacherRegistrationCode;
import tutor.identityservice.repository.TeacherRegistrationCodeRepository;

import java.time.Instant;
import java.time.Duration;

@Service
public class TeacherRegistrationCodeService {
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final Duration CODE_TTL = Duration.ofDays(1);
    private final TeacherRegistrationCodeRepository repository;
    private final CodeHasher codeHasher;
    private final RandomGenerationService randomGenerationService;

    public TeacherRegistrationCodeService(
            TeacherRegistrationCodeRepository repository,
            CodeHasher codeHasher,
            RandomGenerationService randomGenerationService
    ) {
        this.repository = repository;
        this.codeHasher = codeHasher;
        this.randomGenerationService = randomGenerationService;
    }

    @Transactional
    public String createTeacherRegistrationCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = randomGenerationService.createTeacherRegistrationCode();
            String codeHash = codeHasher.hash(code);
            if (repository.existsByCodeHash(codeHash)) {
                continue;
            }

            Instant now = Instant.now();
            repository.saveAndFlush(new TeacherRegistrationCode(codeHash, now, now.plus(CODE_TTL)));
            return code;
        }

        throw new IllegalStateException("Не удалось сгенерировать уникальный код");
    }

    @Transactional
    public boolean revokeInvitationCode(Long id) {
        var code = repository.findByIdForUpdate(id);
        if (code.isEmpty()) {
            return false;
        }

        repository.delete(code.get());
        return true;
    }

    @Transactional
    public boolean consumeInvitationCode(String invitationCode) {
        var hashedCode = codeHasher.hash(invitationCode);

        var foundCode = repository.findByCodeHash(hashedCode);

        if(foundCode.isEmpty()) {
            return false;
        }

        Instant now = Instant.now();

        if(foundCode.get().getExpiredAt().isBefore(now)) {
            return false;
        }

        repository.deleteById(foundCode.get().getId());
        return true;
    }
}
