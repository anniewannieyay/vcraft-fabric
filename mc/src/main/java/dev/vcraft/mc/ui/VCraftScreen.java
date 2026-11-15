package dev.vcraft.mc.ui;

import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.AutoUpdate;
import dev.vcraft.core.Config;
import dev.vcraft.core.NowPlaying;
import dev.vcraft.core.RelayConnection;
import dev.vcraft.core.Secure;
import dev.vcraft.core.State;
import dev.vcraft.core.VCraft;
import dev.vcraft.core.VoiceEngine;
import dev.vcraft.mc.Gfx;
import dev.vcraft.mc.Keys;
import dev.vcraft.mc.Mc;
import dev.vcraft.mc.Skins;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
//#if v26
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
import net.minecraft.client.gui.GuiGraphics;
//#endif

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleConsumer;

public final class VCraftScreen extends Screen {
    private static final int BG = 0xFF1E1F22;
    private static final int SIDEBAR = 0xFF2B2D31;
    private static final int MAIN = 0xFF313338;
    private static final int RAISED = 0xFF383A40;
    private static final int HOVER = 0xFF404249;
    private static final int SELECTED = 0xFF4A4D55;
    private static final int LINE = 0xFF26282C;
    private static final int TEXT = 0xFFDBDEE1;
    private static final int MUTED = 0xFF949BA4;
    private static final int FAINT = 0xFF6D727A;
    private static final int GREEN = 0xFF3BA55C;
    private static final int GREEN_HI = 0xFF45C06B;
    private static final int RED = 0xFFED4245;
    private static final int RED_HI = 0xFFF25F62;
    private static final int GOLD = 0xFFF0B232;
    private static final int[] AVATAR_COLORS = {0xFF5865F2, 0xFF3BA55C, 0xFFED4245, 0xFFF0B232, 0xFFEB459E, 0xFF1ABC9C, 0xFFE67E22, 0xFF9B59B6};

    private enum Page { FRIENDS, CHAT, NEW_GROUP, CALL, SETTINGS, ADDON }

    private enum Modal { RENAME_GROUP, SAFETY, JOIN_SERVER, DELETE_ACCOUNT }

    private static Page page = Page.FRIENDS;
    private static String conv;
    private static String addonPage;
    private static final java.util.Map<String, String> addonInputs = new java.util.HashMap<>();
    private final java.util.Map<String, EditBox> addonBoxes = new java.util.HashMap<>();
    private static int friendsTab;
    private static boolean showMembers = true;

    private final Screen parent;

    private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {}

    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> overlayHits = new ArrayList<>();

    private record MenuItem(String label, int color, Runnable action) {}

    private List<MenuItem> menu;
    private int menuX, menuY;
    private Modal modal;
    private String modalSubject;
    private State.ServerInvite pendingJoin;

    private int mouseX, mouseY;
    private int sidebarScroll, chatScroll, pageScroll;
    private int sidebarMaxScroll, chatMaxScroll, pageMaxScroll;
    private int sbX, sbY, sbW, sbH, chatX, chatY, chatW, chatH, pageX, pageY, pageW, pageH;

    private EditBox composer, addFriend, relayUrl, groupName, renameBox;
    private String tooltip;
    private static String composerText = "", addFriendText = "", groupNameText = "";
    private static State.Reply replyTo;
    private final Set<String> newGroupMembers = new LinkedHashSet<>();

    private DoubleConsumer dragging;
    private int dragX, dragW;
    private List<String> discs = List.of();
    private boolean previewing;

    private int x0, y0, w, h;

    public VCraftScreen(Screen parent) {
        super(Component.literal("VCraft"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        composer = box(500, "Message", composerText, s -> composerText = s);
        addFriend = box(16, "Enter a Minecraft username", addFriendText, s -> addFriendText = s);
        relayUrl = box(256, "wss://relay.example.com", VCraft.config().serverUrl, null);
        groupName = box(32, "Group name", groupNameText, s -> groupNameText = s);
        renameBox = box(32, "New name", "", null);
        discs = VCraft.platform().discSounds();
        addonBoxes.clear();
        if (page == Page.CHAT && conv != null) State.openConversation(conv);
    }

    // ---- Addon pages ----

    public static void openAddonPage(String pageId, String arg) {
        VCraftApi.Page p = findAddonPage(pageId);
        if (p == null) return;
        addonPage = pageId;
        page = Page.ADDON;
        try {
            p.opened(arg);
        } catch (Throwable t) {
            VCraft.LOGGER.warn("VCraft addon page {} failed to open", pageId, t);
        }
        if (!(Mc.screen() instanceof VCraftScreen)) Mc.setScreen(new VCraftScreen(Mc.screen()));
    }

    public static void confirmJoinServer(String name, String address) {
        VCraftScreen s = Mc.screen() instanceof VCraftScreen v ? v : new VCraftScreen(Mc.screen());
        if (s != Mc.screen()) Mc.setScreen(s);
        s.pendingJoin = new State.ServerInvite(name == null ? address : name, address);
        s.openModal(Modal.JOIN_SERVER, null);
    }

    private static VCraftApi.Page findAddonPage(String id) {
        for (VCraftApi.Page p : AddonHost.INSTANCE.pages) if (p.id().equals(id)) return p;
        return null;
    }

    private void goAddon(VCraftApi.Page p) {
        openAddonPage(p.id(), null);
        go(Page.ADDON);
    }

    private void drawAddon(Gfx g, int x, int y, int pw, int ph) {
        VCraftApi.Page p = findAddonPage(addonPage);
        if (p == null) {
            go(Page.FRIENDS);
            return;
        }
        header(g, x, y, pw);
        Icons.draw(g, p.icon(), x + 12, y + 12, MUTED);
        g.text(g.clip(p.title(), pw - 40), x + 28, y + 13, 0xFFFFFFFF);
        setPageArea(x, y + 34, pw, ph - 36);
        g.scissor(pageX, pageY, pageW, pageH);
        ScreenCanvas canvas = new ScreenCanvas(g);
        try {
            p.render(canvas, pageX + 16, pageY + 10 - pageScroll, pageW - 32, pageH - 10);
        } catch (Throwable t) {
            g.text("This addon page crashed: " + t, pageX + 16, pageY + 10, RED);
            VCraft.LOGGER.warn("VCraft addon page {} failed to draw", p.id(), t);
        }
        g.endScissor();
        pageMaxScroll = Math.max(0, canvas.content + 20 - pageH);
    }

    /** Canvas whose widgets work: clicks go through the screen's hit list, inputs are real text boxes. */
    private final class ScreenCanvas extends GfxCanvas {
        int content;

        ScreenCanvas(Gfx g) {
            super(g);
        }

        @Override
        public boolean hovered(int x, int y, int w, int h) {
            return in(x, y, w, h) && inside(mouseX, mouseY, pageX, pageY, pageW, pageH);
        }

        @Override
        public void tooltip(String text) {
            VCraftScreen.this.tooltip = text;
        }

        @Override
        public void button(int x, int y, int w, int h, String label, Style style, Runnable onClick) {
            boolean hover = hovered(x, y, w, h);
            int c = color(style, hover);
            if (c != 0) g.panel(x, y, w, h, c);
            g.centered(g.clip(label, w - 6), x + w / 2, y + (h - 8) / 2, style == Style.SUBTLE && !hover ? MUTED : 0xFFFFFFFF);
            addHit(x, y, w, h, onClick);
        }

        @Override
        public void iconButton(int x, int y, int size, String[] icon, int color, Runnable onClick) {
            boolean hover = hovered(x, y, size, size);
            if (hover) g.panel(x, y, size, size, HOVER);
            icon(icon, x + (size - 9) / 2, y + (size - 9) / 2, hover ? 0xFFFFFFFF : color);
            addHit(x, y, size, size, onClick);
        }

        @Override
        public void toggle(int x, int y, boolean on, Runnable onClick) {
            boolean hover = hovered(x, y, 30, 14);
            g.panel(x, y, 30, 14, on ? (hover ? GREEN_HI : GREEN) : (hover ? 0xFF6D727A : 0xFF4E5058));
            g.panel(on ? x + 18 : x + 2, y + 2, 10, 10, 0xFFFFFFFF);
            addHit(x, y, 30, 14, onClick);
        }

        private void addHit(int x, int y, int w, int h, Runnable onClick) {
            if (onClick == null) return;
            // Only the visible part of the page is clickable.
            int top = Math.max(y, pageY);
            int bottom = Math.min(y + h, pageY + pageH);
            if (bottom <= top) return;
            hits.add(new Hit(x, top, w, bottom - top, () -> {
                try {
                    onClick.run();
                } catch (Throwable t) {
                    VCraft.LOGGER.warn("VCraft addon button failed", t);
                }
            }, null));
        }

        @Override
        public String input(String id, int x, int y, int w, String hint, int maxLength) {
            EditBox b = addonBoxes.computeIfAbsent(id, k -> box(maxLength, hint, addonInputs.getOrDefault(k, ""), s -> addonInputs.put(k, s)));
            b.setHint(Component.literal(hint).withColor(FAINT & 0xFFFFFF));
            b.setMaxLength(maxLength);
            g.panel(x, y, w, 18, BG);
            if (y + 4 >= pageY && y + 14 <= pageY + pageH) placeBox(b, x + 5, y + 5, w - 10);
            return addonInputs.getOrDefault(id, "");
        }

        @Override
        public void setInput(String id, String value) {
            addonInputs.put(id, value == null ? "" : value);
            EditBox b = addonBoxes.get(id);
            if (b != null) b.setValue(value == null ? "" : value);
        }

        @Override
        public int scroll() {
            return pageScroll;
        }

        @Override
        public void contentHeight(int height) {
            content = Math.max(content, height);
        }

        @Override
        public void clip(int x, int y, int w, int h) {
            int cx = Math.max(x, pageX), cy = Math.max(y, pageY);
            int cr = Math.min(x + w, pageX + pageW), cb = Math.min(y + h, pageY + pageH);
            g.scissor(cx, cy, Math.max(0, cr - cx), Math.max(0, cb - cy));
        }

        @Override
        public void endClip() {
            g.endScissor();
            g.scissor(pageX, pageY, pageW, pageH);
        }
    }

    private EditBox box(int max, String hint, String value, java.util.function.Consumer<String> responder) {
        EditBox b = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 12, Component.literal(hint));
        b.setBordered(false);
        b.setMaxLength(max);
        b.setTextColor(TEXT);
        b.setTextShadow(false);
        b.setHint(Component.literal(hint).withColor(FAINT & 0xFFFFFF));
        b.setValue(value == null ? "" : value);
        if (responder != null) b.setResponder(responder);
        b.visible = false;
        addRenderableWidget(b);
        return b;
    }


    //#if v26
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
        drawBase(new Gfx(graphics), mx, my);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
        super.extractRenderState(graphics, mx, my, delta);
        drawOverlay(new Gfx(graphics));
    }
    //#else
    @Override
    public void renderBackground(GuiGraphics graphics, int mx, int my, float delta) {
        drawBase(new Gfx(graphics), mx, my);
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float delta) {
        super.render(graphics, mx, my, delta);
        drawOverlay(new Gfx(graphics));
    }
    //#endif

    private void drawBase(Gfx g, int mx, int my) {
        mouseX = mx;
        mouseY = my;
        hits.clear();
        tooltip = null;
        for (EditBox b : List.of(composer, addFriend, relayUrl, groupName, renameBox)) b.visible = false;
        for (EditBox b : addonBoxes.values()) b.visible = false;

        if (State.focusRequest != null) {
            openChat(State.focusRequest);
            State.focusRequest = null;
        }
        if (page == Page.CHAT && !State.conversationExists(conv) && VCraft.relay().connected()) page = Page.FRIENDS;
        if (page == Page.CALL && State.call == null) page = Page.FRIENDS;

        g.fill(0, 0, width, height, Minecraft.getInstance().level != null ? 0xD0101113 : BG);
        w = Math.min(width - 16, 720);
        h = height - 16;
        x0 = (width - w) / 2;
        y0 = 8;
        g.panel(x0, y0, w, h, MAIN);

        int sideW = Math.min(200, Math.max(150, w / 3));
        drawSidebar(g, x0, y0, sideW, h);
        int mainX = x0 + sideW;
        int mainW = w - sideW;
        if (!VCraft.relay().connected() && page != Page.SETTINGS) {
            drawDisconnected(g, mainX, y0, mainW, h);
        } else {
            switch (page) {
                case FRIENDS -> drawFriends(g, mainX, y0, mainW, h);
                case CHAT -> drawChat(g, mainX, y0, mainW, h);
                case NEW_GROUP -> drawNewGroup(g, mainX, y0, mainW, h);
                case CALL -> drawCall(g, mainX, y0, mainW, h);
                case SETTINGS -> drawSettings(g, mainX, y0, mainW, h);
                case ADDON -> drawAddon(g, mainX, y0, mainW, h);
            }
        }
        drawNotice(g, mainX, mainW);
        if (modal != null) {
            hits.clear();
            drawModal(g);
        }
        if (getFocused() instanceof EditBox b && !b.visible) setFocused(null);
    }

    private void drawOverlay(Gfx g) {
        overlayHits.clear();
        if (tooltip != null && menu == null && modal == null) {
            String tip = g.clip(tooltip, Math.min(300, width - 16));
            int tw = g.width(tip) + 10;
            int tx = Math.max(4, Math.min(mouseX - tw / 2, width - tw - 4));
            int ty = Math.max(4, mouseY - 22);
            g.panel(tx, ty, tw, 16, 0xFF111214);
            g.text(tip, tx + 5, ty + 4, 0xFFFFFFFF);
        }
        if (menu == null) return;
        int mw = 40;
        for (MenuItem item : menu) mw = Math.max(mw, g.width(item.label()) + 20);
        int mh = menu.size() * 16 + 6;
        int mx = Math.min(menuX, width - mw - 4);
        int my = Math.min(menuY, height - mh - 4);
        g.panel(mx - 1, my - 1, mw + 2, mh + 2, 0xFF111214);
        g.panel(mx, my, mw, mh, 0xFF232428);
        int y = my + 3;
        for (MenuItem item : menu) {
            boolean hover = in(mx + 3, y, mw - 6, 16);
            if (item.action() != null && hover) g.panel(mx + 3, y, mw - 6, 16, item.color() == RED ? RED : 0xFF5865F2);
            int color = item.action() == null ? FAINT : hover ? 0xFFFFFFFF : item.color();
            g.text(item.label(), mx + 9, y + 4, color);
            if (item.action() != null) overlayHits.add(new Hit(mx + 3, y, mw - 6, 16, item.action(), null));
            y += 16;
        }
    }


    private void drawSidebar(Gfx g, int x, int y, int sw, int sh) {
        g.fill(x, y + 1, sw, sh - 2, SIDEBAR);
        g.fill(x + 1, y, sw - 1, 1, SIDEBAR);

        Icons.drawLogo(g, x + 8, y + 7);
        g.text("VCraft", x + 30, y + 11, 0xFFFFFFFF);
        RelayConnection.Status st = VCraft.relay().status();
        int dot = st == RelayConnection.Status.CONNECTED ? GREEN : st == RelayConnection.Status.DISCONNECTED ? RED : GOLD;
        g.fill(x + sw - 14, y + 13, 5, 5, dot);
        g.fill(x, y + 30, sw, 1, LINE);

        int bottom = y + sh - 40;
        int callH = State.call != null ? 46 : 0;
        List<State.Invite> live = liveInvites();
        int inviteH = live.isEmpty() ? 0 : 40;

        int listTop = y + 34;
        int listBottom = bottom - callH - inviteH;
        sbX = x;
        sbY = listTop;
        sbW = sw;
        sbH = listBottom - listTop;
        g.scissor(x, listTop, sw, sbH);
        int ly = listTop - sidebarScroll;

        int pending = State.incoming.size();
        ly = navItem(g, x, ly, sw, Icons.PEOPLE, "Friends", page == Page.FRIENDS, pending, () -> go(Page.FRIENDS));
        ly = navItem(g, x, ly, sw, Icons.GLOBE, "VCraft Community", page == Page.CHAT && State.LOBBY.equals(conv),
                State.unread.getOrDefault(State.LOBBY, 0), () -> openChat(State.LOBBY));
        for (VCraftApi.Page p : AddonHost.INSTANCE.pages) {
            if (!p.inSidebar()) continue;
            int badge;
            try {
                badge = p.badge();
            } catch (Throwable t) {
                badge = 0;
            }
            ly = navItem(g, x, ly, sw, p.icon(), g.clip(p.title(), sw - 50), page == Page.ADDON && p.id().equals(addonPage), badge, () -> goAddon(p));
        }
        ly += 6;
        ly = sectionHeader(g, x, ly, sw, "DIRECT MESSAGES", null);
        for (State.Profile f : State.friends) {
            ly = convRow(g, x, ly, sw, f.uuid(), f.name(), f.status(), null, f.uuid());
        }
        if (State.friends.isEmpty() && VCraft.relay().connected()) {
            g.text("No friends yet", x + 12, ly + 4, FAINT);
            ly += 16;
        }
        ly += 6;
        ly = sectionHeader(g, x, ly, sw, "GROUPS", () -> {
            newGroupMembers.clear();
            go(Page.NEW_GROUP);
        });
        for (State.Group gr : State.groups) {
            long on = gr.members().stream().filter(State.Profile::online).count();
            ly = convRow(g, x, ly, sw, gr.conv(), gr.name(), null, on + " of " + gr.members().size() + " online", gr.id());
        }
        if (State.groups.isEmpty() && VCraft.relay().connected()) {
            g.text("No groups yet", x + 12, ly + 4, FAINT);
            ly += 16;
        }
        g.endScissor();
        sidebarMaxScroll = Math.max(0, ly + sidebarScroll - listBottom + 6);
        sidebarScroll = Math.min(sidebarScroll, sidebarMaxScroll);

        int iy = listBottom;
        if (!live.isEmpty()) {
            State.Invite inv = live.get(live.size() - 1);
            g.fill(x, iy, sw, inviteH, 0xFF232428);
            g.fill(x, iy, 2, inviteH, GREEN);
            g.text(g.clip(inv.fromName(), sw - 70), x + 8, iy + 6, 0xFFFFFFFF);
            g.text("is calling...", x + 8, iy + 18, MUTED);
            iconButton(g, x + sw - 54, iy + 9, 22, 22, Icons.PHONE, GREEN, GREEN_HI, 0xFFFFFFFF, () -> State.acceptInvite(inv));
            iconButton(g, x + sw - 28, iy + 9, 22, 22, Icons.HANGUP, RED, RED_HI, 0xFFFFFFFF, () -> State.declineInvite(inv));
        }

        if (State.call != null) {
            int cy = bottom - callH;
            g.fill(x, cy, sw, 1, LINE);
            g.fill(x, cy + 1, sw, callH - 1, 0xFF232428);
            boolean hoverCall = in(x, cy + 1, sw - 60, callH - 1);
            g.text("Voice Connected", x + 8, cy + 9, hoverCall ? GREEN_HI : GREEN);
            State.Call call = State.call;
            String with = call.members().size() <= 1 ? "Waiting for others..." : (call.members().size() - 1) + " other" + (call.members().size() == 2 ? "" : "s") + " in call";
            g.text(g.clip(with, sw - 66), x + 8, cy + 21, MUTED);
            hits.add(new Hit(x, cy + 1, sw - 60, callH - 1, () -> go(Page.CALL), null));
            iconButton(g, x + sw - 54, cy + 12, 22, 22, Icons.REFRESH, RAISED, HOVER, TEXT, State::reconnectVoice);
            iconButton(g, x + sw - 28, cy + 12, 22, 22, Icons.HANGUP, RED, RED_HI, 0xFFFFFFFF, () -> State.send("call_leave", null, null));
        }

        g.fill(x, bottom, sw, 40, 0xFF232428);
        g.fill(x, sh + y - 1, sw, 1, 0xFF232428);
        String name = State.myName != null ? State.myName : Minecraft.getInstance().getUser().getName();
        boolean hoverMe = in(x + 4, bottom + 4, sw - 72, 32);
        if (hoverMe) g.panel(x + 4, bottom + 4, sw - 72, 32, HOVER);
        avatar(g, x + 9, bottom + 10, 20, State.myUuid == null ? String.valueOf(Minecraft.getInstance().getUser().getProfileId()) : State.myUuid, name, State.myUuid == null ? "offline" : State.myStatus);
        String shownName = g.clip(name, sw - 110);
        g.text(shownName, x + 35, bottom + 9, 0xFFFFFFFF);
        if (35 + g.width(shownName) + 40 < sw - 66) rankBadge(g, x + 39 + g.width(shownName), bottom + 8, State.myRank);
        g.text(g.clip(VCraft.relay().connected() ? State.statusLabel(State.myStatus) : "Not connected", sw - 110), x + 35, bottom + 21, MUTED);
        hits.add(new Hit(x + 4, bottom + 4, sw - 72, 32, () -> openStatusMenu(x + 6, bottom - 74), null));
        int bx = x + sw - 66;
        toggleIcon(g, bx, bottom + 9, Icons.MIC, State.muted || State.deafened, State::toggleMute);
        toggleIcon(g, bx + 21, bottom + 9, Icons.HEADPHONES, State.deafened, State::toggleDeafen);
        iconButton(g, bx + 42, bottom + 9, 20, 22, Icons.GEAR, 0, HOVER, page == Page.SETTINGS ? 0xFFFFFFFF : MUTED, () -> go(Page.SETTINGS));
    }

    private int navItem(Gfx g, int x, int y, int sw, String[] icon, String label, boolean selected, int badge, Runnable action) {
        boolean hover = in(x + 6, y, sw - 12, 24);
        if (selected || hover) g.panel(x + 6, y, sw - 12, 24, selected ? SELECTED : HOVER);
        Icons.draw(g, icon, x + 13, y + 8, selected ? 0xFFFFFFFF : MUTED);
        g.text(label, x + 30, y + 8, selected || hover ? 0xFFFFFFFF : MUTED);
        if (badge > 0) badge(g, x + sw - 14, y + 7, badge);
        hits.add(new Hit(x + 6, y, sw - 12, 24, action, null));
        return y + 26;
    }

    private int sectionHeader(Gfx g, int x, int y, int sw, String label, Runnable plus) {
        g.text(label, x + 12, y + 4, FAINT);
        if (plus != null) {
            boolean hover = in(x + sw - 22, y, 14, 14);
            Icons.draw(g, Icons.PLUS, x + sw - 20, y + 3, hover ? 0xFFFFFFFF : MUTED);
            hits.add(new Hit(x + sw - 22, y, 14, 14, plus, null));
        }
        return y + 16;
    }

    private int convRow(Gfx g, int x, int y, int sw, String convId, String name, String status, String sub, String id) {
        boolean selected = page == Page.CHAT && convId.equals(conv);
        boolean hover = in(x + 6, y, sw - 12, 26);
        if (selected || hover) g.panel(x + 6, y, sw - 12, 26, selected ? SELECTED : HOVER);
        boolean group = convId.startsWith("g:");
        if (group) groupAvatar(g, x + 11, y + 4, 18, name);
        else avatar(g, x + 11, y + 4, 18, id, name, status);
        int unread = State.unread.getOrDefault(convId, 0);
        boolean dim = !group && !"online".equals(status) && !"idle".equals(status) && !"dnd".equals(status);
        int nameColor = selected || unread > 0 ? 0xFFFFFFFF : hover ? TEXT : dim ? FAINT : MUTED;
        int textW = sw - 46 - (unread > 0 ? 18 : 0);
        if (sub != null) {
            g.text(g.clip(name, textW), x + 35, y + 4, nameColor);
            g.text(g.clip(sub, textW), x + 35, y + 15, FAINT);
        } else {
            String rank = State.rank(id);
            int rw = rank == null ? 0 : g.width(rankLabel(rank)) + 10;
            String shown = g.clip(name, textW - rw);
            g.text(shown, x + 35, y + 9, nameColor);
            rankBadge(g, x + 39 + g.width(shown), y + 8, rank);
        }
        if (unread > 0) badge(g, x + sw - 16, y + 8, unread);
        hits.add(new Hit(x + 6, y, sw - 12, 26, () -> openChat(convId),
                () -> openConvMenu(convId, mouseX, mouseY)));
        return y + 28;
    }


    private void drawFriends(Gfx g, int x, int y, int pw, int ph) {
        header(g, x, y, pw);
        Icons.draw(g, Icons.PEOPLE, x + 12, y + 12, MUTED);
        g.text("Friends", x + 28, y + 13, 0xFFFFFFFF);
        int tx = x + 28 + g.width("Friends") + 14;
        g.fill(tx - 7, y + 10, 1, 14, LINE);
        String[] tabs = {"Online", "All", "Pending", "Blocked"};
        for (int i = 0; i < tabs.length; i++) {
            String label = tabs[i];
            int tw = g.width(label) + 12;
            boolean sel = friendsTab == i;
            boolean hover = in(tx, y + 8, tw, 18);
            if (sel || hover) g.panel(tx, y + 8, tw, 18, sel ? SELECTED : HOVER);
            g.text(label, tx + 6, y + 13, sel ? 0xFFFFFFFF : MUTED);
            final int idx = i;
            hits.add(new Hit(tx, y + 8, tw, 18, () -> {
                friendsTab = idx;
                pageScroll = 0;
            }, null));
            if (i == 2 && !State.incoming.isEmpty()) badge(g, tx + tw + 2, y + 12, State.incoming.size());
            tx += tw + (i == 2 && !State.incoming.isEmpty() ? 18 : 4);
        }
        drawKofi(g, tx, x + pw - 10, y + 8);

        int cy = y + 44;
        g.text("ADD FRIEND", x + 16, cy, 0xFFFFFFFF);
        g.text("Send a request with their Minecraft username.", x + 16, cy + 12, MUTED);
        int fieldY = cy + 26;
        g.panel(x + 16, fieldY, pw - 32, 26, BG);
        placeBox(addFriend, x + 24, fieldY + 9, pw - 140);
        boolean can = !addFriend.getValue().isBlank();
        button(g, x + pw - 112, fieldY + 4, 92, 18, "Send Request", can ? GREEN : 0xFF2D5E3E, can ? GREEN_HI : 0xFF2D5E3E, can ? 0xFFFFFFFF : MUTED, this::sendFriendRequest);

        int listY = fieldY + 40;
        String title = switch (friendsTab) {
            case 0 -> "ONLINE";
            case 1 -> "ALL FRIENDS";
            case 2 -> "PENDING";
            default -> "BLOCKED";
        };
        List<Runnable> rows = new ArrayList<>();
        List<State.Profile> people = new ArrayList<>();
        if (friendsTab == 0) State.friends.stream().filter(State.Profile::online).forEach(people::add);
        if (friendsTab == 1) people.addAll(State.friends);
        if (friendsTab == 2) {
            people.addAll(State.incoming);
            people.addAll(State.outgoing);
        }
        if (friendsTab == 3) people.addAll(State.blocked);
        g.text(title + " - " + people.size(), x + 16, listY, FAINT);
        listY += 14;

        setPageArea(x, listY, pw, y + ph - listY - 4);
        g.scissor(pageX, pageY, pageW, pageH);
        int ry = listY - pageScroll;
        for (State.Profile p : people) {
            ry = friendRow(g, x + 10, ry, pw - 20, p);
        }
        if (people.isEmpty()) {
            String empty = switch (friendsTab) {
                case 0 -> "None of your friends are online right now.";
                case 1 -> "Add a friend above to get started.";
                case 2 -> "No pending friend requests.";
                default -> "You haven't blocked anyone.";
            };
            g.centered(empty, x + pw / 2, listY + 30, FAINT);
        }
        g.endScissor();
        pageMaxScroll = Math.max(0, ry + pageScroll - (pageY + pageH));
    }

    private static final String KOFI_URL = "https://ko-fi.com/annestinksbad";
    private static final int KOFI = 0xFFFF5E5B;

    private void listeningLine(Gfx g, String song, int x, int y, int maxW) {
        Icons.draw(g, Icons.NOTE, x, y, GREEN);
        g.text(g.clip(song, Math.max(20, maxW - 12)), x + 12, y, 0xFF8FDDA8);
    }

    // Support button in the top right of the Friends page; shrinks to just the heart if space is tight.
    private void drawKofi(Gfx g, int minX, int right, int y) {
        String label = "Support me";
        int full = g.width(label) + 26;
        boolean compact = right - full < minX + 6;
        int bw = compact ? 20 : full;
        int bx = right - bw;
        if (bx < minX) return;
        boolean hover = in(bx, y, bw, 18);
        g.panel(bx, y, bw, 18, hover ? 0xFFFF7A77 : KOFI);
        Icons.draw(g, Icons.HEART, bx + 6, y + 5, 0xFFFFFFFF);
        if (!compact) g.text(label, bx + 19, y + 5, 0xFFFFFFFF);
        hits.add(new Hit(bx, y, bw, 18, () -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(KOFI_URL)), null));
    }

    private int friendRow(Gfx g, int x, int y, int rw, State.Profile p) {
        g.fill(x + 6, y, rw - 12, 1, LINE);
        boolean hover = in(x, y + 1, rw, 40);
        if (hover) g.panel(x, y + 2, rw, 38, HOVER);
        boolean incoming = State.incoming.contains(p);
        boolean outgoing = State.outgoing.contains(p);
        boolean blocked = friendsTab == 3;
        avatar(g, x + 8, y + 10, 22, p.uuid(), p.name(), incoming || outgoing || blocked ? null : p.status());
        g.text(p.name(), x + 38, y + 10, 0xFFFFFFFF);
        rankBadge(g, x + 42 + g.width(p.name()), y + 9, State.rank(p.uuid()));
        int bx = x + rw - 32;
        String song = incoming || outgoing || blocked ? null : State.listening.get(p.uuid());
        if (song != null && Secure.trust(p.uuid()) != Secure.Trust.CHANGED) {
            listeningLine(g, song, x + 38, y + 22, bx - 64 - x - 38);
        } else {
            String sub = incoming ? "Incoming friend request" : outgoing ? "Outgoing friend request" : blocked ? "Blocked"
                    : State.statusLabel(p.status()) + trustNote(p.uuid());
            g.text(sub, x + 38, y + 22, Secure.trust(p.uuid()) == Secure.Trust.CHANGED && !incoming && !outgoing ? RED : MUTED);
        }
        if (incoming) {
            iconButton(g, bx, y + 9, 24, 24, Icons.CLOSE, RAISED, RED, MUTED, () -> State.send("friend_remove", "uuid", p.uuid()));
            iconButton(g, bx - 28, y + 9, 24, 24, Icons.CHECK, RAISED, GREEN, MUTED, () -> State.send("friend_accept", "uuid", p.uuid()));
        } else if (outgoing) {
            iconButton(g, bx, y + 9, 24, 24, Icons.CLOSE, RAISED, RED, MUTED, () -> State.send("friend_remove", "uuid", p.uuid()));
        } else if (blocked) {
            button(g, x + rw - 74, y + 12, 66, 18, "Unblock", RAISED, HOVER, TEXT, () -> State.send("unblock", "uuid", p.uuid()));
        } else {
            iconButton(g, bx, y + 9, 24, 24, Icons.DOTS, RAISED, HOVER, MUTED, () -> openConvMenu(p.uuid(), mouseX, mouseY));
            iconButton(g, bx - 28, y + 9, 24, 24, Icons.PHONE, RAISED, HOVER, MUTED, () -> State.call(p.uuid()));
            iconButton(g, bx - 56, y + 9, 24, 24, Icons.REPLY, RAISED, HOVER, MUTED, () -> openChat(p.uuid()));
            hits.add(new Hit(x, y + 1, rw - 90, 40, () -> openChat(p.uuid()), () -> openConvMenu(p.uuid(), mouseX, mouseY)));
        }
        return y + 42;
    }

    private static String trustNote(String uuid) {
        return switch (Secure.trust(uuid)) {
            case CHANGED -> "  -  key changed, verify!";
            case NONE -> "  -  no encryption key yet";
            default -> "";
        };
    }

    private void sendFriendRequest() {
        String name = addFriend.getValue().trim();
        if (name.isEmpty()) return;
        State.send("friend_request", "name", name);
        addFriend.setValue("");
    }


    private record Line(FormattedCharSequence text, int color) {}

    private record Item(State.Message msg, boolean showHeader, int gap, List<Line> lines, int height) {}

    private void drawChat(Gfx g, int x, int y, int pw, int ph) {
        State.Group group = State.group(conv);
        State.Profile friend = State.friend(conv);
        boolean members = group != null && showMembers && pw > 360;
        int memberW = members ? 130 : 0;
        int cw = pw - memberW;

        header(g, x, y, cw);
        boolean lobby = State.LOBBY.equals(conv);
        int ey = y + 34;
        if (lobby) {
            g.panel(x + 10, y + 7, 20, 20, GREEN);
            Icons.draw(g, Icons.GLOBE, x + 15, y + 12, 0xFFFFFFFF);
            g.text("VCraft Community", x + 36, y + 9, 0xFFFFFFFF);
            g.text(State.lobbyOnline + " online  -  official public chat", x + 36, y + 20, MUTED);
            g.fill(x, ey, cw, 14, 0x30F0B232);
            g.text(g.clip(State.lobbyMuted ? "You're muted here, but you can still read along."
                    : "Public: everyone can read this, and it isn't end-to-end encrypted. Be kind!", cw - 20), x + 10, ey + 3, 0xFFF5D88A);
            drawChatBody(g, x, y, cw, ph, ey);
            return;
        }
        if (group != null) {
            groupAvatar(g, x + 10, y + 7, 20, group.name());
            g.text(g.clip(group.name(), cw - 190), x + 36, y + 9, 0xFFFFFFFF);
            g.text(group.members().size() + (group.members().size() == 1 ? " member" : " members"), x + 36, y + 20, MUTED);
        } else if (friend != null) {
            avatar(g, x + 10, y + 7, 20, friend.uuid(), friend.name(), friend.status());
            g.text(friend.name(), x + 36, y + 9, 0xFFFFFFFF);
            rankBadge(g, x + 40 + g.width(friend.name()), y + 8, State.rank(friend.uuid()));
            String song = State.listening.get(friend.uuid());
            if (song != null) listeningLine(g, song, x + 36, y + 20, cw - 150);
            else g.text(State.statusLabel(friend.status()), x + 36, y + 20, MUTED);
        }
        int hx = x + cw - 30;
        iconButton(g, hx, y + 7, 22, 20, Icons.DOTS, 0, HOVER, MUTED, () -> openConvMenu(conv, mouseX, mouseY + 10));
        if (group != null) {
            hx -= 24;
            iconButton(g, hx, y + 7, 22, 20, Icons.PEOPLE, 0, HOVER, showMembers ? 0xFFFFFFFF : MUTED, () -> showMembers = !showMembers);
        }
        for (VCraftApi.ChatButton b : AddonHost.INSTANCE.chatButtons) {
            String c = conv;
            try {
                if (!b.visible(c)) continue;
                hx -= 24;
                iconButton(g, hx, y + 7, 22, 20, b.icon(), 0, HOVER, MUTED, () -> b.click(c));
                if (in(hx, y + 7, 22, 20)) tooltip = b.tooltip(c);
            } catch (Throwable t) {
                VCraft.LOGGER.warn("VCraft addon chat button failed", t);
            }
        }
        hx -= 24;
        iconButton(g, hx, y + 7, 22, 20, Icons.SERVER, 0, HOVER, MUTED, () -> State.inviteToServer(conv));
        hx -= 24;
        iconButton(g, hx, y + 7, 22, 20, Icons.PHONE, 0, HOVER, MUTED, () -> {
            if (group != null) State.callGroup(group);
            else State.call(conv);
        });

        Secure.Trust trust = group == null ? Secure.trust(conv) : Secure.Trust.TRUSTED;
        List<String> changed = new ArrayList<>();
        if (group != null) {
            for (State.Profile p : group.members()) if (Secure.trust(p.uuid()) == Secure.Trust.CHANGED) changed.add(p.name());
        }
        boolean warn = trust != Secure.Trust.TRUSTED || !changed.isEmpty();
        g.fill(x, ey, cw, 14, warn ? 0x40ED4245 : 0x203BA55C);
        Icons.draw(g, Icons.LOCK, x + 10, ey + 3, warn ? RED : GREEN);
        String encText = group != null
                ? (changed.isEmpty() ? "End-to-end encrypted" : "Key changed for " + String.join(", ", changed) + " - they won't get messages")
                : switch (trust) {
                    case TRUSTED -> "End-to-end encrypted. Click to verify safety number";
                    case CHANGED -> "Their encryption key changed. Click to verify";
                    case NONE -> "No encryption key yet - they need to sign in once";
                };
        g.text(g.clip(encText, cw - 30), x + 24, ey + 3, warn ? 0xFFFFB0B0 : 0xFFA6E3B8);
        if (group == null) hits.add(new Hit(x, ey, cw, 14, () -> openModal(Modal.SAFETY, conv), null));
        drawChatBody(g, x, y, cw, ph, ey);
        if (members) drawMembers(g, x + cw, y, memberW, ph, group);
    }

    private void drawChatBody(Gfx g, int x, int y, int cw, int ph, int ey) {
        State.Group group = State.group(conv);
        State.Profile friend = State.friend(conv);
        boolean lobby = State.LOBBY.equals(conv);
        int composerH = 26;
        int cy = y + ph - composerH - 10;
        if (replyTo != null) {
            g.panel(x + 12, cy - 20, cw - 24, 22, 0xFF2B2D31);
            g.text(g.clip("Replying to " + replyTo.name() + ":  " + replyTo.text(), cw - 60), x + 20, cy - 14, MUTED);
            iconButton(g, x + cw - 30, cy - 18, 14, 14, Icons.CLOSE, 0, HOVER, MUTED, () -> replyTo = null);
        }
        g.panel(x + 12, cy, cw - 24, composerH, RAISED);
        boolean songButton = NowPlaying.installed() && !(lobby && State.lobbyMuted);
        placeBox(composer, x + 22, cy + 9, cw - (songButton ? 72 : 50));
        if (songButton) {
            String song = NowPlaying.current();
            iconButton(g, x + cw - 56, cy + 4, 18, 18, Icons.NOTE, 0, HOVER, song != null ? GREEN : FAINT, () -> State.sendSong(conv));
            if (in(x + cw - 56, cy + 4, 18, 18)) {
                tooltip = song != null ? "Share what you're listening to: " + song : "Nothing is playing";
            }
        }
        composer.setHint(Component.literal(lobby && State.lobbyMuted ? "You're muted in the community chat" : "Message " + (lobby ? "VCraft Community" : group != null ? group.name() : friend != null ? "@" + friend.name() : "")).withColor(FAINT & 0xFFFFFF));
        composer.setMaxLength(lobby ? 300 : 500);
        iconButton(g, x + cw - 34, cy + 4, 18, 18, Icons.RIGHT, 0, HOVER, composer.getValue().isBlank() ? FAINT : TEXT, this::sendComposer);

        int top = ey + 16;
        int bottom = cy - (replyTo != null ? 24 : 4);
        chatX = x;
        chatY = top;
        chatW = cw;
        chatH = bottom - top;
        drawMessages(g, x, top, cw, bottom - top);
    }

    private void drawMessages(Gfx g, int x, int top, int cw, int areaH) {
        List<State.Message> msgs = State.conversations.getOrDefault(conv, List.of());
        int textW = cw - 70;
        List<Item> items = new ArrayList<>();
        State.Message prev = null;
        int total = 0;
        for (State.Message m : msgs) {
            boolean header = !m.system() && (prev == null || prev.system() || !m.from().equals(prev.from()) || m.ts() - prev.ts() > 5 * 60_000 || m.reply() != null);
            List<Line> lines = new ArrayList<>();
            if (m.system()) {
                for (FormattedCharSequence s : font.split(Component.literal(m.text()), textW)) lines.add(new Line(s, MUTED));
            } else if (m.song() == null) {
                int color = m.secure() ? TEXT : 0xFFFF8A8C;
                for (FormattedCharSequence s : font.split(Component.literal(m.text()), textW)) lines.add(new Line(s, color));
            }
            int gap = (header || m.system()) && prev != null ? 6 : 0;
            int hgt = gap + lines.size() * 10 + (header ? 14 : 0) + (m.reply() != null ? 12 : 0) + (m.invite() != null ? 44 : 0) + (m.song() != null ? 40 : 0) + 4;
            items.add(new Item(m, header, gap, lines, hgt));
            total += hgt;
            prev = m;
        }
        chatMaxScroll = Math.max(0, total - areaH + 8);
        chatScroll = Math.max(0, Math.min(chatScroll, chatMaxScroll));

        g.scissor(x, top, cw, areaH);
        int yy = top + areaH - 4 - total + chatScroll;
        if (total < areaH - 8) {
            String who = State.convName(conv);
            int sy = top + 12;
            if (State.LOBBY.equals(conv)) {
                sy = para(g, "Welcome to the VCraft Community, the official chat for everyone using VCraft.", x + 16, sy, cw - 32, MUTED);
                para(g, "Say hi, find people to play with, and keep it friendly. Moderators can mute people who aren't.", x + 16, sy + 2, cw - 32, FAINT);
            } else {
                sy = para(g, "This is the start of your conversation with " + who + ".", x + 16, sy, cw - 32, MUTED);
                para(g, "Messages are end-to-end encrypted: only the people in it can read them.", x + 16, sy + 2, cw - 32, FAINT);
            }
        }
        for (Item it : items) {
            if (yy + it.height() >= top && yy <= top + areaH) drawMessage(g, x, yy, cw, it, top, areaH);
            yy += it.height();
        }
        g.endScissor();
    }

    private void drawMessage(Gfx g, int x, int y, int cw, Item it, int clipTop, int clipH) {
        State.Message m = it.msg();
        int yy = y + it.gap();
        if (m.system()) {
            int ly = yy + 2;
            for (Line l : it.lines()) {
                g.text(l.text(), x + 50, ly, l.color());
                ly += 10;
            }
            g.fill(x + 16, yy + 6, 26, 1, FAINT);
            return;
        }
        boolean mine = m.from().equals(State.myUuid);
        int top = y + it.gap() - 2;
        boolean hover = in(x, Math.max(top, clipTop), cw, Math.min(y + it.height(), clipTop + clipH) - Math.max(top, clipTop)) && menu == null;
        if (hover) g.fill(x, top, cw, y + it.height() - top, 0xFF2E3035);

        if (m.reply() != null) {
            g.fill(x + 30, yy + 5, 12, 1, FAINT);
            g.fill(x + 30, yy + 5, 1, 6, FAINT);
            g.text(g.clip(m.reply().name() + "  " + m.reply().text(), cw - 120), x + 50, yy + 1, FAINT);
            yy += 12;
        }
        if (it.showHeader()) {
            avatar(g, x + 14, yy + 2, 26, m.from(), m.fromName(), null);
            g.text(m.fromName(), x + 50, yy + 2, mine ? 0xFF7DD3A0 : 0xFFFFFFFF);
            int after = x + 54 + g.width(m.fromName());
            after += rankBadge(g, after, yy + 1, State.rank(m.from()));
            g.text(time(m.ts()), after + 2, yy + 2, FAINT);
            yy += 14;
        }
        for (Line l : it.lines()) {
            g.text(l.text(), x + 50, yy, l.color());
            yy += 10;
        }
        if (m.song() != null) {
            int cardW = Math.min(260, cw - 70);
            g.panel(x + 50, yy + 1, cardW, 36, 0xFF2B2D31);
            g.fill(x + 50, yy + 1, 2, 36, GREEN);
            g.panel(x + 58, yy + 7, 24, 24, 0xFF1F3A2A);
            Icons.draw(g, Icons.NOTE, x + 66, yy + 15, GREEN);
            g.text(mine ? "YOU'RE LISTENING TO" : "LISTENING TO", x + 90, yy + 8, FAINT);
            g.text(g.clip(m.song(), cardW - 48), x + 90, yy + 20, 0xFFFFFFFF);
        }
        if (m.invite() != null) {
            int cardW = Math.min(240, cw - 70);
            g.panel(x + 50, yy + 3, cardW, 38, 0xFF2B2D31);
            g.fill(x + 50, yy + 3, 2, 38, GREEN);
            Icons.draw(g, Icons.SERVER, x + 58, yy + 9, GREEN);
            g.text(g.clip(m.invite().name(), cardW - 90), x + 72, yy + 9, 0xFFFFFFFF);
            g.text(g.clip(m.invite().address(), cardW - 90), x + 72, yy + 22, MUTED);
            State.ServerInvite inv = m.invite();
            if (!mine) {
                button(g, x + 50 + cardW - 54, yy + 12, 46, 18, "Join", GREEN, GREEN_HI, 0xFFFFFFFF, () -> {
                    pendingJoin = inv;
                    openModal(Modal.JOIN_SERVER, m.fromName());
                });
            } else {
                g.text("Sent", x + 50 + cardW - 34, yy + 16, FAINT);
            }
        }

        if (hover && m.id() != null) {
            boolean lobbyMsg = State.LOBBY.equals(conv);
            boolean canDelete = mine || (lobbyMsg && State.isStaff());
            boolean canMenu = lobbyMsg && !mine;
            int bw = 24 + (canDelete ? 22 : 0) + (canMenu ? 22 : 0);
            int bx = x + cw - bw - 12;
            int by = Math.max(top - 6, clipTop);
            g.panel(bx - 1, by - 1, bw + 2, 22, 0xFF1E1F22);
            g.panel(bx, by, bw, 20, RAISED);
            State.Reply reply = new State.Reply(m.id(), m.fromName(), m.invite() != null ? "Server invite: " + m.invite().name() : m.text());
            iconButton(g, bx + 2, by + 2, 20, 16, Icons.REPLY, 0, HOVER, MUTED, () -> {
                replyTo = reply;
                setFocused(composer);
            });
            int ax = bx + 24;
            if (canDelete) {
                iconButton(g, ax, by + 2, 20, 16, Icons.TRASH, 0, RED, 0xFFFF8A8C, () -> State.deleteMessage(conv, m.id()));
                ax += 22;
            }
            if (canMenu) {
                iconButton(g, ax, by + 2, 20, 16, Icons.DOTS, 0, HOVER, MUTED, () -> openLobbyUserMenu(m.from(), m.fromName(), mouseX, mouseY));
            }
        }
    }

    private void drawMembers(Gfx g, int x, int y, int mw, int ph, State.Group group) {
        g.fill(x, y + 1, mw - 1, ph - 2, SIDEBAR);
        g.fill(x, y, 1, ph, LINE);
        g.text("MEMBERS - " + group.members().size(), x + 10, y + 12, FAINT);
        int my = y + 28;
        for (State.Profile p : group.members()) {
            boolean hover = in(x + 4, my, mw - 9, 24);
            if (hover) g.panel(x + 4, my, mw - 9, 24, HOVER);
            avatar(g, x + 8, my + 4, 16, p.uuid(), p.name(), p.status());
            String label = g.clip(p.name(), mw - 46);
            g.text(label, x + 30, my + 8, p.online() ? TEXT : FAINT);
            int after = x + 33 + g.width(label);
            if (p.uuid().equals(group.owner())) {
                g.text("*", after, my + 8, GOLD);
                after += 8;
            }
            if (after + 30 < x + mw) rankBadge(g, after, my + 7, State.rank(p.uuid()));
            hits.add(new Hit(x + 4, my, mw - 9, 24, () -> openMemberMenu(group, p, mouseX, mouseY), () -> openMemberMenu(group, p, mouseX, mouseY)));
            my += 26;
            if (my > y + ph - 30) break;
        }
        boolean hoverAdd = in(x + 4, y + ph - 28, mw - 9, 22);
        if (hoverAdd) g.panel(x + 4, y + ph - 28, mw - 9, 22, HOVER);
        Icons.draw(g, Icons.PLUS, x + 10, y + ph - 21, hoverAdd ? 0xFFFFFFFF : MUTED);
        g.text("Add people", x + 26, y + ph - 20, hoverAdd ? 0xFFFFFFFF : MUTED);
        hits.add(new Hit(x + 4, y + ph - 28, mw - 9, 22, () -> openAddToGroupMenu(group, mouseX, mouseY - 80), null));
    }

    private void sendComposer() {
        String text = composer.getValue().trim();
        if (text.isEmpty() || conv == null) return;
        if (!State.sendMessage(conv, text, replyTo, null)) return;
        composer.setValue("");
        replyTo = null;
        chatScroll = 0;
    }


    private void drawNewGroup(Gfx g, int x, int y, int pw, int ph) {
        header(g, x, y, pw);
        Icons.draw(g, Icons.PEOPLE, x + 12, y + 12, MUTED);
        g.text("Create a Group", x + 28, y + 13, 0xFFFFFFFF);

        int cy = y + 46;
        g.text("GROUP NAME", x + 16, cy, MUTED);
        g.panel(x + 16, cy + 12, pw - 32, 24, BG);
        placeBox(groupName, x + 24, cy + 20, pw - 48);

        g.text("ADD FRIENDS  (" + newGroupMembers.size() + " selected)", x + 16, cy + 48, MUTED);
        setPageArea(x, cy + 62, pw, ph - (cy + 62 - y) - 44);
        g.scissor(pageX, pageY, pageW, pageH);
        int ry = pageY - pageScroll;
        for (State.Profile f : State.friends) {
            boolean sel = newGroupMembers.contains(f.uuid());
            boolean hover = in(x + 12, ry, pw - 24, 26);
            if (hover) g.panel(x + 12, ry, pw - 24, 26, HOVER);
            avatar(g, x + 18, ry + 4, 18, f.uuid(), f.name(), f.status());
            g.text(f.name(), x + 44, ry + 9, TEXT);
            int bx = x + pw - 36;
            g.panel(bx, ry + 6, 14, 14, sel ? GREEN : BG);
            g.outline(bx, ry + 6, 14, 14, sel ? GREEN : FAINT);
            if (sel) Icons.draw(g, Icons.CHECK, bx + 3, ry + 9, 0xFFFFFFFF);
            hits.add(new Hit(x + 12, ry, pw - 24, 26, () -> {
                if (!newGroupMembers.remove(f.uuid())) newGroupMembers.add(f.uuid());
            }, null));
            ry += 28;
        }
        if (State.friends.isEmpty()) g.text("Add some friends first.", x + 18, ry + 4, FAINT);
        g.endScissor();
        pageMaxScroll = Math.max(0, ry + pageScroll - (pageY + pageH));

        boolean ok = !groupName.getValue().isBlank();
        button(g, x + pw - 116, y + ph - 34, 100, 22, "Create Group", ok ? GREEN : 0xFF2D5E3E, ok ? GREEN_HI : 0xFF2D5E3E, ok ? 0xFFFFFFFF : MUTED, this::createGroup);
        button(g, x + pw - 190, y + ph - 34, 66, 22, "Cancel", 0, HOVER, TEXT, () -> go(Page.FRIENDS));
    }

    private void createGroup() {
        String name = groupName.getValue().trim();
        if (name.isEmpty()) return;
        State.createGroup(name, new ArrayList<>(newGroupMembers));
        groupName.setValue("");
        newGroupMembers.clear();
    }


    private void drawCall(Gfx g, int x, int y, int pw, int ph) {
        State.Call call = State.call;
        header(g, x, y, pw);
        Icons.draw(g, Icons.PHONE, x + 12, y + 12, GREEN);
        g.text("Voice Call", x + 28, y + 13, 0xFFFFFFFF);

        int tileW = 104, tileH = 80, gap = 10;
        List<State.CallMember> members = call.members();
        int perRow = Math.max(1, (pw - 32 + gap) / (tileW + gap));
        int rowsUsed = (members.size() + call.ringing().size() + perRow - 1) / perRow;
        int gridW = Math.min(perRow, members.size() + call.ringing().size()) * (tileW + gap) - gap;
        int gx = x + (pw - gridW) / 2;
        int gy = y + 50;
        int i = 0;
        for (State.CallMember m : members) {
            int tx = gx + (i % perRow) * (tileW + gap);
            int ty = gy + (i / perRow) * (tileH + gap);
            boolean speaking = State.isSpeaking(m.uuid());
            boolean me = m.uuid().equals(State.myUuid);
            g.panel(tx, ty, tileW, tileH, 0xFF232428);
            if (speaking) g.outline(tx, ty, tileW, tileH, GREEN);
            avatar(g, tx + tileW / 2 - 16, ty + 12, 32, m.uuid(), m.name(), null);
            g.centered(g.clip(m.name() + (me ? " (you)" : ""), tileW - 8), tx + tileW / 2, ty + 52, 0xFFFFFFFF);
            if (m.deafened()) Icons.drawStruck(g, Icons.HEADPHONES, tx + tileW - 14, ty + 4, MUTED);
            else if (m.muted()) Icons.drawStruck(g, Icons.MIC, tx + tileW - 14, ty + 4, MUTED);
            boolean mutedForMe = VCraft.config().mutedUsers.contains(m.uuid());
            if (!me) {
                String state = Secure.trust(m.uuid()) != Secure.Trust.TRUSTED ? "key not trusted"
                        : !Secure.hasVoiceKeyFrom(m.uuid()) ? "securing..." : mutedForMe ? "muted for you" : "";
                if (!state.isEmpty()) g.centered(state, tx + tileW / 2, ty + 64, mutedForMe ? FAINT : GOLD);
                Runnable memberMenu = () -> openMenu(mouseX, mouseY, List.of(
                        new MenuItem(mutedForMe ? "Unmute for me" : "Mute for me", TEXT, () -> State.toggleUserMute(m.uuid())),
                        new MenuItem("Block", RED, () -> State.send("block", "uuid", m.uuid()))));
                hits.add(new Hit(tx, ty, tileW, tileH, memberMenu, memberMenu));
            }
            i++;
        }
        for (State.Profile p : call.ringing()) {
            int tx = gx + (i % perRow) * (tileW + gap);
            int ty = gy + (i / perRow) * (tileH + gap);
            g.panel(tx, ty, tileW, tileH, 0xFF232428);
            avatar(g, tx + tileW / 2 - 16, ty + 12, 32, p.uuid(), p.name(), null);
            g.fill(tx + tileW / 2 - 16, ty + 12, 32, 32, 0x80232428);
            g.centered(g.clip(p.name(), tileW - 8), tx + tileW / 2, ty + 52, MUTED);
            g.centered("Ringing...", tx + tileW / 2, ty + 64, FAINT);
            i++;
        }

        int iy = gy + rowsUsed * (tileH + gap) + 6;
        g.text("INVITE TO CALL", x + 16, iy, MUTED);
        int ix = x + 16;
        int iyy = iy + 14;
        List<State.Profile> candidates = new ArrayList<>();
        for (State.Profile f : State.friends) if (f.online() && !State.inCall(f.uuid())) candidates.add(f);
        for (State.Group gr : State.groups) {
            for (State.Profile p : gr.members()) {
                if (p.online() && !State.inCall(p.uuid()) && !p.uuid().equals(State.myUuid) && candidates.stream().noneMatch(c -> c.uuid().equals(p.uuid()))) candidates.add(p);
            }
        }
        for (State.Profile p : candidates) {
            int cw = g.width(p.name()) + 34;
            if (ix + cw > x + pw - 16) {
                ix = x + 16;
                iyy += 24;
            }
            if (iyy > y + ph - 70) break;
            boolean hover = in(ix, iyy, cw, 20);
            g.panel(ix, iyy, cw, 20, hover ? HOVER : RAISED);
            avatar(g, ix + 4, iyy + 4, 12, p.uuid(), p.name(), null);
            g.text(p.name(), ix + 20, iyy + 6, TEXT);
            Icons.draw(g, Icons.PLUS, ix + cw - 12, iyy + 6, hover ? GREEN_HI : MUTED);
            hits.add(new Hit(ix, iyy, cw, 20, () -> State.call(p.uuid()), null));
            ix += cw + 6;
        }
        if (candidates.isEmpty()) g.text("Nobody else is online.", x + 16, iyy + 4, FAINT);

        int by = y + ph - 46;
        int bx = x + pw / 2 - 87;
        bigToggle(g, bx, by, Icons.MIC, State.muted || State.deafened, State::toggleMute);
        bigToggle(g, bx + 46, by, Icons.HEADPHONES, State.deafened, State::toggleDeafen);
        iconButton(g, bx + 92, by, 40, 32, Icons.REFRESH, RAISED, HOVER, 0xFFFFFFFF, State::reconnectVoice);
        iconButton(g, bx + 138, by, 40, 32, Icons.HANGUP, RED, RED_HI, 0xFFFFFFFF, () -> State.send("call_leave", null, null));
        if (in(bx + 92, by, 40, 32)) g.centered("Reconnect voice", bx + 112, by - 12, MUTED);
    }


    private void drawSettings(Gfx g, int x, int y, int pw, int ph) {
        header(g, x, y, pw);
        Icons.draw(g, Icons.GEAR, x + 12, y + 12, MUTED);
        g.text("Settings", x + 28, y + 13, 0xFFFFFFFF);
        Config cfg = VCraft.config();
        setPageArea(x, y + 34, pw, ph - 36);
        g.scissor(pageX, pageY, pageW, pageH);
        int sy = pageY + 10 - pageScroll;
        int lx = x + 18;
        int cx = x + pw / 2;
        int cw = pw / 2 - 18;

        sy = section(g, lx, sy, pw - 36, "ACCOUNT");
        g.text("Status", lx, sy + 5, TEXT);
        int segX = cx;
        int segW = cw / 4;
        String[] labels = {"Online", "Idle", "DND", "Offline"};
        for (int i = 0; i < 4; i++) {
            String s = State.STATUSES.get(i);
            boolean sel = s.equals(State.myStatus);
            boolean hover = in(segX + i * segW, sy, segW - 2, 18);
            g.panel(segX + i * segW, sy, segW - 2, 18, sel ? SELECTED : hover ? HOVER : RAISED);
            g.fill(segX + i * segW + 5, sy + 7, 4, 4, State.statusColor(s));
            g.text(g.clip(labels[i], segW - 16), segX + i * segW + 12, sy + 5, sel ? 0xFFFFFFFF : MUTED);
            hits.add(new Hit(segX + i * segW, sy, segW - 2, 18, () -> State.setStatus(s), null));
        }
        sy += 24;
        sy = para(g, "Do Not Disturb silences pop-ups, sounds and ringing. Offline hides you.", lx, sy, pw - 36, FAINT) + 8;

        sy = section(g, lx, sy, pw - 36, "CONNECTION");
        g.text("Relay address", lx, sy + 5, TEXT);
        g.panel(cx, sy, cw - 52, 18, BG);
        placeBox(relayUrl, cx + 6, sy + 5, cw - 64);
        button(g, cx + cw - 48, sy, 48, 18, "Save", GREEN, GREEN_HI, 0xFFFFFFFF, this::saveRelay);
        sy += 22;
        RelayConnection.Status st = VCraft.relay().status();
        String stText = switch (st) {
            case CONNECTED -> "Connected as " + State.myName;
            case CONNECTING -> "Connecting...";
            case AUTHENTICATING -> "Signing in...";
            default -> VCraft.relay().lastError() != null ? VCraft.relay().lastError() : "Not connected";
        };
        g.text(g.clip(stText, cw), cx, sy + 2, st == RelayConnection.Status.CONNECTED ? GREEN : st == RelayConnection.Status.DISCONNECTED ? RED : GOLD);
        sy += 16;
        sy = toggleRow(g, lx, cx, cw, sy, "Connect on startup", cfg.autoConnect, () -> {
            cfg.autoConnect = !cfg.autoConnect;
            cfg.save();
        });

        sy = section(g, lx, sy + 4, pw - 36, "UPDATES");
        sy = toggleRow(g, lx, cx, cw, sy, "Automatic updates", cfg.autoUpdate, () -> {
            cfg.autoUpdate = !cfg.autoUpdate;
            cfg.save();
        });
        g.text("VCraft " + AutoUpdate.currentVersion(), lx, sy + 5, TEXT);
        boolean busy = AutoUpdate.status == AutoUpdate.Status.CHECKING || AutoUpdate.status == AutoUpdate.Status.DOWNLOADING;
        button(g, cx, sy, 100, 18, busy ? "Checking..." : "Check now", RAISED, HOVER, TEXT, () -> {
            if (!busy) AutoUpdate.check();
        });
        sy += 22;
        if (AutoUpdate.detail != null) {
            int color = switch (AutoUpdate.status) {
                case READY -> GREEN;
                case FAILED -> RED;
                default -> MUTED;
            };
            sy = para(g, AutoUpdate.detail, lx, sy, pw - 36, color) + 6;
        }

        sy = section(g, lx, sy + 4, pw - 36, "VOICE");
        g.text("Input mode", lx, sy + 5, TEXT);
        int half = cw / 2;
        segment(g, cx, sy, half - 2, "Voice Activity", !cfg.pushToTalk, () -> {
            cfg.pushToTalk = false;
            cfg.save();
        });
        segment(g, cx + half, sy, half - 2, "Push to Talk", cfg.pushToTalk, () -> {
            cfg.pushToTalk = true;
            cfg.save();
        });
        sy += 24;
        sy = sliderRow(g, lx, cx, cw, sy, "Activation threshold", (cfg.voiceThresholdDb + 70) / 60, Math.round(cfg.voiceThresholdDb) + " dB",
                v -> cfg.voiceThresholdDb = Math.round(-70 + v * 60));
        VoiceEngine voice = VCraft.voice();
        if (voice.running()) {
            double level = (Math.max(-70, Math.min(-10, voice.micLevelDb())) + 70) / 60.0;
            boolean over = voice.micLevelDb() >= cfg.voiceThresholdDb;
            g.fill(cx, sy - 6, (int) (cw * level), 3, over ? GREEN : FAINT);
        }
        sy = sliderRow(g, lx, cx, cw, sy, "Mic volume", cfg.micVolume / 2, Math.round(cfg.micVolume * 100) + "%", v -> cfg.micVolume = Math.round(v * 200) / 100.0);
        sy = sliderRow(g, lx, cx, cw, sy, "Voice volume", cfg.outputVolume / 2, Math.round(cfg.outputVolume * 100) + "%", v -> cfg.outputVolume = Math.round(v * 200) / 100.0);
        g.text("Mic test", lx, sy + 5, TEXT);
        button(g, cx, sy, 110, 18, voice.micTest() ? "Stop testing" : "Let's check", voice.micTest() ? RED : RAISED, voice.micTest() ? RED_HI : HOVER, 0xFFFFFFFF,
                () -> voice.setMicTest(!voice.micTest()));
        if (voice.error() != null) g.text(g.clip(voice.error(), cw - 116), cx + 116, sy + 5, RED);
        sy += 26;

        sy = section(g, lx, sy, pw - 36, "NOTIFICATIONS");
        sy = toggleRow(g, lx, cx, cw, sy, "Pop-up notifications", cfg.notifications, () -> {
            cfg.notifications = !cfg.notifications;
            cfg.save();
        });
        sy = toggleRow(g, lx, cx, cw, sy, "Message sound", cfg.messageSound, () -> {
            cfg.messageSound = !cfg.messageSound;
            cfg.save();
            if (cfg.messageSound) VCraft.platform().playDing();
        });

        sy = section(g, lx, sy, pw - 36, "NOW LISTENING");
        sy = toggleRow(g, lx, cx, cw, sy, "Show friends my song", cfg.shareListening, () -> {
            cfg.shareListening = !cfg.shareListening;
            cfg.save();
        });
        sy = para(g, NowPlaying.installed()
                ? "Friends see what you're playing on your computer, end-to-end encrypted. Hidden while you're Invisible or when Now Playing's sharing is off."
                : "Install the Now Playing mod to show friends what you're listening to and share songs in chat.", lx, sy - 2, pw - 36, FAINT) + 8;
        g.text("Ringtone", lx, sy + 5, TEXT);
        List<String> options = new ArrayList<>(discs);
        options.add(0, "none");
        int idx = Math.max(0, options.indexOf(cfg.ringtone));
        iconButton(g, cx, sy, 18, 18, Icons.LEFT, RAISED, HOVER, TEXT, () -> cycleRingtone(options, -1));
        g.panel(cx + 20, sy, cw - 62, 18, BG);
        g.centered(g.clip(discName(options.get(idx)), cw - 70), cx + 20 + (cw - 62) / 2, sy + 5, 0xFFFFFFFF);
        iconButton(g, cx + cw - 40, sy, 18, 18, Icons.RIGHT, RAISED, HOVER, TEXT, () -> cycleRingtone(options, 1));
        iconButton(g, cx + cw - 18, sy, 18, 18, previewing ? Icons.STOP : Icons.PLAY, RAISED, HOVER, previewing ? RED : GREEN, this::togglePreview);
        sy += 22;
        sy = para(g, "Any music disc works, including ones from mods and resource packs.", lx, sy, pw - 36, FAINT) + 8;

        sy = section(g, lx, sy, pw - 36, "PRIVACY");
        g.text("Blocked users", lx, sy + 5, TEXT);
        button(g, cx, sy, Math.min(cw, 130), 18, State.blocked.size() + " blocked - manage", RAISED, HOVER, TEXT, () -> {
            friendsTab = 3;
            go(Page.FRIENDS);
        });
        sy += 24;
        g.text("Your data", lx, sy + 5, TEXT);
        int dataW = Math.min(cw / 2 - 2, 120);
        button(g, cx, sy, dataW, 18, "Download my data", RAISED, HOVER, TEXT, State::exportData);
        button(g, cx + dataW + 4, sy, dataW, 18, "Delete account", 0xFF5C2B2D, RED, 0xFFFFB0B0, () -> openModal(Modal.DELETE_ACCOUNT, null));
        sy += 24;
        sy = para(g, "Messages and calls are end-to-end encrypted. Your key lives in config/vcraft - back it up to keep your identity on a new PC.", lx, sy, pw - 36, FAINT) + 10;

        sy = section(g, lx, sy, pw - 36, "CONTROLS");
        sy = para(g, "Open VCraft: O    Push to talk: V    Mute: M    Deafen: N", lx, sy, pw - 36, MUTED);
        sy = para(g, "Change them in Options > Controls > Key Binds > VCraft.", lx, sy, pw - 36, FAINT) + 2;
        sy = para(g, "Commands: /vc msg, call, invite, accept, decline, hangup, status, add", lx, sy, pw - 36, FAINT) + 12;
        if (State.isStaff()) {
            sy = para(g, "Admin (" + ("owner".equals(State.myRank) ? "owner" : "mod") + "): /vc admin stats, list, kick, mute, unmute, clear"
                    + ("owner".equals(State.myRank) ? ", ban, unban, rank, delete" : ""), lx, sy - 8, pw - 36, GOLD) + 12;
        }
        g.endScissor();
        pageMaxScroll = Math.max(0, sy + pageScroll - (pageY + pageH));
    }

    private int para(Gfx g, String text, int x, int y, int maxW, int color) {
        for (FormattedCharSequence line : font.split(Component.literal(text), Math.max(40, maxW))) {
            g.text(line, x, y, color);
            y += 10;
        }
        return y;
    }

    private int section(Gfx g, int x, int y, int sw, String title) {
        g.text(title, x, y, FAINT);
        g.fill(x + g.width(title) + 6, y + 4, sw - g.width(title) - 6, 1, LINE);
        return y + 14;
    }

    private int toggleRow(Gfx g, int lx, int cx, int cw, int y, String label, boolean on, Runnable toggle) {
        g.text(label, lx, y + 5, TEXT);
        int sx = cx;
        boolean hover = in(sx, y + 2, 30, 14);
        g.panel(sx, y + 2, 30, 14, on ? (hover ? GREEN_HI : GREEN) : (hover ? 0xFF6D727A : 0xFF4E5058));
        g.panel(on ? sx + 18 : sx + 2, y + 4, 10, 10, 0xFFFFFFFF);
        hits.add(new Hit(sx, y + 2, 30, 14, toggle, null));
        return y + 22;
    }

    private void segment(Gfx g, int x, int y, int sw, String label, boolean sel, Runnable action) {
        boolean hover = in(x, y, sw, 18);
        g.panel(x, y, sw, 18, sel ? SELECTED : hover ? HOVER : RAISED);
        g.centered(g.clip(label, sw - 6), x + sw / 2, y + 5, sel ? 0xFFFFFFFF : MUTED);
        hits.add(new Hit(x, y, sw, 18, action, null));
    }

    private int sliderRow(Gfx g, int lx, int cx, int cw, int y, String label, double value, String shown, DoubleConsumer set) {
        g.text(label, lx, y + 5, TEXT);
        int trackW = cw - 46;
        value = Math.max(0, Math.min(1, value));
        g.panel(cx, y + 7, trackW, 4, 0xFF4E5058);
        g.panel(cx, y + 7, (int) (trackW * value), 4, GREEN);
        int kx = cx + (int) (trackW * value) - 4;
        g.panel(kx, y + 3, 9, 12, 0xFFFFFFFF);
        g.text(shown, cx + trackW + 8, y + 5, MUTED);
        DoubleConsumer setter = v -> set.accept(Math.max(0, Math.min(1, v)));
        hits.add(new Hit(cx - 4, y, trackW + 8, 18, () -> {
            dragging = setter;
            dragX = cx;
            dragW = trackW;
            setter.accept((mouseX - cx) / (double) trackW);
        }, null));
        return y + 22;
    }

    private void cycleRingtone(List<String> options, int dir) {
        int idx = Math.max(0, options.indexOf(VCraft.config().ringtone));
        idx = (idx + dir + options.size()) % options.size();
        VCraft.config().ringtone = options.get(idx);
        VCraft.config().save();
        if (previewing) {
            VCraft.platform().stopRingtone();
            if (!"none".equals(options.get(idx))) VCraft.platform().startRingtone(options.get(idx));
            else previewing = false;
        }
    }

    private void togglePreview() {
        if (previewing || "none".equals(VCraft.config().ringtone)) {
            VCraft.platform().stopRingtone();
            previewing = false;
        } else {
            VCraft.platform().startRingtone(VCraft.config().ringtone);
            previewing = true;
        }
    }

    static String discName(String id) {
        if ("none".equals(id)) return "None (silent)";
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = id.substring(colon + 1).replace("music_disc.", "");
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("[_.]")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String name = "Music Disc - " + sb;
        return "minecraft".equals(ns) ? name : name + " (" + ns + ")";
    }

    private void saveRelay() {
        VCraft.config().serverUrl = relayUrl.getValue().trim();
        VCraft.config().save();
        VCraft.relay().reconnect();
        State.notice("Reconnecting to " + VCraft.config().serverUrl, false);
    }


    private void drawDisconnected(Gfx g, int x, int y, int pw, int ph) {
        RelayConnection.Status st = VCraft.relay().status();
        int cx = x + pw / 2;
        int cy = y + ph / 2 - 40;
        g.panel(cx - 22, cy - 30, 44, 44, SIDEBAR);
        Icons.draw(g, Icons.HEADPHONES, cx - 4, cy - 12, st == RelayConnection.Status.DISCONNECTED ? RED : GOLD);
        String title = switch (st) {
            case CONNECTING -> "Connecting to VCraft...";
            case AUTHENTICATING -> "Signing in with your Minecraft account...";
            default -> "You're not connected";
        };
        g.centered(title, cx, cy + 24, 0xFFFFFFFF);
        if (st == RelayConnection.Status.DISCONNECTED) {
            String err = VCraft.relay().lastError();
            if (err != null) g.centered(g.clip(err, pw - 40), cx, cy + 38, MUTED);
            g.centered(g.clip("Relay: " + VCraft.config().serverUrl, pw - 40), cx, cy + 50, FAINT);
            button(g, cx - 84, cy + 66, 80, 20, "Retry", GREEN, GREEN_HI, 0xFFFFFFFF, () -> VCraft.relay().reconnect());
            button(g, cx + 4, cy + 66, 80, 20, "Settings", RAISED, HOVER, TEXT, () -> go(Page.SETTINGS));
        }
    }

    private void drawNotice(Gfx g, int mainX, int mainW) {
        if (State.notice == null || System.currentTimeMillis() - State.noticeTime > 5000) return;
        String text = g.clip(State.notice, mainW - 60);
        int nw = g.width(text) + 20;
        int nx = mainX + (mainW - nw) / 2;
        int ny = y0 + 40;
        g.panel(nx, ny, nw, 18, State.noticeIsError ? 0xF0B53A3D : 0xF0248046);
        g.text(text, nx + 10, ny + 5, 0xFFFFFFFF);
    }

    private void openModal(Modal m, String subject) {
        modal = m;
        modalSubject = subject;
        menu = null;
        if (m == Modal.RENAME_GROUP) {
            State.Group gr = State.group(subject);
            renameBox.setValue(gr == null ? "" : gr.name());
            setFocused(renameBox);
        }
    }

    private void drawModal(Gfx g) {
        g.fill(0, 0, width, height, 0xA0000000);
        int mw = Math.min(320, width - 40);
        int mh = switch (modal) {
            case SAFETY -> 128;
            case JOIN_SERVER, DELETE_ACCOUNT -> 112;
            default -> 96;
        };
        int mx = (width - mw) / 2;
        int my = (height - mh) / 2;
        g.panel(mx, my, mw, mh, MAIN);
        if (modal == Modal.JOIN_SERVER && pendingJoin != null) {
            State.ServerInvite inv = pendingJoin;
            g.text("Join " + g.clip(inv.name(), mw - 70) + "?", mx + 16, my + 14, 0xFFFFFFFF);
            para(g, (modalSubject == null ? "" : modalSubject + " invited you to this server. ")
                    + "Only join servers you trust: the server will see your IP address.", mx + 16, my + 30, mw - 32, MUTED);
            g.panel(mx + 16, my + 58, mw - 32, 18, BG);
            g.text(g.clip(inv.address(), mw - 48), mx + 22, my + 63, 0xFFFFFFFF);
            button(g, mx + mw - 86, my + mh - 30, 70, 20, "Join", GREEN, GREEN_HI, 0xFFFFFFFF, () -> {
                modal = null;
                Mc.setScreen(null);
                VCraft.platform().connectToServer(inv.name(), inv.address());
            });
            button(g, mx + mw - 160, my + mh - 30, 66, 20, "Cancel", 0, HOVER, TEXT, () -> modal = null);
        } else if (modal == Modal.DELETE_ACCOUNT) {
            g.text("Delete your VCraft account?", mx + 16, my + 14, 0xFFFFFFFF);
            para(g, "This permanently removes your friends, groups and message history from the relay. Your friends will no longer see you. This can't be undone.", mx + 16, my + 30, mw - 32, MUTED);
            button(g, mx + mw - 126, my + mh - 30, 110, 20, "Delete account", RED, RED_HI, 0xFFFFFFFF, () -> {
                modal = null;
                State.deleteAccount();
            });
            button(g, mx + mw - 200, my + mh - 30, 66, 20, "Cancel", 0, HOVER, TEXT, () -> modal = null);
        } else if (modal == Modal.RENAME_GROUP) {
            g.text("Rename group", mx + 16, my + 14, 0xFFFFFFFF);
            g.panel(mx + 16, my + 32, mw - 32, 22, BG);
            placeBox(renameBox, mx + 24, my + 39, mw - 48);
            button(g, mx + mw - 86, my + mh - 30, 70, 20, "Save", GREEN, GREEN_HI, 0xFFFFFFFF, this::saveRename);
            button(g, mx + mw - 160, my + mh - 30, 66, 20, "Cancel", 0, HOVER, TEXT, () -> modal = null);
        } else {
            String name = State.convName(modalSubject);
            Secure.Trust trust = Secure.trust(modalSubject);
            String number = Secure.safetyNumber(modalSubject);
            g.text("Verify " + name, mx + 16, my + 14, 0xFFFFFFFF);
            g.text("Compare this number with " + name + " over voice.", mx + 16, my + 30, MUTED);
            g.text("If it matches on both screens, nobody can listen in.", mx + 16, my + 41, MUTED);
            g.panel(mx + 16, my + 56, mw - 32, 24, BG);
            g.centered(number == null ? "No key yet" : number, mx + mw / 2, my + 64, trust == Secure.Trust.CHANGED ? GOLD : 0xFFFFFFFF);
            if (trust == Secure.Trust.CHANGED) {
                g.text("Their key changed since you last talked.", mx + 16, my + 86, RED);
                button(g, mx + mw - 126, my + mh - 30, 110, 20, "Trust new key", GREEN, GREEN_HI, 0xFFFFFFFF, () -> {
                    State.trustNewKey(modalSubject);
                    State.notice("Now trusting " + name + "'s new key.", false);
                    modal = null;
                });
            }
            button(g, mx + 16, my + mh - 30, 66, 20, "Close", RAISED, HOVER, TEXT, () -> modal = null);
        }
    }

    private void saveRename() {
        State.Group gr = State.group(modalSubject);
        String name = renameBox.getValue().trim();
        if (gr != null && !name.isEmpty()) State.groupAction("group_rename", gr.id(), "name", name);
        modal = null;
    }


    private void openMenu(int x, int y, List<MenuItem> items) {
        menu = items;
        menuX = x;
        menuY = y;
    }

    private void openStatusMenu(int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        for (String s : State.STATUSES) {
            items.add(new MenuItem(State.statusLabel(s).replace(" (invisible)", ""), State.statusColor(s), () -> State.setStatus(s)));
        }
        openMenu(x, y, items);
    }

    private void openConvMenu(String convId, int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        State.Group gr = State.group(convId);
        if (gr != null) {
            items.add(new MenuItem("Open chat", TEXT, () -> openChat(convId)));
            items.add(new MenuItem("Call group", TEXT, () -> State.callGroup(gr)));
            items.add(new MenuItem("Invite to this server", TEXT, () -> State.inviteToServer(convId)));
            items.add(new MenuItem("Add people", TEXT, () -> openAddToGroupMenu(gr, x, y)));
            items.add(new MenuItem("Rename group", TEXT, () -> openModal(Modal.RENAME_GROUP, convId)));
            items.add(new MenuItem("Leave group", RED, () -> State.groupAction("group_leave", gr.id(), null, null)));
        } else {
            State.Profile f = State.friend(convId);
            if (f == null) return;
            boolean muted = VCraft.config().mutedUsers.contains(convId);
            items.add(new MenuItem("Message", TEXT, () -> openChat(convId)));
            items.add(new MenuItem("Call", TEXT, () -> State.call(convId)));
            items.add(new MenuItem("Invite to this server", TEXT, () -> State.inviteToServer(convId)));
            items.add(new MenuItem("Verify encryption", TEXT, () -> openModal(Modal.SAFETY, convId)));
            items.add(new MenuItem(muted ? "Unmute" : "Mute", TEXT, () -> State.toggleUserMute(convId)));
            items.add(new MenuItem("Remove friend", RED, () -> State.send("friend_remove", "uuid", convId)));
            items.add(new MenuItem("Block", RED, () -> State.send("block", "uuid", convId)));
        }
        openMenu(x, y, items);
    }

    private void openMemberMenu(State.Group gr, State.Profile p, int x, int y) {
        if (p.uuid().equals(State.myUuid)) return;
        List<MenuItem> items = new ArrayList<>();
        boolean friend = State.friend(p.uuid()) != null;
        if (friend) items.add(new MenuItem("Message", TEXT, () -> openChat(p.uuid())));
        else items.add(new MenuItem("Add friend", TEXT, () -> State.send("friend_request", "name", p.name())));
        if (p.online()) items.add(new MenuItem("Call", TEXT, () -> State.call(p.uuid())));
        boolean muted = VCraft.config().mutedUsers.contains(p.uuid());
        items.add(new MenuItem(muted ? "Unmute" : "Mute", TEXT, () -> State.toggleUserMute(p.uuid())));
        if (State.myUuid != null && State.myUuid.equals(gr.owner())) {
            items.add(new MenuItem("Remove from group", RED, () -> State.groupAction("group_kick", gr.id(), "uuid", p.uuid())));
        }
        items.add(new MenuItem("Block", RED, () -> State.send("block", "uuid", p.uuid())));
        openMenu(x, y, items);
    }

    private void openAddToGroupMenu(State.Group gr, int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        for (State.Profile f : State.friends) {
            if (gr.members().stream().anyMatch(m -> m.uuid().equals(f.uuid()))) continue;
            items.add(new MenuItem("Add " + f.name(), TEXT, () -> State.groupAction("group_add", gr.id(), "uuid", f.uuid())));
        }
        if (items.isEmpty()) items.add(new MenuItem("All your friends are already here", FAINT, null));
        openMenu(x, y, items);
    }


    private void header(Gfx g, int x, int y, int hw) {
        g.fill(x, y + 33, hw, 1, LINE);
    }

    private void button(Gfx g, int x, int y, int bw, int bh, String label, int color, int hoverColor, int textColor, Runnable action) {
        boolean hover = in(x, y, bw, bh);
        int c = hover ? hoverColor : color;
        if (c != 0) g.panel(x, y, bw, bh, c);
        g.centered(g.clip(label, bw - 6), x + bw / 2, y + (bh - 8) / 2, textColor);
        hits.add(new Hit(x, y, bw, bh, action, null));
    }

    private void iconButton(Gfx g, int x, int y, int bw, int bh, String[] icon, int color, int hoverColor, int iconColor, Runnable action) {
        boolean hover = in(x, y, bw, bh);
        int c = hover ? hoverColor : color;
        if (c != 0) g.panel(x, y, bw, bh, c);
        Icons.draw(g, icon, x + (bw - 9) / 2, y + (bh - 9) / 2, hover && color == 0 ? 0xFFFFFFFF : iconColor);
        hits.add(new Hit(x, y, bw, bh, action, null));
    }

    private void toggleIcon(Gfx g, int x, int y, String[] icon, boolean off, Runnable action) {
        boolean hover = in(x, y, 20, 22);
        if (hover) g.panel(x, y, 20, 22, HOVER);
        if (off) Icons.drawStruck(g, icon, x + 6, y + 7, RED);
        else Icons.draw(g, icon, x + 6, y + 7, hover ? 0xFFFFFFFF : MUTED);
        hits.add(new Hit(x, y, 20, 22, action, null));
    }

    private void bigToggle(Gfx g, int x, int y, String[] icon, boolean off, Runnable action) {
        boolean hover = in(x, y, 40, 32);
        g.panel(x, y, 40, 32, off ? (hover ? 0xFFF5F5F5 : 0xFFDBDEE1) : (hover ? HOVER : RAISED));
        if (off) Icons.drawStruck(g, icon, x + 16, y + 12, 0xFF1E1F22);
        else Icons.draw(g, icon, x + 16, y + 12, 0xFFFFFFFF);
        hits.add(new Hit(x, y, 40, 32, action, null));
    }

    /** Draws a rank pill (Owner / Mod) and returns the width it used, or 0 for no rank. */
    private int rankBadge(Gfx g, int x, int y, String rank) {
        if (rank == null) return 0;
        String label = rankLabel(rank);
        int bw = g.width(label) + 6;
        int color = switch (rank) {
            case "owner" -> GOLD;
            case "mod" -> 0xFF5865F2;
            case "tester" -> 0xFF1ABC9C;
            default -> 0xFF6D727A;
        };
        g.panel(x, y, bw, 10, color);
        g.text(label, x + 3, y + 1, "owner".equals(rank) ? 0xFF2B2D31 : 0xFFFFFFFF);
        return bw + 4;
    }

    private static String rankLabel(String rank) {
        return switch (rank) {
            case "owner" -> "Owner";
            case "mod" -> "Mod";
            case "tester" -> "Tester";
            default -> rank.isEmpty() ? rank : Character.toUpperCase(rank.charAt(0)) + rank.substring(1);
        };
    }

    private void openLobbyUserMenu(String uuid, String name, int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(name, FAINT, null));
        if (State.friend(uuid) != null) items.add(new MenuItem("Message", TEXT, () -> openChat(uuid)));
        else items.add(new MenuItem("Add friend", TEXT, () -> State.send("friend_request", "name", name)));
        if (State.isStaff() && !"owner".equals(State.rank(uuid))) {
            items.add(new MenuItem("Mute in community", GOLD, () -> State.lobbyMute(uuid, true)));
            items.add(new MenuItem("Unmute in community", TEXT, () -> State.lobbyMute(uuid, false)));
        }
        items.add(new MenuItem("Block", RED, () -> State.send("block", "uuid", uuid)));
        openMenu(x, y, items);
    }

    private void badge(Gfx g, int rightX, int y, int count) {
        String s = count > 99 ? "99+" : String.valueOf(count);
        int bw = Math.max(12, g.width(s) + 6);
        g.panel(rightX - bw, y, bw, 11, RED);
        g.centered(s, rightX - bw / 2, y + 2, 0xFFFFFFFF);
    }

    private void avatar(Gfx g, int x, int y, int size, String id, String name, String status) {
        g.face(Skins.get(id), x, y, size);
        if (status != null) {
            int d = size >= 20 ? 8 : 6;
            int dx = x + size - d + 2;
            int dy = y + size - d + 2;
            g.fill(dx - 1, dy - 1, d + 2, d + 2, SIDEBAR);
            g.fill(dx, dy, d, d, State.statusColor(status));
        }
    }

    private void groupAvatar(Gfx g, int x, int y, int size, String name) {
        int color = AVATAR_COLORS[Math.floorMod(name.hashCode() * 31, AVATAR_COLORS.length)];
        g.panel(x, y, size, size, color);
        Icons.draw(g, Icons.PEOPLE, x + (size - 9) / 2, y + (size - 9) / 2, 0xFFFFFFFF);
    }

    private void placeBox(EditBox b, int x, int y, int bw) {
        b.visible = true;
        b.setX(x);
        b.setY(y);
        b.setWidth(Math.max(10, bw));
    }

    private void setPageArea(int x, int y, int pw, int ph) {
        pageX = x;
        pageY = y;
        pageW = pw;
        pageH = Math.max(10, ph);
        pageScroll = Math.max(0, Math.min(pageScroll, pageMaxScroll));
    }

    private boolean in(int x, int y, int bw, int bh) {
        return mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + bh;
    }

    private static boolean inside(double mx, double my, int x, int y, int bw, int bh) {
        return mx >= x && mx < x + bw && my >= y && my < y + bh;
    }

    private static List<State.Invite> liveInvites() {
        long now = System.currentTimeMillis();
        List<State.Invite> out = new ArrayList<>();
        for (State.Invite i : State.invites.values()) if (i.expires() > now) out.add(i);
        return out;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ROOT);

    private static String time(long ts) {
        ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        if (t.toLocalDate().equals(today)) return "Today at " + TIME.format(t);
        if (t.toLocalDate().equals(today.minusDays(1))) return "Yesterday at " + TIME.format(t);
        return DATE.format(t);
    }


    private void go(Page p) {
        page = p;
        pageScroll = 0;
        menu = null;
        if (p != Page.CHAT) State.viewing = null;
    }

    private void openChat(String convId) {
        if (!convId.equals(conv)) {
            replyTo = null;
            chatScroll = 0;
        }
        conv = convId;
        go(Page.CHAT);
        State.openConversation(convId);
    }


    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        boolean right = event.button() == Keys.MOUSE_RIGHT;
        if (menu != null) {
            for (int i = overlayHits.size() - 1; i >= 0; i--) {
                Hit hit = overlayHits.get(i);
                if (inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
                    menu = null;
                    click();
                    hit.left().run();
                    return true;
                }
            }
            menu = null;
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (!inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) continue;
            Runnable action = right && hit.right() != null ? hit.right() : hit.left();
            if (action == null) continue;
            if (dragging == null) click();
            action.run();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.accept((event.x() - dragX) / dragW);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            VCraft.config().save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        int step = (int) Math.round(vertical * 24);
        if (menu != null) return true;
        if (inside(mx, my, sbX, sbY, sbW, sbH)) {
            sidebarScroll = Math.max(0, Math.min(sidebarMaxScroll, sidebarScroll - step));
        } else if (page == Page.CHAT && inside(mx, my, chatX, chatY, chatW, chatH)) {
            chatScroll = Math.max(0, Math.min(chatMaxScroll, chatScroll + step));
        } else {
            pageScroll = Math.max(0, Math.min(pageMaxScroll, pageScroll - step));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == Keys.ESCAPE) {
            if (menu != null) {
                menu = null;
                return true;
            }
            if (modal != null) {
                modal = null;
                return true;
            }
            if (replyTo != null && page == Page.CHAT) {
                replyTo = null;
                return true;
            }
        }
        if (key == Keys.ENTER || key == Keys.KP_ENTER) {
            if (composer.isFocused() && composer.visible) {
                sendComposer();
                return true;
            }
            if (addFriend.isFocused() && addFriend.visible) {
                sendFriendRequest();
                return true;
            }
            if (relayUrl.isFocused() && relayUrl.visible) {
                saveRelay();
                return true;
            }
            if (groupName.isFocused() && groupName.visible) {
                createGroup();
                return true;
            }
            if (renameBox.isFocused() && renameBox.visible) {
                saveRename();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (page == Page.CHAT && modal == null && composer.visible && !(getFocused() instanceof EditBox)) setFocused(composer);
        return super.charTyped(event);
    }

    private static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    public void removed() {
        State.viewing = null;
        if (previewing) VCraft.platform().stopRingtone();
        if (VCraft.voice().micTest()) VCraft.voice().setMicTest(false);
    }

    @Override
    public void onClose() {
        Mc.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
