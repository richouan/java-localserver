import java.io.File;

public class MimeTypes {

    public static String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    public static String generateDirectoryListing(File dir, String uriPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Index of ").append(uriPath).append("</title></head><body>");
        sb.append("<h1>Index of ").append(uriPath).append("</h1><hr><ul>");

        if (!uriPath.equals("/") && !uriPath.equals("")) {
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
}

