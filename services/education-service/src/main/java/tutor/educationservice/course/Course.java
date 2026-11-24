package tutor.educationservice.course;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "course")
public class Course {
    public enum ChatMode { NORMAL, ANNOUNCEMENTS }
    public enum DeletionState { ACTIVE, DELETING }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "teacher_id", nullable = false, updatable = false)
    private UUID teacherId;
    @Column(nullable = false, columnDefinition = "text")
    private String title;
    @Column(name = "meeting_url", columnDefinition = "text")
    private String meetingUrl;
    @Enumerated(EnumType.STRING)
    @Column(name = "chat_mode", nullable = false, columnDefinition = "text")
    private ChatMode chatMode = ChatMode.NORMAL;
    @Enumerated(EnumType.STRING)
    @Column(name = "deletion_state", nullable = false, columnDefinition = "text")
    private DeletionState deletionState = DeletionState.ACTIVE;
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    protected Course() {}

    Course(UUID teacherId, String title, Instant createdAt) {
        this.teacherId = teacherId;
        this.title = title;
        this.createdAt = createdAt;
    }

    public UUID teacherId() { return teacherId; }
    public ChatMode chatMode() { return chatMode; }
    public void changeChatMode(ChatMode mode) { this.chatMode = mode; }
    void rename(String title) { this.title = title; }
    CourseDto dto() { return new CourseDto(id, teacherId, title, meetingUrl, chatMode, createdAt); }
}
