import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RequestHandler {
    private final SessionManager sessionManager;
    private final CgiHandler cgiHandler;

    public RequestHandler(SessionManager sessionManager, CgiHandler cgiHandler) {
        this.sessionManager = sessionManager;
        this.cgiHandler = cgiHandler;
    }

    public boolean processRequest(Client client, Config.ServerBlock serverBlock, SelectionKey key, Selector selector, Server server) throws IOException {
        server.incrementRequestCount();

        // Built-in endpoints
        if ("/status".equals(client.getPath()) || "/metrics".equals(client.getPath())) {
            StatusHandler.handleStatus(client, server.getStartTime(), server.getRequestCount(),
                    server.getActiveClientCount(), this.sessionManager.getActiveSessionCount());
            return false;
        }

        if ("/session".equals(client.getPath())) {
            this.sessionManager.handleSession(client);
            return false;
        }

        // Route matching
        Router.RouteMatch routeMatch = Router.route(serverBlock, client.getMethod(), client.getPath());

        // Redirects
        if (routeMatch.isRedirect) {
            client.prepareRedirect(routeMatch.rule.redirect);
            return false;
        }

        // Method validation (405)
        if (!routeMatch.isMethodAllowed) {
            sendError(client, serverBlock, 405, "Method Not Allowed");
            return false;
        }

        Path filePath = routeMatch.targetPath;
        Path rootPath = Paths.get(routeMatch.rule.root).normalize();

        // Security check: Directory traversal (403)
        if (routeMatch.isForbidden || !filePath.startsWith(rootPath)) {
            sendError(client, serverBlock, 403, "Forbidden");
            return false;
        }

        // Canonical 301 trailing slash redirect for directories (GET/HEAD only)
        if ("GET".equalsIgnoreCase(client.getMethod()) && routeMatch.isDirectory && !client.getPath().endsWith("/")) {
            String qs = client.getQueryString();
            String target = client.getPath() + "/" + (qs != null && !qs.isEmpty() ? "?" + qs : "");
            client.prepareRedirect(target);
            return false;
        }

        // Asynchronous CGI execution
        if (("GET".equalsIgnoreCase(client.getMethod()) || "POST".equalsIgnoreCase(client.getMethod())) && this.cgiHandler.isCgi(filePath, routeMatch)) {
            if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
                sendError(client, serverBlock, 404, "Not Found");
                return false;
            }
            this.cgiHandler.handleCgiAsync(client, serverBlock, filePath, key, selector, this);
            return true;
        }

        // POST File Uploads
        if ("POST".equalsIgnoreCase(client.getMethod())) {
            if (routeMatch.rule.uploadDir != null && !routeMatch.rule.uploadDir.isEmpty()) {
                UploadHandler.handleFileUpload(client, serverBlock, routeMatch, this);
                return false;
            }

            String responseBody = "<html><body><h1>200 OK</h1><p>POST body received successfully (" + client.getBodySize() + " bytes).</p></body></html>";
            client.prepareResponse(200, "OK", responseBody, "text/html");
            return false;
        }

        // DELETE requests
        if ("DELETE".equalsIgnoreCase(client.getMethod())) {
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                try {
                    Files.delete(filePath);
                    client.prepareResponse(204, "No Content", "", "text/plain");
                } catch (IOException e) {
                    sendError(client, serverBlock, 500, "Internal Server Error");
                }
            } else {
                sendError(client, serverBlock, 404, "Not Found");
            }
            return false;
        }

        // Directory Handling (index.html vs Autoindex vs 403)
        if (Files.isDirectory(filePath)) {
            if (!client.getPath().endsWith("/")) {
                String qs = client.getQueryString();
                String target = client.getPath() + "/" + (qs != null && !qs.isEmpty() ? "?" + qs : "");
                client.prepareRedirect(target);
                return false;
            }

            if (routeMatch.rule.listing) {
                String listingHtml = MimeTypes.generateDirectoryListing(filePath.toFile(), client.getPath());
                client.prepareResponse(200, "OK", listingHtml, "text/html");
            } else {
                sendError(client, serverBlock, 403, "Forbidden");
            }
            return false;
        }

        // Static File Serving
        if (Files.exists(filePath)) {
            String contentType = MimeTypes.getMimeType(filePath.toString());
            client.prepareFileResponse(200, "OK", filePath, contentType);
        } else {
            sendError(client, serverBlock, 404, "Not Found");
        }
        return false;
    }

    public void sendError(Client client, Config.ServerBlock serverBlock, int statusCode, String defaultReason) {
        if (statusCode >= 400) {
            client.setKeepAlive(false);
        }

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
}

