import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, zero-dependency configuration parser.
 * Reads simple .conf files line-by-line without external JSON libraries.
 */
public class Config {
    private String host;
    private int port;
    private String root;
    private long bodyLimit;
    private Map<String, String> routes; // path -> root folder

    public Config() {
        this.host = "127.0.0.1";
        this.port = 8080;
        this.root = "www";
        this.bodyLimit = 1048576; // 1MB default
        this.routes = new HashMap<>();
    }

    public static Config load(String filePath) throws IOException {
        Config config = new Config();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            String currentSection = "";

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // ignore empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    continue;
                }

                int eqIndex = line.indexOf("=");
                if (eqIndex != -1) {
                    String key = line.substring(0, eqIndex).trim().toLowerCase();
                    String value = line.substring(eqIndex + 1).trim();

                    if (currentSection.equals("server")) {
                        if (key.equals("host")) config.host = value;
                        else if (key.equals("port")) config.port = Integer.parseInt(value);
                        else if (key.equals("root")) config.root = value;
                        else if (key.equals("body_limit")) config.bodyLimit = Long.parseLong(value);
                    } else if (currentSection.startsWith("route")) {
                        // e.g. [route /]
                        String routePath = currentSection.replace("route", "").trim();
                        if (key.equals("root")) {
                            config.routes.put(routePath, value);
                        }
                    }
                }
            }
        }

        return config;
    }

    public String getHost() {
        return this.host;
    }

    public int getPort() {
        return this.port;
    }

    public String getRoot() {
        return this.root;
    }

    public long getBodyLimit() {
        return this.bodyLimit;
    }

    public Map<String, String> getRoutes() {
        return this.routes;
    }
}
