package dev.vcraft.relay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads admin commands from the relay's terminal. */
final class AdminConsole extends Thread {
    private static final String HELP = """
            Commands:
              stats            Connections, accounts, groups and stored messages
              list             Who's online
              kick <name>      Disconnect a player
              ban <name>       Ban a player (they can't sign in until unbanned)
              unban <name>     Lift a ban
              rank <name> <owner|mod|tester|none>   Give or remove a rank badge
              mute <name>      Stop someone posting in the community chat
              unmute <name>    Let them post again
              clear <name>     Remove all their community chat messages
              delete <name>    Permanently delete a player's data (data-deletion requests)
              stop             Save and shut down""";

    private final RelayServer server;

    AdminConsole(RelayServer server) {
        super("relay-console");
        setDaemon(true);
        this.server = server;
    }

    @Override
    public void run() {
        System.out.println("[relay] Type 'help' for admin commands.");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts[0].isEmpty()) continue;
                String arg = parts.length > 1 ? parts[1].trim() : null;
                switch (parts[0].toLowerCase()) {
                    case "help", "?" -> System.out.println(HELP);
                    case "stats" -> reply(server::adminStats);
                    case "list" -> reply(server::adminList);
                    case "kick" -> withName(arg, () -> reply(() -> server.adminKick(arg)));
                    case "ban" -> withName(arg, () -> reply(() -> server.adminBan(arg)));
                    case "unban" -> withName(arg, () -> reply(() -> server.adminUnban(arg)));
                    case "rank" -> withName(arg, () -> {
                        String[] a = arg.split("\\s+");
                        reply(() -> server.adminRank(a[0], a.length > 1 ? a[1].toLowerCase() : null));
                    });
                    case "mute" -> withName(arg, () -> reply(() -> server.adminLobbyMute(arg, true)));
                    case "unmute" -> withName(arg, () -> reply(() -> server.adminLobbyMute(arg, false)));
                    case "clear" -> withName(arg, () -> reply(() -> server.adminLobbyClear(arg)));
                    case "delete" -> withName(arg, () -> reply(() -> server.adminDelete(arg)));
                    case "stop", "exit" -> System.exit(0);
                    default -> System.out.println("Unknown command. Type 'help'.");
                }
            }
        } catch (Exception e) {
            System.err.println("[relay] Console stopped: " + e);
        }
    }

    private void withName(String arg, Runnable action) {
        if (arg == null) System.out.println("Which player?");
        else action.run();
    }

    private void reply(java.util.function.Supplier<String> command) {
        server.runOnLogic(() -> System.out.println("[relay] " + command.get()));
    }
}
