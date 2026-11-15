package dev.vcraft.mc;

import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.State;
import dev.vcraft.mc.ui.GfxCanvas;
import dev.vcraft.core.VCraft;
import net.minecraft.client.DeltaTracker;
//#if v26
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.GuiGraphics;
//#endif

final class CallHud {
    //#if v26
    static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    //#else
    static void draw(GuiGraphics graphics, DeltaTracker delta) {
    //#endif
        if (Mc.hudHidden()) return;
        Gfx g = new Gfx(graphics);
        int x = 4;
        int y = 4;

        if (State.deafened || State.muted) {
            String s = State.deafened ? "Deafened" : "Muted";
            g.panel(x - 2, y - 2, g.width(s) + 6, 13, 0xA0000000);
            g.text(s, x + 1, y, 0xFFED4245);
            y += 15;
        }

        State.Call call = State.call;
        if (call != null) y = drawCall(g, call, x, y);
        if (!AddonHost.INSTANCE.huds.isEmpty()) {
            GfxCanvas canvas = new GfxCanvas(g);
            for (VCraftApi.Hud hud : AddonHost.INSTANCE.huds) {
                try {
                    y = hud.render(canvas, x, y);
                } catch (Throwable t) {
                    VCraft.LOGGER.warn("VCraft addon HUD failed", t);
                }
            }
        }
    }

    private static int drawCall(Gfx g, State.Call call, int x, int y) {
        for (State.CallMember m : call.members()) {
            boolean speaking = State.isSpeaking(m.uuid());
            boolean mutedForMe = VCraft.config().mutedUsers.contains(m.uuid());
            String tag = m.deafened() ? "  deafened" : m.muted() ? "  muted" : mutedForMe ? "  muted for you" : "";
            int w = g.width(m.name()) + g.width(tag) + 18;
            g.panel(x - 2, y - 2, w, 13, 0xA0000000);
            if (speaking) g.outline(x - 1, y - 1, 11, 11, 0xFF3BA55C);
            g.face(Skins.get(m.uuid()), x, y, 9);
            g.text(m.name(), x + 13, y, speaking ? 0xFFFFFFFF : 0xFFC4C9CE);
            g.text(tag, x + 13 + g.width(m.name()), y, 0xFF949BA4);
            y += 14;
        }
        for (State.Profile p : call.ringing()) {
            String line = "Ringing " + p.name() + "...";
            g.panel(x - 2, y - 2, g.width(line) + 6, 13, 0xA0000000);
            g.text(line, x + 1, y, 0xFF949BA4);
            y += 14;
        }
        return y;
    }

    private CallHud() {
    }
}
