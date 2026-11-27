package tutor.identityservice.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import tutor.identityservice.entity.*;
import java.util.*;

public interface StudentRegRequestRepository extends JpaRepository<StudentRegRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StudentRegRequest r where r.id = :id")
    Optional<StudentRegRequest> findForUpdate(@Param("id") Long id);
    Page<StudentRegRequest> findAllByParent_Id(UUID parentId, Pageable pageable);
    Page<StudentRegRequest> findAllByStatus(StudentRegistrationStatus status, Pageable pageable);
    Optional<StudentRegRequest> findByParent_IdAndIdempotencyKey(UUID parentId, String key);
}
