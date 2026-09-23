import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Set;

public class Server {
    private int port;
    private boolean isRunning;
    private Selector selector;

    public Server(int port) {
        this.port = port;
        this.isRunning = false;
    }

    public void start() throws IOException {
        // open the single selector
        this.selector = Selector.open();
        this.isRunning = true;
        System.out.println("Server started on port " + this.port);

        // main event loop
        while (this.isRunning) {
            // wait up to 1 second for I/O events
            int ready = this.selector.select(1000);
            if (ready == 0) {
                continue;
            }

            Set<SelectionKey> keys = this.selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();

            while (it.hasNext()) {
                SelectionKey key = it.next();
                // must remove processed key
                it.remove();

                if (!key.isValid()) {
                    continue;
                }

                // will handle accept, read, write in next steps
            }
        }
    }

    public void stop() {
        this.isRunning = false;
        if (this.selector != null && this.selector.isOpen()) {
            try {
                this.selector.close();
            } catch (IOException e) {
                System.err.println("Error closing selector: " + e.getMessage());
            }
        }
        System.out.println("Server stopped.");
    }
}
