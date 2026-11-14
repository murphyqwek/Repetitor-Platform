package tutor.identityservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tutor.identityservice.entity.AppUser;

import java.util.UUID;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByLogin(String login);
}
