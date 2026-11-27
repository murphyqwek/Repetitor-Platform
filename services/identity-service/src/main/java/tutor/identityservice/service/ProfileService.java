package tutor.identityservice.service;

import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.dto.*;
import tutor.identityservice.entity.*;
import tutor.identityservice.repository.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {
    private final AppUserRepository users;
    private final UserRoleRepository roles;
    private final StudentProfileRepository students;
    private final ParentStudentRepository links;

    public AppUser user(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    public ProfileDTO profile(UUID id) {
        var user = user(id);
        var userRoles = roles.findAllById_UserId(id).stream().map(r -> r.getId().getRole()).collect(Collectors.toSet());
        Short grade = students.findById(id).map(StudentProfile::getSchoolGrade).orElse(null);
        return new ProfileDTO(id, user.getLogin(), user.getFirstName(), user.getSurname(), user.getPatronymic(),
                userRoles, user.isBlocked(), user.getCreatedAt(), user.isNameRequested(), grade);
    }

    @Transactional
    public ProfileDTO update(UUID id, ProfileUpdateDTO body) {
        var user = user(id);
        if (body.firstName() != null) user.setFirstName(body.firstName().strip());
        if (body.surname() != null) user.setSurname(body.surname().strip());
        if (body.patronymic() != null) user.setPatronymic(body.patronymic().strip());
        user.setNameRequested(user.getFirstName() != null && !user.getFirstName().isBlank()
                && user.getSurname() != null && !user.getSurname().isBlank());
        if (body.grade() != null) {
            var student = students.findById(id).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Класс доступен только ученику"));
            student.setSchoolGrade(body.grade());
        }
        return profile(id);
    }

    public void requireParent(UUID parentId, UUID studentId) {
        if (!links.existsById_ParentIdAndId_StudentId(parentId, studentId)) {
            throw new AccessDeniedException("Нет доступа к ребёнку");
        }
    }

    public List<ProfileDTO> children(UUID parentId) {
        return links.findAllById_ParentId(parentId).stream().map(l -> profile(l.getId().getStudentId())).toList();
    }
}
