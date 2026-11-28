package tutor.assignmentservice.bank;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import org.springframework.http.HttpStatus;
import tutor.assignmentservice.common.error.ApiException;

final class TaskMarkdown {
    private static final Parser PARSER = Parser.builder().maxOpenBlockParsers(100).build();
    private static final MarkdownRenderer RENDERER = MarkdownRenderer.builder().build();
    private static final Pattern REFERENCE = Pattern.compile(
            "attachment:([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");

    record Usage(boolean statement, boolean explanation) {}

    record Parsed(Document document, Set<UUID> files, String source) {
        String canonical(Function<UUID, String> hash) {
            if (files.isEmpty()) return source;
            document.accept(new AbstractVisitor() {
                @Override public void visit(Image image) {
                    image.setDestination("attachment:" + hash.apply(reference(image.getDestination())));
                    visitChildren(image);
                }

                @Override public void visit(LinkReferenceDefinition definition) {
                    definition.unlink();
                }
            });
            return RENDERER.render(document);
        }
    }

    private TaskMarkdown() {}

    static Parsed parse(String source) {
        var ids = new HashSet<UUID>();
        Document document = (Document) PARSER.parse(source == null ? "" : source);
        document.accept(new AbstractVisitor() {
            @Override public void visit(HtmlInline html) { throw invalid("Raw HTML is prohibited"); }
            @Override public void visit(HtmlBlock html) { throw invalid("Raw HTML is prohibited"); }

            @Override public void visit(Link link) {
                try {
                    String scheme = URI.create(link.getDestination()).getScheme();
                    if (scheme != null && !Set.of("http", "https", "mailto").contains(scheme.toLowerCase(java.util.Locale.ROOT)))
                        throw invalid("Unsupported link protocol");
                } catch (IllegalArgumentException ex) {
                    throw invalid("Invalid link URL");
                }
                visitChildren(link);
            }

            @Override public void visit(Image image) {
                ids.add(reference(image.getDestination()));
                visitChildren(image);
            }
        });
        return new Parsed(document, Set.copyOf(ids), source);
    }

    static Map<UUID, Usage> usage(Parsed statement, Parsed explanation) {
        var files = new HashMap<UUID, Usage>();
        for (UUID id : statement.files()) files.put(id, new Usage(true, explanation.files().contains(id)));
        for (UUID id : explanation.files()) files.putIfAbsent(id, new Usage(false, true));
        if (files.size() > 10)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "ATTACHMENT_LIMIT_EXCEEDED", "A task may use up to 10 images");
        return files;
    }

    private static UUID reference(String value) {
        var match = REFERENCE.matcher(value);
        if (!match.matches()) throw invalid("Images must reference an uploaded attachment");
        return UUID.fromString(match.group(1));
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MARKDOWN", detail);
    }
}
