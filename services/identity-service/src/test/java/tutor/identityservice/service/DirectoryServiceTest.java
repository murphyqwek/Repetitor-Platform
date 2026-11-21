package tutor.identityservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.entity.*;
import tutor.identityservice.repository.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DirectoryServiceTest {
    final DirectoryRepository directory = mock(DirectoryRepository.class);
    final ParentStudentRepository links = mock(ParentStudentRepository.class);
    final UserRoleRepository roles = mock(UserRoleRepository.class);
    final StudentProfileRepository profiles = mock(StudentProfileRepository.class);
    final AppUserRepository users = mock(AppUserRepository.class);
    final DirectoryService service = new DirectoryService(directory, links, roles, profiles, users);

    @Test
    void lookupReturnsRolesAndBlockedStateWithoutSearchOrRelationships() {
        var target = user("Иванов");
        target.setBlocked(true);
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(roles.findAllById_UserId(target.getId())).thenReturn(List.of(new UserRole(target, Role.STUDENT), new UserRole(target, Role.TEACHER)));
        var result = service.lookup(target.getId());
        assertEquals(target.getId(), result.id());
        assertEquals("Иванов", result.surname());
        assertTrue(result.blocked());
        assertEquals(Set.of(Role.STUDENT, Role.TEACHER), result.roles());
        verifyNoInteractions(directory, links, profiles);
    }

    @Test
    void lookupMissingUserReturns404() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class, () -> service.lookup(id));
        assertEquals(404, error.getStatusCode().value());
        verifyNoInteractions(directory, links, roles, profiles);
    }

    @Test
    void cursorContinuesFromLastReturnedUserAndIsBoundToSearch() {
        var first = user("Иванов");
        var second = user("Ивановский");
        when(directory.search(anyString(), anyString(), eq(true), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(first, second));
        when(roles.findAllById_UserIdIn(any())).thenReturn(List.of(new UserRole(first, Role.STUDENT)));
        var profile = new StudentProfile(first, "encrypted", (short) 5);
        profile.setUserId(first.getId());
        when(profiles.findAllById(any())).thenReturn(List.of(profile));
        var parent = user("Родитель");
        when(links.findAllById_StudentIdIn(any())).thenReturn(List.of(new ParentStudent(parent, first)));
        var result = service.search(Role.STUDENT, " Иванов ", 1, null);
        assertTrue(result.hasNext());
        assertEquals(1, result.content().size());
        assertEquals((short) 5, result.content().get(0).grade());
        assertEquals(parent.getId(), result.content().get(0).parents().get(0).id());
        when(directory.search(anyString(), anyString(), eq(false), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(second));
        var next = service.search(Role.STUDENT, "ИВАНОВ", 1, result.nextCursor());
        assertFalse(next.hasNext());
        assertNull(next.nextCursor());
        verify(directory).search(eq("STUDENT"), eq("иванов"), eq(false), eq("иванов"), eq("иван"), eq(""), eq(first.getId()), any());
        assertThrows(ResponseStatusException.class, () -> service.search(Role.PARENT, "Иванов", 1, result.nextCursor()));
        assertThrows(ResponseStatusException.class, () -> service.search(Role.STUDENT, "Петров", 1, result.nextCursor()));
    }

    @Test
    void emptyResultsDoNotFetchRelationshipsAndInvalidCursorIsRejected() {
        when(directory.search(anyString(), anyString(), anyBoolean(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        assertTrue(service.search(Role.PARENT, "Иван", 20, null).content().isEmpty());
        verifyNoInteractions(links, roles, profiles);
        assertThrows(ResponseStatusException.class, () -> service.search(Role.ADMIN, "Иван", 20, null));
        assertThrows(ResponseStatusException.class, () -> service.search(Role.STUDENT, "Иван", 20, "invalid"));
    }

    AppUser user(String surname) {
        var user = IdentityServicesTest.user("hidden-login");
        user.setSurname(surname);
        user.setFirstName("Иван");
        return user;
    }
}
