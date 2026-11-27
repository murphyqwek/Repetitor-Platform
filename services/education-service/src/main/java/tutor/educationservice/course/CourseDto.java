package tutor.educationservice.course;

import java.time.Instant;
import java.util.UUID;

public record CourseDto(UUID id, UUID teacherId, String title, String meetingUrl, Course.ChatMode chatMode, Instant createdAt) {}
