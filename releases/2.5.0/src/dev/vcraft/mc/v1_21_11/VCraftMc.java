package dev.vcraft.mc.v1_21_11;

import dev.vcraft.core.AddonHost;
import dev.vcraft.core.AutoUpdate;
import dev.vcraft.core.NowPlaying;
import dev.vcraft.core.State;
import dev.vcraft.core.VCraft;
import dev.vcraft.mc.v1_21_11.ui.VCraftScreen;
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
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_3675;
import net.minecraft.class_4185;
import net.minecraft.class_433;
import net.minecraft.class_442;
import net.minecraft.class_304.class_11900;

public final class VCraftMc implements ClientModInitializer {
   public static class_304 openKey;
   public static class_304 pushToTalkKey;
   public static class_304 muteKey;
   public static class_304 deafenKey;
   public static class_304 acceptKey;
   public static class_304 hangUpKey;
   public static class_304 compactKey;

   /** One of VCraft's keys by name (the settings page lists them). */
   public static class_304 key(String name) {
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
      class_11900 category = class_11900.method_74698(class_2960.method_60655("vcraft", "main"));
      openKey = Mc.registerKey(new class_304("key.vcraft.open", 79, category));
      pushToTalkKey = Mc.registerKey(new class_304("key.vcraft.push_to_talk", 86, category));
      muteKey = Mc.registerKey(new class_304("key.vcraft.mute", 77, category));
      deafenKey = Mc.registerKey(new class_304("key.vcraft.deafen", 78, category));
      acceptKey = Mc.registerKey(new class_304("key.vcraft.accept_call", -1, category));
      hangUpKey = Mc.registerKey(new class_304("key.vcraft.hang_up", -1, category));
      compactKey = Mc.registerKey(new class_304("key.vcraft.compact_hud", -1, category));
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         while (openKey.method_1436()) {
            Mc.setScreen(new VCraftScreen(Mc.screen()));
         }

         while (muteKey.method_1436()) {
            State.toggleMute();
         }

         while (deafenKey.method_1436()) {
            State.toggleDeafen();
         }

         while (acceptKey.method_1436()) {
            State.Invite inv = State.latestInvite();
            if (inv != null) {
               State.acceptInvite(inv);
            }
         }

         while (hangUpKey.method_1436()) {
            State.Invite inv = State.latestInvite();
            if (inv != null) {
               State.declineInvite(inv);
            } else if (State.call != null) {
               State.send("call_leave", null, null);
            }
         }

         while (compactKey.method_1436()) {
            VCraft.config().hudCompact = !VCraft.config().hudCompact;
            VCraft.config().save();
         }

         dev.vcraft.core.Config cfg = VCraft.config();
         if ("game".equals(cfg.language)) {
            dev.vcraft.core.I18n.set(VCraft.platform().language());
         }

         State.setAutoMuted(cfg.autoMutePaused && VCraft.platform().paused() || cfg.autoMuteUnfocused && !VCraft.platform().focused());
         VCraft.voice().setPushToTalkHeld(Mc.screen() == null && pushToTalkKey.method_1434());
         State.tick();
      });
      ScreenEvents.AFTER_INIT
         .register(
            (AfterInit)(client, screen, width, height) -> {
               if (screen instanceof class_442 || screen instanceof class_433) {
                  Mc.addWidget(
                     screen,
                     class_4185.method_46430(class_2561.method_43470("VCraft"), b -> Mc.setScreen(new VCraftScreen(screen)))
                        .method_46434(4, 4, 60, 20)
                        .method_46431()
                  );
               }
            }
         );
      HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, class_2960.method_60655("vcraft", "call"), CallHud::draw);
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

   private static void moveNowPlayingKey(class_310 client) {
      if (!VCraft.config().nowPlayingKeyMoved && NowPlaying.installed()) {
         VCraft.config().nowPlayingKeyMoved = true;
         VCraft.config().save();
         class_304 nowPlaying = null;
         boolean bTaken = false;

         for (class_304 k : client.field_1690.field_1839) {
            if (k.method_1431().equals("key.nowplaying.toggle")) {
               nowPlaying = k;
            } else if (!k.method_1431().startsWith("key.debug.") && k.method_1428().equals("key.keyboard.b")) {
               bTaken = true;
            }
         }

         if (nowPlaying != null && nowPlaying.method_1435(deafenKey)) {
            nowPlaying.method_1422(bTaken ? class_3675.field_16237 : class_3675.method_15981("key.keyboard.b"));
            class_304.method_1426();
            client.field_1690.method_1640();
            VCraft.LOGGER.info("Moved Now Playing's toggle key off N so it doesn't clash with VCraft's deafen key");
         }
      }
   }
}
