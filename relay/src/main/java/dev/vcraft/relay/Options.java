package dev.vcraft.relay;

import java.nio.file.Path;

final class Options {
    int port = Integer.parseInt(env("VCRAFT_PORT", "24470"));
    String bind = env("VCRAFT_BIND", "0.0.0.0");
    Path data = Path.of(env("VCRAFT_DATA", "vcraft-data"));
    boolean offline = Boolean.parseBoolean(env("VCRAFT_OFFLINE", "false"));
    boolean trustProxy = Boolean.parseBoolean(env("VCRAFT_TRUST_PROXY", "false"));
    int maxConnections = Integer.parseInt(env("VCRAFT_MAX_CONNECTIONS", "5000"));
    int maxPerIp = Integer.parseInt(env("VCRAFT_MAX_PER_IP", "8"));
    int authTimeoutSeconds = Integer.parseInt(env("VCRAFT_AUTH_TIMEOUT", "20"));
    int retentionDays = Integer.parseInt(env("VCRAFT_RETENTION_DAYS", "365"));
    String botToken = System.getenv("VCRAFT_BOT_TOKEN");
    /** The host name clients connect to: part of the Mojang session id they join (see SessionId). */
    String publicHost = env("VCRAFT_PUBLIC_HOST", "vcraft.2-31-104-109.sslip.io");
    boolean console = true;

    static final String USAGE = """
            Usage: java -jar vcraft-relay.jar [options]
              --port <n>             Port to listen on (default 24470)
              --bind <addr>          Address to bind (default 0.0.0.0)
              --data <dir>           Data directory (default vcraft-data)
              --trust-proxy          Read client IPs from CF-Connecting-IP / X-Forwarded-For.
                                     Only use behind Cloudflare or your own reverse proxy.
              --max-connections <n>  Total connection limit (default 5000)
              --max-per-ip <n>       Connections per IP (default 8)
              --auth-timeout <s>     Seconds a client has to sign in (default 20)
              --retention-days <n>   Delete messages older than this; 0 keeps forever (default 365)
              --no-console           Don't read admin commands from stdin
              --bot-token <secret>   Let test bots that present this token sign in without a Minecraft
                                     account. Bot names must start with "Bot". Leave unset in production.
              --public-host <name>   The host name clients connect to (default vcraft.2-31-104-109.sslip.io).
                                     Clients bind their sign-in to it, so it must match the relay URL.
              --offline              Skip Mojang account checks. Local testing only.
            Every option also has a VCRAFT_* environment variable.""";

    static Options parse(String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> o.port = Integer.parseInt(args[++i]);
                case "--bind" -> o.bind = args[++i];
                case "--data" -> o.data = Path.of(args[++i]);
                case "--offline" -> o.offline = true;
                case "--trust-proxy" -> o.trustProxy = true;
                case "--max-connections" -> o.maxConnections = Integer.parseInt(args[++i]);
                case "--max-per-ip" -> o.maxPerIp = Integer.parseInt(args[++i]);
                case "--auth-timeout" -> o.authTimeoutSeconds = Integer.parseInt(args[++i]);
                case "--retention-days" -> o.retentionDays = Integer.parseInt(args[++i]);
                case "--no-console" -> o.console = false;
                case "--bot-token" -> o.botToken = args[++i];
                case "--public-host" -> o.publicHost = args[++i];
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i] + "\n" + USAGE);
            }
        }
        return o;
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
