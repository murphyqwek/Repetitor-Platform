package tutor.assignmentservice;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class ProviderStub implements AutoCloseable {
    public record Reply(int status, String body, long delayMillis) {}
    private final HttpServer server;
    public final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(503, "", 0));
    public final AtomicReference<String> authorization = new AtomicReference<>();
    public ProviderStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    var response = reply.get();
                    if (response.delayMillis() > 0) {
                        try { Thread.sleep(response.delayMillis()); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                    }
                    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.status(), bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    public void respond(int status, String body) { reply.set(new Reply(status,body,0)); }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void close() { server.stop(0); }
}
