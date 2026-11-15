package dev.vcraft.addon.grouprules;

import dev.vcraft.addon.grouprules.GroupRulesAddon.Mode;
import dev.vcraft.addon.grouprules.GroupRulesAddon.Rules;
import dev.vcraft.api.Canvas;
import dev.vcraft.api.VCraftApi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class GroupRulesPage implements VCraftApi.Page {
    static final String[] ICON = {
            ".#######.",
            "##.....##",
            "#..###..#",
            "#.......#",
            "#..###..#",
            "#.......#",
            ".#..#..#.",
            "..#...#..",
            "...###..."};
    private static final int[] SLOW_STEPS = {0, 5, 10, 30, 60, 300, 900};

    private final GroupRulesAddon addon;
    private String conv;
    private boolean editorLoaded;
    private Mode mode = Mode.MASK;
    private int slowSeconds;
    private boolean adminsOnly, requireAccept;
    private final Set<String> admins = new LinkedHashSet<>();

    GroupRulesPage(GroupRulesAddon addon) {
        this.addon = addon;
    }

    @Override
    public String id() {
        return GroupRulesAddon.ID;
    }

    @Override
    public String title() {
        VCraftApi.GroupInfo g = conv == null ? null : addon.api.group(conv);
        return g == null ? "Group rules" : "Rules: " + g.name();
    }

    @Override
    public String[] icon() {
        return ICON;
    }

    @Override
    public boolean inSidebar() {
        return false;
    }

    @Override
    public void opened(String arg) {
        if (arg != null && !arg.equals(conv)) {
            conv = arg;
            editorLoaded = false;
        }
        String group = GroupRulesAddon.groupId(conv);
        if (group != null) addon.get(group, false);
    }

    @Override
    public void render(Canvas c, int x, int y, int w, int h) {
        int top = y;
        VCraftApi api = addon.api;
        VCraftApi.GroupInfo g = conv == null ? null : api.group(conv);
        if (g == null) {
            c.text("Open a group chat, then click the rules button.", x, y, Canvas.MUTED);
            return;
        }
        Rules r = addon.rules.get(g.id());
        boolean owner = g.owner().equals(api.myUuid());
        boolean admin = addon.isAdmin(g, api.myUuid());

        c.button(x, y - 2, 70, 16, "< Back", Canvas.Style.SUBTLE, () -> api.ui().openPage(GroupRulesAddon.ID, conv));
        y += 20;

        if (r == null || r.policy == null || r.policy.version() == 0) {
            y = c.paragraph(owner ? "This group has no rules yet. Set them up below." : "This group has no rules.", x, y, w, Canvas.MUTED) + 8;
        } else {
            y = summary(c, r, x, y, w, owner || admin);
            if (r.policy.requireAccept() && !r.policy.accepted() && !owner) {
                c.button(x, y, 140, 20, "I accept the rules", Canvas.Style.PRIMARY, () -> addon.accept(g.id()));
                y += 28;
            }
        }
        if (owner) y = editor(c, g, r, x, y + 4, w);
        if (admin) y = moderation(c, g, r, x, y + 4, w, owner);
        c.contentHeight(y - top);
    }

    private int summary(Canvas c, Rules r, int x, int y, int w, boolean staff) {
        y = c.section("RULES", x, y, w);
        if (r.problem != null) y = c.paragraph(r.problem, x, y, w, Canvas.RED) + 4;
        if (r.content != null) {
            c.text("✔ Signed by the group owner", x, y, 0xFF8FDDA8);
            y += 14;
            if (!r.content.text().isBlank()) y = c.paragraph(r.content.text(), x, y, w, Canvas.TEXT) + 6;
            int n = r.content.keywords().size();
            if (n > 0) {
                String how = switch (r.content.mode()) {
                    case HIDE -> "Messages using them are hidden.";
                    case MASK -> "They're replaced with ***.";
                    case BLOCK -> "Messages using them can't be sent.";
                };
                String words = staff ? String.join(", ", r.content.keywords()) : n + " word" + (n == 1 ? "" : "s");
                y = c.paragraph("Filtered: " + words + ". " + how, x, y, w, Canvas.MUTED) + 4;
            }
        }
        StringBuilder s = new StringBuilder();
        if (r.policy.slowSeconds() > 0) s.append("Slow mode: one message every ").append(slow(r.policy.slowSeconds())).append(". ");
        if (r.policy.adminsOnly()) s.append("Only admins can post. ");
        if (r.policy.requireAccept()) s.append(r.policy.accepted() ? "You've accepted the rules. " : "You need to accept the rules to post. ");
        Long until = r.policy.mutes().get(addon.api.myUuid());
        if (until != null && until > System.currentTimeMillis()) s.append("You're muted for ").append((until - System.currentTimeMillis()) / 60_000 + 1).append(" more min. ");
        if (!s.isEmpty()) y = c.paragraph(s.toString().trim(), x, y, w, Canvas.GOLD) + 4;
        return y + 6;
    }

    private int editor(Canvas c, VCraftApi.GroupInfo g, Rules r, int x, int y, int w) {
        if (!editorLoaded) {
            editorLoaded = true;
            c.setInput("grule.text", r != null && r.content != null ? r.content.text() : "");
            c.setInput("grule.words", r != null && r.content != null ? String.join(", ", r.content.keywords()) : "");
            mode = r != null && r.content != null ? r.content.mode() : Mode.MASK;
            slowSeconds = r != null && r.policy != null ? r.policy.slowSeconds() : 0;
            adminsOnly = r != null && r.policy != null && r.policy.adminsOnly();
            requireAccept = r != null && r.policy != null && r.policy.requireAccept();
            admins.clear();
            if (r != null && r.policy != null) admins.addAll(r.policy.admins());
        }
        y = c.section("EDIT RULES", x, y, w);
        c.text("Rules text (members see this)", x, y, Canvas.MUTED);
        String text = c.input("grule.text", x, y + 11, w, "Be kind. No spam.", 500);
        y += 34;
        c.text("Filtered words, separated by commas", x, y, Canvas.MUTED);
        String words = c.input("grule.words", x, y + 11, w, "word, another word", 1500);
        y += 34;
        c.text("Filtered words are", x, y + 5, Canvas.TEXT);
        int bx = x + w - 210;
        modeButton(c, bx, y, "Hidden", Mode.HIDE);
        modeButton(c, bx + 70, y, "Masked ***", Mode.MASK);
        modeButton(c, bx + 140, y, "Blocked", Mode.BLOCK);
        y += 24;
        c.text("Slow mode", x, y + 5, Canvas.TEXT);
        c.button(x + w - 120, y, 22, 18, "-", Canvas.Style.NORMAL, () -> slowSeconds = step(slowSeconds, -1));
        c.centered(slowSeconds == 0 ? "Off" : slow(slowSeconds), x + w - 60, y + 5, Canvas.WHITE);
        c.button(x + w - 22, y, 22, 18, "+", Canvas.Style.NORMAL, () -> slowSeconds = step(slowSeconds, 1));
        y += 24;
        c.text("Only admins can post", x, y + 3, Canvas.TEXT);
        c.toggle(x + w - 30, y, adminsOnly, () -> adminsOnly = !adminsOnly);
        y += 20;
        c.text("Members must accept the rules to post", x, y + 3, Canvas.TEXT);
        c.toggle(x + w - 30, y, requireAccept, () -> requireAccept = !requireAccept);
        y += 24;
        c.text("Admins", x, y, Canvas.MUTED);
        y += 12;
        for (VCraftApi.Member m : g.members()) {
            if (m.uuid().equals(g.owner())) continue;
            c.face(m.uuid(), x, y, 12);
            c.text(c.clip(m.name(), w - 60), x + 18, y + 2, Canvas.TEXT);
            boolean on = admins.contains(m.uuid());
            c.toggle(x + w - 30, y, on, () -> {
                if (on) admins.remove(m.uuid());
                else admins.add(m.uuid());
            });
            y += 18;
        }
        y += 6;
        List<String> keywords = GroupRulesAddon.parseKeywords(words);
        c.button(x + w - 100, y, 100, 20, "Save rules", Canvas.Style.PRIMARY,
                () -> addon.save(g, mode, keywords, text.trim(), slowSeconds, adminsOnly, requireAccept, new LinkedHashSet<>(admins)));
        y = c.paragraph("Words are filtered by every member's VCraft, because messages are end-to-end encrypted and the relay "
                + "can't read them. Members without the Group Rules addon don't filter.", x, y + 26, w - 4, Canvas.FAINT);
        return y + 8;
    }

    private void modeButton(Canvas c, int x, int y, String label, Mode m) {
        c.button(x, y, 66, 18, label, mode == m ? Canvas.Style.PRIMARY : Canvas.Style.NORMAL, () -> mode = m);
    }

    private int moderation(Canvas c, VCraftApi.GroupInfo g, Rules r, int x, int y, int w, boolean owner) {
        y = c.section("MEMBERS", x, y, w);
        long now = System.currentTimeMillis();
        for (VCraftApi.Member m : g.members()) {
            if (m.uuid().equals(addon.api.myUuid()) || m.uuid().equals(g.owner())) continue;
            if (!owner && addon.isAdmin(g, m.uuid())) continue;
            c.face(m.uuid(), x, y, 14);
            c.text(c.clip(m.name(), w - 170), x + 20, y + 3, Canvas.TEXT);
            Long until = r == null || r.policy == null ? null : r.policy.mutes().get(m.uuid());
            boolean muted = until != null && until > now;
            if (muted) {
                c.button(x + w - 150, y, 90, 16, "Unmute", Canvas.Style.NORMAL, () -> addon.mute(g.id(), m.uuid(), 0));
            } else {
                c.button(x + w - 150, y, 90, 16, "Mute 10 min", Canvas.Style.NORMAL, () -> addon.mute(g.id(), m.uuid(), 10));
            }
            c.button(x + w - 54, y, 54, 16, "Kick", Canvas.Style.DANGER, () -> addon.kick(g.id(), m.uuid()));
            y += 20;
        }
        return y + 6;
    }

    private static int step(int current, int dir) {
        int i = 0;
        while (i < SLOW_STEPS.length - 1 && SLOW_STEPS[i] < current) i++;
        return SLOW_STEPS[Math.max(0, Math.min(SLOW_STEPS.length - 1, i + dir))];
    }

    private static String slow(int s) {
        return s < 60 ? s + "s" : s / 60 + " min";
    }
}
