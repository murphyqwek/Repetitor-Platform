create table material_folder (
    id uuid primary key,
    teacher_id uuid not null,
    parent_id uuid,
    name text not null,
    state text not null default 'ACTIVE' check (state in ('ACTIVE', 'DELETING')),
    created_at timestamptz not null default clock_timestamp(),
    unique (id, teacher_id),
    foreign key (parent_id, teacher_id) references material_folder(id, teacher_id)
);
create index material_folder_parent on material_folder (teacher_id, parent_id);
create index material_folder_children on material_folder (parent_id);

create table material_file (
    id uuid primary key,
    teacher_id uuid not null,
    folder_id uuid,
    name text not null,
    object_key text not null unique,
    mime_type text not null,
    size_bytes bigint not null check (size_bytes >= 0),
    state text not null default 'ACTIVE' check (state in ('ACTIVE', 'DELETING')),
    created_at timestamptz not null default clock_timestamp(),
    foreign key (folder_id, teacher_id) references material_folder(id, teacher_id)
);
create index material_file_folder on material_file (teacher_id, folder_id);
create index material_file_folder_id on material_file (folder_id);

create table pending_upload (
    id uuid primary key,
    staging_key text not null unique,
    object_key text not null unique,
    published boolean not null default false,
    cleaning boolean not null default false
);

alter table cleanup_job drop constraint cleanup_job_resource_type_check;
alter table cleanup_job add constraint cleanup_job_resource_type_check
    check (resource_type in ('COURSE', 'CHAT_MESSAGE', 'MATERIAL_FILE', 'MATERIAL_FOLDER', 'PENDING_UPLOAD'));
