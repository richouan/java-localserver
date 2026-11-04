import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public class VHostResolver {

    public static Config.ServerBlock resolveServerBlock(Config config, SocketChannel channel, String hostHeader) {
        int localPort = 8080;
        try {
            InetSocketAddress localAddr = (InetSocketAddress) channel.getLocalAddress();
            if (localAddr != null) {
                localPort = localAddr.getPort();
            }
        } catch (IOException ignored) {}

        String reqHost = hostHeader;
        if (reqHost != null && reqHost.contains(":")) {
            reqHost = reqHost.substring(0, reqHost.indexOf(":")).trim();
        }

        Config.ServerBlock defaultMatch = null;

        for (Config.ServerBlock block : config.getServers()) {
            if (block.port == localPort) {
                if (defaultMatch == null) {
                    defaultMatch = block;
                }
                if (reqHost != null && reqHost.equalsIgnoreCase(block.serverName)) {
                    return block;
                }
            }
        }

        return defaultMatch != null ? defaultMatch : config.getServers().get(0);
    }
}

