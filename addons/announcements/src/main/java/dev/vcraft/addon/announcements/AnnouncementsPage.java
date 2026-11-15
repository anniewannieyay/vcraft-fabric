package dev.vcraft.addon.announcements;

import dev.vcraft.addon.announcements.AnnouncementsAddon.Item;
import dev.vcraft.addon.announcements.AnnouncementsAddon.Trust;
import dev.vcraft.api.Canvas;
import dev.vcraft.api.VCraftApi;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

final class AnnouncementsPage implements VCraftApi.Page {
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.ROOT);
    private static final DateTimeFormatter POSTED = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ROOT);
    private static final DateTimeFormatter TYPED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    static final String[] ICON = {
            "......##.",
            "....####.",
            "##.#####.",
            "########.",
            "########.",
            "##.#####.",
            ".#..####.",
            ".#....##.",
            ".##......"};

    private final AnnouncementsAddon addon;
    private boolean inviteHere;

    AnnouncementsPage(AnnouncementsAddon addon) {
        this.addon = addon;
    }

    @Override
    public String id() {
        return AnnouncementsAddon.ID;
    }

    @Override
    public String title() {
        return "Announcements";
    }

    @Override
    public String[] icon() {
        return ICON;
    }

    @Override
    public int badge() {
        return addon.unseen();
    }

    @Override
    public void render(Canvas c, int x, int y, int w, int h) {
        int top = y;
        addon.markSeen();
        VCraftApi api = addon.api;

        c.fill(x - 16, y - 10, w + 32, 16, 0x40F0B232);
        c.text(c.clip("Public: not end-to-end encrypted. Posted and signed by the VCraft owner.", w), x, y - 6, 0xFFF5D58A);
        y += 14;
        c.text("Pop-up for new announcements", x, y + 4, Canvas.TEXT);
        c.toggle(x + w - 30, y + 1, addon.settings.popups, () -> addon.setPopups(!addon.settings.popups));
        y += 24;

        if ("owner".equals(api.myRank())) y = composer(c, x, y, w) + 6;

        y = c.section("EVENTS", x, y, w);
        if (addon.items.isEmpty()) {
            c.text("No announcements yet.", x, y + 2, Canvas.FAINT);
            y += 16;
        }
        for (Item it : addon.items) y = item(c, it, x, y, w) + 8;
        c.contentHeight(y - top);
    }

    private int composer(Canvas c, int x, int y, int w) {
        VCraftApi api = addon.api;
        y = c.section("NEW ANNOUNCEMENT", x, y, w);
        String title = c.input("ann.title", x, y, w, "Title", 80);
        y += 22;
        String body = c.input("ann.body", x, y, w, "What's happening?", 1000);
        y += 22;
        String when = c.input("ann.when", x, y, Math.min(200, w), "When (optional): 2026-11-02 19:00", 16);
        y += 24;
        String here = api.game().serverAddress();
        if (here != null) {
            c.text(c.clip("Invite people to " + api.game().serverName(), w - 40), x, y + 4, Canvas.TEXT);
            c.toggle(x + w - 30, y + 1, inviteHere, () -> inviteHere = !inviteHere);
            y += 22;
        }
        Long whenMs = null;
        String error = null;
        if (!when.isBlank()) {
            try {
                whenMs = LocalDateTime.parse(when.trim(), TYPED).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                error = "Write the time like 2026-11-02 19:00";
            }
        }
        if (error != null) c.text(error, x, y + 5, Canvas.RED);
        Long finalWhen = whenMs;
        boolean ok = !title.isBlank() && error == null;
        c.button(x + w - 80, y, 80, 18, "Post", ok ? Canvas.Style.PRIMARY : Canvas.Style.NORMAL, ok ? () -> {
            addon.post(title.trim(), body.trim(), finalWhen, inviteHere && here != null);
            c.setInput("ann.title", "");
            c.setInput("ann.body", "");
            c.setInput("ann.when", "");
            inviteHere = false;
        } : null);
        return y + 24;
    }

    private int item(Canvas c, Item it, int x, int y, int w) {
        int start = y;
        int accent = it.trust() == Trust.VERIFIED ? Canvas.GOLD : Canvas.RED;
        int tx = x + 10;
        int tw = w - 10;
        c.text(c.clip(it.title(), tw - 70), tx, y, Canvas.WHITE);
        if ("owner".equals(addon.api.myRank())) {
            c.button(x + w - 54, y - 3, 54, 14, "Delete", Canvas.Style.SUBTLE, () -> addon.delete(it));
        }
        y += 12;
        String by = "by " + (it.authorName() == null ? "the owner" : it.authorName()) + "  ·  " + POSTED.format(local(it.created()));
        c.text(c.clip(by, tw), tx, y, Canvas.FAINT);
        y += 12;
        switch (it.trust()) {
            case VERIFIED -> c.text("✔ Signed by the VCraft owner", tx, y, 0xFF8FDDA8);
            case KEY_CHANGED -> {
                c.text(c.clip("⚠ Signed with a different key than before", tw - 90), tx, y, Canvas.GOLD);
                c.button(x + w - 84, y - 3, 84, 14, "Trust new key", Canvas.Style.NORMAL, () -> addon.trustNewKey(it));
            }
            case INVALID -> c.text("✖ Signature doesn't match: this may not be from the owner", tx, y, Canvas.RED);
        }
        y += 14;
        if (!it.body().isBlank()) y = c.paragraph(it.body(), tx, y, tw, Canvas.TEXT) + 4;
        if (it.when() != null) {
            c.text("When: " + SHOWN.format(local(it.when())), tx, y, Canvas.GOLD);
            y += 14;
        }
        if (it.serverAddress() != null && it.trust() != Trust.INVALID) {
            c.panel(tx, y, Math.min(260, tw), 26, Canvas.PANEL);
            c.text(c.clip(it.serverName() == null ? it.serverAddress() : it.serverName(), Math.min(260, tw) - 60), tx + 6, y + 4, Canvas.WHITE);
            c.text(c.clip(it.serverAddress(), Math.min(260, tw) - 60), tx + 6, y + 15, Canvas.MUTED);
            c.button(tx + Math.min(260, tw) - 46, y + 4, 40, 18, "Join", Canvas.Style.PRIMARY,
                    () -> addon.api.ui().confirmJoinServer(it.serverName(), it.serverAddress()));
            y += 30;
        }
        if (it.trust() != Trust.INVALID) {
            String label = it.me() ? "✔ Going (" + it.going() + ")" : "I'm going" + (it.going() > 0 ? " (" + it.going() + ")" : "");
            c.button(tx, y, 100, 16, label, it.me() ? Canvas.Style.PRIMARY : Canvas.Style.NORMAL, () -> addon.rsvp(it, !it.me()));
            y += 20;
        }
        c.fill(x, start - 2, 2, y - start, accent);
        return y;
    }

    private static LocalDateTime local(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }
}
