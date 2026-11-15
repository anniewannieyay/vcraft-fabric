package dev.vcraft.mc.v26_3;

import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.I18n;
import dev.vcraft.core.Social;
import dev.vcraft.core.State;
import dev.vcraft.core.VCraft;
import dev.vcraft.mc.v26_3.ui.GfxCanvas;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class CallHud {
   static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
      if (!Mc.hudHidden()) {
         Gfx g = new Gfx(graphics);
         int x = 4;
         int y = 4;
         if (State.deafened || State.muted || State.autoMuted) {
            String s = State.deafened
               ? I18n.t("Deafened")
               : (State.muted ? I18n.t("Muted") : (VCraft.platform().paused() ? I18n.t("Muted while paused") : I18n.t("Muted while in another window")));
            g.panel(x - 2, y - 2, g.width(s) + 6, 13, -1610612736);
            g.text(s, x + 1, y, -1228219);
            y += 15;
         }

         State.Call call = State.call;
         if (call != null) {
            y = VCraft.config().hudCompact ? drawCompact(g, call, x, y) : drawCall(g, call, x, y);
         }

         if (VCraft.config().hudUnread && !(Mc.screen() instanceof dev.vcraft.mc.v26_3.ui.VCraftScreen)) {
            int unread = Social.unreadTotal();
            if (unread > 0) {
               String s = unread == 1 ? I18n.t("1 unread message") : I18n.tf("{0} unread messages", String.valueOf(Math.min(unread, 999)));
               g.panel(x - 2, y - 2, g.width(s) + 16, 13, -1610612736);
               g.fill(x, y + 2, 5, 5, -1228219);
               g.text(s, x + 10, y, -1);
               y += 15;
            }
         }

         Social.Request r = Social.latestRequest();
         if (r != null) {
            String s = "call".equals(r.kind()) ? I18n.tf("{0} wants to join your call", r.name()) : I18n.tf("{0} wants to join a group", r.name());
            g.panel(x - 2, y - 2, g.width(s) + 6, 13, -1610612736);
            g.text(s, x + 1, y, -1002958);
            y += 15;
         }

         if (!AddonHost.INSTANCE.huds.isEmpty()) {
            GfxCanvas canvas = new GfxCanvas(g);

            for (VCraftApi.Hud hud : AddonHost.INSTANCE.huds) {
               try {
                  y = hud.render(canvas, x, y);
               } catch (Throwable var10) {
                  VCraft.LOGGER.warn("VCraft addon HUD failed", var10);
               }
            }
         }
      }
   }

   private static int drawCall(Gfx g, State.Call call, int x, int y) {
      for (State.CallMember m : call.members()) {
         boolean speaking = State.isSpeaking(m.uuid());
         boolean mutedForMe = VCraft.config().mutedUsers.contains(m.uuid());
         String tag = m.deafened() ? "  " + I18n.t("deafened") : (m.muted() ? "  " + I18n.t("muted") : (mutedForMe ? "  " + I18n.t("muted for you") : ""));
         String name = Social.displayName(m.uuid(), m.name());
         int w = g.width(name) + g.width(tag) + 18;
         g.panel(x - 2, y - 2, w, 13, -1610612736);
         if (speaking) {
            g.outline(x - 1, y - 1, 11, 11, -12868260);
         }

         g.face(Skins.get(m.uuid()), x, y, 9);
         g.text(name, x + 13, y, speaking ? -1 : -3880498);
         g.text(tag, x + 13 + g.width(name), y, -7038044);
         y += 14;
      }

      for (State.Profile p : call.ringing()) {
         String line = I18n.tf("Ringing {0}...", Social.displayName(p.uuid(), p.name()));
         g.panel(x - 2, y - 2, g.width(line) + 6, 13, -1610612736);
         g.text(line, x + 1, y, -7038044);
         y += 14;
      }

      return y;
   }

   /** The call as a row of faces: green ring while talking, red corner when muted. */
   private static int drawCompact(Gfx g, State.Call call, int x, int y) {
      int n = call.members().size() + call.ringing().size();
      g.panel(x - 2, y - 2, n * 14 + 2, 15, -1610612736);
      int fx = x;
      for (State.CallMember m : call.members()) {
         if (State.isSpeaking(m.uuid())) {
            g.outline(fx - 1, y - 1, 13, 13, -12868260);
         }

         g.face(Skins.get(m.uuid()), fx, y, 11);
         if (m.muted() || m.deafened() || VCraft.config().mutedUsers.contains(m.uuid())) {
            g.fill(fx + 8, y + 8, 4, 4, -1228219);
         }

         fx += 14;
      }

      for (State.Profile p : call.ringing()) {
         g.face(Skins.get(p.uuid()), fx, y, 11);
         g.fill(fx, y, 11, 11, -1876745176);
         fx += 14;
      }

      return y + 17;
   }

   private CallHud() {
   }
}
