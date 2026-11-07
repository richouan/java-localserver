import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Server {
    private final Config config;
    private boolean isRunning;
    private Selector selector;
    private final List<ServerSocketChannel> serverChannels;
    private final long startTime;
    private long requestCount = 0;

    private final SessionManager sessionManager;
    private final CgiHandler cgiHandler;
    private final RequestHandler requestHandler;

    private static final long TIMEOUT_MS = 30000;

    public Server(Config config) {
        this.config = config;
        this.isRunning = false;
        this.serverChannels = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
        this.sessionManager = new SessionManager();
        this.cgiHandler = new CgiHandler();
        this.requestHandler = new RequestHandler(this.sessionManager, this.cgiHandler);
    }

    public void start() throws IOException {
        // the heart of I/O multiplexing (Linux epoll)
        this.selector = Selector.open();

        Map<String, Config.ServerBlock> virtualHosts = new HashMap<>();
        Set<Integer> boundPorts = new HashSet<>();

        for (Config.ServerBlock serverBlock : this.config.getServers()) {
            String vhostKey = serverBlock.host + ":" + serverBlock.port + ":" + serverBlock.serverName.toLowerCase();
            if (virtualHosts.containsKey(vhostKey)) {
                System.err.println("Warning: Duplicate server block detected for " + vhostKey + "; subsequent definition will be ignored.");
                continue;
            }
            virtualHosts.put(vhostKey, serverBlock);

            if (boundPorts.contains(serverBlock.port)) {
                System.out.println("Port " + serverBlock.port + " already bound; sharing socket for virtual host '" + serverBlock.serverName + "'.");
                continue;
            }

            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false); // Make it non-blocking
                ssc.bind(new InetSocketAddress(serverBlock.host, serverBlock.port));

                // Register listening socket for OP_ACCEPT
                ssc.register(this.selector, SelectionKey.OP_ACCEPT);
                this.serverChannels.add(ssc);
                boundPorts.add(serverBlock.port);

                System.out.println("Listening on " + serverBlock.host + ":" + serverBlock.port);
            } catch (IOException e) {
                System.err.println("Failed to bind port " + serverBlock.port + ": " + e.getMessage() + ". Continuing with remaining servers.");
            }
        }

        if (this.serverChannels.isEmpty()) {
            throw new IOException("No listening ports could be bound.");
        }

        this.isRunning = true;

        while (this.isRunning) {
            int ready = this.selector.select(1000);

            // Prune idle sockets
            checkTimeouts();

            if (ready == 0) {
                continue;
            }

            Set<SelectionKey> keys = this.selector.selectedKeys();
            Iterator<SelectionKey> it = keys.iterator();

            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove(); // Remove key so it is not processed twice

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
                } catch (Throwable t) {
                    System.err.println("Safely handled socket exception: " + t.getMessage());
                    closeClient(key);
                }
            }
        }
    }

    public Config.ServerBlock resolveServerBlock(SocketChannel channel, String hostHeader) {
        return VHostResolver.resolveServerBlock(this.config, channel, hostHeader);
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
        SocketChannel clientChannel;

        // Non-blocking accept loop: drain all pending connections from kernel backlog
        while ((clientChannel = server.accept()) != null) {
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
        int bytesRead = channel.read(buffer); // STRICT INVARIANT: Exactly 1 read

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
        client.appendBytes(bytes);

        // Header size protection (431)
        if (client.getHeaderSize() > 16384) {
            client.setKeepAlive(false);
            this.requestHandler.sendError(client, resolveServerBlock(channel, null), 431, "Request Header Fields Too Large");
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        if (!client.areHeadersParsed()) {
            if (!client.parseHeaders()) {
                if (client.getHeaderSize() > 16384) {
                    client.setKeepAlive(false);
                    this.requestHandler.sendError(client, resolveServerBlock(channel, null), 431, "Request Header Fields Too Large");
                    key.interestOps(SelectionKey.OP_WRITE);
                }
                return;
            }
        }

        if (client.getHeaderSize() > 16384) {
            client.setKeepAlive(false);
            this.requestHandler.sendError(client, resolveServerBlock(channel, null), 431, "Request Header Fields Too Large");
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        if (client.isBadRequest()) {
            this.requestHandler.sendError(client, resolveServerBlock(channel, null), 400, "Bad Request");
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        String hostHeader = client.getHeaders().get("host");
        Config.ServerBlock serverBlock = resolveServerBlock(channel, hostHeader);

        // Body limit check (413)
        if (!client.isChunked() && client.getContentLength() > serverBlock.bodyLimit) {
            this.requestHandler.sendError(client, serverBlock, 413, "Payload Too Large");
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        // Dynamically enforce bodyLimit for chunked requests
        if (client.isChunked()) {
            int bodyWire = client.getRawBytesSize() - client.getHeaderSize();
            if (bodyWire > serverBlock.bodyLimit + 16384) {
                client.setKeepAlive(false);
                this.requestHandler.sendError(client, serverBlock, 413, "Payload Too Large");
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
        }

        if (client.getBodySize() > serverBlock.bodyLimit) {
            this.requestHandler.sendError(client, serverBlock, 413, "Payload Too Large");
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        if (client.isBodyComplete()) {
            boolean isAsyncCgi = this.requestHandler.processRequest(client, serverBlock, key, this.selector, this);
            if (!isAsyncCgi) {
                key.interestOps(SelectionKey.OP_WRITE);
            }
        } else if (client.isBadRequest()) {
            this.requestHandler.sendError(client, serverBlock, 400, "Bad Request");
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        Client client = (Client) key.attachment();
        SocketChannel channel = client.getChannel();
        ByteBuffer headerBuffer = client.getResponseBuffer();

        // 1. Send HTTP headers
        if (headerBuffer != null && headerBuffer.hasRemaining()) {
            channel.write(headerBuffer);
            client.updateActivity();
            if (headerBuffer.hasRemaining()) {
                return; // Partial write; return to selector
            }
            if (client.getFileChannel() != null && client.getFileBytesRemaining() > 0) {
                return; // Headers completed; next select cycle streams file chunks
            }
        }

        // 2. Stream file body slices if present
        FileChannel fileChannel = client.getFileChannel();
        if (fileChannel != null && client.getFileBytesRemaining() > 0) {
            ByteBuffer fileBuf = client.getReadBuffer();

            if (fileBuf.hasRemaining()) {
                channel.write(fileBuf);
                client.updateActivity();
                return; // STRICT INVARIANT: 1 write per select cycle
            }

            fileBuf.clear();
            int readFromFile = fileChannel.read(fileBuf);
            if (readFromFile > 0) {
                fileBuf.flip();
                channel.write(fileBuf);
                client.decrementFileBytes(readFromFile);
                client.updateActivity();
                return; // STRICT INVARIANT: 1 write per select cycle
            }

            if (client.getFileBytesRemaining() > 0 || fileBuf.hasRemaining()) {
                return;
            }
        }

        // 3. Keep-Alive & Pipelining resolution
        if (client.isKeepAlive()) {
            client.resetForNextRequest();
            if (client.getRawBytesSize() > 0) {
                if (!client.areHeadersParsed()) {
                    client.parseHeaders();
                }
                if (client.isBadRequest()) {
                    this.requestHandler.sendError(client, resolveServerBlock(channel, null), 400, "Bad Request");
                    key.interestOps(SelectionKey.OP_WRITE);
                    return;
                }
                if (client.areHeadersParsed() && client.isBodyComplete()) {
                    String hostHeader = client.getHeaders().get("host");
                    Config.ServerBlock serverBlock = resolveServerBlock(channel, hostHeader);
                    boolean isAsyncCgi = this.requestHandler.processRequest(client, serverBlock, key, this.selector, this);
                    if (!isAsyncCgi) {
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                    return;
                }
            }
            key.interestOps(SelectionKey.OP_READ);
        } else {
            closeClient(key);
        }
    }

    private void closeClient(SelectionKey key) {
        try {
            Client client = (Client) key.attachment();
            if (client != null) {
                client.closeFileChannel();
            }
            key.cancel();
            key.channel().close();
        } catch (IOException ignored) {}
    }

    public synchronized void incrementRequestCount() {
        this.requestCount++;
    }

    public long getStartTime() {
        return this.startTime;
    }

    public synchronized long getRequestCount() {
        return this.requestCount;
    }

    public int getActiveClientCount() {
        if (this.selector == null) return 0;
        return Math.max(0, this.selector.keys().size() - this.serverChannels.size());
    }

    public void stop() {
        this.isRunning = false;

        for (ServerSocketChannel ssc : this.serverChannels) {
            if (ssc != null && ssc.isOpen()) {
                try {
                    ssc.close();
                } catch (IOException ignored) {}
            }
        }

        if (this.selector != null && this.selector.isOpen()) {
            try {
                this.selector.close();
            } catch (IOException ignored) {}
        }

        this.cgiHandler.shutdown();
        System.out.println("Server stopped.");
    }
}
