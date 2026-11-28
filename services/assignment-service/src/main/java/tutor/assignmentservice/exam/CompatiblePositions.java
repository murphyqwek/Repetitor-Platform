package tutor.assignmentservice.exam;

import java.util.*;
import java.util.stream.Collectors;
import static tutor.assignmentservice.exam.CatalogDtos.*;

/** Candidate mapping for the task reclassification workflow introduced with task versions in stage 5. */
public final class CompatiblePositions {
    private CompatiblePositions() {}
    public static Map<UUID,UUID> between(Structure previous,Structure next) {
        if(!previous.examDefinitionId().equals(next.examDefinitionId())) return Map.of();
        var byNumber=next.positions().stream().collect(Collectors.toMap(Position::position,p->p));
        var result=new LinkedHashMap<UUID,UUID>();
        for(var old:previous.positions()) {
            var replacement=byNumber.get(old.position());
            if(replacement!=null && old.maxScore()==replacement.maxScore() && old.checkMode()==replacement.checkMode()
                    && topics(old).equals(topics(replacement))) result.put(old.id(),replacement.id());
        }
        return Map.copyOf(result);
    }
    private static Set<Integer> topics(Position position) {
        return position.topics().stream().map(TopicLabel::id).collect(Collectors.toSet());
    }
}
