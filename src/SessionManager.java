import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SessionManager {
    private final Map<String, Integer> sessions = new HashMap<>();

    public synchronized void handleSession(Client client) {
        if (this.sessions.size() > 5000) {
            this.sessions.clear(); // Bounded cache defense against RAM leaks
        }

        String sid = getCookieValue(client, "session_id");
        int visits = 1;

        if (sid != null && this.sessions.containsKey(sid)) {
            visits = this.sessions.get(sid) + 1;
            this.sessions.put(sid, visits);
            String html = "<html><body><h1>Session Active</h1><p>Session ID: " + sid + "</p><p>Visits: " + visits + "</p></body></html>";
            client.prepareResponse(200, "OK", html, "text/html");
        } else {
            sid = UUID.randomUUID().toString().substring(0, 8);
            this.sessions.put(sid, 1);
            client.setResponseHeader("Set-Cookie", "session_id=" + sid + "; Path=/");
            String html = "<html><body><h1>New Session Created</h1><p>Session ID: " + sid + "</p><p>Visits: 1</p></body></html>";
            client.prepareResponse(200, "OK", html, "text/html");
        }
    }

    public static String getCookieValue(Client client, String cookieName) {
        String cookieHeader = client.getHeaders().get("cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=");
            if (kv.length == 2 && kv[0].trim().equals(cookieName)) {
                return kv[1].trim();
            }
        }
        return null;
    }

    public synchronized int getActiveSessionCount() {
        return this.sessions.size();
    }
}

