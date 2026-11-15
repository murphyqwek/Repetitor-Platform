package tutor.identityservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.entity.*;
import tutor.identityservice.repository.*;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectoryService {
    private final DirectoryRepository directory;
    private final ParentStudentRepository links;
    private final UserRoleRepository roles;
    private final StudentProfileRepository profiles;

    public Result search(Role type, String fio, int limit, String cursor) {
        if (type != Role.STUDENT && type != Role.PARENT) throw invalidCursor();
        fio = fio.strip().toLowerCase(Locale.ROOT);
        var after = cursor == null ? null : decode(cursor, type, fio);
        var users = directory.search(type.name(), fio, after == null, after == null ? "" : after.surname(),
                after == null ? "" : after.firstName(), after == null ? "" : after.patronymic(),
                after == null ? new UUID(0, 0) : after.id(), PageRequest.of(0, limit + 1));
        boolean hasNext = users.size() > limit;
        var page = users.subList(0, Math.min(limit, users.size()));
        var ids = page.stream().map(AppUser::getId).toList();
        var parents = type == Role.STUDENT && !ids.isEmpty() ? links.findAllById_StudentIdIn(ids).stream()
                .collect(Collectors.groupingBy(l -> l.getId().getStudentId(),
                        Collectors.mapping(l -> name(l.getParent()), Collectors.toList()))) : Map.<UUID, List<Name>>of();
        var userRoles = ids.isEmpty() ? Map.<UUID, Set<Role>>of() : roles.findAllById_UserIdIn(ids).stream()
                .collect(Collectors.groupingBy(r -> r.getId().getUserId(),
                        Collectors.mapping(r -> r.getId().getRole(), Collectors.toSet())));
        var grades = new HashMap<UUID, Short>();
        if (type == Role.STUDENT) profiles.findAllById(ids).forEach(p -> grades.put(p.getUserId(), p.getSchoolGrade()));
        var content = page.stream().map(u -> new Entry(u.getId(), u.getFirstName(), u.getSurname(), u.getPatronymic(),
                u.isBlocked(), userRoles.getOrDefault(u.getId(), Set.of()), grades.get(u.getId()),
                parents.getOrDefault(u.getId(), List.of()))).toList();
        return new Result(content, limit, hasNext, hasNext ? encode(page.get(page.size() - 1), type, fio) : null);
    }

    public record Name(UUID id, String firstName, String surname, String patronymic) {}
    public record Entry(UUID id, String firstName, String surname, String patronymic, boolean blocked,
                        Set<Role> roles, Short grade, List<Name> parents) {}
    public record Result(List<Entry> content, int limit, boolean hasNext, String nextCursor) {}
    private record Cursor(String surname, String firstName, String patronymic, UUID id) {}

    private Name name(AppUser user) {
        return new Name(user.getId(), user.getFirstName(), user.getSurname(), user.getPatronymic());
    }

    private String encode(AppUser user, Role type, String fio) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeUTF(type.name());
            output.writeUTF(fio);
            output.writeUTF(normalize(user.getSurname()));
            output.writeUTF(normalize(user.getFirstName()));
            output.writeUTF(normalize(user.getPatronymic()));
            output.writeUTF(user.getId().toString());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Cursor decode(String cursor, Role type, String fio) {
        try {
            if (cursor.length() > 4096) throw invalidCursor();
            var input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(cursor)));
            if (!input.readUTF().equals(type.name()) || !input.readUTF().equals(fio)) throw invalidCursor();
            var result = new Cursor(input.readUTF(), input.readUTF(), input.readUTF(), UUID.fromString(input.readUTF()));
            if (input.available() != 0) throw invalidCursor();
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidCursor();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный тип поиска или курсор");
    }
}
