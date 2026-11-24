create table cleanup_job (
    id uuid primary key,
    resource_type text not null check (resource_type in ('COURSE', 'CHAT_MESSAGE', 'MATERIAL_FILE', 'MATERIAL_FOLDER')),
    resource_id uuid not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts integer not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null default clock_timestamp(),
    locked_until timestamptz,
    lease_token uuid,
    last_error text,
    created_at timestamptz not null default clock_timestamp(),
    updated_at timestamptz not null default clock_timestamp(),
    check ((status = 'PROCESSING') = (locked_until is not null and lease_token is not null))
);

create unique index cleanup_job_open_resource
    on cleanup_job (resource_type, resource_id) where status in ('PENDING', 'PROCESSING', 'FAILED');
create index cleanup_job_pending on cleanup_job (next_attempt_at, id) where status = 'PENDING';
create index cleanup_job_expired on cleanup_job (locked_until, id) where status = 'PROCESSING';
