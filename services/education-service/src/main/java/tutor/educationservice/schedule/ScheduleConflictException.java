package tutor.educationservice.schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tutor.educationservice.common.error.ApiException;

public class ScheduleConflictException extends ApiException {
    public record Conflict(String subjectType, UUID subjectId, Instant startsAt, Instant endsAt) {}
    private final List<Conflict> conflicts;

    public ScheduleConflictException(List<Conflict> conflicts) {
        super(HttpStatus.CONFLICT, "SCHEDULE_CONFLICTS", "Schedule conflicts require confirmation");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<Conflict> conflicts() { return conflicts; }
}
