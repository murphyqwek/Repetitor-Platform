package tutor.educationservice;

import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class IdentityHttpStub implements AutoCloseable {
    record Person(UUID id, List<String> roles, boolean blocked) {}

    final Map<UUID, Person> people = new ConcurrentHashMap<>();
    final AtomicInteger lookups = new AtomicInteger();
    final AtomicInteger lookupStatus = new AtomicInteger(200);
    volatile Runnable beforeLookup = () -> {};
    volatile String firstName = "Иван";
    private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
        var thread = new Thread(task, "identity-http-test");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpServer server;

    IdentityHttpStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(threads);
            server.createContext("/api/v1/", this::handle);
            server.start();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            UUID actor = UUID.fromString(SignedJWT.parse(cookie.substring("access_token=".length())).getJWTClaimsSet().getSubject());
            var caller = people.get(actor);
            if (caller == null) { reply(exchange, 404, "{}"); return; }
            if (caller.blocked()) { reply(exchange, 403, "{}"); return; }
            String path = exchange.getRequestURI().getPath();
            Person person;
            if (path.equals("/api/v1/users/me")) person = caller;
            else if (path.startsWith("/api/v1/directory/users/")) {
                if (!caller.roles().contains("TEACHER") && !caller.roles().contains("ADMIN")) { reply(exchange, 403, "{}"); return; }
                lookups.incrementAndGet();
                beforeLookup.run();
                if (lookupStatus.get() != 200) { reply(exchange, lookupStatus.get(), "private-upstream-diagnostic"); return; }
                person = people.get(UUID.fromString(path.substring("/api/v1/directory/users/".length())));
            } else { reply(exchange, 404, "{}"); return; }
            if (person == null) { reply(exchange, 404, "{}"); return; }
            reply(exchange, 200, "{\"id\":\"" + person.id() + "\",\"firstName\":\"" + firstName + "\",\"surname\":\"Иванов\",\"roles\":[\""
                    + String.join("\",\"", person.roles()) + "\"],\"blocked\":" + person.blocked() + ",\"nameRequested\":true}");
        } catch (Exception exception) {
            reply(exchange, 500, "{}");
        } finally {
            exchange.close();
        }
    }

    private void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Override
    public void close() {
        server.stop(0);
        threads.shutdownNow();
    }
}
