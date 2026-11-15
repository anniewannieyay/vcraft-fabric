package dev.vcraft.core;

import dev.vcraft.api.VCraftApi;
import java.nio.file.Path;
import java.util.List;

public interface Platform {
   void runOnClient(Runnable var1);

   String username();

   String accountId();

   void authenticate(String var1) throws Exception;

   Path configDir();

   void toast(String var1, String var2);

   void chat(String var1, int var2, String var3);

   void playDing();

   void startRingtone(String var1);

   void stopRingtone();

   List<String> discSounds();

   Platform.ServerInfo currentServer();

   void connectToServer(String var1, String var2);

   VCraftApi.Game game();

   void openAddonPage(String var1, String var2);

   void confirmJoinServer(String var1, String var2);

   void openLink(String var1);

   /** In a world (single player or a server). */
   default boolean inWorld() {
      return this.currentServer() != null;
   }

   /** The pause menu is open. */
   default boolean paused() {
      return false;
   }

   /** The game window has the focus. */
   default boolean focused() {
      return true;
   }

   /** The game's language code (en_us, fr_fr...). */
   default String language() {
      return "en_us";
   }

   /** The key bound to one of VCraft's keys ("open", "push_to_talk"...), as its display name. */
   default String keyName(String key) {
      return "";
   }

   /** Copies text to the clipboard. */
   default void copy(String text) {
   }

   public static record ServerInfo(String name, String address) {
   }
}
