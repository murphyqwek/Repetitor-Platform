package tutor.identityservice.service;

import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import tutor.identityservice.entity.InvitationCode;
import tutor.identityservice.entity.Role;
import tutor.identityservice.repository.InvitationCodeRepository;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Service
public class InvitationCodeService {
    private static final int MAX_GENERATION_ATTEMPTS = 10;
    private static final Duration CODE_TTL = Duration.ofDays(5);
    private final InvitationCodeRepository repository;
    private final CodeHasher codeHasher;
    private final RandomGenerationService randomGenerationService;

    public InvitationCodeService(
            InvitationCodeRepository repository,
            CodeHasher codeHasher,
            RandomGenerationService randomGenerationService
    ) {
        this.repository = repository;
        this.codeHasher = codeHasher;
        this.randomGenerationService = randomGenerationService;
    }

    @Transactional
    public String createInvitationCode(UUID id) {
        Objects.requireNonNull(id, "User ID must not be null");

        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String code = randomGenerationService.createInvitationCode();
            String codeHash = codeHasher.hash(code);

            if (repository.existsByCodeHash(codeHash)) {
                continue;
            }

            Instant now = Instant.now();

            repository.saveAndFlush(new InvitationCode(codeHash, now, now.plus(CODE_TTL), id));
            return code;
        }

        throw new IllegalStateException("Не удалось сгенерировать уникальный код");
    }

    @Transactional
    public boolean revokeInvitationCode(Long id, UUID userId, Role role) {
        Objects.requireNonNull(userId, "Teacher ID must not be null");
        var code = repository.findByIdForUpdate(id);
        if (code.isEmpty()) {
            return false;
        }

        if (!Role.ADMIN.equals(role) && !userId.equals(code.get().getTeacherId())) {
            throw new AccessDeniedException("Нельзя отозвать чужое приглашение");
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

        if(!foundCode.get().getExpiredAt().isAfter(now)) {
            return false;
        }

        repository.deleteById(foundCode.get().getId());
        return true;
    }
}
