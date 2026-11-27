package tutor.identityservice.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
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
public class IdentityUserDetailsService implements UserDetailsService {
    private final UserService userService;

    public IdentityUserDetailsService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String login) {
        return userService.loadUserByUsername(login);
    }
}
