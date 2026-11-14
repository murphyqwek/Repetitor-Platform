package tutor.identityservice.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.identityservice.entity.Role;
import tutor.identityservice.repository.AppUserRepository;
import tutor.identityservice.repository.UserRoleRepository;
import tutor.identityservice.security.IdentityUserDetails;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserService {
    private final AppUserRepository appUserRepository;
    private final UserRoleRepository userRoleRepository;

    public UserService(AppUserRepository appUserRepository, UserRoleRepository userRoleRepository) {
        this.appUserRepository = appUserRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional(readOnly = true)
    public Set<Role> getUserRoles(UUID userId) {
        var user = appUserRepository.findById(userId).orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));

        var roles = userRoleRepository.findAllById_UserId(user.getId()).stream()
                .map(userRole -> userRole.getId().getRole())
                .collect(Collectors.toSet());

        return roles;
    }

    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        var user = appUserRepository.findByLogin(login).orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));

        var roles = userRoleRepository.findAllById_UserId(user.getId()).stream()
                .map(userRole -> userRole.getId().getRole())
                .collect(Collectors.toSet());

        return new IdentityUserDetails(user, roles);
    }

    @Transactional(readOnly = true)
    public boolean isUserBlocked(UUID id) {
        return userRoleRepository.findBlockedById(id).orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
    }
}
