CREATE FUNCTION valid_lesson_weekdays(days smallint[]) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT array_ndims(days) = 1 AND cardinality(days) BETWEEN 1 AND 7
        AND NOT EXISTS (SELECT 1 FROM unnest(days) AS day WHERE day IS NULL OR day NOT BETWEEN 1 AND 7)
        AND cardinality(days) = (SELECT count(DISTINCT day) FROM unnest(days) AS day)
$$;

CREATE TABLE lesson_series (
    id uuid PRIMARY KEY,
    course_id uuid NOT NULL REFERENCES course(id),
    start_date date NOT NULL,
    weekdays smallint[] NOT NULL CHECK (valid_lesson_weekdays(weekdays)),
    start_time time NOT NULL,
    duration_minutes smallint NOT NULL CHECK (duration_minutes BETWEEN 5 AND 300),
    stop_from_date date,
    created_at timestamptz NOT NULL
);

CREATE INDEX idx_lesson_series_course ON lesson_series (course_id, created_at DESC, id DESC);

CREATE TABLE lesson_exception (
    id uuid PRIMARY KEY,
    series_id uuid NOT NULL REFERENCES lesson_series(id),
    original_date date NOT NULL,
    kind text NOT NULL CHECK (kind IN ('RESCHEDULED', 'CANCELLED')),
    new_starts_at timestamptz,
    new_ends_at timestamptz,
    reason text,
    created_at timestamptz NOT NULL,
    UNIQUE (series_id, original_date),
    CONSTRAINT ck_lesson_exception_interval CHECK (
        (kind = 'CANCELLED' AND new_starts_at IS NULL AND new_ends_at IS NULL)
        OR (kind = 'RESCHEDULED' AND new_starts_at IS NOT NULL AND new_ends_at IS NOT NULL
            AND new_ends_at >= new_starts_at + interval '5 minutes'
            AND new_ends_at <= new_starts_at + interval '5 hours')
    )
);

CREATE INDEX idx_lesson_exception_new_starts ON lesson_exception (new_starts_at) WHERE kind = 'RESCHEDULED';
