package dev.vcraft.mc;

import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.Platform;
import dev.vcraft.mc.ui.VCraftScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class McPlatform implements Platform {
    private SoundInstance ringtone;

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public void runOnClient(Runnable task) {
        mc().execute(task);
    }

    @Override
    public String username() {
        return mc().getUser().getName();
    }

    @Override
    public String accountId() {
        User user = mc().getUser();
        return user.getProfileId() != null ? user.getProfileId().toString() : user.getName();
    }

    @Override
    public void authenticate(String serverId) throws Exception {
        User user = mc().getUser();
        mc().services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void toast(String title, String body) {
        SystemToast.addOrUpdate(Mc.toasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(title), Component.literal(body));
    }

    @Override
    public void chat(String tag, int tagColor, String body) {
        Mc.chat(Component.literal("[" + tag + "] ").withColor(tagColor & 0xFFFFFF)
                .append(Component.literal(body).withColor(0xDBDEE1)));
    }

    @Override
    public void playDing() {
        mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f, 0.9f));
    }

    @Override
    public void startRingtone(String soundId) {
        stopRingtone();
        Identifier id = Identifier.tryParse(soundId);
        if (id == null) return;
        ringtone = new SimpleSoundInstance(id, SoundSource.MASTER, 0.8f, 1.0f, RandomSource.create(),
                true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        mc().getSoundManager().play(ringtone);
    }

    @Override
    public void stopRingtone() {
        if (ringtone != null) {
            mc().getSoundManager().stop(ringtone);
            ringtone = null;
        }
    }

    @Override
    public List<String> discSounds() {
        List<String> out = new ArrayList<>();
        for (Identifier id : mc().getSoundManager().getAvailableSounds()) {
            if (id.getPath().startsWith("music_disc.")) out.add(id.toString());
        }
        out.sort(null);
        return out;
    }

    @Override
    public ServerInfo currentServer() {
        ServerData data = mc().getCurrentServer();
        if (data == null || mc().hasSingleplayerServer() || mc().level == null) return null;
        return new ServerInfo(data.name, data.ip);
    }

    @Override
    public void connectToServer(String name, String address) {
        if (!ServerAddress.isValidAddress(address)) {
            toast("VCraft", "That server address isn't valid.");
            return;
        }
        ServerData data = new ServerData(name, address, ServerData.Type.OTHER);
        if (mc().level != null) mc().disconnectWithProgressScreen();
        ConnectScreen.startConnecting(new TitleScreen(), mc(), ServerAddress.parseString(address), data, false, null);
    }

    private final VCraftApi.Game game = new VCraftApi.Game() {
        public String serverAddress() {
            ServerInfo s = currentServer();
            return s == null ? null : s.address();
        }

        public String serverName() {
            ServerInfo s = currentServer();
            return s == null ? null : s.name();
        }

        public Position myPosition() {
            Player p = mc().player;
            return p == null ? null : position(p);
        }

        public Map<String, Position> playerPositions() {
            Map<String, Position> out = new HashMap<>();
            ClientLevel level = mc().level;
            if (level == null) return out;
            for (Player p : level.players()) {
                if (p != mc().player) out.put(p.getUUID().toString(), position(p));
            }
            return out;
        }

        private Position position(Player p) {
            return new Position(p.getX(), p.getY(), p.getZ(), p.level().dimension().toString());
        }
    };

    @Override
    public VCraftApi.Game game() {
        return game;
    }

    @Override
    public void openAddonPage(String pageId, String arg) {
        VCraftScreen.openAddonPage(pageId, arg);
    }

    @Override
    public void confirmJoinServer(String name, String address) {
        VCraftScreen.confirmJoinServer(name, address);
    }

    @Override
    public void openLink(String url) {
        Screen current = Mc.screen();
        ConfirmLinkScreen.confirmLinkNow(current, URI.create(url));
    }
}
