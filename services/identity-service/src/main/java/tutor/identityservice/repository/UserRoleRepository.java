package tutor.identityservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tutor.identityservice.entity.UserRole;
import tutor.identityservice.entity.UserRoleId;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
}
