import java.io.File;
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

        Set<Integer> boundPorts = new HashSet<>();

        for (Config.ServerBlock serverBlock : this.config.getServers()) {
            if (boundPorts.contains(serverBlock.port)) {
                continue;
            }

            try {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.bind(new InetSocketAddress(serverBlock.host, serverBlock.port));

                ssc.register(this.selector, SelectionKey.OP_ACCEPT);
                this.serverChannels.add(ssc);
                boundPorts.add(serverBlock.port);

                System.out.println("Listening on " + serverBlock.host + ":" + serverBlock.port);
            } catch (IOException e) {
                System.err.println("Failed to bind port " + serverBlock.port + ": " + e.getMessage());
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

    private Config.ServerBlock resolveServerBlock(SocketChannel channel, String hostHeader) {
        int localPort = 8080;
        try {
            InetSocketAddress localAddr = (InetSocketAddress) channel.getLocalAddress();
            localPort = localAddr.getPort();
        } catch (IOException e) {
        }

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
                    return block;
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

        String hostHeader = client.getHeaders().get("host");
        Config.ServerBlock serverBlock = resolveServerBlock(channel, hostHeader);

        if (client.getState() == Client.State.READING_HEADERS) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            client.getRawRequest().append(new String(bytes, StandardCharsets.ISO_8859_1));

            if (client.parseHeaders()) {
                hostHeader = client.getHeaders().get("host");
                serverBlock = resolveServerBlock(channel, hostHeader);

                if (client.getContentLength() > serverBlock.bodyLimit) {
                    sendError(client, serverBlock, 413, "Payload Too Large");
                    key.interestOps(SelectionKey.OP_WRITE);
                    return;
                }

                if ("POST".equalsIgnoreCase(client.getMethod()) && client.getContentLength() > 0) {
                    String content = client.getRawRequest().toString();
                    int headerEnd = content.indexOf("\r\n\r\n");
                    String leftover = content.substring(headerEnd + 4);

                    if (!leftover.isEmpty()) {
                        byte[] leftoverBytes = leftover.getBytes(StandardCharsets.ISO_8859_1);
                        int toWrite = (int) Math.min(leftoverBytes.length, client.getContentLength());
                        client.appendBody(leftoverBytes, 0, toWrite);
                    }

                    if (client.isBodyComplete()) {
                        processRequest(client, serverBlock);
                        key.interestOps(SelectionKey.OP_WRITE);
                    } else {
                        client.setState(Client.State.READING_BODY);
                    }
                } else {
                    processRequest(client, serverBlock);
                    key.interestOps(SelectionKey.OP_WRITE);
                }
            }
        } else if (client.getState() == Client.State.READING_BODY) {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            if (client.getBodySize() + bytes.length > serverBlock.bodyLimit) {
                sendError(client, serverBlock, 413, "Payload Too Large");
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }

            int remainingNeeded = (int) (client.getContentLength() - client.getBodySize());
            int toWrite = Math.min(bytes.length, remainingNeeded);
            client.appendBody(bytes, 0, toWrite);

            if (client.isBodyComplete()) {
                processRequest(client, serverBlock);
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private void processRequest(Client client, Config.ServerBlock serverBlock) throws IOException {
        Router.RouteMatch routeMatch = Router.route(serverBlock, client.getMethod(), client.getPath());

        if (routeMatch.isRedirect) {
            client.prepareRedirect(routeMatch.rule.redirect);
            return;
        }

        if (!routeMatch.isMethodAllowed) {
            sendError(client, serverBlock, 405, "Method Not Allowed");
            return;
        }

        Path filePath = routeMatch.targetPath;
        Path rootPath = Paths.get(routeMatch.rule.root).normalize();

        if (!filePath.startsWith(rootPath)) {
            sendError(client, serverBlock, 403, "Forbidden");
            return;
        }

        if ("POST".equalsIgnoreCase(client.getMethod())) {
            String responseBody = "<html><body><h1>200 OK</h1><p>POST body received successfully (" + client.getBodySize() + " bytes).</p></body></html>";
            client.prepareResponse(200, "OK", responseBody, "text/html");
            return;
        }

        if (Files.isDirectory(filePath)) {
            if (routeMatch.rule.listing) {
                String listingHtml = generateDirectoryListing(filePath.toFile(), client.getPath());
                client.prepareResponse(200, "OK", listingHtml, "text/html");
            } else {
                sendError(client, serverBlock, 403, "Forbidden");
            }
            return;
        }

        if (Files.exists(filePath)) {
            String contentType = getMimeType(filePath.toString());
            client.prepareFileResponse(200, "OK", filePath, contentType);
        } else {
            sendError(client, serverBlock, 404, "Not Found");
        }
    }

    private String generateDirectoryListing(File dir, String uriPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Index of ").append(uriPath).append("</title></head><body>");
        sb.append("<h1>Index of ").append(uriPath).append("</h1><hr><ul>");

        if (!uriPath.equals("/")) {
            sb.append("<li><a href=\"..\">.. (Parent Directory)</a></li>");
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (f.isDirectory()) {
                    name += "/";
                }
                sb.append("<li><a href=\"").append(name).append("\">").append(name).append("</a></li>");
            }
        }

        sb.append("</ul><hr></body></html>");
        return sb.toString();
    }

    private void sendError(Client client, Config.ServerBlock serverBlock, int statusCode, String defaultReason) {
        String customPath = null;
        if (serverBlock != null && serverBlock.errorPages.containsKey(statusCode)) {
            customPath = serverBlock.errorPages.get(statusCode);
        } else {
            String defaultErrorFile = "errors/" + statusCode + ".html";
            if (Files.exists(Paths.get(defaultErrorFile))) {
                customPath = defaultErrorFile;
            }
        }

        if (customPath != null && Files.exists(Paths.get(customPath))) {
            try {
                client.prepareFileResponse(statusCode, defaultReason, Paths.get(customPath), "text/html");
                return;
            } catch (IOException ignored) {}
        }

        String fallbackHtml = "<html><body><h1>" + statusCode + " " + defaultReason + "</h1></body></html>";
        client.prepareResponse(statusCode, defaultReason, fallbackHtml, "text/html");
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
        }
    }

    public void stop() {
        this.isRunning = false;

        for (ServerSocketChannel ssc : this.serverChannels) {
            if (ssc != null && ssc.isOpen()) {
                try {
                    ssc.close();
                } catch (IOException e) {
                }
            }
        }

        if (this.selector != null && this.selector.isOpen()) {
            try {
                this.selector.close();
            } catch (IOException e) {
            }
        }
        System.out.println("Server stopped.");
    }
}
