package dev.vcraft.core;

import java.nio.file.Path;
import java.util.List;

/** Everything VCraft needs from the running game, implemented once per Minecraft version. */
public interface Platform {
    record ServerInfo(String name, String address) {}

    void runOnClient(Runnable task);

    String username();

    String accountId();

    void authenticate(String serverId) throws Exception;

    Path configDir();

    void toast(String title, String body);

    void chat(String tag, int tagColor, String body);

    void playDing();

    void startRingtone(String soundId);

    void stopRingtone();

    List<String> discSounds();

    ServerInfo currentServer();

    void connectToServer(String name, String address);

    // For addons.

    dev.vcraft.api.VCraftApi.Game game();

    void openAddonPage(String pageId, String arg);

    void confirmJoinServer(String name, String address);

    void openLink(String url);
}
