create table chat_attachment (
    id uuid primary key,
    course_id uuid not null references course(id) on delete cascade,
    message_id uuid,
    uploader_id uuid not null,
    object_key text not null unique,
    original_name text not null,
    mime_type text not null,
    size_bytes bigint not null check (size_bytes between 0 and 20000000),
    upload_state text not null check (upload_state in ('UPLOADING', 'READY', 'ATTACHED', 'DELETING')),
    created_at timestamptz not null default clock_timestamp(),
    expires_at timestamptz,
    foreign key (message_id, course_id) references chat_message(id, course_id) on delete cascade,
    check ((upload_state = 'ATTACHED') = (message_id is not null)),
    check ((upload_state = 'ATTACHED') = (expires_at is null))
);
create index chat_attachment_message on chat_attachment (message_id, course_id);
create index chat_attachment_course on chat_attachment (course_id);

alter table cleanup_job drop constraint cleanup_job_resource_type_check;
alter table cleanup_job add constraint cleanup_job_resource_type_check
    check (resource_type in ('COURSE', 'CHAT_MESSAGE', 'MATERIAL_FILE', 'MATERIAL_FOLDER', 'PENDING_UPLOAD', 'CHAT_ATTACHMENT'));
