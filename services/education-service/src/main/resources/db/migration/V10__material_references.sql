create table material_reference (
    id uuid primary key,
    file_id uuid not null references material_file(id),
    consumer_service text not null check (consumer_service = 'ASSIGNMENT'),
    consumer_entity_id uuid not null,
    created_at timestamptz not null default clock_timestamp(),
    unique (file_id, consumer_service, consumer_entity_id)
);

alter table material_file add column cleanup_job_id uuid;
alter table material_folder add column cleanup_job_id uuid;
