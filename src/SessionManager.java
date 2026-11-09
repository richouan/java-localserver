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
            String html = sessionPage(sid, visits, false);
            client.prepareResponse(200, "OK", html, "text/html");
        } else {
            sid = UUID.randomUUID().toString().substring(0, 8);
            this.sessions.put(sid, 1);
            client.setResponseHeader("Set-Cookie", "session_id=" + sid + "; Path=/");
            String html = sessionPage(sid, 1, true);
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

    private static String sessionPage(String sid, int visits, boolean isNew) {
        String statusLabel = isNew ? "new session" : "returning";
        String statusColor = isNew ? "var(--amber)" : "var(--green)";
        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\"><head>\n"
            + "<meta charset=\"UTF-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "<title>Session — LocalServer</title>\n"
            + "<style>\n"
            + "@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;600;700&family=IBM+Plex+Sans:wght@400;500;600&display=swap');\n"
            + "*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}\n"
            + ":root{--bg:#0d0d0d;--surface:#111;--border:#222;--border-hi:#333;--fg:#e8e8e8;--muted:#555;--dim:#2a2a2a;--green:#00ff87;--amber:#ffb800;--mono:'IBM Plex Mono',monospace;--sans:'IBM Plex Sans',system-ui,sans-serif}\n"
            + "body{font-family:var(--sans);background:var(--bg);color:var(--fg);min-height:100vh;display:flex;flex-direction:column}\n"
            + ".topbar{border-bottom:1px solid var(--border);padding:0 32px;height:48px;display:flex;align-items:center;justify-content:space-between;font-family:var(--mono);font-size:.75rem;color:var(--muted)}\n"
            + ".topbar-logo{color:var(--green);font-weight:700;font-size:.8rem;letter-spacing:.05em;text-transform:uppercase}\n"
            + ".wrap{flex:1;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;padding:80px 32px;max-width:700px;margin:0 auto;width:100%}\n"
            + ".label{font-family:var(--mono);font-size:.65rem;color:var(--muted);letter-spacing:.15em;text-transform:uppercase;margin-bottom:20px}\n"
            + "h1{font-size:clamp(2rem,6vw,3.5rem);font-weight:700;letter-spacing:-.03em;color:#fff;line-height:1;margin-bottom:32px}\n"
            + ".card{background:var(--surface);border:1px solid var(--border);padding:28px;margin-bottom:32px;width:100%}\n"
            + ".row{display:flex;justify-content:space-between;align-items:center;padding:14px 0;border-bottom:1px solid var(--border)}\n"
            + ".row:last-child{border-bottom:none}\n"
            + ".rk{font-family:var(--mono);font-size:.7rem;color:var(--muted);letter-spacing:.08em;text-transform:uppercase}\n"
            + ".rv{font-family:var(--mono);font-size:.9rem;font-weight:600;color:var(--fg)}\n"
            + ".rv.big{font-size:2rem;color:#fff}\n"
            + ".status-badge{display:inline-flex;align-items:center;gap:8px;font-family:var(--mono);font-size:.7rem;letter-spacing:.08em;text-transform:uppercase;padding:6px 12px;border:1px solid;margin-bottom:32px}\n"
            + ".back{font-family:var(--mono);font-size:.75rem;font-weight:600;letter-spacing:.05em;padding:10px 20px;border:1px solid var(--border-hi);color:var(--muted);text-decoration:none;text-transform:uppercase;display:inline-flex;align-items:center;gap:8px;transition:all .1s;margin-right:12px}\n"
            + ".back:hover{border-color:var(--fg);color:var(--fg)}\n"
            + ".reload{font-family:var(--mono);font-size:.75rem;font-weight:600;letter-spacing:.05em;padding:10px 20px;border:1px solid;text-decoration:none;text-transform:uppercase;display:inline-flex;align-items:center;gap:8px;transition:all .1s;cursor:pointer}\n"
            + "footer{border-top:1px solid var(--border);padding:16px 32px;font-family:var(--mono);font-size:.7rem;color:var(--muted)}\n"
            + "</style></head><body>\n"
            + "<nav class=\"topbar\"><span class=\"topbar-logo\">LocalServer</span><span>Session Manager</span></nav>\n"
            + "<div class=\"wrap\">\n"
            + "  <div class=\"label\">// cookies &amp; sessions</div>\n"
            + "  <h1>" + (isNew ? "New Session" : "Welcome Back") + "</h1>\n"
            + "  <div class=\"status-badge\" style=\"color:" + statusColor + ";border-color:" + statusColor + "40\">" + statusLabel + "</div>\n"
            + "  <div class=\"card\">\n"
            + "    <div class=\"row\"><span class=\"rk\">Session ID</span><span class=\"rv\">" + sid + "</span></div>\n"
            + "    <div class=\"row\"><span class=\"rk\">Visits:</span><span class=\"rv big\">Visits: " + visits + "</span></div>\n"
            + "    <div class=\"row\"><span class=\"rk\">Cookie</span><span class=\"rv\">session_id=" + sid + "; Path=/</span></div>\n"
            + "    <div class=\"row\"><span class=\"rk\">Storage</span><span class=\"rv\">server-side HashMap</span></div>\n"
            + "  </div>\n"
            + "  <div>\n"
            + "    <a class=\"back\" href=\"/\">← home</a>\n"
            + "    <a class=\"reload\" href=\"/session\" style=\"color:var(--green);border-color:var(--green)40\">reload &rarr;</a>\n"
            + "  </div>\n"
            + "</div>\n"
            + "<footer>LocalServer &bull; HTTP/1.1 &bull; session: " + sid + "</footer>\n"
            + "</body></html>\n";
    }
}

