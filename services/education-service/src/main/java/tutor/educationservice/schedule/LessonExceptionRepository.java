package tutor.educationservice.schedule;

import java.time.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LessonExceptionRepository extends JpaRepository<LessonException, UUID> {
    List<LessonException> findBySeriesIdIn(Collection<UUID> seriesIds);
    Optional<LessonException> findBySeriesIdAndOriginalDate(UUID seriesId, LocalDate originalDate);

    @Query(value = """
            SELECT * FROM lesson_exception WHERE series_id IN (:seriesIds)
              AND ((original_date >= :fromDate AND original_date < :toDate)
                   OR (kind = 'RESCHEDULED' AND new_starts_at < :endsAt AND new_ends_at > :startsAt))
            """, nativeQuery = true)
    List<LessonException> calendar(@Param("seriesIds") List<UUID> seriesIds, @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate, @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}
