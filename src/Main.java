import java.nio.file.Files;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) {
        System.out.println("Starting LocalServer...");

        String configFile = "config.conf";
        if (args.length > 0) {
            configFile = args[0];
        }

        try {
            Config config;
            if (Files.exists(Paths.get(configFile))) {
                config = Config.load(configFile);
                System.out.println("Loaded config from: " + configFile);
            } else {
                config = new Config();
                System.out.println("No config found, using defaults.");
            }

            Server server = new Server(config.getPort());
            server.start();
        } catch (Exception e) {
            System.err.println("Fatal error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
