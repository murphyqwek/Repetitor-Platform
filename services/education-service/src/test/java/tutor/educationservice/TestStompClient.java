package tutor.educationservice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

final class TestStompClient implements WebSocket.Listener, AutoCloseable {
    private static final HttpClient http = HttpClient.newHttpClient();
    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder buffer = new StringBuilder();
    private final WebSocket socket;
    private final ObjectMapper mapper;

    TestStompClient(int port, String token, ObjectMapper mapper) throws Exception {
        this.mapper = mapper;
        socket = http.newWebSocketBuilder().subprotocols("v12.stomp").connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), this).get(10, TimeUnit.SECONDS);
        try {
            sendFrame("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer " + token + "\n\n");
            assertThat(take()).startsWith("CONNECTED");
        } catch (Exception | AssertionError error) {
            socket.abort();
            throw error;
        }
    }

    void subscribe(String id, String destination) throws Exception {
        sendFrame("SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\n\n");
    }

    void send(String course, String body) throws Exception {
        sendFrame("SEND\ndestination:/app/courses/" + course + "/chat.send\ncontent-type:application/json\n\n" + body);
    }

    private void sendFrame(String frame) throws Exception { socket.sendText(frame + "\0", true).get(5, TimeUnit.SECONDS); }

    String take() throws InterruptedException {
        String value = frames.poll(10, TimeUnit.SECONDS);
        assertThat(value).as("STOMP frame").isNotNull();
        return value;
    }

    JsonNode event() throws InterruptedException {
        String frame = take();
        assertThat(frame).startsWith("MESSAGE");
        return mapper.readTree(frame.substring(frame.indexOf("\n\n") + 2));
    }

    int closeStatus() throws Exception { return closed.get(10, TimeUnit.SECONDS); }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            for (String frame : buffer.toString().split("\0")) {
                if (!frame.isBlank()) frames.add(frame.stripLeading());
            }
            buffer.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.complete(statusCode);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }

    @Override
    public void close() { socket.abort(); }
}
