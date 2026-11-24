package tutor.educationservice.material;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.educationservice.common.error.ApiException;
import tutor.educationservice.common.pagination.CursorCodec;
import tutor.educationservice.course.CourseService;
import tutor.educationservice.security.CurrentUser;

@Service
class MaterialAccessService {
    record Rule(UUID courseId, boolean allowRead) {}
    record CourseFile(UUID id, String name, UUID folderId, String contentType, long sizeBytes, java.time.Instant createdAt, String downloadUrl) {
        CourseFile(MaterialFile file, UUID course) {
            this(file.id(), file.name(), file.folderId(), file.contentType(), file.sizeBytes(), file.createdAt(),
                    "/api/v1/courses/" + course + "/materials/files/" + file.id() + "/content");
        }
    }
    record Page(List<CourseFile> items, String nextCursor, boolean hasMore) {}

    private static final String ACCESSIBLE = """
            with recursive granted(id) as (
                select a.folder_id from folder_course_access a join material_folder f on f.id = a.folder_id
                where a.course_id = ? and f.state = 'ACTIVE'
                union
                select f.id from material_folder f join granted g on f.parent_id = g.id where f.state = 'ACTIVE'
            )
            select f.* from material_file f
            left join file_course_access a on a.file_id = f.id and a.course_id = ?
            where f.state = 'ACTIVE' and coalesce(a.allow_read, exists(select 1 from granted g where g.id = f.folder_id))
            """;

    private final JdbcTemplate jdbc;
    private final MaterialService materials;
    private final CourseService courses;
    private final CursorCodec cursors;

    MaterialAccessService(JdbcTemplate jdbc, MaterialService materials, CourseService courses, CursorCodec cursors) {
        this.jdbc = jdbc;
        this.materials = materials;
        this.courses = courses;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    public List<UUID> folderGrants(UUID folder) {
        materials.ownedFolder(folder, false);
        return jdbc.queryForList("select course_id from folder_course_access where folder_id = ? order by course_id", UUID.class, folder);
    }

    @Transactional
    public void setFolderGrant(UUID folder, UUID course, boolean grant) {
        courses.managedForUpdate(course);
        materials.ownedFolder(folder, true);
        if (grant) jdbc.update("insert into folder_course_access(folder_id, course_id) values (?, ?) on conflict do nothing", folder, course);
        else jdbc.update("delete from folder_course_access where folder_id = ? and course_id = ?", folder, course);
    }

    @Transactional(readOnly = true)
    public List<Rule> fileRules(UUID file) {
        materials.ownedFile(file, false);
        return jdbc.query("""
                select course_id, allow_read from file_course_access where file_id = ? order by course_id
                """, (row, index) -> new Rule(row.getObject(1, UUID.class), row.getBoolean(2)), file);
    }

    @Transactional
    public void setFileRule(UUID file, UUID course, Boolean allow) {
        courses.managedForUpdate(course);
        materials.ownedFile(file, true);
        if (allow == null) jdbc.update("delete from file_course_access where file_id = ? and course_id = ?", file, course);
        else jdbc.update("""
                insert into file_course_access(file_id, course_id, allow_read) values (?, ?, ?)
                on conflict (file_id, course_id) do update set allow_read = excluded.allow_read
                """, file, course, allow);
    }

    @Transactional(readOnly = true)
    public Page list(UUID course, Integer requestedLimit, String cursor) {
        courses.get(course);
        int limit = CursorCodec.limit(requestedLimit);
        String binding = "course-materials:v1:" + course + ":" + CurrentUser.get().id();
        var after = cursor == null ? null : cursors.decode(cursor, binding);
        var time = after == null ? null : OffsetDateTime.ofInstant(after.createdAt(), ZoneOffset.UTC);
        var rows = jdbc.query(ACCESSIBLE + """
                and (?::timestamptz is null or (f.created_at, f.id) < (?, ?))
                order by f.created_at desc, f.id desc limit ?
                """, (row, index) -> new CourseFile(MaterialRepository.file(row).dto(), course), course, course,
                time, time, after == null ? null : after.id(), limit + 1);
        boolean more = rows.size() > limit;
        var items = rows.subList(0, Math.min(rows.size(), limit));
        var last = more ? items.get(items.size() - 1) : null;
        String next = last == null ? null : cursors.encode(new CursorCodec.Position(last.createdAt(), last.id()), binding);
        return new Page(items, next, more);
    }

    @Transactional(readOnly = true)
    public MaterialRepository.File download(UUID course, UUID file) {
        courses.get(course);
        return jdbc.query(ACCESSIBLE + "and f.id = ?", (row, index) -> MaterialRepository.file(row), course, course, file)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Material not found in course"));
    }
}
