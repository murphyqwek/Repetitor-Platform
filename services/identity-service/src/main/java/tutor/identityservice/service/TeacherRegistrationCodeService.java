package tutor.identityservice.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.identityservice.repository.TeacherRegistrationCodeRepository;

import java.time.Instant;

@Service
public class TeacherRegistrationCodeService {
    private final TeacherRegistrationCodeRepository repository;
    private final CodeHasher codeHasher;

    public TeacherRegistrationCodeService(TeacherRegistrationCodeRepository repository, CodeHasher codeHasher) {
        this.repository = repository;
        this.codeHasher = codeHasher;
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
