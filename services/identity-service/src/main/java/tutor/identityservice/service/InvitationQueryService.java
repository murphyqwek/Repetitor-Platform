package tutor.identityservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tutor.identityservice.dto.*;
import tutor.identityservice.repository.*;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvitationQueryService {
    private final InvitationCodeRepository parentCodes;
    private final TeacherRegistrationCodeRepository teacherCodes;

    public PageDTO<InvitationDTO> parents(UUID userId, boolean admin, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt", "id").descending());
        var now = Instant.now();
        var codes = admin ? parentCodes.findByExpiredAtAfter(now, pageable)
                : parentCodes.findByTeacherIdAndExpiredAtAfter(userId, now, pageable);
        return PageDTO.from(codes.map(c -> new InvitationDTO(c.getId(), c.getCreatedAt(), c.getExpiredAt())));
    }

    public PageDTO<InvitationDTO> teachers(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt", "id").descending());
        return PageDTO.from(teacherCodes.findByExpiredAtAfter(Instant.now(), pageable)
                .map(c -> new InvitationDTO(c.getId(), c.getCreatedAt(), c.getExpiredAt())));
    }
}
