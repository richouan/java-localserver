import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Router resolves an incoming request path against the virtual server's configured routes.
 * Uses longest-prefix matching as specified in the project architecture.
 */
public class Router {

    public static class RouteMatch {
        public Config.RouteRule rule;
        public Path targetPath;
        public boolean isMethodAllowed;
        public boolean isRedirect;
    }

    public static RouteMatch route(Config.ServerBlock serverBlock, String method, String requestPath) {
        RouteMatch match = new RouteMatch();

        // 1. Find the best matching route using longest-prefix match
        Config.RouteRule bestRule = null;
        for (Config.RouteRule r : serverBlock.routes) {
            if (requestPath.startsWith(r.path)) {
                if (bestRule == null || r.path.length() > bestRule.path.length()) {
                    bestRule = r;
                }
            }
        }

        // If no specific route matched, fallback to default
        if (bestRule == null) {
            bestRule = new Config.RouteRule();
            bestRule.root = serverBlock.root;
        }

        match.rule = bestRule;

        // 2. Check if route is a redirect
        if (bestRule.redirect != null && !bestRule.redirect.isEmpty()) {
            match.isRedirect = true;
            return match;
        }

        // 3. Verify HTTP method permission
        match.isMethodAllowed = bestRule.methods.contains(method.toUpperCase());
        if (!match.isMethodAllowed) {
            return match;
        }

        // 4. Resolve relative path against route root
        String subPath = requestPath.substring(bestRule.path.length());
        if (subPath.startsWith("/")) {
            subPath = subPath.substring(1);
        }

        Path resolved = Paths.get(bestRule.root, subPath).normalize();

        // If path is a directory, check for default index file
        if (Files.isDirectory(resolved) && bestRule.index != null && !bestRule.index.isEmpty()) {
            Path indexPath = resolved.resolve(bestRule.index);
            if (Files.exists(indexPath)) {
                resolved = indexPath;
            }
        } else if (requestPath.equals("/") || requestPath.equals(bestRule.path)) {
            if (Files.isDirectory(resolved) && bestRule.index != null) {
                resolved = resolved.resolve(bestRule.index);
            }
        }

        match.targetPath = resolved;
        return match;
    }
}
