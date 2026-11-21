CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    event_type text NOT NULL CHECK (event_type IN ('LessonRescheduled', 'LessonCancelled', 'LessonSeriesStopped')),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    created_at timestamptz NOT NULL,
    published_at timestamptz
);

CREATE INDEX idx_outbox_event_pending ON outbox_event (created_at, id) WHERE published_at IS NULL;
