import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;
import java.util.Set;

public class Server {
    private int port;
    private boolean isRunning;
    private Selector selector;
    private ServerSocketChannel serverChannel;

    public Server(int port) {
        this.port = port;
        this.isRunning = false;
    }

    public void start() throws IOException {
        // open the single selector
        this.selector = Selector.open();

        // create and configure the listening server socket
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false); // non-blocking mode
        this.serverChannel.bind(new InetSocketAddress(this.port));

        // register server socket with selector for incoming connections
        this.serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);

        this.isRunning = true;
        System.out.println("Server listening on port " + this.port);

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
                it.remove();

                if (!key.isValid()) {
                    continue;
                }

                // check if a client wants to connect
                if (key.isAcceptable()) {
                    // will accept the client in the next commit
                }
            }
        }
    }

    public void stop() {
        this.isRunning = false;

        if (this.serverChannel != null && this.serverChannel.isOpen()) {
            try {
                this.serverChannel.close();
            } catch (IOException e) {
                System.err.println("Error closing server channel: " + e.getMessage());
            }
        }

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
