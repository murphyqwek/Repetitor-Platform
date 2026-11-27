package tutor.identityservice.repository;

import org.springframework.data.jpa.repository.*;
import tutor.identityservice.entity.*;
import java.util.*;

public interface ParentStudentRepository extends JpaRepository<ParentStudent, ParentStudentId> {
    boolean existsById_ParentIdAndId_StudentId(UUID parentId, UUID studentId);
    @EntityGraph(attributePaths = {"student"})
    List<ParentStudent> findAllById_ParentId(UUID parentId);
    @EntityGraph(attributePaths = {"parent"})
    List<ParentStudent> findAllById_StudentIdIn(Collection<UUID> studentIds);
}
