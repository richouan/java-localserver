public class StatusHandler {

    public static void handleStatus(Client client, long startTime, long requestCount, int activeClients, int activeSessions) {
        long uptimeSec = (System.currentTimeMillis() - startTime) / 1000;
        String html = "<html><head><title>Server Status</title></head><body>"
                + "<h1>Server Status & Metrics</h1>"
                + "<p><strong>Uptime:</strong> " + uptimeSec + "s</p>"
                + "<p><strong>Total Requests:</strong> " + requestCount + "</p>"
                + "<p><strong>Active Connections:</strong> " + activeClients + "</p>"
                + "<p><strong>Active Sessions:</strong> " + activeSessions + "</p>"
                + "</body></html>";
        client.prepareResponse(200, "OK", html, "text/html");
    }
}

