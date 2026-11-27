create table chat_message (
    id uuid primary key,
    course_id uuid not null references course(id) on delete cascade,
    author_id uuid not null,
    author_role text not null check (author_role in ('STUDENT', 'TEACHER', 'ADMIN')),
    author_display_name text not null,
    body text check (char_length(body) <= 4000),
    client_message_id uuid not null,
    state text not null default 'ACTIVE' check (state in ('ACTIVE', 'DELETING')),
    created_at timestamptz not null default clock_timestamp(),
    unique (course_id, author_id, client_message_id),
    unique (id, course_id)
);
create index chat_message_history on chat_message (course_id, created_at desc, id desc) where state = 'ACTIVE';

create table chat_send_receipt (
    course_id uuid not null references course(id) on delete cascade,
    author_id uuid not null,
    client_message_id uuid not null,
    message_id uuid not null,
    request_hash text not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (course_id, author_id, client_message_id)
);
