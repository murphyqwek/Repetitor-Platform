package tutor.educationservice.course;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CourseStudentDto(UUID studentId, String displayName, boolean blocked, boolean canSendMessages, Instant enrolledAt) {
    public record Page(List<CourseStudentDto> items, String nextCursor, boolean hasMore) {}
    public record Permissions(UUID studentId, boolean canSendMessages) {}
}
