import java.io.IOException;

public class Server {
    private int port;

    private boolean isRunning;

    public Server(int port) {
        this.port = port;
        this.isRunning = false;
    }

    public void start() throws IOException {
        this.isRunning = true;
        System.out.println("Server initialized on port " + this.port);
    }

    public void stop() {
        this.isRunning = false;
        System.out.println("Server stopped.");
    }
}
