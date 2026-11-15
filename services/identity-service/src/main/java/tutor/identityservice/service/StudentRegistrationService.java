package tutor.identityservice.service;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.dto.*;
import tutor.identityservice.entity.*;
import tutor.identityservice.repository.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentRegistrationService {
    private final StudentRegRequestRepository requests;
    private final AppUserRepository users;
    private final StudentProfileRepository students;
    private final ParentStudentRepository links;
    private final CreateUserService createUsers;
    private final RandomGenerationService random;
    private final StudentCredentialCipher cipher;
    private final ProfileService profiles;

    @Transactional
    public StudentRequestDTO create(UUID parentId, String key, CreateStudentRequestDTO body) {
        // Serialize one parent's submissions so retries cannot create duplicate children.
        var parent = users.findForUpdate(parentId).orElseThrow();
        var previous = requests.findByParent_IdAndIdempotencyKey(parentId, key);
        if (previous.isPresent()) {
            var request = previous.get();
            if (!request.getFirstName().equals(body.firstName().strip()) || !request.getSurname().equals(body.surname().strip())
                    || !Objects.equals(request.getPatronymic(), normalize(body.patronymic()))
                    || !Objects.equals(request.getSchoolGrade(), body.grade())
                    || !Objects.equals(request.getPolicyVersion(), body.policyVersion())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ключ уже использован с другими данными");
            }
            return dto(request);
        }
        var request = new StudentRegRequest(parent, body.firstName().strip(), body.surname().strip(), normalize(body.patronymic()));
        request.setSchoolGrade(body.grade());
        request.setPolicyVersion(body.policyVersion());
        request.setConsentAt(Instant.now());
        request.setIdempotencyKey(key);
        return dto(requests.saveAndFlush(request));
    }

    public PageDTO<StudentRequestDTO> mine(UUID parentId, int page, int size) {
        return PageDTO.from(requests.findAllByParent_Id(parentId, pageable(page, size)).map(this::dto));
    }

    public PageDTO<StudentRequestDTO> pending(int page, int size) {
        return PageDTO.from(requests.findAllByStatus(StudentRegistrationStatus.PENDING, pageable(page, size)).map(this::dto));
    }

    @Transactional
    public StudentRequestDTO decide(Long id, boolean approve) {
        var request = requests.findForUpdate(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Заявка не найдена"));
        if (request.getStatus() != StudentRegistrationStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Заявка уже рассмотрена");
        }
        if (!approve) {
            request.setStatus(StudentRegistrationStatus.REJECTED);
            return dto(request);
        }
        var parent = profiles.user(request.getParent().getId());
        if (parent.isBlocked()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Родитель заблокирован");
        String login = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "st-" + random.createInvitationCode().substring(0, 8);
            if (users.findByLogin(candidate).isEmpty()) {
                login = candidate;
                break;
            }
        }
        if (login == null) throw new IllegalStateException("Не удалось создать логин ученика");
        String password = random.createRefreshToken().substring(0, 20);
        var student = createUsers.saveUser(login, password, Role.STUDENT, true, request.getPolicyVersion());
        student.setFirstName(request.getFirstName());
        student.setSurname(request.getSurname());
        student.setPatronymic(request.getPatronymic());
        var profile = new StudentProfile(student, cipher.encrypt(student.getId(), password), request.getSchoolGrade());
        profile.setGuardianId(parent.getId());
        profile.setConsentAt(request.getConsentAt());
        profile.setPolicyVersion(request.getPolicyVersion());
        students.save(profile);
        links.save(new ParentStudent(parent, student));
        request.setStudent(student);
        request.setStatus(StudentRegistrationStatus.APPROVED);
        return dto(request);
    }

    public Credentials credentials(UUID parentId, UUID studentId) {
        profiles.requireParent(parentId, studentId);
        var student = students.findById(studentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new Credentials(studentId, student.getUser().getLogin(), cipher.decrypt(studentId, student.getPassword()));
    }

    public record Credentials(UUID studentId, String login, String password) {}

    private StudentRequestDTO dto(StudentRegRequest r) {
        return new StudentRequestDTO(r.getId(), r.getParent().getId(), r.getFirstName(), r.getSurname(), r.getPatronymic(),
                r.getSchoolGrade(), r.getStatus(), r.getStudent() == null ? null : r.getStudent().getId());
    }

    private Pageable pageable(int page, int size) {
        return PageRequest.of(page, size, Sort.by("id").descending());
    }

    private String normalize(String value) {
        return value == null ? null : value.strip();
    }
}
