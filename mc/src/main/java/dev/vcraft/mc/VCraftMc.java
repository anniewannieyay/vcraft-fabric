package dev.vcraft.mc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.AutoUpdate;
import dev.vcraft.core.NowPlaying;
import dev.vcraft.core.State;
import dev.vcraft.core.VCraft;
import dev.vcraft.mc.ui.VCraftScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class VCraftMc implements ClientModInitializer {
    public static KeyMapping openKey;
    public static KeyMapping pushToTalkKey;
    public static KeyMapping muteKey;
    public static KeyMapping deafenKey;

    @Override
    public void onInitializeClient() {
        VCraft.init(new McPlatform());
        AddonHost.loadAddons();
        SpeakingIcons.register();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(VCraft.MOD_ID, "main"));
        openKey = Mc.registerKey(new KeyMapping("key.vcraft.open", Keys.O, category));
        pushToTalkKey = Mc.registerKey(new KeyMapping("key.vcraft.push_to_talk", Keys.V, category));
        muteKey = Mc.registerKey(new KeyMapping("key.vcraft.mute", Keys.M, category));
        deafenKey = Mc.registerKey(new KeyMapping("key.vcraft.deafen", Keys.N, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) Mc.setScreen(new VCraftScreen(Mc.screen()));
            while (muteKey.consumeClick()) State.toggleMute();
            while (deafenKey.consumeClick()) State.toggleDeafen();
            VCraft.voice().setPushToTalkHeld(Mc.screen() == null && pushToTalkKey.isDown());
            State.tick();
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
                Mc.addWidget(screen, Button.builder(Component.literal("VCraft"), b -> Mc.setScreen(new VCraftScreen(screen)))
                        .bounds(4, 4, 60, 20).build());
            }
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(VCraft.MOD_ID, "call"), CallHud::draw);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> Commands.register(dispatcher));

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            moveNowPlayingKey(client);
            if (VCraft.config().autoConnect) VCraft.relay().connect();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AutoUpdate.installOnExit();
            VCraft.relay().disconnect();
            VCraft.voice().stop();
        });
    }

    /**
     * Now Playing's "toggle sharing" key defaults to N, the same as VCraft's deafen key. The first time both
     * are installed, move Now Playing's to B (or unbind it if B is taken). After that, players' own bindings stay.
     */
    private static void moveNowPlayingKey(Minecraft client) {
        if (VCraft.config().nowPlayingKeyMoved || !NowPlaying.installed()) return;
        VCraft.config().nowPlayingKeyMoved = true;
        VCraft.config().save();
        KeyMapping nowPlaying = null;
        boolean bTaken = false;
        for (KeyMapping k : client.options.keyMappings) {
            if (k.getName().equals("key.nowplaying.toggle")) nowPlaying = k;
            // F3 shortcuts (key.debug.*) only fire while F3 is held, so they don't count.
            else if (!k.getName().startsWith("key.debug.") && k.saveString().equals("key.keyboard.b")) bTaken = true;
        }
        if (nowPlaying == null || !nowPlaying.same(deafenKey)) return;
        nowPlaying.setKey(bTaken ? InputConstants.UNKNOWN : InputConstants.getKey("key.keyboard.b"));
        KeyMapping.resetMapping();
        client.options.save();
        VCraft.LOGGER.info("Moved Now Playing's toggle key off N so it doesn't clash with VCraft's deafen key");
    }
}
