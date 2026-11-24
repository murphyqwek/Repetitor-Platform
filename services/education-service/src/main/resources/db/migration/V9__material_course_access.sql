create table folder_course_access (
    folder_id uuid not null references material_folder(id) on delete cascade,
    course_id uuid not null references course(id) on delete cascade,
    primary key (folder_id, course_id)
);
create index folder_course_access_course on folder_course_access (course_id, folder_id);

create table file_course_access (
    file_id uuid not null references material_file(id) on delete cascade,
    course_id uuid not null references course(id) on delete cascade,
    allow_read boolean not null,
    primary key (file_id, course_id)
);
create index file_course_access_course on file_course_access (course_id, file_id);
create index material_file_page on material_file (created_at desc, id desc) where state = 'ACTIVE';
