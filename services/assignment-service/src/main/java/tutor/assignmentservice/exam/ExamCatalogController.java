package tutor.assignmentservice.exam;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tutor.assignmentservice.security.CurrentUser;
import static tutor.assignmentservice.exam.CatalogDtos.*;

@RestController
@RequestMapping("/api/v1")
public class ExamCatalogController {
    private final ExamCatalog catalog;
    public ExamCatalogController(ExamCatalog catalog) { this.catalog=catalog; }

    @GetMapping("/subjects")
    public List<Subject> subjects() { return catalog.subjects(); }
    @GetMapping("/exam-types")
    public List<ExamType> types(@RequestParam(required=false) Integer subjectId) { return catalog.types(subjectId); }
    @GetMapping("/topics")
    public Page<Topic> topics(@RequestParam(required=false) Integer subjectId,@RequestParam(required=false) String search,
            @RequestParam(required=false) Boolean active,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
        return catalog.topics(subjectId,search,active,limit,cursor);
    }
    @PostMapping("/topics")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Topic> topic(@Valid @RequestBody TopicCreate request) {
        var value=catalog.createTopic(request);
        return created("/api/v1/topics/"+value.id(),value);
    }
    @PatchMapping("/topics/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Topic patch(@PathVariable int id,@Valid @RequestBody TopicPatch request) { return catalog.patchTopic(id,request); }
    @DeleteMapping("/topics/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable int id) {
        catalog.patchTopic(id,new TopicPatch(null,false));
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/exams")
    public Page<ExamSummary> exams(@RequestParam(required=false) Integer examTypeId,@RequestParam(required=false) Integer year,
            @RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
        return catalog.exams(examTypeId,year,limit,cursor);
    }
    @PostMapping("/exams")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ExamDetail> exam(@Valid @RequestBody ExamCreate request) {
        var value=catalog.createExam(request);
        return created("/api/v1/exams/"+value.id(),value);
    }
    @GetMapping("/exams/{id}")
    public ExamDetail exam(@PathVariable UUID id) { return catalog.exam(id); }
    @GetMapping("/exams/{id}/structures")
    public Page<Structure> structures(@PathVariable UUID id,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
        return catalog.structures(id,limit,cursor);
    }
    @PostMapping("/exams/{id}/structures")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Structure> structure(@PathVariable UUID id,@Valid @RequestBody StructureCreate request) {
        var value=catalog.createStructure(id,request,CurrentUser.get().id());
        return created("/api/v1/exams/"+id+"/structures/"+value.id(),value);
    }
    @GetMapping("/exams/{id}/structures/{version}")
    public Structure structure(@PathVariable UUID id,@PathVariable UUID version) { return catalog.structure(id,version); }
    @GetMapping("/exams/{id}/scales")
    public List<ScaleSummary> scales(@PathVariable UUID id) { return catalog.scales(id); }
    @PostMapping("/exams/{id}/scales")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScaleDetail> scale(@PathVariable UUID id,@Valid @RequestBody ScaleCreate request) {
        var value=catalog.createScale(id,request);
        return created("/api/v1/grading-scales/"+value.id(),value);
    }
    @GetMapping("/grading-scales/{id}")
    public ScaleDetail scale(@PathVariable UUID id) { return catalog.scale(id); }
    @GetMapping("/grading-scales/{id}/versions")
    public Page<ScaleVersion> versions(@PathVariable UUID id,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
        return catalog.scaleVersions(id,limit,cursor);
    }
    @PostMapping("/grading-scales/{id}/versions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ScaleVersion> version(@PathVariable UUID id,@Valid @RequestBody ScaleUpdate request) {
        var value=catalog.createScaleVersion(id,request);
        return created("/api/v1/grading-scales/"+id+"/versions/"+value.id(),value);
    }
    @GetMapping("/grading-scales/{id}/versions/{version}")
    public ScaleVersion version(@PathVariable UUID id,@PathVariable UUID version) { return catalog.scaleVersion(id,version); }

    private static <T> ResponseEntity<T> created(String path,T body) { return ResponseEntity.created(URI.create(path)).body(body); }
}
