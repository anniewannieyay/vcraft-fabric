package dev.vcraft.mc.v26_3;

import com.mojang.blaze3d.platform.InputConstants;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.AutoUpdate;
import dev.vcraft.core.NowPlaying;
import dev.vcraft.core.State;
import dev.vcraft.core.VCraft;
import dev.vcraft.mc.v26_3.ui.VCraftScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStarted;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStopping;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AfterInit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping.Category;
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
   public static KeyMapping acceptKey;
   public static KeyMapping hangUpKey;
   public static KeyMapping compactKey;

   /** One of VCraft's keys by name (the settings page lists them). */
   public static KeyMapping key(String name) {
      return switch (name) {
         case "open" -> openKey;
         case "push_to_talk" -> pushToTalkKey;
         case "mute" -> muteKey;
         case "deafen" -> deafenKey;
         case "accept_call" -> acceptKey;
         case "hang_up" -> hangUpKey;
         case "compact_hud" -> compactKey;
         default -> null;
      };
   }

   public void onInitializeClient() {
      VCraft.init(new McPlatform());
      AddonHost.loadAddons();
      SpeakingIcons.register();
      Category category = Category.register(Identifier.fromNamespaceAndPath("vcraft", "main"));
      openKey = Mc.registerKey(new KeyMapping("key.vcraft.open", 79, category));
      pushToTalkKey = Mc.registerKey(new KeyMapping("key.vcraft.push_to_talk", 86, category));
      muteKey = Mc.registerKey(new KeyMapping("key.vcraft.mute", 77, category));
      deafenKey = Mc.registerKey(new KeyMapping("key.vcraft.deafen", 78, category));
      acceptKey = Mc.registerKey(new KeyMapping("key.vcraft.accept_call", -1, category));
      hangUpKey = Mc.registerKey(new KeyMapping("key.vcraft.hang_up", -1, category));
      compactKey = Mc.registerKey(new KeyMapping("key.vcraft.compact_hud", -1, category));
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         while (openKey.consumeClick()) {
            Mc.setScreen(new VCraftScreen(Mc.screen()));
         }

         while (muteKey.consumeClick()) {
            State.toggleMute();
         }

         while (deafenKey.consumeClick()) {
            State.toggleDeafen();
         }

         while (acceptKey.consumeClick()) {
            State.Invite inv = State.latestInvite();
            if (inv != null) {
               State.acceptInvite(inv);
            }
         }

         while (hangUpKey.consumeClick()) {
            State.Invite inv = State.latestInvite();
            if (inv != null) {
               State.declineInvite(inv);
            } else if (State.call != null) {
               State.send("call_leave", null, null);
            }
         }

         while (compactKey.consumeClick()) {
            VCraft.config().hudCompact = !VCraft.config().hudCompact;
            VCraft.config().save();
         }

         dev.vcraft.core.Config cfg = VCraft.config();
         if ("game".equals(cfg.language)) {
            dev.vcraft.core.I18n.set(VCraft.platform().language());
         }

         State.setAutoMuted(cfg.autoMutePaused && VCraft.platform().paused() || cfg.autoMuteUnfocused && !VCraft.platform().focused());
         VCraft.voice().setPushToTalkHeld(Mc.screen() == null && pushToTalkKey.isDown());
         State.tick();
      });
      ScreenEvents.AFTER_INIT.register((AfterInit)(client, screen, width, height) -> {
         if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
            Mc.addWidget(screen, Button.builder(Component.literal("VCraft"), b -> Mc.setScreen(new VCraftScreen(screen))).bounds(4, 4, 60, 20).build());
         }
      });
      HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("vcraft", "call"), CallHud::draw);
      ClientCommandRegistrationCallback.EVENT.register((ClientCommandRegistrationCallback)(dispatcher, context) -> {
         Commands.register(dispatcher);
         Commands.register25(dispatcher);
      });
      ClientLifecycleEvents.CLIENT_STARTED.register((ClientStarted)client -> {
         moveNowPlayingKey(client);
         if (VCraft.config().autoConnect) {
            VCraft.relay().connect();
         }
      });
      ClientLifecycleEvents.CLIENT_STOPPING.register((ClientStopping)client -> {
         AutoUpdate.installOnExit();
         VCraft.relay().disconnect();
         VCraft.voice().stop();
      });
   }

   private static void moveNowPlayingKey(Minecraft client) {
      if (!VCraft.config().nowPlayingKeyMoved && NowPlaying.installed()) {
         VCraft.config().nowPlayingKeyMoved = true;
         VCraft.config().save();
         KeyMapping nowPlaying = null;
         boolean bTaken = false;

         for (KeyMapping k : client.options.keyMappings) {
            if (k.getName().equals("key.nowplaying.toggle")) {
               nowPlaying = k;
            } else if (!k.getName().startsWith("key.debug.") && k.saveString().equals("key.keyboard.b")) {
               bTaken = true;
            }
         }

         if (nowPlaying != null && nowPlaying.same(deafenKey)) {
            nowPlaying.setKey(bTaken ? InputConstants.UNKNOWN : InputConstants.getKey("key.keyboard.b"));
            KeyMapping.resetMapping();
            client.options.save();
            VCraft.LOGGER.info("Moved Now Playing's toggle key off N so it doesn't clash with VCraft's deafen key");
         }
      }
   }
}
