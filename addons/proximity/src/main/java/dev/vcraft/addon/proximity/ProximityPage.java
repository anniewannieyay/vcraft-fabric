package dev.vcraft.addon.proximity;

import dev.vcraft.api.Canvas;
import dev.vcraft.api.VCraftApi;

final class ProximityPage implements VCraftApi.Page {
    private final ProximityAddon addon;
    private final VCraftApi api;

    ProximityPage(ProximityAddon addon, VCraftApi api) {
        this.addon = addon;
        this.api = api;
    }

    @Override
    public String id() {
        return ProximityAddon.ID;
    }

    @Override
    public String title() {
        return "Proximity Chat";
    }

    @Override
    public String[] icon() {
        return ProximityAddon.ICON;
    }

    @Override
    public void render(Canvas c, int x, int y, int w, int h) {
        int top = y;
        ProximityAddon.Settings s = addon.settings;
        y = c.section("PROXIMITY CHAT", x, y, w);
        c.text("Talk to nearby players", x, y + 4, Canvas.TEXT);
        c.toggle(x + w - 30, y + 1, s.enabled, () -> addon.setEnabled(!s.enabled));
        y += 22;
        y = c.paragraph("Hear other VCraft players on the same Minecraft server without starting a call. Voices get quieter "
                + "with distance and go silent past your range. Mute, deafen and push-to-talk work as usual, and proximity "
                + "pauses while you're in a call.", x, y, w, Canvas.MUTED) + 6;
        y = c.paragraph("Privacy: while this is on, the relay is told a code made from the server's address so it can put you "
                + "in the same voice room as other players there. That means the relay knows which server you're on "
                + "(anyone who knows an address can work out its code). Voice stays end-to-end encrypted: the relay can't hear it.",
                x, y, w, Canvas.GOLD) + 10;

        c.text("Range", x, y + 5, Canvas.TEXT);
        int bx = x + w - 120;
        c.button(bx, y, 22, 18, "-", Canvas.Style.NORMAL, () -> addon.setRange(s.range - 8));
        c.centered(s.range + " blocks", bx + 60, y + 5, Canvas.WHITE);
        c.button(bx + 98, y, 22, 18, "+", Canvas.Style.NORMAL, () -> addon.setRange(s.range + 8));
        y += 26;
        c.text("Speaker icon above people talking", x, y + 4, Canvas.TEXT);
        c.toggle(x + w - 30, y + 1, s.speakingIcons, () -> addon.setSpeakingIcons(!s.speakingIcons));
        y += 28;

        y = c.section("STATUS", x, y, w);
        String status;
        int color = Canvas.MUTED;
        if (!s.enabled) {
            status = "Off.";
        } else if (!api.connected()) {
            status = "Waiting for VCraft to connect...";
        } else if (api.game().serverAddress() == null) {
            status = "Join a multiplayer server to use proximity chat.";
        } else if (api.voice().room() == null) {
            status = "Joining the voice room for " + api.game().serverName() + "...";
        } else if (api.voice().inCall()) {
            status = "Paused while you're in a call.";
            color = Canvas.GOLD;
        } else {
            int others = api.voice().roomMembers().size() - 1;
            status = others <= 0 ? "On. No other VCraft players with proximity chat here yet."
                    : "On. " + others + " other VCraft player" + (others == 1 ? "" : "s") + " on this server.";
            color = Canvas.GREEN;
        }
        y = c.paragraph(status, x, y, w, color) + 8;

        if (s.enabled && api.voice().room() != null) {
            y = c.section("PLAYERS", x, y, w);
            if (addon.nearby().isEmpty()) {
                c.text("Nobody else yet.", x, y + 2, Canvas.FAINT);
                y += 16;
            }
            for (ProximityAddon.Nearby n : addon.nearby()) {
                boolean talking = api.voice().isTalking(n.uuid());
                if (talking && n.gain() > 0) c.fill(x - 1, y - 1, 18, 18, Canvas.GREEN);
                c.face(n.uuid(), x, y, 16);
                c.text(c.clip(n.name(), w - 150), x + 24, y + 4, Canvas.WHITE);
                String where = Double.isInfinite(n.distance()) ? "not nearby"
                        : Math.round(n.distance()) + " blocks" + (n.gain() <= 0 ? " (out of range)" : "");
                c.text(where, x + w - c.width(where), y + 4, n.gain() > 0 ? Canvas.MUTED : Canvas.FAINT);
                y += 22;
                if (n.trust() == VCraftApi.Crypto.Trust.CHANGED) {
                    y = c.paragraph("Their encryption key changed since you last met, so you can't hear each other. This "
                            + "happens when someone reinstalls or plays on another computer. If you're unsure, compare "
                            + "safety numbers with them first.", x + 24, y, w - 24, Canvas.GOLD) + 2;
                    String sn = api.crypto().safetyNumber(n.uuid());
                    if (sn != null) y = c.paragraph("Safety number: " + sn, x + 24, y, w - 24, Canvas.MUTED) + 2;
                    c.button(x + 24, y, 110, 18, "Trust new key", Canvas.Style.PRIMARY, () -> api.crypto().trustNewKey(n.uuid()));
                    y += 26;
                }
            }
        }
        c.contentHeight(y - top);
    }
}
