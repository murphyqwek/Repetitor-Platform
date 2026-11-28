package tutor.assignmentservice.exam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tutor.assignmentservice.common.error.ApiException;
import tutor.assignmentservice.common.pagination.CursorCodec;
import tutor.assignmentservice.security.CurrentUser;
import static tutor.assignmentservice.exam.CatalogDtos.*;

@Service
@Transactional(readOnly = true)
public class ExamCatalog {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CursorCodec cursors;

    public ExamCatalog(JdbcTemplate jdbc, Clock clock, CursorCodec cursors) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cursors = cursors;
    }

    public List<Subject> subjects() {
        return jdbc.query("select id,name from subject order by name,id", (r,i) -> new Subject(r.getInt(1),r.getString(2)));
    }

    public List<ExamType> types(Integer subject) {
        return jdbc.query("select id,code,name,subject_id from exam_type where (?::integer is null or subject_id=?) order by name,id",
                (r,i) -> new ExamType(r.getInt(1),r.getString(2),r.getString(3),r.getInt(4)),subject,subject);
    }

    private static Topic topic(ResultSet r, int i) throws SQLException {
        return new Topic(r.getInt("id"),r.getInt("subject_id"),r.getString("name"),r.getBoolean("is_active"));
    }

    public Page<Topic> topics(Integer subject, String search, Boolean active, Integer limit, String cursor) {
        String text = search == null ? "" : search.strip();
        String filter = List.of(String.valueOf(subject),text,String.valueOf(active)).toString();
        var args = new ArrayList<Object>(Arrays.asList(subject,subject,text,active,active));
        String query = "select * from topic where (?::integer is null or subject_id=?) and position(lower(?) in lower(name))>0 "
                + "and (?::boolean is null or is_active=?)";
        return page(query,args,"name",false,"topics",filter,limit,cursor,ExamCatalog::topic,
                t -> new CursorCodec.CatalogPosition(t.name(),Integer.toString(t.id())),false);
    }

    @Transactional
    public Topic createTopic(TopicCreate input) {
        requireSubject(input.subjectId());
        int id = jdbc.queryForObject("insert into topic(subject_id,name) values (?,?) returning id",Integer.class,input.subjectId(),name(input.name()));
        return topic(id);
    }

    @Transactional
    public Topic patchTopic(int id, TopicPatch input) {
        topic(id);
        if (input.name() == null && input.isActive() == null) throw invalid("Empty topic patch");
        jdbc.update("update topic set name=coalesce(?,name),is_active=coalesce(?,is_active) where id=?",
                input.name()==null ? null : name(input.name()),input.isActive(),id);
        return topic(id);
    }

    public Topic topic(int id) {
        return one(jdbc.query("select * from topic where id=?",ExamCatalog::topic,id));
    }

    private void requireSubject(int id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from subject where id=?)",Boolean.class,id)))
            throw invalid("Unknown subject");
    }

    private static ExamSummary exam(ResultSet r,int i) throws SQLException {
        return new ExamSummary(r.getObject("id",UUID.class),r.getInt("exam_type_id"),r.getInt("year"),r.getObject("current_structure_version_id",UUID.class));
    }

    public Page<ExamSummary> exams(Integer type,Integer year,Integer limit,String cursor) {
        return page("select * from exam_definition where (?::integer is null or exam_type_id=?) and (?::integer is null or year=?)",
                new ArrayList<>(Arrays.asList(type,type,year,year)),"year",true,"exams",type+"/"+year,limit,cursor,ExamCatalog::exam,
                e -> new CursorCodec.CatalogPosition(Integer.toString(e.year()),e.id().toString()),true);
    }

    public ExamDetail exam(UUID id) {
        var e=one(jdbc.query("select * from exam_definition where id=?",ExamCatalog::exam,id));
        return new ExamDetail(e.id(),e.examTypeId(),e.year(),e.currentStructureVersionId(),structure(id,e.currentStructureVersionId()));
    }

    @Transactional
    public ExamDetail createExam(ExamCreate input) {
        if (jdbc.queryForList("select id from exam_type where id=?",input.examTypeId()).isEmpty()) throw invalid("Unknown exam type");
        var id=UUID.randomUUID();
        jdbc.update("insert into exam_definition(id,exam_type_id,year,created_at) values (?,?,?,?)",
                id,input.examTypeId(),input.year(),java.sql.Timestamp.from(clock.instant()));
        writeStructure(id,1,input.structure());
        return exam(id);
    }

    @Transactional
    public Structure createStructure(UUID examId,StructureCreate input) {
        var current=one(jdbc.query("select current_structure_version_id from exam_definition where id=? for update",
                (r,i)->r.getObject(1,UUID.class),examId));
        if (!current.equals(input.expectedStructureVersionId())) throw conflict("STRUCTURE_VERSION_CONFLICT");
        int version=jdbc.queryForObject("select version+1 from exam_structure_version where id=?",Integer.class,current);
        UUID id=writeStructure(examId,version,input.structure());
        return structure(examId,id);
    }

    private UUID writeStructure(UUID exam,int version,StructureInput input) {
        var positions=input.positions();
        var numbers=new HashSet<Integer>();
        int subject=jdbc.queryForObject("select t.subject_id from exam_definition e join exam_type t on t.id=e.exam_type_id where e.id=?",Integer.class,exam);
        for(var p:positions) {
            if(!numbers.add(p.position()) || p.position()>positions.size()) throw invalid("Positions must be consecutive from 1");
            for(int topic:p.topicIds()) {
                var value=topic(topic);
                if(value.subjectId()!=subject || !value.isActive()) throw invalid("Select active topics in the exam subject");
            }
        }
        var id=UUID.randomUUID();
        jdbc.update("insert into exam_structure_version(id,exam_definition_id,version,duration_minutes,created_at) values(?,?,?,?,?)",
                id,exam,version,input.durationMinutes(),java.sql.Timestamp.from(clock.instant()));
        for(var p:positions) {
            var position=UUID.randomUUID();
            jdbc.update("insert into exam_position(id,structure_version_id,position,max_score,check_mode) values(?,?,?,?,?)",
                    position,id,p.position(),p.maxScore(),p.checkMode().name());
            for(int topic:p.topicIds()) jdbc.update("insert into exam_position_topic values(?,?)",position,topic);
        }
        jdbc.update("update exam_definition set current_structure_version_id=? where id=?",id,exam);
        return id;
    }

    public Structure structure(UUID exam,UUID version) {
        return one(jdbc.query("select * from exam_structure_version where exam_definition_id=? and id=?",
                (r,i)->structure(r),exam,version));
    }

    private Structure structure(ResultSet r) throws SQLException {
        UUID id=r.getObject("id",UUID.class);
        var positions=jdbc.query("select * from exam_position where structure_version_id=? order by position",
                (p,i)->new Position(p.getObject("id",UUID.class),p.getInt("position"),p.getInt("max_score"),CheckMode.valueOf(p.getString("check_mode")),
                        jdbc.query("select t.id,t.name from topic t join exam_position_topic pt on t.id=pt.topic_id where pt.exam_position_id=? order by t.name,t.id",
                                (t,j)->new TopicLabel(t.getInt(1),t.getString(2)),p.getObject("id",UUID.class))),id);
        return new Structure(id,r.getObject("exam_definition_id",UUID.class),r.getInt("version"),r.getInt("duration_minutes"),
                positions.stream().mapToInt(Position::maxScore).sum(),positions,r.getTimestamp("created_at").toInstant());
    }

    public Page<Structure> structures(UUID exam,Integer limit,String cursor) {
        exam(exam);
        return page("select * from exam_structure_version where exam_definition_id=?",new ArrayList<>(List.of(exam)),
                "version",true,"structures",exam.toString(),limit,cursor,(r,i)->structure(r),
                v->new CursorCodec.CatalogPosition(Integer.toString(v.version()),v.id().toString()),true);
    }

    private static ScaleSummary scale(ResultSet r,int i) throws SQLException {
        return new ScaleSummary(r.getObject("id",UUID.class),r.getObject("exam_definition_id",UUID.class),
                r.getString("name"),r.getObject("current_version_id",UUID.class));
    }

    public List<ScaleSummary> scales(UUID exam) {
        exam(exam);
        return jdbc.query("select * from grading_scale where exam_definition_id=? order by name,id",ExamCatalog::scale,exam);
    }

    public ScaleDetail scale(UUID id) {
        var s=one(jdbc.query("select * from grading_scale where id=?",ExamCatalog::scale,id));
        return new ScaleDetail(s.id(),s.examDefinitionId(),s.name(),s.currentVersionId(),scaleVersion(id,s.currentVersionId()));
    }

    @Transactional
    public ScaleDetail createScale(UUID exam,ScaleCreate input) {
        var e=exam(exam);
        var id=UUID.randomUUID();
        jdbc.update("insert into grading_scale(id,exam_definition_id,name) values(?,?,?)",id,e.id(),name(input.name()));
        writeScale(id,exam,1,input.structureVersionId(),input.values());
        return scale(id);
    }

    @Transactional
    public ScaleVersion createScaleVersion(UUID scale,ScaleUpdate input) {
        var summary=one(jdbc.query("select * from grading_scale where id=? for update",ExamCatalog::scale,scale));
        if(!summary.currentVersionId().equals(input.expectedVersionId())) throw conflict("SCALE_VERSION_CONFLICT");
        int version=jdbc.queryForObject("select version+1 from grading_scale_version where id=?",Integer.class,summary.currentVersionId());
        var id=writeScale(scale,summary.examDefinitionId(),version,input.structureVersionId(),input.values());
        return scaleVersion(scale,id);
    }

    private UUID writeScale(UUID scale,UUID exam,int version,UUID structure,List<Value> values) {
        var layout=structure(exam,structure);
        boolean profile="EGE_PROFILE".equals(jdbc.queryForObject("select t.code from exam_type t join exam_definition e on e.exam_type_id=t.id where e.id=?",String.class,exam));
        if(values.size()!=layout.maxScore()+1) throw invalid("Scale must cover every score from zero to maximum");
        var sorted=values.stream().sorted(Comparator.comparingInt(Value::primaryScore)).toList();
        int previous=-1;
        for(int i=0;i<sorted.size();i++) {
            var v=sorted.get(i);
            Integer score=profile ? v.testScore() : v.schoolMark();
            Integer other=profile ? v.schoolMark() : v.testScore();
            if(v.primaryScore()!=i || score==null || other!=null || score<previous) throw invalid("Scale values must be complete, monotonic and match exam type");
            previous=score;
        }
        var id=UUID.randomUUID();
        jdbc.update("insert into grading_scale_version(id,scale_id,structure_version_id,version,created_at) values(?,?,?,?,?)",
                id,scale,structure,version,java.sql.Timestamp.from(clock.instant()));
        for(var v:sorted) jdbc.update("insert into scale_value values(?,?,?,?)",id,v.primaryScore(),v.testScore(),v.schoolMark());
        jdbc.update("update grading_scale set current_version_id=? where id=?",id,scale);
        return id;
    }

    public ScaleVersion scaleVersion(UUID scale,UUID id) {
        return one(jdbc.query("select * from grading_scale_version where scale_id=? and id=?", (r,i)->scaleVersion(r),scale,id));
    }

    private ScaleVersion scaleVersion(ResultSet r) throws SQLException {
        UUID id=r.getObject("id",UUID.class);
        var values=jdbc.query("select primary_score,test_score,school_mark::integer from scale_value where scale_version_id=? order by primary_score",
                (v,i)->new Value(v.getInt(1),v.getObject(2,Integer.class),v.getObject(3,Integer.class)),id);
        return new ScaleVersion(id,r.getObject("scale_id",UUID.class),r.getObject("structure_version_id",UUID.class),
                r.getInt("version"),values,r.getTimestamp("created_at").toInstant());
    }

    public Page<ScaleVersion> scaleVersions(UUID scale,Integer limit,String cursor) {
        scale(scale);
        return page("select * from grading_scale_version where scale_id=?",new ArrayList<>(List.of(scale)),
                "version",true,"scale-versions",scale.toString(),limit,cursor,(r,i)->scaleVersion(r),
                v->new CursorCodec.CatalogPosition(Integer.toString(v.version()),v.id().toString()),true);
    }

    private <T> Page<T> page(String sql,List<Object> args,String sort,boolean descending,String resource,String filters,Integer requested,
                             String cursor,RowMapper<T> mapper,Function<T,CursorCodec.CatalogPosition> position,boolean uuid) {
        int limit=CursorCodec.limit(requested);
        var scope=new CursorCodec.Scope(CurrentUser.get().id(),resource,sort+(descending ? " DESC" : " ASC"),filters);
        if(cursor!=null) {
            var after=cursors.decodeCatalog(cursor,scope);
            try {
                args.add(sort.equals("name") ? after.sortKey() : Integer.valueOf(after.sortKey()));
                args.add(uuid ? UUID.fromString(after.id()) : Integer.valueOf(after.id()));
            } catch(IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURSOR","Invalid cursor"); }
            sql+=" and ("+sort+",id) "+(descending ? "<" : ">")+" (?,?)";
        }
        sql+=" order by "+sort+(descending ? " desc,id desc" : " asc,id asc")+" limit ?";
        args.add(limit+1);
        var found=jdbc.query(sql,mapper,args.toArray());
        boolean more=found.size()>limit;
        var items=List.copyOf(found.subList(0,Math.min(limit,found.size())));
        return new Page<>(items,more ? cursors.encodeCatalog(position.apply(items.get(items.size()-1)),scope) : null,more);
    }

    private static String name(String name) {
        if(name==null || name.isBlank()) throw invalid("Name must not be blank");
        return name.strip();
    }
    private static <T> T one(List<T> rows) {
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Resource not found");
        return rows.get(0);
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"INVALID_REQUEST",message); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Current version changed"); }
}
