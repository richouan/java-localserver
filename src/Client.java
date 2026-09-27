import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
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
