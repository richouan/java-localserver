public class Main {
    public static void main(String[] args) {
        System.out.println("Starting LocalServer...");

        int port = 8080;

        try {
            Server server = new Server(port);
            server.start();
        } catch (Exception e) {
            System.err.println("Fatal error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
