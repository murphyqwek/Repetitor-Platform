package tutor.identityservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tutor.identityservice.entity.StudentProfile;
import java.util.UUID;

public interface StudentProfileRepository extends JpaRepository<StudentProfile, UUID> {}
