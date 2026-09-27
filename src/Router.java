import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Router {

    public static class RouteMatch {
        public Config.RouteRule rule;
        public Path targetPath;
        public boolean isMethodAllowed;
        public boolean isRedirect;
    }

    public static RouteMatch route(Config.ServerBlock serverBlock, String method, String requestPath) {
        RouteMatch match = new RouteMatch();

        Config.RouteRule bestRule = null;
        for (Config.RouteRule r : serverBlock.routes) {
            if (requestPath.startsWith(r.path)) {
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

        match.isMethodAllowed = bestRule.methods.contains(method.toUpperCase());
        if (!match.isMethodAllowed) {
            return match;
        }

        String subPath = requestPath.substring(bestRule.path.length());
        if (subPath.startsWith("/")) {
            subPath = subPath.substring(1);
        }

        Path resolved = Paths.get(bestRule.root, subPath).normalize();

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
