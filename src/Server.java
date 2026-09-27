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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Server {
    private Config config;
    private boolean isRunning;
    private Selector selector;
    private List<ServerSocketChannel> serverChannels;

    private static final long TIMEOUT_MS = 30000;

    public Server(Config config) {
        this.config = config;
        this.isRunning = false;
        this.serverChannels = new ArrayList<>();
    }

    public void start() throws IOException {
        this.selector = Selector.open();

        // Track bound ports to detect duplicate port conflicts
        Set<Integer> boundPorts = new HashSet<>();

        // Bind a ServerSocketChannel for each unique port across all server blocks
        for (Config.ServerBlock serverBlock : this.config.getServers()) {
            if (boundPorts.contains(serverBlock.port)) {
                // Same physical port shared by another virtual host (valid virtual host group)
                continue;
            }

            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.bind(new InetSocketAddress(serverBlock.host, serverBlock.port));

                // Register each listening socket to the SAME selector
                ssc.register(this.selector, SelectionKey.OP_ACCEPT);
                this.serverChannels.add(ssc);
                boundPorts.add(serverBlock.port);

                System.out.println("Listening on " + serverBlock.host + ":" + serverBlock.port);
            } catch (IOException e) {
                System.err.println("Failed to bind port " + serverBlock.port + ": " + e.getMessage());
                // An invalid server configuration must not destroy valid ones (audit rule)
            }
        }

        if (this.serverChannels.isEmpty()) {
            throw new IOException("No listening ports could be bound.");
        }

        this.isRunning = true;

        while (this.isRunning) {
            int ready = this.selector.select(1000);

            checkTimeouts();

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

    // Resolve matching virtual server block by port and Host header
    private Config.ServerBlock resolveServerBlock(SocketChannel channel, String hostHeader) {
        int localPort = 8080;
        try {
            InetSocketAddress localAddr = (InetSocketAddress) channel.getLocalAddress();
            localPort = localAddr.getPort();
        } catch (IOException e) {
            // fallback
        }

        // Clean host header (strip :port if present)
        String reqHost = hostHeader;
        if (reqHost != null && reqHost.contains(":")) {
            reqHost = reqHost.substring(0, reqHost.indexOf(":")).trim();
        }

        Config.ServerBlock defaultMatch = null;

        for (Config.ServerBlock block : this.config.getServers()) {
            if (block.port == localPort) {
                if (defaultMatch == null) {
                    defaultMatch = block;
                }
                if (reqHost != null && reqHost.equalsIgnoreCase(block.serverName)) {
                    return block; // exact host match found!
                }
            }
        }

        return defaultMatch != null ? defaultMatch : this.config.getServers().get(0);
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        List<SelectionKey> timedOut = new ArrayList<>();

        for (SelectionKey key : this.selector.keys()) {
            if (key.isValid() && key.channel() instanceof SocketChannel) {
                Client client = (Client) key.attachment();
                if (client != null && (now - client.getLastActiveTime() > TIMEOUT_MS)) {
                    timedOut.add(key);
                }
            }
        }

        for (SelectionKey key : timedOut) {
            closeClient(key);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = server.accept();

        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            Client client = new Client(clientChannel);
            clientChannel.register(this.selector, SelectionKey.OP_READ, client);
        }
    }

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

        if (client.getState() == Client.State.READING_HEADERS) {
            if (client.parseHeaders()) {
                // Find matching ServerBlock based on port and Host header
                String hostHeader = client.getHeaders().get("host");
                Config.ServerBlock serverBlock = resolveServerBlock(channel, hostHeader);

                String requestedPath = client.getPath();
                if (requestedPath.equals("/")) {
                    requestedPath = "/index.html";
                }

                Path filePath = Paths.get(serverBlock.root, requestedPath).normalize();

                if (!filePath.startsWith(Paths.get(serverBlock.root))) {
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

    private void handleWrite(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        SocketChannel channel = client.getChannel();
        ByteBuffer headerBuffer = client.getResponseBuffer();

        if (headerBuffer != null && headerBuffer.hasRemaining()) {
            channel.write(headerBuffer);
            client.updateActivity();
            if (headerBuffer.hasRemaining()) {
                return;
            }
        }

        FileChannel fileChannel = client.getFileChannel();
        if (fileChannel != null && client.getFileBytesRemaining() > 0) {
            ByteBuffer fileBuf = client.getReadBuffer();

            if (fileBuf.hasRemaining()) {
                channel.write(fileBuf);
                client.updateActivity();
                if (fileBuf.hasRemaining()) {
                    return;
                }
            }

            fileBuf.clear();
            int readFromFile = fileChannel.read(fileBuf);
            if (readFromFile > 0) {
                fileBuf.flip();
                channel.write(fileBuf);
                client.decrementFileBytes(readFromFile);
                client.updateActivity();
            }

            if (client.getFileBytesRemaining() > 0 || fileBuf.hasRemaining()) {
                return;
            }
        }

        if (client.isKeepAlive()) {
            client.resetForNextRequest();
            key.interestOps(SelectionKey.OP_READ);
        } else {
            closeClient(key);
        }
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
            // ignore
        }
    }

    public void stop() {
        this.isRunning = false;

        for (ServerSocketChannel ssc : this.serverChannels) {
            if (ssc != null && ssc.isOpen()) {
                try {
                    ssc.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        if (this.selector != null && this.selector.isOpen()) {
            try {
                this.selector.close();
            } catch (IOException e) {
                // ignore
            }
        }
        System.out.println("Server stopped.");
    }
}
