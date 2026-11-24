package tutor.educationservice.chat;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.course.Course.ChatMode;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.security.*;

@Component
class ChatAccess {
    private final CourseService courses;
    private final JdbcTemplate jdbc;

    ChatAccess(CourseService courses, JdbcTemplate jdbc) {
        this.courses = courses;
        this.jdbc = jdbc;
    }

    Role writer(UUID courseId) {
        var user = CurrentUser.get();
        var course = courses.visibleForUpdate(courseId);
        if (user.roles().contains(Role.ADMIN)) return Role.ADMIN;
        if (user.roles().contains(Role.TEACHER) && user.id().equals(course.teacherId())) return Role.TEACHER;
        if (course.chatMode() == ChatMode.NORMAL && user.roles().contains(Role.STUDENT)
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from course_student where course_id = ? and student_id = ? and can_send_messages)
                        """, Boolean.class, courseId, user.id()))) return Role.STUDENT;
        throw new ApiException(HttpStatus.FORBIDDEN, "CHAT_SEND_FORBIDDEN", "Sending chat messages is forbidden");
    }
}
