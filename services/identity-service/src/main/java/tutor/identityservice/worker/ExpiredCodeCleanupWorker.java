package tutor.identityservice.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tutor.identityservice.repository.InvitationCodeRepository;
import tutor.identityservice.repository.InvitationIdempotencyRepository;
import tutor.identityservice.repository.TeacherRegistrationCodeRepository;

import java.time.Clock;
import java.time.Instant;

@Component
public class ExpiredCodeCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(ExpiredCodeCleanupWorker.class);

    private final InvitationCodeRepository invitationCodeRepository;
    private final TeacherRegistrationCodeRepository teacherRegistrationCodeRepository;
    private final Clock clock;
    private final InvitationIdempotencyRepository idempotencyRepository;

    public ExpiredCodeCleanupWorker(
            InvitationCodeRepository invitationCodeRepository,
            TeacherRegistrationCodeRepository teacherRegistrationCodeRepository,
            Clock clock,
            InvitationIdempotencyRepository idempotencyRepository
    ) {
        this.invitationCodeRepository = invitationCodeRepository;
        this.teacherRegistrationCodeRepository = teacherRegistrationCodeRepository;
        this.clock = clock;
        this.idempotencyRepository = idempotencyRepository;
    }

    @Scheduled(
            cron = "${identity.cleanup.expired-codes.cron}",
            zone = "${identity.cleanup.expired-codes.zone}"
    )
    @Transactional
    public void deleteExpiredCodes() {
        Instant now = clock.instant();
        int idempotencyResults = idempotencyRepository.deleteExpiredRequests(now);
        int invitations = invitationCodeRepository.deleteExpiredCodes(now);
        int teacherCodes = teacherRegistrationCodeRepository.deleteExpiredCodes(now);
        log.info("Expired code cleanup: invitations={}, teacherRegistrationCodes={}, idempotencyResults={}, cutoff={}",
                invitations, teacherCodes, idempotencyResults, now);
    }
}
