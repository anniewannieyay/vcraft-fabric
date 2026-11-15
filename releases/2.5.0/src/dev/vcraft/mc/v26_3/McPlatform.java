package dev.vcraft.mc.v26_3;

import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.Platform;
import dev.vcraft.mc.v26_3.ui.VCraftScreen;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerData.Type;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

final class McPlatform implements Platform {
   private SoundInstance ringtone;
   private final VCraftApi.Game game = new VCraftApi.Game() {
      @Override
      public String serverAddress() {
         Platform.ServerInfo s = McPlatform.this.currentServer();
         return s == null ? null : s.address();
      }

      @Override
      public String serverName() {
         Platform.ServerInfo s = McPlatform.this.currentServer();
         return s == null ? null : s.name();
      }

      @Override
      public VCraftApi.Game.Position myPosition() {
         Player p = McPlatform.mc().player;
         return p == null ? null : this.position(p);
      }

      @Override
      public Map<String, VCraftApi.Game.Position> playerPositions() {
         Map<String, VCraftApi.Game.Position> out = new HashMap<>();
         ClientLevel level = McPlatform.mc().level;
         if (level == null) {
            return out;
         } else {
            for (Player p : level.players()) {
               if (p != McPlatform.mc().player) {
                  out.put(p.getUUID().toString(), this.position(p));
               }
            }

            return out;
         }
      }

      private VCraftApi.Game.Position position(Player p) {
         return new VCraftApi.Game.Position(p.getX(), p.getY(), p.getZ(), p.level().dimension().toString());
      }
   };

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
      SystemToast.addOrUpdate(Mc.toasts(), SystemToastId.PERIODIC_NOTIFICATION, Component.literal(title), Component.literal(body));
   }

   @Override
   public void chat(String tag, int tagColor, String body) {
      Mc.chat(Component.literal("[" + tag + "] ").withColor(tagColor & 16777215).append(Component.literal(body).withColor(14409441)));
   }

   @Override
   public void playDing() {
      mc().getSoundManager().play(SimpleSoundInstance.forUI((SoundEvent)SoundEvents.NOTE_BLOCK_BELL.value(), 1.5F, 0.9F));
   }

   @Override
   public void startRingtone(String soundId) {
      this.stopRingtone();
      Identifier id = Identifier.tryParse(soundId);
      if (id != null) {
         this.ringtone = new SimpleSoundInstance(id, SoundSource.MASTER, 0.8F, 1.0F, RandomSource.create(), true, 0, Attenuation.NONE, 0.0, 0.0, 0.0, true);
         mc().getSoundManager().play(this.ringtone);
      }
   }

   @Override
   public void stopRingtone() {
      if (this.ringtone != null) {
         mc().getSoundManager().stop(this.ringtone);
         this.ringtone = null;
      }
   }

   @Override
   public List<String> discSounds() {
      List<String> out = new ArrayList<>();

      for (Identifier id : mc().getSoundManager().getAvailableSounds()) {
         if (id.getPath().startsWith("music_disc.")) {
            out.add(id.toString());
         }
      }

      out.sort(null);
      return out;
   }

   @Override
   public Platform.ServerInfo currentServer() {
      ServerData data = mc().getCurrentServer();
      return data != null && !mc().hasSingleplayerServer() && mc().level != null ? new Platform.ServerInfo(data.name, data.ip) : null;
   }

   @Override
   public void connectToServer(String name, String address) {
      if (!ServerAddress.isValidAddress(address)) {
         this.toast("VCraft", "That server address isn't valid.");
      } else {
         ServerData data = new ServerData(name, address, Type.OTHER);
         if (mc().level != null) {
            mc().disconnectWithProgressScreen();
         }

         ConnectScreen.startConnecting(new TitleScreen(), mc(), ServerAddress.parseString(address), data, false, null);
      }
   }

   @Override
   public VCraftApi.Game game() {
      return this.game;
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

   @Override
   public boolean inWorld() {
      return mc().level != null;
   }

   @Override
   public boolean paused() {
      return Mc.screen() instanceof net.minecraft.client.gui.screens.PauseScreen;
   }

   @Override
   public boolean focused() {
      return mc().isWindowActive();
   }

   @Override
   public String language() {
      try {
         String code = mc().options.languageCode;
         return code == null ? "en_us" : code;
      } catch (NullPointerException e) {
         return "en_us";
      }
   }

   @Override
   public String keyName(String key) {
      KeyMapping k = VCraftMc.key(key);
      return k == null || k.isUnbound() ? "" : k.getTranslatedKeyMessage().getString();
   }

   @Override
   public void copy(String text) {
      mc().keyboardHandler.setClipboard(text);
   }
}
