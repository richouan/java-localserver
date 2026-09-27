import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

        // When headers are complete, resolve file and prepare response
        if (client.getState() == Client.State.READING_HEADERS) {
            if (client.parseHeaders()) {
                System.out.println("Parsed request: " + client.getMethod() + " " + client.getPath() + " (" + client.getVersion() + ")");

                // Serve static file from www directory
                String requestedPath = client.getPath();
                if (requestedPath.equals("/")) {
                    requestedPath = "/index.html";
                }

                Path filePath = Paths.get("www", requestedPath).normalize();

                // Prevent directory traversal attacks
                if (!filePath.startsWith(Paths.get("www"))) {
                    client.prepareResponse(403, "Forbidden", "<h1>403 Forbidden</h1>", "text/html");
                } else if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                    String contentType = getMimeType(filePath.toString());
                    client.prepareFileResponse(200, "OK", filePath, contentType);
                } else {
                    client.prepareResponse(404, "Not Found", "<h1>404 Not Found</h1>", "text/html");
                }

                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    // Write outgoing response in non-blocking chunks (1 write per select)
    private void handleWrite(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        SocketChannel channel = client.getChannel();
        ByteBuffer headerBuffer = client.getResponseBuffer();

        // 1. Send HTTP header bytes first (handle partial write)
        if (headerBuffer != null && headerBuffer.hasRemaining()) {
            channel.write(headerBuffer);
            client.updateActivity();
            if (headerBuffer.hasRemaining()) {
                return; // still has header bytes left, resume on next OP_WRITE
            }
        }

        // 2. Stream static file bytes using FileChannel directly into socket
        FileChannel fileChannel = client.getFileChannel();
        if (fileChannel != null && client.getFileBytesRemaining() > 0) {
            ByteBuffer fileBuf = client.getReadBuffer();

            // If previous partial write still has unsent file bytes in buffer, flush them first
            if (fileBuf.hasRemaining()) {
                channel.write(fileBuf);
                client.updateActivity();
                if (fileBuf.hasRemaining()) {
                    return; // network socket buffer full, wait for next OP_WRITE
                }
            }

            // Read the next chunk from file
            fileBuf.clear();
            int readFromFile = fileChannel.read(fileBuf);
            if (readFromFile > 0) {
                fileBuf.flip();
                channel.write(fileBuf);
                client.decrementFileBytes(readFromFile);
                client.updateActivity();
            }

            // If more file bytes remain or buffer still has remaining bytes, wait for next OP_WRITE
            if (client.getFileBytesRemaining() > 0 || fileBuf.hasRemaining()) {
                return;
            }
        }

        // All response bytes written cleanly
        closeClient(key);
    }

    private String getMimeType(String filename) {
        if (filename.endsWith(".html") || filename.endsWith(".htm")) return "text/html";
        if (filename.endsWith(".css")) return "text/css";
        if (filename.endsWith(".js")) return "application/javascript";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private void closeClient(SelectionKey key) {
        try {
            Client client = (Client) key.attachment();
            if (client != null) {
                client.closeFileChannel();
            }
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
