package dev.vcraft.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;
//#if v26
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.GuiGraphics;
//#endif

/** Drawing calls that differ between Minecraft versions. */
public final class Gfx {
    //#if v26
    public final GuiGraphicsExtractor g;

    public Gfx(GuiGraphicsExtractor g) {
        this.g = g;
    }
    //#else
    public final GuiGraphics g;

    public Gfx(GuiGraphics g) {
        this.g = g;
    }
    //#endif

    public final Font font = Minecraft.getInstance().font;

    public void fill(int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0) g.fill(x, y, x + w, y + h, color);
    }

    // Corner pixels left out so it looks slightly rounded.
    public void panel(int x, int y, int w, int h, int color) {
        if (w < 3 || h < 3) {
            fill(x, y, w, h, color);
            return;
        }
        fill(x + 1, y, w - 2, h, color);
        fill(x, y + 1, 1, h - 2, color);
        fill(x + w - 1, y + 1, 1, h - 2, color);
    }

    public void outline(int x, int y, int w, int h, int color) {
        //#if v26
        g.outline(x, y, w, h, color);
        //#else
        g.renderOutline(x, y, w, h, color);
        //#endif
    }

    public void text(String s, int x, int y, int color) {
        //#if v26
        g.text(font, s, x, y, color, false);
        //#else
        g.drawString(font, s, x, y, color, false);
        //#endif
    }

    public void text(Component s, int x, int y, int color) {
        //#if v26
        g.text(font, s, x, y, color, false);
        //#else
        g.drawString(font, s, x, y, color, false);
        //#endif
    }

    public void text(FormattedCharSequence s, int x, int y, int color) {
        //#if v26
        g.text(font, s, x, y, color, false);
        //#else
        g.drawString(font, s, x, y, color, false);
        //#endif
    }

    public void centered(String s, int cx, int y, int color) {
        text(s, cx - font.width(s) / 2, y, color);
    }

    public int width(String s) {
        return font.width(s);
    }

    public String clip(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    public void face(PlayerSkin skin, int x, int y, int size) {
        //#if v26
        PlayerFaceExtractor.extractRenderState(g, skin, x, y, size);
        //#else
        PlayerFaceRenderer.draw(g, skin, x, y, size);
        //#endif
    }

    public void scissor(int x, int y, int w, int h) {
        g.enableScissor(x, y, x + w, y + h);
    }

    public void endScissor() {
        g.disableScissor();
    }
}
