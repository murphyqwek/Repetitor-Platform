package tutor.identityservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tutor.identityservice.event.InvitationResultCommitted;
import tutor.identityservice.repository.InvitationIdempotencyRepository;
import tutor.identityservice.service.InvitationIdempotencyService.Operation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class InvitationCreationService {
    private final InvitationIdempotencyRepository repository;
    private final InvitationCodeService invitationCodeService;
    private final TeacherRegistrationCodeService teacherRegistrationCodeService;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration ttl;

    public InvitationCreationService(
            InvitationIdempotencyRepository repository,
            InvitationCodeService invitationCodeService,
            TeacherRegistrationCodeService teacherRegistrationCodeService,
            ApplicationEventPublisher events, Clock clock,
            @Value("${identity.invitations.idempotency-ttl:15m}") Duration ttl
    ) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Idempotency TTL must be positive");
        }
        this.repository = repository;
        this.invitationCodeService = invitationCodeService;
        this.teacherRegistrationCodeService = teacherRegistrationCodeService;
        this.events = events;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String create(String key, UUID userId, Operation operation) {
        Instant now = clock.instant();
        repository.reserve(userId, operation.name(), key, now, now.plus(ttl));
        var request = repository.findRequestForUpdate(userId, operation, key).orElseThrow();

        // Recheck time after waiting for a concurrent request's transaction.
        now = clock.instant();
        if (request.getResultCode().isEmpty() || !request.getExpiredAt().isAfter(now)) {
            String code = switch (operation) {
                case PARENT_INVITATION -> invitationCodeService.createInvitationCode(userId);
                case TEACHER_REGISTRATION -> teacherRegistrationCodeService.createTeacherRegistrationCode();
            };
            now = clock.instant();
            request.complete(code, now, now.plus(ttl));
        }

        events.publishEvent(new InvitationResultCommitted(
                key, userId, operation, request.getResultCode(), request.getExpiredAt()));
        return request.getResultCode();
    }
}
