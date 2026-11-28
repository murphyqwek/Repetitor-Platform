package tutor.assignmentservice.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tutor.assignmentservice.common.error.ApiException;

class AnswerCheckerTest {
    private final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"2,5", "2.50", "+2.5", " 2.5 "})
    void decimalsAreComparedExactly(String answer) {
        assertThat(score(AnswerType.NUMBER, "{}", "{\"acceptedAnswers\":[\"2.5\"]}",
                json.writeValueAsString(java.util.Map.of("value", answer)))).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5/2", "NaN", "1e2", "2.50000000000000001"})
    void unequalOrUnsupportedDecimalsScoreZero(String answer) {
        assertThat(score(AnswerType.NUMBER, "{}", "{\"acceptedAnswers\":[\"2.5\"]}",
                json.writeValueAsString(java.util.Map.of("value", answer)))).isZero();
    }

    @Test
    void normalizesTextButPreservesYo() {
        String correct = "{\"acceptedAnswers\":[\"Москва\",\"Город Орёл\"]}";
        assertThat(score(AnswerType.TEXT, "{}", correct, "{\"value\":\"  город\\u00a0ОРЁЛ  \"}")).isEqualTo(5);
        assertThat(score(AnswerType.TEXT, "{}", correct, "{\"value\":\"город Орел\"}")).isZero();
    }

    @Test
    void choiceMustMatchKnownUniqueOptions() {
        String config = "{\"options\":[{\"id\":\"A\",\"text\":\"1\"},{\"id\":\"B\",\"text\":\"2\"}]}";
        assertThat(score(AnswerType.SINGLE_CHOICE, config, "{\"correctOptionId\":\"B\"}", "{\"optionId\":\"B\"}")).isEqualTo(5);
        assertThat(score(AnswerType.SINGLE_CHOICE, config, "{\"correctOptionId\":\"B\"}", "{\"optionId\":\"C\"}")).isZero();
        String correct = "{\"correctOptionIds\":[\"A\",\"B\"]}";
        assertThat(score(AnswerType.MULTIPLE_CHOICE, config, correct, "{\"optionIds\":[\"B\",\"A\"]}")).isEqualTo(5);
        assertThat(score(AnswerType.MULTIPLE_CHOICE, config, correct, "{\"optionIds\":[\"A\",\"A\"]}")).isZero();
        assertThat(score(AnswerType.MULTIPLE_CHOICE, config, correct, "{\"optionIds\":[\"A\"]}")).isZero();
    }

    @Test
    void matchingAllowsPartialDraftsAndRequiresFullMatchForScore() {
        String config = "{\"keys\":[\"A\",\"B\"]}";
        String correct = "{\"A\":\"1\",\"B\":\"Да\"}";
        AnswerChecker.validate(AnswerType.MATCHING, json.readTree(config), json.readTree("{\"A\":\"1\",\"B\":\" \"}"), true);
        assertThat(score(AnswerType.MATCHING, config, correct, "{\"B\":\"ДА\",\"A\":\"1\"}")).isEqualTo(5);
        assertThat(score(AnswerType.MATCHING, config, correct, "{\"A\":\"1\"}")).isZero();
        assertThatThrownBy(() -> AnswerChecker.validate(AnswerType.MATCHING, json.readTree(config),
                json.readTree("{\"C\":\"1\"}"), true)).isInstanceOf(ApiException.class);
    }

    @Test
    void filesRequireManualScoringAndExplicitMaximum() {
        assertThat(AnswerValidator.definition(AnswerType.TEXT, json.readTree("{}"),
                json.readTree("{\"acceptedAnswers\":[\"yes\"]}"), null).maxScore()).isOne();
        assertThat(AnswerValidator.definition(AnswerType.FILES, json.readTree("{}"), null, 10).maxScore()).isEqualTo(10);
        assertThatThrownBy(() -> AnswerValidator.definition(AnswerType.FILES, json.readTree("{}"), null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AnswerValidator.definition(AnswerType.FILES, json.readTree("{}"), json.readTree("{}"), 10))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AnswerValidator.definition(AnswerType.FILES, json.readTree("{}"), null, 101))
                .isInstanceOf(ApiException.class);
    }

    private int score(AnswerType type, String config, String correct, String body) {
        return AnswerChecker.score(type, json.readTree(config), json.readTree(correct), json.readTree(body), 5);
    }
}
