package dev.vcraft.mc.v1_21_11;

import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.Platform;
import dev.vcraft.mc.v1_21_11.ui.VCraftScreen;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_1109;
import net.minecraft.class_1113;
import net.minecraft.class_1657;
import net.minecraft.class_2561;
import net.minecraft.class_2960;
import net.minecraft.class_304;
import net.minecraft.class_310;
import net.minecraft.class_320;
import net.minecraft.class_3414;
import net.minecraft.class_3417;
import net.minecraft.class_3419;
import net.minecraft.class_370;
import net.minecraft.class_407;
import net.minecraft.class_412;
import net.minecraft.class_437;
import net.minecraft.class_442;
import net.minecraft.class_5819;
import net.minecraft.class_638;
import net.minecraft.class_639;
import net.minecraft.class_642;
import net.minecraft.class_1113.class_1114;
import net.minecraft.class_370.class_9037;
import net.minecraft.class_642.class_8678;

final class McPlatform implements Platform {
   private class_1113 ringtone;
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
         class_1657 p = McPlatform.mc().field_1724;
         return p == null ? null : this.position(p);
      }

      @Override
      public Map<String, VCraftApi.Game.Position> playerPositions() {
         Map<String, VCraftApi.Game.Position> out = new HashMap<>();
         class_638 level = McPlatform.mc().field_1687;
         if (level == null) {
            return out;
         } else {
            for (class_1657 p : level.method_18456()) {
               if (p != McPlatform.mc().field_1724) {
                  out.put(p.method_5667().toString(), this.position(p));
               }
            }

            return out;
         }
      }

      private VCraftApi.Game.Position position(class_1657 p) {
         return new VCraftApi.Game.Position(p.method_23317(), p.method_23318(), p.method_23321(), p.method_73183().method_27983().toString());
      }
   };

   private static class_310 mc() {
      return class_310.method_1551();
   }

   @Override
   public void runOnClient(Runnable task) {
      mc().execute(task);
   }

   @Override
   public String username() {
      return mc().method_1548().method_1676();
   }

   @Override
   public String accountId() {
      class_320 user = mc().method_1548();
      return user.method_44717() != null ? user.method_44717().toString() : user.method_1676();
   }

   @Override
   public void authenticate(String serverId) throws Exception {
      class_320 user = mc().method_1548();
      mc().method_73361().comp_837().joinServer(user.method_44717(), user.method_1674(), serverId);
   }

   @Override
   public Path configDir() {
      return FabricLoader.getInstance().getConfigDir();
   }

   @Override
   public void toast(String title, String body) {
      class_370.method_1990(Mc.toasts(), class_9037.field_47588, class_2561.method_43470(title), class_2561.method_43470(body));
   }

   @Override
   public void chat(String tag, int tagColor, String body) {
      Mc.chat(class_2561.method_43470("[" + tag + "] ").method_54663(tagColor & 16777215).method_10852(class_2561.method_43470(body).method_54663(14409441)));
   }

   @Override
   public void playDing() {
      mc().method_1483().method_4873(class_1109.method_4757((class_3414)class_3417.field_14793.comp_349(), 1.5F, 0.9F));
   }

   @Override
   public void startRingtone(String soundId) {
      this.stopRingtone();
      class_2960 id = class_2960.method_12829(soundId);
      if (id != null) {
         this.ringtone = new class_1109(id, class_3419.field_15250, 0.8F, 1.0F, class_5819.method_43047(), true, 0, class_1114.field_5478, 0.0, 0.0, 0.0, true);
         mc().method_1483().method_4873(this.ringtone);
      }
   }

   @Override
   public void stopRingtone() {
      if (this.ringtone != null) {
         mc().method_1483().method_4870(this.ringtone);
         this.ringtone = null;
      }
   }

   @Override
   public List<String> discSounds() {
      List<String> out = new ArrayList<>();

      for (class_2960 id : mc().method_1483().method_4864()) {
         if (id.method_12832().startsWith("music_disc.")) {
            out.add(id.toString());
         }
      }

      out.sort(null);
      return out;
   }

   @Override
   public Platform.ServerInfo currentServer() {
      class_642 data = mc().method_1558();
      return data != null && !mc().method_1496() && mc().field_1687 != null ? new Platform.ServerInfo(data.field_3752, data.field_3761) : null;
   }

   @Override
   public void connectToServer(String name, String address) {
      if (!class_639.method_36224(address)) {
         this.toast("VCraft", "That server address isn't valid.");
      } else {
         class_642 data = new class_642(name, address, class_8678.field_45611);
         if (mc().field_1687 != null) {
            mc().method_72100();
         }

         class_412.method_36877(new class_442(), mc(), class_639.method_2950(address), data, false, null);
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
      class_437 current = Mc.screen();
      class_407.method_61033(current, URI.create(url));
   }

   @Override
   public boolean inWorld() {
      return mc().field_1687 != null;
   }

   @Override
   public boolean paused() {
      return Mc.screen() instanceof net.minecraft.class_433;
   }

   @Override
   public boolean focused() {
      return mc().method_1569();
   }

   @Override
   public String language() {
      try {
         String code = mc().field_1690.field_1883;
         return code == null ? "en_us" : code;
      } catch (NullPointerException e) {
         return "en_us";
      }
   }

   @Override
   public String keyName(String key) {
      class_304 k = VCraftMc.key(key);
      return k == null || k.method_1415() ? "" : k.method_16007().getString();
   }

   @Override
   public void copy(String text) {
      mc().field_1774.method_1455(text);
   }
}
