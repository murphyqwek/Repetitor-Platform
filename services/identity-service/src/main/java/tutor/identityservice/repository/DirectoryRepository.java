package tutor.identityservice.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import tutor.identityservice.entity.AppUser;
import java.util.List;
import java.util.UUID;

public interface DirectoryRepository extends Repository<AppUser, UUID> {
    @Query(value = """
            SELECT u.* FROM app_user u
            WHERE EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role = :type)
              AND position(lower(:fio) in lower(concat_ws(' ', u.surname, u.first_name, u.patronymic))) > 0
              AND (:first OR (lower(coalesce(u.surname, '')), lower(coalesce(u.first_name, '')),
                    lower(coalesce(u.patronymic, '')), u.id) > (:surname, :firstName, :patronymic, :id))
            ORDER BY lower(coalesce(u.surname, '')), lower(coalesce(u.first_name, '')),
                     lower(coalesce(u.patronymic, '')), u.id
            """, nativeQuery = true)
    List<AppUser> search(@Param("type") String type, @Param("fio") String fio, @Param("first") boolean first,
                        @Param("surname") String surname, @Param("firstName") String firstName,
                        @Param("patronymic") String patronymic, @Param("id") UUID id, Pageable pageable);
}
