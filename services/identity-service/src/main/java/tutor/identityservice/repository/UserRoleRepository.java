package tutor.identityservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tutor.identityservice.entity.UserRole;
import tutor.identityservice.entity.UserRoleId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
    List<UserRole> findAllById_UserId(UUID userId);
    List<UserRole> findAllById_UserIdIn(java.util.Collection<UUID> userIds);

    @Query("select u.blocked from AppUser u where u.id = :id")
    Optional<Boolean> findBlockedById(@Param("id") UUID id);
}
