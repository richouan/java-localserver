import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Client {
    public enum State {
        READING_HEADERS,
        READING_BODY,
        WRITING_RESPONSE,
        CLOSED
    }

    private SocketChannel channel;
    private State state;
    private ByteBuffer readBuffer;
    private StringBuilder rawRequest;
    private long lastActiveTime;

    // Parsed HTTP request data
    private String method;
    private String path;
    private String version;
    private Map<String, String> headers;

    // Outgoing response buffer
    private ByteBuffer responseBuffer;

    public Client(SocketChannel channel) {
        this.channel = channel;
        this.state = State.READING_HEADERS;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.rawRequest = new StringBuilder();
        this.headers = new HashMap<>();
        this.lastActiveTime = System.currentTimeMillis();
    }

    public SocketChannel getChannel() {
        return this.channel;
    }

    public State getState() {
        return this.state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public ByteBuffer getReadBuffer() {
        return this.readBuffer;
    }

    public StringBuilder getRawRequest() {
        return this.rawRequest;
    }

    public void updateActivity() {
        this.lastActiveTime = System.currentTimeMillis();
    }

    public long getLastActiveTime() {
        return this.lastActiveTime;
    }

    public String getMethod() {
        return this.method;
    }

    public String getPath() {
        return this.path;
    }

    public String getVersion() {
        return this.version;
    }

    public Map<String, String> getHeaders() {
        return this.headers;
    }

    public ByteBuffer getResponseBuffer() {
        return this.responseBuffer;
    }

    // Prepare a simple HTTP response
    public void prepareResponse(int statusCode, String statusText, String body, String contentType) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder res = new StringBuilder();
        res.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        res.append("Content-Type: ").append(contentType).append("\r\n");
        res.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        res.append("Connection: close\r\n");
        res.append("\r\n");

        byte[] headerBytes = res.toString().getBytes(StandardCharsets.UTF_8);

        // Allocate a buffer holding headers + body
        this.responseBuffer = ByteBuffer.allocate(headerBytes.length + bodyBytes.length);
        this.responseBuffer.put(headerBytes);
        this.responseBuffer.put(bodyBytes);
        this.responseBuffer.flip(); // ready to write

        this.state = State.WRITING_RESPONSE;
    }

    // Parse HTTP headers once \r\n\r\n is found
    public boolean parseHeaders() {
        String content = this.rawRequest.toString();
        int headerEnd = content.indexOf("\r\n\r\n");
        if (headerEnd == -1) {
            return false;
        }

        String headerSection = content.substring(0, headerEnd);
        String[] lines = headerSection.split("\r\n");

        if (lines.length == 0) {
            return false;
        }

        // 1. Parse Request Line: e.g. "GET /index.html HTTP/1.1"
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 3) {
            return false;
        }
        this.method = requestLine[0].trim();
        this.path = requestLine[1].trim();
        this.version = requestLine[2].trim();

        // 2. Parse Headers
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colonIndex = line.indexOf(":");
            if (colonIndex != -1) {
                String key = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                this.headers.put(key, value);
            }
        }

        return true;
    }
}
