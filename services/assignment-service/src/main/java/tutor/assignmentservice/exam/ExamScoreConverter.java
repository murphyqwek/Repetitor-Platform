package tutor.assignmentservice.exam;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tutor.assignmentservice.common.error.ApiException;

@Component
public class ExamScoreConverter {
    private final JdbcTemplate jdbc;
    public ExamScoreConverter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Score(Integer testScore,Integer schoolMark) {}

    @Transactional(readOnly=true)
    public Score convert(UUID scaleVersionId,int primaryScore) {
        var result=jdbc.query("select test_score,school_mark::integer from scale_value where scale_version_id=? and primary_score=?",
                (r,i)->new Score(r.getObject(1,Integer.class),r.getObject(2,Integer.class)),scaleVersionId,primaryScore);
        if(result.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,"INVALID_SCORE","No value in the specified scale version");
        return result.get(0);
    }
}
