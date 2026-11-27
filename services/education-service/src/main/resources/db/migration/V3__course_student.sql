CREATE TABLE course_student (
    course_id uuid NOT NULL REFERENCES course(id),
    student_id uuid NOT NULL,
    can_send_messages boolean NOT NULL DEFAULT true,
    enrolled_at timestamptz NOT NULL,
    PRIMARY KEY (course_id, student_id)
);

CREATE INDEX idx_course_student_student_course ON course_student (student_id, course_id);
CREATE INDEX idx_course_student_page ON course_student (course_id, enrolled_at DESC, student_id DESC);
