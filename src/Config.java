import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Config {

    public static class RouteRule {
        public String path = "/";
        public String root = "www";
        public String index = "index.html";
        public Set<String> methods = new HashSet<>(Arrays.asList("GET"));
        public String redirect = null;
        public boolean listing = false;
        public String cgiExtension = null;
        public String uploadDir = null;
    }

    public static class ServerBlock {
        public String host = "127.0.0.1";
        public int port = 8080;
        public String serverName = "";
        public String root = "www";
        public long bodyLimit = 1048576;
        public Map<Integer, String> errorPages = new HashMap<>();
        public List<RouteRule> routes = new ArrayList<>();
    }

    private List<ServerBlock> servers;

    public Config() {
        this.servers = new ArrayList<>();
        ServerBlock defaultServer = new ServerBlock();
        RouteRule defaultRoute = new RouteRule();
        defaultServer.routes.add(defaultRoute);
        this.servers.add(defaultServer);
    }

    public static Config load(String filePath) throws IOException {
        Config config = new Config();
        config.servers.clear();

        ServerBlock currentServer = null;
        RouteRule currentRoute = null;
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
                        currentRoute = null;
                    } else if (currentSection.startsWith("route") && currentServer != null) {
                        currentRoute = new RouteRule();
                        currentRoute.path = currentSection.replace("route", "").trim();
                        currentRoute.root = currentServer.root;
                        currentServer.routes.add(currentRoute);
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
                    } else if (currentSection.equals("error") || currentSection.equals("errors")) {
                        try {
                            int code = Integer.parseInt(key);
                            currentServer.errorPages.put(code, value);
                        } catch (NumberFormatException ignored) {}
                    } else if (currentRoute != null) {
                        if (key.equals("root")) currentRoute.root = value;
                        else if (key.equals("index")) currentRoute.index = value;
                        else if (key.equals("redirect")) currentRoute.redirect = value;
                        else if (key.equals("listing")) currentRoute.listing = Boolean.parseBoolean(value);
                        else if (key.equals("cgi")) currentRoute.cgiExtension = value;
                        else if (key.equals("upload_dir") || key.equals("upload")) currentRoute.uploadDir = value;
                        else if (key.equals("methods")) {
                            currentRoute.methods.clear();
                            for (String m : value.split(",")) {
                                currentRoute.methods.add(m.trim().toUpperCase());
                            }
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
