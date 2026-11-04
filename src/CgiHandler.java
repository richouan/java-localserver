import java.io.File;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CgiHandler {
    private final ExecutorService cgiExecutor;

    public CgiHandler() {
        this.cgiExecutor = Executors.newCachedThreadPool();
    }

    public boolean isCgi(Path filePath, Router.RouteMatch routeMatch) {
        if (filePath == null || filePath.getFileName() == null) return false;
        String name = filePath.getFileName().toString().toLowerCase();
        if (routeMatch.rule.cgiExtension != null && !routeMatch.rule.cgiExtension.isEmpty()) {
            if (name.endsWith(routeMatch.rule.cgiExtension.toLowerCase())) {
                return true;
            }
        }
        return name.endsWith(".py") || name.endsWith(".sh") || name.endsWith(".pl") || name.endsWith(".cgi") || name.endsWith(".php");
    }

    public List<String> buildCgiCommand(Path scriptPath) {
        List<String> cmd = new ArrayList<>();
        String name = scriptPath.getFileName().toString().toLowerCase();
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

        if (name.endsWith(".py")) {
            cmd.add(isWin ? "python" : "python3");
        } else if (name.endsWith(".sh")) {
            cmd.add(isWin && Files.exists(Paths.get("C:\\Program Files\\Git\\bin\\bash.exe"))
                    ? "C:\\Program Files\\Git\\bin\\bash.exe" : "bash");
        } else if (name.endsWith(".pl")) {
            cmd.add("perl");
        } else if (name.endsWith(".php")) {
            cmd.add("php-cgi");
        }
        cmd.add(scriptPath.toAbsolutePath().toString());
        return cmd;
    }

    public void handleCgiAsync(Client client, Config.ServerBlock serverBlock, Path scriptPath, SelectionKey key, Selector selector, RequestHandler requestHandler) {
        key.interestOps(0);

        this.cgiExecutor.submit(() -> {
            try {
                handleCgi(client, serverBlock, scriptPath, requestHandler);
            } catch (Exception e) {
                requestHandler.sendError(client, serverBlock, 500, "Internal Server Error");
            } finally {
                try {
                    if (key.isValid() && client.getChannel().isOpen()) {
                        key.interestOps(SelectionKey.OP_WRITE);
                        selector.wakeup();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    public void handleCgi(Client client, Config.ServerBlock serverBlock, Path scriptPath, RequestHandler requestHandler) {
        try {
            List<String> cmd = buildCgiCommand(scriptPath);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (scriptPath.getParent() != null) {
                pb.directory(scriptPath.getParent().toFile());
            }

            // Set up CGI/1.1 environment variables
            Map<String, String> env = pb.environment();
            env.put("REQUEST_METHOD", client.getMethod());
            env.put("QUERY_STRING", client.getQueryString() != null ? client.getQueryString() : "");
            env.put("CONTENT_LENGTH", String.valueOf(client.getBodySize()));
            env.put("CONTENT_TYPE", client.getHeaders().getOrDefault("content-type", ""));
            env.put("PATH_INFO", client.getPath());
            env.put("SCRIPT_NAME", client.getPath());
            env.put("REMOTE_ADDR", "127.0.0.1");
            env.put("SCRIPT_FILENAME", scriptPath.toAbsolutePath().toString());
            env.put("SERVER_PROTOCOL", "HTTP/1.1");
            env.put("GATEWAY_INTERFACE", "CGI/1.1");
            env.put("REDIRECT_STATUS", "200");
            env.put("SERVER_NAME", serverBlock.serverName.isEmpty() ? serverBlock.host : serverBlock.serverName);
            env.put("SERVER_PORT", String.valueOf(serverBlock.port));

            // Pass all HTTP headers to CGI as HTTP_X variables
            for (Map.Entry<String, String> h : client.getHeaders().entrySet()) {
                env.put("HTTP_" + h.getKey().toUpperCase().replace("-", "_"), h.getValue());
            }

            Process p = pb.start();

            // Pipe request body to CGI stdin if present
            if (client.getBodySize() > 0) {
                p.getOutputStream().write(client.getBody());
                p.getOutputStream().flush();
            }
            p.getOutputStream().close(); // Send EOF to CGI

            // Non-blocking timeout guard: wait up to 5 seconds
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                requestHandler.sendError(client, serverBlock, 504, "Gateway Timeout");
                return;
            }

            if (p.exitValue() != 0) {
                requestHandler.sendError(client, serverBlock, 502, "Bad Gateway");
                return;
            }

            byte[] output = p.getInputStream().readAllBytes();

            // Locate \r\n\r\n or \n\n delimiter in raw CGI output
            int headerEnd = -1;
            int delimLen = 0;
            for (int i = 0; i < output.length - 1; i++) {
                if (output[i] == '\r' && i + 3 < output.length && output[i + 1] == '\n' && output[i + 2] == '\r' && output[i + 3] == '\n') {
                    headerEnd = i;
                    delimLen = 4;
                    break;
                } else if (output[i] == '\n' && output[i + 1] == '\n') {
                    headerEnd = i;
                    delimLen = 2;
                    break;
                }
            }

            int statusCode = 200;
            String statusText = "OK";
            String contentType = "text/html";
            byte[] bodyBytes;

            if (headerEnd != -1) {
                String headerPart = new String(output, 0, headerEnd, StandardCharsets.UTF_8);
                int bodyStart = headerEnd + delimLen;
                bodyBytes = (bodyStart < output.length) ? Arrays.copyOfRange(output, bodyStart, output.length) : new byte[0];

                for (String line : headerPart.split("\r?\n")) {
                    int colon = line.indexOf(":");
                    if (colon != -1) {
                        String name = line.substring(0, colon).trim();
                        String val = line.substring(colon + 1).trim();
                        if (name.equalsIgnoreCase("Status")) {
                            String[] sp = val.split(" ", 2);
                            try {
                                statusCode = Integer.parseInt(sp[0]);
                                if (sp.length > 1) statusText = sp[1];
                            } catch (NumberFormatException ignored) {}
                        } else if (name.equalsIgnoreCase("Content-Type")) {
                            contentType = val;
                        } else {
                            client.setResponseHeader(name, val);
                        }
                    }
                }
            } else {
                bodyBytes = output;
            }

            client.prepareResponse(statusCode, statusText, bodyBytes, contentType);
        } catch (Exception e) {
            requestHandler.sendError(client, serverBlock, 500, "Internal Server Error");
        }
    }

    public void shutdown() {
        if (this.cgiExecutor != null && !this.cgiExecutor.isShutdown()) {
            this.cgiExecutor.shutdownNow();
        }
    }
}

