package tutor.assignmentservice.bank;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tutor.assignmentservice.common.error.ApiException;

public final class AnswerValidator {
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]+([.,][0-9]+)?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private AnswerValidator() {}

    record Definition(Object canonicalConfig, Object canonicalAnswer, int maxScore) {}

    static Definition definition(AnswerType type, JsonNode config, JsonNode correct, Integer score) {
        if (type == null) throw invalid("Answer type is required");
        if (config == null) config = JsonNodeFactory.instance.objectNode();
        int max = score == null && type != AnswerType.FILES ? 1 : score == null ? 0 : score;
        if (max < 1 || max > 100)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SCORE", "Maximum score must be between 1 and 100");

        return switch (type) {
            case TEXT, NUMBER -> {
                fields(config);
                fields(correct, "acceptedAnswers");
                var answers = new TreeSet<String>();
                for (String value : strings(correct.get("acceptedAnswers"))) answers.add(normalize(type, value));
                yield new Definition(List.of(), answers, max);
            }
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> {
                var options = options(config);
                var allowed = options.stream().map(Option::id).toList();
                Object normalized;
                if (type == AnswerType.SINGLE_CHOICE) {
                    fields(correct, "correctOptionId");
                    normalized = option(correct.get("correctOptionId"), allowed);
                } else {
                    fields(correct, "correctOptionIds");
                    normalized = selected(correct.get("correctOptionIds"), allowed);
                }
                yield new Definition(options, normalized, max);
            }
            case MATCHING -> {
                var keys = keys(config);
                yield new Definition(keys, matching(correct, keys, false), max);
            }
            case FILES -> {
                fields(config);
                if (correct != null && !correct.isNull()) throw invalid("File answers cannot contain a correct answer");
                yield new Definition(List.of(), null, max);
            }
        };
    }

    static Object response(AnswerType type, JsonNode config, JsonNode body, boolean draft) {
        return switch (type) {
            case TEXT, NUMBER -> {
                fields(body, "value");
                yield normalize(type, string(body.get("value")));
            }
            case SINGLE_CHOICE -> {
                fields(body, "optionId");
                yield option(body.get("optionId"), options(config).stream().map(Option::id).toList());
            }
            case MULTIPLE_CHOICE -> {
                fields(body, "optionIds");
                yield selected(body.get("optionIds"), options(config).stream().map(Option::id).toList());
            }
            case MATCHING -> matching(body, keys(config), draft);
            case FILES -> {
                if (body != null && !body.isNull()) throw invalid("File answers must be uploaded separately");
                yield null;
            }
        };
    }

    static String normalize(AnswerType type, String value) {
        String normalized = WHITESPACE.matcher(value).replaceAll(" ").strip();
        if (normalized.isEmpty()) throw invalid("Answer text is required");
        if (type == AnswerType.TEXT) return normalized.toLowerCase(Locale.ROOT);
        if (!NUMBER.matcher(normalized).matches()) throw invalid("Invalid decimal answer");
        return new BigDecimal(normalized.replace(',', '.')).stripTrailingZeros().toPlainString();
    }

    private record Option(String id, String text) {}

    private static List<Option> options(JsonNode config) {
        fields(config, "options");
        JsonNode values = array(config.get("options"), 2);
        var options = new ArrayList<Option>();
        var ids = new HashSet<String>();
        for (JsonNode value : values) {
            fields(value, "id", "text");
            String id = nonblank(value.get("id"));
            if (!ids.add(id)) throw invalid("Duplicate option ID");
            options.add(new Option(id, nonblank(value.get("text"))));
        }
        return options;
    }

    private static List<String> keys(JsonNode config) {
        fields(config, "keys");
        var keys = strings(config.get("keys"));
        if (Set.copyOf(keys).size() != keys.size()) throw invalid("Duplicate matching key");
        return keys;
    }

    private static String option(JsonNode node, List<String> allowed) {
        String id = nonblank(node);
        if (!allowed.contains(id)) throw invalid("Unknown option ID");
        return id;
    }

    private static Set<String> selected(JsonNode node, List<String> allowed) {
        var selection = new TreeSet<String>();
        for (JsonNode entry : array(node, 1)) {
            if (!selection.add(option(entry, allowed))) throw invalid("Duplicate option ID");
        }
        return selection;
    }

    private static TreeMap<String, String> matching(JsonNode node, List<String> keys, boolean draft) {
        if (node == null || !node.isObject() || node.size() > keys.size()) throw invalid("Invalid matching answer");
        var result = new TreeMap<String, String>();
        for (var field : node.properties()) {
            if (!keys.contains(field.getKey())) throw invalid("Unknown matching key");
            String value = string(field.getValue());
            if (draft && WHITESPACE.matcher(value).replaceAll(" ").isBlank()) continue;
            result.put(field.getKey(), normalize(AnswerType.TEXT, value));
        }
        if (!draft && result.size() != keys.size()) throw invalid("Matching answer is incomplete");
        return result;
    }

    private static List<String> strings(JsonNode node) {
        var result = new ArrayList<String>();
        for (JsonNode value : array(node, 1)) result.add(nonblank(value));
        return result;
    }

    private static JsonNode array(JsonNode node, int minimum) {
        if (node == null || !node.isArray() || node.size() < minimum || node.size() > 20)
            throw invalid("Invalid answer list size");
        return node;
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isString() || node.asString().length() > 1000 || node.asString().indexOf(0) >= 0)
            throw invalid("Invalid answer string");
        return node.asString();
    }

    private static String nonblank(JsonNode node) {
        String value = string(node);
        if (WHITESPACE.matcher(value).replaceAll(" ").isBlank()) throw invalid("Answer string is required");
        return value;
    }

    private static void fields(JsonNode node, String... fields) {
        if (node == null || !node.isObject() || node.size() != fields.length)
            throw invalid("Invalid answer object");
        for (String field : fields) {
            if (!node.has(field)) throw invalid("Invalid answer field");
        }
    }

    static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_ANSWER_FORMAT", detail);
    }
}
