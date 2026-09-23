import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Client {
    // client state enum
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

    public Client(SocketChannel channel) {
        this.channel = channel;
        this.state = State.READING_HEADERS;
        // 8KB buffer for incoming data
        this.readBuffer = ByteBuffer.allocate(8192);
        this.rawRequest = new StringBuilder();
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
}
