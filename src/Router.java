import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Router {

    public static class RouteMatch {
        public Config.RouteRule rule;
        public Path targetPath;
        public boolean isMethodAllowed;
        public boolean isRedirect;
        public boolean isDirectory;
        public boolean isForbidden;
    }

    public static RouteMatch route(Config.ServerBlock serverBlock, String method, String requestPath) {
        RouteMatch match = new RouteMatch();

        Config.RouteRule bestRule = null;
        for (Config.RouteRule r : serverBlock.routes) {
            if (matchesRoute(requestPath, r.path)) {
                if (bestRule == null || r.path.length() > bestRule.path.length()) {
                    bestRule = r;
                }
            }
        }

        if (bestRule == null) {
            bestRule = new Config.RouteRule();
            bestRule.root = serverBlock.root;
        }

        match.rule = bestRule;

        if (bestRule.redirect != null && !bestRule.redirect.isEmpty()) {
            match.isRedirect = true;
            return match;
        }

        match.isMethodAllowed = bestRule.methods.contains(method);
        if (!match.isMethodAllowed) {
            return match;
        }

        String subPath = requestPath.substring(bestRule.path.length());
        if (subPath.startsWith("/")) {
            subPath = subPath.substring(1);
        }

        Path resolved = Paths.get(bestRule.root, subPath).normalize();
        Path rootAbs = Paths.get(bestRule.root).toAbsolutePath().normalize();
        Path resolvedAbs = resolved.toAbsolutePath().normalize();

        // Detect directory traversal
        if (!resolvedAbs.startsWith(rootAbs)) {
            match.isForbidden = true;
            return match;
        }

        match.isDirectory = Files.isDirectory(resolved);

        if (match.isDirectory && bestRule.index != null && !bestRule.index.isEmpty()) {
            Path indexPath = resolved.resolve(bestRule.index);
            if (Files.exists(indexPath)) {
                resolved = indexPath;
            }
        }

        match.targetPath = resolved;
        return match;
    }

    private static boolean matchesRoute(String requestPath, String routePath) {
        if (routePath.equals("/")) {
            return true;
        }
        if (requestPath.equals(routePath)) {
            return true;
        }
        if (routePath.endsWith("/")) {
            return requestPath.startsWith(routePath);
        }
        return requestPath.startsWith(routePath + "/");
    }
}
