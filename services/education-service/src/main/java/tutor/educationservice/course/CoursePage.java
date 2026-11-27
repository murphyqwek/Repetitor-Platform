package tutor.educationservice.course;

import java.util.List;

public record CoursePage(List<CourseDto> items, String nextCursor, boolean hasMore) {}
