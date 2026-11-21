CREATE TABLE single_lesson (
    id uuid PRIMARY KEY,
    course_id uuid NOT NULL REFERENCES course(id),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_single_lesson_interval CHECK (
        ends_at > starts_at
        AND ends_at >= starts_at + interval '5 minutes'
        AND ends_at <= starts_at + interval '5 hours'
    )
);

CREATE INDEX idx_single_lesson_course_starts ON single_lesson (course_id, starts_at);
