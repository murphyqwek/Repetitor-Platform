package tutor.identityservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tutor.identityservice.entity.AppUser;
import tutor.identityservice.repository.AppUserRepository;
import tutor.identityservice.repository.UserRoleRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {
    final AppUserRepository users = mock(AppUserRepository.class);
    final UserService service = new UserService(users, mock(UserRoleRepository.class));

    @Test
    void statusCanBeChangedAndRepeatedWithoutChangingOtherFields() {
        var user = new AppUser();
        UUID id = UUID.randomUUID();
        user.setId(id);
        user.setLogin("parent1");
        user.setPasswordHash("hash");
        when(users.findForUpdate(id)).thenReturn(Optional.of(user));

        for (boolean blocked : new boolean[]{true, true, false, false}) {
            service.setBlocked(id, blocked);
            assertEquals(blocked, user.isBlocked());
            assertEquals("parent1", user.getLogin());
            assertEquals("hash", user.getPasswordHash());
        }
    }

    @Test
    void missingUserReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(users.findForUpdate(id)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class, () -> service.setBlocked(id, true));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }
}
