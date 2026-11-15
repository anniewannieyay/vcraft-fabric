package dev.vcraft.api;

/**
 * Drawing and widgets for addon pages and the HUD, styled like the rest of VCraft. Pages are redrawn every
 * frame (immediate mode): call {@link #button} each frame and it handles the click itself.
 * Widgets do nothing on the HUD, which can't be clicked.
 */
public interface Canvas {
    int TEXT = 0xFFDBDEE1;
    int WHITE = 0xFFFFFFFF;
    int MUTED = 0xFF949BA4;
    int FAINT = 0xFF6D727A;
    int GREEN = 0xFF3BA55C;
    int RED = 0xFFED4245;
    int GOLD = 0xFFF0B232;
    int BLUE = 0xFF5865F2;
    int PANEL = 0xFF2B2D31;
    int RAISED = 0xFF383A40;
    int LINE = 0xFF26282C;

    enum Style { PRIMARY, NORMAL, DANGER, SUBTLE }

    int width(String text);

    /** Shortens text with "..." to fit. */
    String clip(String text, int maxWidth);

    void text(String text, int x, int y, int color);

    void centered(String text, int centerX, int y, int color);

    /** Wrapped text; returns the y below it. */
    int paragraph(String text, int x, int y, int maxWidth, int color);

    void fill(int x, int y, int w, int h, int color);

    /** A rectangle with rounded-looking corners. */
    void panel(int x, int y, int w, int h, int color);

    /** Section title with a rule, like VCraft's settings page; returns the y below it. */
    int section(String title, int x, int y, int width);

    void icon(String[] pattern, int x, int y, int color);

    /** The player's skin face. */
    void face(String uuid, int x, int y, int size);

    boolean hovered(int x, int y, int w, int h);

    void tooltip(String text);

    void button(int x, int y, int w, int h, String label, Style style, Runnable onClick);

    void iconButton(int x, int y, int size, String[] icon, int color, Runnable onClick);

    void toggle(int x, int y, boolean on, Runnable onClick);

    /** A one-line text field, identified by id. Returns its current text. */
    String input(String id, int x, int y, int w, String hint, int maxLength);

    void setInput(String id, String value);

    /** Scroll offset for the page body; report the content height so VCraft can clamp it. */
    int scroll();

    void contentHeight(int height);

    /** Clip drawing to a rectangle until {@link #endClip}. */
    void clip(int x, int y, int w, int h);

    void endClip();

    long now();
}
