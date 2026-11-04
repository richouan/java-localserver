import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class UploadHandler {

    public static class ParsedMultipart {
        public String filename;
        public byte[] data;
        public ParsedMultipart(String filename, byte[] data) {
            this.filename = filename;
            this.data = data;
        }
    }

    public static void handleFileUpload(Client client, Config.ServerBlock serverBlock, Router.RouteMatch routeMatch, RequestHandler requestHandler) {
        try {
            Path uploadDir = Paths.get(routeMatch.rule.uploadDir).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String contentType = client.getHeaders().getOrDefault("content-type", "");
            byte[] fileBytes = client.getBody();
            String fileName = null;

            if (contentType.toLowerCase().contains("multipart/form-data")) {
                int bIdx = contentType.indexOf("boundary=");
                if (bIdx != -1) {
                    String boundary = contentType.substring(bIdx + 9).trim();
                    if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                        boundary = boundary.substring(1, boundary.length() - 1);
                    }
                    ParsedMultipart mp = parseMultipart(fileBytes, boundary);
                    if (mp != null) {
                        if (mp.filename != null && !mp.filename.isEmpty()) {
                            fileName = mp.filename;
                        }
                        fileBytes = mp.data;
                    }
                }
            }

            if (fileName == null || fileName.isEmpty()) {
                Path target = routeMatch.targetPath;
                fileName = (target != null && !Files.isDirectory(target) && !target.equals(uploadDir))
                        ? target.getFileName().toString()
                        : "upload_" + System.currentTimeMillis() + ".dat";
            }

            Path destFile = uploadDir.resolve(fileName).normalize();
            if (!destFile.startsWith(uploadDir)) {
                requestHandler.sendError(client, serverBlock, 403, "Forbidden");
                return;
            }

            Files.write(destFile, fileBytes);
            String responseHtml = "<html><body><h1>201 Created</h1><p>File uploaded successfully: " + fileName + " (" + fileBytes.length + " bytes)</p><p><a href=\"/listing/\">View Directory Listing</a></p></body></html>";
            client.prepareResponse(201, "Created", responseHtml, "text/html");
        } catch (IOException e) {
            requestHandler.sendError(client, serverBlock, 500, "Internal Server Error");
        }
    }

    public static ParsedMultipart parseMultipart(byte[] body, String boundary) {
        try {
            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int firstBoundary = indexOf(body, boundaryBytes, 0);
            if (firstBoundary == -1) return null;

            int headerStart = firstBoundary + boundaryBytes.length;
            if (headerStart + 2 <= body.length && body[headerStart] == '\r' && body[headerStart + 1] == '\n') {
                headerStart += 2;
            }

            byte[] headerEndDelim = new byte[] { '\r', '\n', '\r', '\n' };
            int headerEnd = indexOf(body, headerEndDelim, headerStart);
            if (headerEnd == -1) return null;

            String partHeaders = new String(body, headerStart, headerEnd - headerStart, StandardCharsets.ISO_8859_1);
            String filename = null;
            for (String line : partHeaders.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-disposition:")) {
                    int fnIdx = line.indexOf("filename=\"");
                    if (fnIdx != -1) {
                        int fnEnd = line.indexOf("\"", fnIdx + 10);
                        if (fnEnd != -1) {
                            filename = line.substring(fnIdx + 10, fnEnd);
                            if (filename.contains("/") || filename.contains("\\")) {
                                filename = Paths.get(filename).getFileName().toString();
                            }
                        }
                    }
                }
            }

            int contentStart = headerEnd + 4;
            byte[] endBoundaryBytes = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int contentEnd = indexOf(body, endBoundaryBytes, contentStart);
            if (contentEnd == -1) {
                endBoundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
                contentEnd = indexOf(body, endBoundaryBytes, contentStart);
            }
            if (contentEnd == -1 || contentEnd < contentStart) return null;

            byte[] content = Arrays.copyOfRange(body, contentStart, contentEnd);
            return new ParsedMultipart(filename, content);
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOf(byte[] source, byte[] target, int fromIndex) {
        if (target.length == 0) return fromIndex;
        for (int i = fromIndex; i <= source.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}

