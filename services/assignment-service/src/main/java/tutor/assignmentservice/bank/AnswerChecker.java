package tutor.assignmentservice.bank;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import tutor.assignmentservice.common.error.ApiException;

public final class AnswerChecker {
    private AnswerChecker() {}

    public static void validate(AnswerType type, JsonNode config, JsonNode body, boolean draft) {
        AnswerValidator.response(type, config, body, draft);
    }

    public static int score(AnswerType type, JsonNode config, JsonNode correct, JsonNode body, int maximum) {
        if (type == AnswerType.FILES) throw AnswerValidator.invalid("File answers require manual grading");
        var expected = AnswerValidator.definition(type, config, correct, maximum).canonicalAnswer();
        Object actual;
        try {
            actual = AnswerValidator.response(type, config, body, false);
        } catch (ApiException invalidResponse) {
            return 0;
        }
        boolean matches = expected instanceof Set<?> accepted && (type == AnswerType.TEXT || type == AnswerType.NUMBER)
                ? accepted.contains(actual) : expected.equals(actual);
        return matches ? maximum : 0;
    }
}
