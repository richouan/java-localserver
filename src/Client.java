import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Client {
    private SocketChannel channel;
    private ByteBuffer readBuffer;
    private ByteArrayOutputStream rawBytes;
    private long lastActiveTime;
    private boolean keepAlive;

    private boolean headersParsed;
    private int headerEnd;
    private String method;
    private String path;
    private String queryString = "";
    private String version;
    private Map<String, String> headers;
    private long contentLength;
    private boolean isChunked;
    private boolean isBadRequest;

    private ByteBuffer responseBuffer;
    private Map<String, String> responseHeaders = new HashMap<>();
    private FileChannel fileChannel;
    private long fileBytesRemaining;

    public Client(SocketChannel channel) {
        this.channel = channel;
        this.readBuffer = ByteBuffer.allocate(8192);
        this.rawBytes = new ByteArrayOutputStream();
        this.headers = new HashMap<>();
        this.lastActiveTime = System.currentTimeMillis();
        this.keepAlive = false;
        this.headersParsed = false;
        this.headerEnd = -1;
        this.contentLength = 0;
        this.isChunked = false;
        this.isBadRequest = false;
    }

    public boolean isBadRequest() {
        return this.isBadRequest;
    }

    public int getRawBytesSize() {
        return this.rawBytes.size();
    }

    public boolean isChunked() {
        return this.isChunked;
    }

    public SocketChannel getChannel() {
        return this.channel;
    }

    public ByteBuffer getReadBuffer() {
        return this.readBuffer;
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

    public String getQueryString() {
        return this.queryString;
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

    public long getContentLength() {
        return this.contentLength;
    }

    public void appendBytes(byte[] data) {
        this.rawBytes.write(data, 0, data.length);
    }

    public boolean areHeadersParsed() {
        return this.headersParsed;
    }

    public boolean parseHeaders() {
        byte[] bytes = this.rawBytes.toByteArray();
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        int idx = content.indexOf("\r\n\r\n");
        if (idx == -1) {
            return false;
        }

        this.headerEnd = idx;
        String headerSection = content.substring(0, idx);
        String[] lines = headerSection.split("\r\n");
        if (lines.length == 0) {
            this.isBadRequest = true;
            this.headersParsed = true;
            return true;
        }

        String[] requestLine = lines[0].split("\\s+");
        if (requestLine.length < 3) {
            this.isBadRequest = true;
            this.headersParsed = true;
            return true;
        }

        this.method = requestLine[0].trim();
        String fullPath = requestLine[1].trim();
        int qIdx = fullPath.indexOf("?");
        if (qIdx != -1) {
            this.path = fullPath.substring(0, qIdx);
            this.queryString = fullPath.substring(qIdx + 1);
        } else {
            this.path = fullPath;
            this.queryString = "";
        }
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

        String connHeader = this.headers.get("connection");
        if ("close".equalsIgnoreCase(connHeader)) {
            this.keepAlive = false;
        } else if ("keep-alive".equalsIgnoreCase(connHeader)) {
            this.keepAlive = true;
        } else {
            this.keepAlive = "HTTP/1.1".equalsIgnoreCase(this.version);
        }

        String cl = this.headers.get("content-length");
        if (cl != null) {
            try {
                this.contentLength = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                this.contentLength = 0;
            }
        }

        String te = this.headers.get("transfer-encoding");
        this.isChunked = te != null && te.toLowerCase().contains("chunked");

        this.headersParsed = true;
        return true;
    }

    public int getBodySize() {
        if (!this.headersParsed) return 0;
        if (this.isChunked) {
            return getBody().length;
        }
        int size = this.rawBytes.size() - (this.headerEnd + 4);
        return Math.max(0, size);
    }

    public byte[] getBody() {
        if (!this.headersParsed) return new byte[0];
        byte[] all = this.rawBytes.toByteArray();
        int start = this.headerEnd + 4;
        if (start >= all.length) return new byte[0];

        if (this.isChunked) {
            return decodeChunked(all, start);
        }

        int end = (int) Math.min(all.length, start + this.contentLength);
        return Arrays.copyOfRange(all, start, end);
    }

    public boolean isBodyComplete() {
        if (!this.headersParsed) return false;
        if (this.isChunked) {
            byte[] all = this.rawBytes.toByteArray();
            String text = new String(all, this.headerEnd + 4, all.length - (this.headerEnd + 4), StandardCharsets.ISO_8859_1);
            return text.endsWith("0\r\n\r\n") || text.contains("\r\n0\r\n\r\n");
        }
        return getBodySize() >= this.contentLength;
    }

    private byte[] decodeChunked(byte[] data, int start) {
        String text = new String(data, start, data.length - start, StandardCharsets.ISO_8859_1);
        String[] parts = text.split("\r\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int i = 0; i < parts.length - 1; i += 2) {
            if ("0".equals(parts[i].trim())) break;
            try {
                out.write(parts[i + 1].getBytes(StandardCharsets.ISO_8859_1));
            } catch (Exception ignored) {}
        }
        return out.toByteArray();
    }

    public void closeFileChannel() {
        if (this.fileChannel != null && this.fileChannel.isOpen()) {
            try {
                this.fileChannel.close();
            } catch (IOException ignored) {}
        }
    }

    public void setResponseHeader(String key, String value) {
        this.responseHeaders.put(key, value);
    }

    public void resetForNextRequest() {
        closeFileChannel();
        this.fileChannel = null;
        this.fileBytesRemaining = 0;
        this.responseBuffer = null;
        this.responseHeaders.clear();
        this.method = null;
        this.path = null;
        this.queryString = "";
        this.version = null;
        this.headers.clear();
        this.isBadRequest = false;

        byte[] all = this.rawBytes.toByteArray();
        int consumed = (this.headerEnd != -1) ? (this.headerEnd + 4 + (int)this.contentLength) : all.length;
        this.rawBytes.reset();
        if (consumed < all.length) {
            this.rawBytes.write(all, consumed, all.length - consumed);
        }

        this.headersParsed = false;
        this.headerEnd = -1;
        this.contentLength = 0;
        this.isChunked = false;
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void prepareRedirect(String location) {
        StringBuilder res = new StringBuilder();
        res.append("HTTP/1.1 301 Moved Permanently\r\n");
        res.append("Location: ").append(location).append("\r\n");
        res.append("Content-Length: 0\r\n");
        res.append("Connection: close\r\n");
        res.append("\r\n");

        byte[] headerBytes = res.toString().getBytes(StandardCharsets.UTF_8);
        this.responseBuffer = ByteBuffer.wrap(headerBytes);
    }

    public void prepareFileResponse(int statusCode, String statusText, Path filePath, String contentType) throws IOException {
        this.fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
        long fileSize = this.fileChannel.size();
        this.fileBytesRemaining = fileSize;

        StringBuilder res = new StringBuilder();
        res.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        res.append("Content-Type: ").append(contentType).append("\r\n");
        res.append("Content-Length: ").append(fileSize).append("\r\n");
        res.append("Connection: ").append(this.keepAlive ? "keep-alive" : "close").append("\r\n");
        for (Map.Entry<String, String> entry : this.responseHeaders.entrySet()) {
            res.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        res.append("\r\n");

        byte[] headerBytes = res.toString().getBytes(StandardCharsets.UTF_8);
        this.responseBuffer = ByteBuffer.wrap(headerBytes);
    }

    public void prepareResponse(int statusCode, String statusText, String body, String contentType) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder res = new StringBuilder();
        res.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        res.append("Content-Type: ").append(contentType).append("\r\n");
        res.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        res.append("Connection: ").append(this.keepAlive ? "keep-alive" : "close").append("\r\n");
        for (Map.Entry<String, String> entry : this.responseHeaders.entrySet()) {
            res.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        res.append("\r\n");

        byte[] headerBytes = res.toString().getBytes(StandardCharsets.UTF_8);

        this.responseBuffer = ByteBuffer.allocate(headerBytes.length + bodyBytes.length);
        this.responseBuffer.put(headerBytes);
        this.responseBuffer.put(bodyBytes);
        this.responseBuffer.flip();
    }
}
