import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ServerBlock represents a virtual server block in the config file.
 * Multiple server blocks can have different ports or hostnames.
 */
public class Config {

    public static class ServerBlock {
        public String host = "127.0.0.1";
        public int port = 8080;
        public String serverName = "";
        public String root = "www";
        public long bodyLimit = 1048576; // 1MB default
        public Map<String, String> routes = new HashMap<>();
    }

    private List<ServerBlock> servers;

    public Config() {
        this.servers = new ArrayList<>();
        // Default fallback server
        ServerBlock defaultServer = new ServerBlock();
        this.servers.add(defaultServer);
    }

    public static Config load(String filePath) throws IOException {
        Config config = new Config();
        config.servers.clear();

        ServerBlock currentServer = null;
        String currentSection = "";

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    if (currentSection.equals("server")) {
                        currentServer = new ServerBlock();
                        config.servers.add(currentServer);
                    }
                    continue;
                }

                int eqIndex = line.indexOf("=");
                if (eqIndex != -1 && currentServer != null) {
                    String key = line.substring(0, eqIndex).trim().toLowerCase();
                    String value = line.substring(eqIndex + 1).trim();

                    if (currentSection.equals("server")) {
                        if (key.equals("host")) currentServer.host = value;
                        else if (key.equals("port")) currentServer.port = Integer.parseInt(value);
                        else if (key.equals("server_name")) currentServer.serverName = value;
                        else if (key.equals("root")) currentServer.root = value;
                        else if (key.equals("body_limit")) currentServer.bodyLimit = Long.parseLong(value);
                    } else if (currentSection.startsWith("route")) {
                        String routePath = currentSection.replace("route", "").trim();
                        if (key.equals("root")) {
                            currentServer.routes.put(routePath, value);
                        }
                    }
                }
            }
        }

        if (config.servers.isEmpty()) {
            config.servers.add(new ServerBlock());
        }

        return config;
    }

    public List<ServerBlock> getServers() {
        return this.servers;
    }
}
