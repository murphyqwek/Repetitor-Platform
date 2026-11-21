CREATE TABLE course (
    id uuid PRIMARY KEY,
    teacher_id uuid NOT NULL,
    title text NOT NULL CHECK (title !~ '^[[:space:]]*$'),
    meeting_url text,
    chat_mode text NOT NULL DEFAULT 'NORMAL' CHECK (chat_mode IN ('NORMAL', 'ANNOUNCEMENTS')),
    deletion_state text NOT NULL DEFAULT 'ACTIVE' CHECK (deletion_state IN ('ACTIVE', 'DELETING')),
    created_at timestamptz NOT NULL
);

CREATE INDEX idx_course_teacher_id ON course (teacher_id, id);
CREATE INDEX idx_course_active_created ON course (created_at DESC, id DESC) WHERE deletion_state = 'ACTIVE';
