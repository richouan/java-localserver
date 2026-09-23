import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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
        this.selector = Selector.open();

        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.bind(new InetSocketAddress(this.port));
        this.serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);

        this.isRunning = true;
        System.out.println("Server listening on port " + this.port);

        while (this.isRunning) {
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

                try {
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    }
                } catch (IOException e) {
                    System.err.println("Error handling connection: " + e.getMessage());
                }
            }
        }
    }

    // accept new client connection
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = server.accept();

        if (clientChannel != null) {
            // configure client socket as non-blocking
            clientChannel.configureBlocking(false);

            // register with the SAME selector for OP_READ
            clientChannel.register(this.selector, SelectionKey.OP_READ);
            System.out.println("New client connected: " + clientChannel.getRemoteAddress());
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
