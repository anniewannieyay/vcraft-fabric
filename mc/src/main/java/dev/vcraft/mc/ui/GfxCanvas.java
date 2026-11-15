package dev.vcraft.mc.ui;

import dev.vcraft.api.Canvas;
import dev.vcraft.mc.Gfx;
import dev.vcraft.mc.Skins;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Addon drawing on top of {@link Gfx}. Widgets are inert here; the VCraft screen's subclass makes them work. */
public class GfxCanvas implements Canvas {
    protected final Gfx g;

    public GfxCanvas(Gfx g) {
        this.g = g;
    }

    @Override
    public int width(String text) {
        return g.width(text);
    }

    @Override
    public String clip(String text, int maxWidth) {
        return g.clip(text, maxWidth);
    }

    @Override
    public void text(String text, int x, int y, int color) {
        g.text(text, x, y, color);
    }

    @Override
    public void centered(String text, int centerX, int y, int color) {
        g.centered(text, centerX, y, color);
    }

    @Override
    public int paragraph(String text, int x, int y, int maxWidth, int color) {
        for (FormattedCharSequence line : g.font.split(Component.literal(text), Math.max(40, maxWidth))) {
            g.text(line, x, y, color);
            y += 10;
        }
        return y;
    }

    @Override
    public void fill(int x, int y, int w, int h, int color) {
        g.fill(x, y, w, h, color);
    }

    @Override
    public void panel(int x, int y, int w, int h, int color) {
        g.panel(x, y, w, h, color);
    }

    @Override
    public int section(String title, int x, int y, int width) {
        g.text(title, x, y, FAINT);
        g.fill(x + g.width(title) + 6, y + 4, width - g.width(title) - 6, 1, LINE);
        return y + 14;
    }

    @Override
    public void icon(String[] pattern, int x, int y, int color) {
        if (pattern != null) Icons.draw(g, pattern, x, y, color);
    }

    @Override
    public void face(String uuid, int x, int y, int size) {
        g.face(Skins.get(uuid), x, y, size);
    }

    @Override
    public boolean hovered(int x, int y, int w, int h) {
        return false;
    }

    @Override
    public void tooltip(String text) {
    }

    @Override
    public void button(int x, int y, int w, int h, String label, Style style, Runnable onClick) {
        g.panel(x, y, w, h, color(style, false));
        g.centered(g.clip(label, w - 6), x + w / 2, y + (h - 8) / 2, style == Style.SUBTLE ? MUTED : WHITE);
    }

    @Override
    public void iconButton(int x, int y, int size, String[] icon, int color, Runnable onClick) {
        icon(icon, x + (size - 9) / 2, y + (size - 9) / 2, color);
    }

    @Override
    public void toggle(int x, int y, boolean on, Runnable onClick) {
        g.panel(x, y, 30, 14, on ? GREEN : 0xFF4E5058);
        g.panel(on ? x + 18 : x + 2, y + 2, 10, 10, WHITE);
    }

    @Override
    public String input(String id, int x, int y, int w, String hint, int maxLength) {
        return "";
    }

    @Override
    public void setInput(String id, String value) {
    }

    @Override
    public int scroll() {
        return 0;
    }

    @Override
    public void contentHeight(int height) {
    }

    @Override
    public void clip(int x, int y, int w, int h) {
        g.scissor(x, y, w, h);
    }

    @Override
    public void endClip() {
        g.endScissor();
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    static int color(Style style, boolean hover) {
        return switch (style) {
            case PRIMARY -> hover ? 0xFF45C06B : GREEN;
            case DANGER -> hover ? 0xFFF25F62 : RED;
            case SUBTLE -> hover ? 0xFF404249 : 0;
            case NORMAL -> hover ? 0xFF404249 : RAISED;
        };
    }
}
