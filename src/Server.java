import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (IOException e) {
                    System.err.println("Error handling client: " + e.getMessage());
                    closeClient(key);
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = server.accept();

        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            Client client = new Client(clientChannel);
            clientChannel.register(this.selector, SelectionKey.OP_READ, client);
            System.out.println("New client connected: " + clientChannel.getRemoteAddress());
        }
    }

    // Read incoming request data (1 read per select)
    private void handleRead(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        SocketChannel channel = client.getChannel();
        ByteBuffer buffer = client.getReadBuffer();

        buffer.clear();
        int bytesRead = channel.read(buffer);

        if (bytesRead == -1) {
            closeClient(key);
            return;
        }

        if (bytesRead == 0) {
            return;
        }

        client.updateActivity();

        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        client.getRawRequest().append(new String(bytes, StandardCharsets.UTF_8));

        // When headers are complete, prepare response and switch to OP_WRITE
        if (client.getState() == Client.State.READING_HEADERS) {
            if (client.parseHeaders()) {
                System.out.println("Parsed request: " + client.getMethod() + " " + client.getPath() + " (" + client.getVersion() + ")");
                
                // Prepare a simple 200 OK test response
                String html = "<html><body><h1>Hello from LocalServer!</h1><p>You requested: " 
                              + client.getPath() + "</p></body></html>";
                client.prepareResponse(200, "OK", html, "text/html");

                // Switch registration to OP_WRITE so selector tells us when socket is ready for writing
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    // Write outgoing response (1 write per select)
    private void handleWrite(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        SocketChannel channel = client.getChannel();
        ByteBuffer buffer = client.getResponseBuffer();

        if (buffer != null && buffer.hasRemaining()) {
            channel.write(buffer);
        }

        // If all bytes written, close connection (HTTP Connection: close)
        if (buffer == null || !buffer.hasRemaining()) {
            closeClient(key);
        }
    }

    private void closeClient(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException e) {
            // ignore cleanup errors
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
