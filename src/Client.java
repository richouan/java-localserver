import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
    private boolean keepAlive;

    // Parsed HTTP request data
    private String method;
    private String path;
    private String version;
    private Map<String, String> headers;

    // Outgoing response buffer (for headers or small responses)
    private ByteBuffer responseBuffer;

    // FileChannel for streaming static files from disk
    private FileChannel fileChannel;
    private long fileBytesRemaining;

    public Client(SocketChannel channel) {
        this.channel = channel;
        this.state = State.READING_HEADERS;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.rawRequest = new StringBuilder();
        this.headers = new HashMap<>();
        this.lastActiveTime = System.currentTimeMillis();
        this.keepAlive = false;
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

    public boolean isKeepAlive() {
        return this.keepAlive;
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

    public FileChannel getFileChannel() {
        return this.fileChannel;
    }

    public long getFileBytesRemaining() {
        return this.fileBytesRemaining;
    }

    public void decrementFileBytes(long count) {
        this.fileBytesRemaining -= count;
    }

    public void closeFileChannel() {
        if (this.fileChannel != null && this.fileChannel.isOpen()) {
            try {
                this.fileChannel.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // Reset client state to wait for the next HTTP request on keep-alive
    public void resetForNextRequest() {
        closeFileChannel();
        this.fileChannel = null;
        this.fileBytesRemaining = 0;
        this.responseBuffer = null;
        this.method = null;
        this.path = null;
        this.version = null;
        this.headers.clear();
        this.rawRequest.setLength(0);
        this.state = State.READING_HEADERS;
        this.lastActiveTime = System.currentTimeMillis();
    }

    // Prepare a response with a FileChannel
    public void prepareFileResponse(int statusCode, String statusText, Path filePath, String contentType) throws IOException {
        this.fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
        long fileSize = this.fileChannel.size();
        this.fileBytesRemaining = fileSize;

        StringBuilder res = new StringBuilder();
        res.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        res.append("Content-Type: ").append(contentType).append("\r\n");
        res.append("Content-Length: ").append(fileSize).append("\r\n");
        res.append("Connection: ").append(this.keepAlive ? "keep-alive" : "close").append("\r\n");
        res.append("\r\n");

        byte[] headerBytes = res.toString().getBytes(StandardCharsets.UTF_8);
        this.responseBuffer = ByteBuffer.wrap(headerBytes);

        this.state = State.WRITING_RESPONSE;
    }

    // Prepare a simple memory string response
    public void prepareResponse(int statusCode, String statusText, String body, String contentType) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder res = new StringBuilder();
        res.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        res.append("Content-Type: ").append(contentType).append("\r\n");
        res.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        res.append("Connection: ").append(this.keepAlive ? "keep-alive" : "close").append("\r\n");
        res.append("\r\n");

        byte[] headerBytes = res.toString().getBytes(StandardCharsets.UTF_8);

        this.responseBuffer = ByteBuffer.allocate(headerBytes.length + bodyBytes.length);
        this.responseBuffer.put(headerBytes);
        this.responseBuffer.put(bodyBytes);
        this.responseBuffer.flip();

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

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 3) {
            return false;
        }
        this.method = requestLine[0].trim();
        this.path = requestLine[1].trim();
        this.version = requestLine[2].trim();

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colonIndex = line.indexOf(":");
            if (colonIndex != -1) {
                String key = line.substring(0, colonIndex).trim().toLowerCase();
                String value = line.substring(colonIndex + 1).trim();
                this.headers.put(key, value);
            }
        }

        // Determine keep-alive (HTTP/1.1 defaults to keep-alive unless Connection: close)
        String connHeader = this.headers.get("connection");
        if ("close".equalsIgnoreCase(connHeader)) {
            this.keepAlive = false;
        } else if ("keep-alive".equalsIgnoreCase(connHeader)) {
            this.keepAlive = true;
        } else {
            // Default in HTTP/1.1 is keep-alive
            this.keepAlive = "HTTP/1.1".equalsIgnoreCase(this.version);
        }

        return true;
    }
}
