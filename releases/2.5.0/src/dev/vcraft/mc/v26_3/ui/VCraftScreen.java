package dev.vcraft.mc.v26_3.ui;

import dev.vcraft.api.Canvas;
import dev.vcraft.api.VCraftApi;
import dev.vcraft.core.AddonHost;
import dev.vcraft.core.AutoUpdate;
import dev.vcraft.core.Config;
import dev.vcraft.core.DebugLog;
import dev.vcraft.core.I18n;
import dev.vcraft.core.Social;
import dev.vcraft.core.VoiceFx;
import dev.vcraft.core.NowPlaying;
import dev.vcraft.core.RelayConnection;
import dev.vcraft.core.Secure;
import dev.vcraft.core.State;
import dev.vcraft.core.VCraft;
import dev.vcraft.core.VoiceEngine;
import dev.vcraft.mc.v26_3.Gfx;
import dev.vcraft.mc.v26_3.Mc;
import dev.vcraft.mc.v26_3.Skins;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

public final class VCraftScreen extends Screen {
   private static final int BG = -14803166;
   private static final int SIDEBAR = -13947599;
   private static final int MAIN = -13552840;
   private static final int RAISED = -13092288;
   private static final int HOVER = -12565943;
   private static final int SELECTED = -11907755;
   private static final int LINE = -14276564;
   private static final int TEXT = -2367775;
   private static final int MUTED = -7038044;
   private static final int FAINT = -9604486;
   private static final int GREEN = -12868260;
   private static final int GREEN_HI = -12205973;
   private static final int RED = -1228219;
   private static final int RED_HI = -893086;
   private static final int GOLD = -1002958;
   private static final int[] AVATAR_COLORS = new int[]{-10983950, -12868260, -1228219, -1002958, -1358434, -15024996, -1671646, -6596170};
   private static VCraftScreen.Page page = VCraftScreen.Page.FRIENDS;
   private static String conv;
   private static String addonPage;
   private static final Map<String, String> addonInputs = new HashMap<>();
   private final Map<String, EditBox> addonBoxes = new HashMap<>();
   private static int friendsTab;
   private static boolean showMembers = true;
   private static boolean showPins;
   private final Screen parent;
   private final List<VCraftScreen.Hit> hits = new ArrayList<>();
   private final List<VCraftScreen.Hit> overlayHits = new ArrayList<>();
   private List<VCraftScreen.MenuItem> menu;
   private int menuX;
   private int menuY;
   private VCraftScreen.Modal modal;
   private String modalSubject;
   private State.ServerInvite pendingJoin;
   private int mouseX;
   private int mouseY;
   private int sidebarScroll;
   private int chatScroll;
   private int pageScroll;
   private int sidebarMaxScroll;
   private int chatMaxScroll;
   private int pageMaxScroll;
   private int sbX;
   private int sbY;
   private int sbW;
   private int sbH;
   private int chatX;
   private int chatY;
   private int chatW;
   private int chatH;
   private int pageX;
   private int pageY;
   private int pageW;
   private int pageH;
   private EditBox composer;
   private EditBox addFriend;
   private EditBox relayUrl;
   private EditBox groupName;
   private EditBox renameBox;
   private EditBox groupCode;
   private EditBox nickBox;
   private EditBox noteBox;
   private String tooltip;
   private static String composerText = "";
   private static String addFriendText = "";
   private static String groupNameText = "";
   private static State.Reply replyTo;
   private final Set<String> newGroupMembers = new LinkedHashSet<>();
   private DoubleConsumer dragging;
   private int dragX;
   private int dragW;
   private List<String> discs = List.of();
   private boolean previewing;
   private int x0;
   private int y0;
   private int w;
   private int h;
   private static final String KOFI_URL = "https://ko-fi.com/annestinksbad";
   private static final int KOFI = -41381;
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);
   private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ROOT);

   public VCraftScreen(Screen parent) {
      super(Component.literal("VCraft"));
      this.parent = parent;
   }

   protected void init() {
      this.composer = this.box(500, I18n.t("Message"), composerText, s -> composerText = s);
      this.addFriend = this.box(16, I18n.t("Enter a Minecraft username"), addFriendText, s -> addFriendText = s);
      this.relayUrl = this.box(256, "wss://relay.example.com", VCraft.config().serverUrl, null);
      this.groupName = this.box(32, "Group name", groupNameText, s -> groupNameText = s);
      this.renameBox = this.box(32, "New name", "", null);
      this.groupCode = this.box(400, I18n.t("Paste a group invite code (vc1...)"), "", null);
      this.nickBox = this.box(24, "", "", null);
      this.noteBox = this.box(160, I18n.t("Anything you want to remember"), "", null);
      this.discs = VCraft.platform().discSounds();
      this.addonBoxes.clear();
      if (page == VCraftScreen.Page.CHAT && conv != null) {
         State.openConversation(conv);
      }
   }

   public static void openAddonPage(String pageId, String arg) {
      VCraftApi.Page p = findAddonPage(pageId);
      if (p != null) {
         addonPage = pageId;
         page = VCraftScreen.Page.ADDON;

         try {
            p.opened(arg);
         } catch (Throwable var4) {
            VCraft.LOGGER.warn("VCraft addon page {} failed to open", pageId, var4);
         }

         if (!(Mc.screen() instanceof VCraftScreen)) {
            Mc.setScreen(new VCraftScreen(Mc.screen()));
         }
      }
   }

   public static void confirmJoinServer(String name, String address) {
      VCraftScreen s = Mc.screen() instanceof VCraftScreen v ? v : new VCraftScreen(Mc.screen());
      if (s != Mc.screen()) {
         Mc.setScreen(s);
      }

      s.pendingJoin = new State.ServerInvite(name == null ? address : name, address);
      s.openModal(VCraftScreen.Modal.JOIN_SERVER, null);
   }

   private static VCraftApi.Page findAddonPage(String id) {
      for (VCraftApi.Page p : AddonHost.INSTANCE.pages) {
         if (p.id().equals(id)) {
            return p;
         }
      }

      return null;
   }

   private void goAddon(VCraftApi.Page p) {
      openAddonPage(p.id(), null);
      this.go(VCraftScreen.Page.ADDON);
   }

   private void drawAddon(Gfx g, int x, int y, int pw, int ph) {
      VCraftApi.Page p = findAddonPage(addonPage);
      if (p == null) {
         this.go(VCraftScreen.Page.FRIENDS);
      } else {
         this.header(g, x, y, pw);
         Icons.draw(g, p.icon(), x + 12, y + 12, -7038044);
         g.text(g.clip(p.title(), pw - 40), x + 28, y + 13, -1);
         this.setPageArea(x, y + 34, pw, ph - 36);
         g.scissor(this.pageX, this.pageY, this.pageW, this.pageH);
         VCraftScreen.ScreenCanvas canvas = new VCraftScreen.ScreenCanvas(g);

         try {
            p.render(canvas, this.pageX + 16, this.pageY + 10 - this.pageScroll, this.pageW - 32, this.pageH - 10);
         } catch (Throwable var9) {
            g.text("This addon page crashed: " + var9, this.pageX + 16, this.pageY + 10, -1228219);
            VCraft.LOGGER.warn("VCraft addon page {} failed to draw", p.id(), var9);
         }

         g.endScissor();
         this.pageMaxScroll = Math.max(0, canvas.content + 20 - this.pageH);
      }
   }

   private EditBox box(int max, String hint, String value, Consumer<String> responder) {
      EditBox b = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 12, Component.literal(hint));
      b.setBordered(false);
      b.setMaxLength(max);
      b.setTextColor(-2367775);
      b.setTextShadow(false);
      b.setHint(Component.literal(hint).withColor(7172730));
      b.setValue(value == null ? "" : value);
      if (responder != null) {
         b.setResponder(responder);
      }

      b.visible = false;
      this.addRenderableWidget(b);
      return b;
   }

   public void extractBackground(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
      this.drawBase(new Gfx(graphics), mx, my);
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
      super.extractRenderState(graphics, mx, my, delta);
      this.drawOverlay(new Gfx(graphics));
   }

   private void drawBase(Gfx g, int mx, int my) {
      this.mouseX = mx;
      this.mouseY = my;
      this.hits.clear();
      this.tooltip = null;
      this.hide(this.groupCode);
      this.hide(this.nickBox);
      this.hide(this.noteBox);

      for (EditBox b : List.of(this.composer, this.addFriend, this.relayUrl, this.groupName, this.renameBox)) {
         b.visible = false;
      }

      for (EditBox b : this.addonBoxes.values()) {
         b.visible = false;
      }

      if (State.focusRequest != null) {
         this.openChat(State.focusRequest);
         State.focusRequest = null;
      }

      if (page == VCraftScreen.Page.CHAT && !State.conversationExists(conv) && VCraft.relay().connected()) {
         page = VCraftScreen.Page.FRIENDS;
      }

      if (page == VCraftScreen.Page.CALL && State.call == null) {
         page = VCraftScreen.Page.FRIENDS;
      }

      g.fill(0, 0, this.width, this.height, Minecraft.getInstance().level != null ? -804253421 : -14803166);
      this.w = Math.min(this.width - 16, 720);
      this.h = this.height - 16;
      this.x0 = (this.width - this.w) / 2;
      this.y0 = 8;
      g.panel(this.x0, this.y0, this.w, this.h, -13552840);
      int sideW = Math.min(200, Math.max(150, this.w / 3));
      this.drawSidebar(g, this.x0, this.y0, sideW, this.h);
      int mainX = this.x0 + sideW;
      int mainW = this.w - sideW;
      if (!VCraft.relay().connected() && page != VCraftScreen.Page.SETTINGS) {
         this.drawDisconnected(g, mainX, this.y0, mainW, this.h);
      } else {
         switch (page) {
            case FRIENDS:
               this.drawFriends(g, mainX, this.y0, mainW, this.h);
               break;
            case CHAT:
               this.drawChat(g, mainX, this.y0, mainW, this.h);
               break;
            case NEW_GROUP:
               this.drawNewGroup(g, mainX, this.y0, mainW, this.h);
               break;
            case CALL:
               this.drawCall(g, mainX, this.y0, mainW, this.h);
               break;
            case SETTINGS:
               this.drawSettings(g, mainX, this.y0, mainW, this.h);
               break;
            case ADDON:
               this.drawAddon(g, mainX, this.y0, mainW, this.h);
         }
      }

      this.drawNotice(g, mainX, mainW);
      if (this.modal != null) {
         this.hits.clear();
         this.drawModal(g);
      }

      if (this.getFocused() instanceof EditBox b && !b.visible) {
         this.setFocused(null);
      }
   }

   private void drawOverlay(Gfx g) {
      this.overlayHits.clear();
      if (this.tooltip != null && this.menu == null && this.modal == null) {
         String tip = g.clip(this.tooltip, Math.min(300, this.width - 16));
         int tw = g.width(tip) + 10;
         int tx = Math.max(4, Math.min(this.mouseX - tw / 2, this.width - tw - 4));
         int ty = Math.max(4, this.mouseY - 22);
         g.panel(tx, ty, tw, 16, -15658476);
         g.text(tip, tx + 5, ty + 4, -1);
      }

      if (this.menu != null) {
         int mw = 40;

         for (VCraftScreen.MenuItem item : this.menu) {
            mw = Math.max(mw, g.width(item.label()) + 20);
         }

         int mh = this.menu.size() * 16 + 6;
         int mx = Math.min(this.menuX, this.width - mw - 4);
         int my = Math.min(this.menuY, this.height - mh - 4);
         g.panel(mx - 1, my - 1, mw + 2, mh + 2, -15658476);
         g.panel(mx, my, mw, mh, -14474200);
         int y = my + 3;

         for (VCraftScreen.MenuItem item : this.menu) {
            boolean hover = this.in(mx + 3, y, mw - 6, 16);
            if (item.action() != null && hover) {
               g.panel(mx + 3, y, mw - 6, 16, item.color() == -1228219 ? -1228219 : -10983950);
            }

            int color = item.action() == null ? -9604486 : (hover ? -1 : item.color());
            g.text(item.label(), mx + 9, y + 4, color);
            if (item.action() != null) {
               this.overlayHits.add(new VCraftScreen.Hit(mx + 3, y, mw - 6, 16, item.action(), null));
            }

            y += 16;
         }
      }
   }

   private void drawSidebar(Gfx g, int x, int y, int sw, int sh) {
      g.fill(x, y + 1, sw, sh - 2, -13947599);
      g.fill(x + 1, y, sw - 1, 1, -13947599);
      Icons.drawLogo(g, x + 8, y + 7);
      g.text("VCraft", x + 30, y + 11, -1);
      RelayConnection.Status st = VCraft.relay().status();
      int dot = st == RelayConnection.Status.CONNECTED ? -12868260 : (st == RelayConnection.Status.DISCONNECTED ? -1228219 : -1002958);
      g.fill(x + sw - 14, y + 13, 5, 5, dot);
      g.fill(x, y + 30, sw, 1, -14276564);
      int bottom = y + sh - 40;
      int callH = State.call != null ? 46 : 0;
      List<State.Invite> live = liveInvites();
      Social.Request request = Social.latestRequest();
      int inviteH = live.isEmpty() && request == null ? 0 : 40;
      int listTop = y + 34;
      int listBottom = bottom - callH - inviteH;
      this.sbX = x;
      this.sbY = listTop;
      this.sbW = sw;
      this.sbH = listBottom - listTop;
      g.scissor(x, listTop, sw, this.sbH);
      int ly = listTop - this.sidebarScroll;
      int pending = State.incoming.size();
      ly = this.navItem(g, x, ly, sw, Icons.PEOPLE, I18n.t("Friends"), page == VCraftScreen.Page.FRIENDS, pending, () -> this.go(VCraftScreen.Page.FRIENDS));
      ly = this.navItem(
         g,
         x,
         ly,
         sw,
         Icons.GLOBE,
         I18n.t("VCraft Community"),
         page == VCraftScreen.Page.CHAT && "lobby".equals(conv),
         State.unread.getOrDefault("lobby", 0),
         () -> this.openChat("lobby")
      );

      for (VCraftApi.Page p : AddonHost.INSTANCE.pages) {
         if (p.inSidebar()) {
            int badge;
            try {
               badge = p.badge();
            } catch (Throwable var21) {
               badge = 0;
            }

            ly = this.navItem(
               g, x, ly, sw, p.icon(), g.clip(p.title(), sw - 50), page == VCraftScreen.Page.ADDON && p.id().equals(addonPage), badge, () -> this.goAddon(p)
            );
         }
      }

      ly += 6;
      ly = this.sectionHeader(g, x, ly, sw, I18n.t("DIRECT MESSAGES"), null);

      for (State.Profile f : State.friends) {
         ly = this.convRow(g, x, ly, sw, f.uuid(), Social.displayName(f.uuid(), f.name()), f.status(), null, f.uuid());
      }

      if (State.friends.isEmpty() && VCraft.relay().connected()) {
         g.text(I18n.t("No friends yet"), x + 12, ly + 4, -9604486);
         ly += 16;
      }

      ly += 6;
      ly = this.sectionHeader(g, x, ly, sw, I18n.t("GROUPS"), () -> {
         this.newGroupMembers.clear();
         this.go(VCraftScreen.Page.NEW_GROUP);
      });

      for (State.Group gr : State.groups) {
         long on = gr.members().stream().filter(State.Profile::online).count();
         String sub = Social.groupMuted(gr.conv()) ? I18n.t("Muted") : I18n.tf("{0} of {1} online", String.valueOf(on), String.valueOf(gr.members().size()));
         ly = this.convRow(g, x, ly, sw, gr.conv(), gr.name(), null, sub, gr.id());
      }

      if (State.groups.isEmpty() && VCraft.relay().connected()) {
         g.text(I18n.t("No groups yet"), x + 12, ly + 4, -9604486);
         ly += 16;
      }

      g.endScissor();
      this.sidebarMaxScroll = Math.max(0, ly + this.sidebarScroll - listBottom + 6);
      this.sidebarScroll = Math.min(this.sidebarScroll, this.sidebarMaxScroll);
      if (!live.isEmpty()) {
         State.Invite inv = live.get(live.size() - 1);
         g.fill(x, listBottom, sw, inviteH, -14474200);
         g.fill(x, listBottom, 2, inviteH, -12868260);
         g.text(g.clip(inv.fromName(), sw - 70), x + 8, listBottom + 6, -1);
         g.text(I18n.t("is calling..."), x + 8, listBottom + 18, -7038044);
         this.iconButton(g, x + sw - 54, listBottom + 9, 22, 22, Icons.PHONE, -12868260, -12205973, -1, () -> State.acceptInvite(inv));
         this.iconButton(g, x + sw - 28, listBottom + 9, 22, 22, Icons.HANGUP, -1228219, -893086, -1, () -> State.declineInvite(inv));
      } else if (request != null) {
         g.fill(x, listBottom, sw, inviteH, -14474200);
         g.fill(x, listBottom, 2, inviteH, -1002958);
         g.text(g.clip(request.name(), sw - 70), x + 8, listBottom + 6, -1);
         State.Group rg = "group".equals(request.kind()) ? State.group("g:" + request.group()) : null;
         String what = rg == null ? I18n.t("wants to join your call") : I18n.tf("wants to join {0}", rg.name());
         g.text(g.clip(what, sw - 70), x + 8, listBottom + 18, -7038044);
         this.iconButton(g, x + sw - 54, listBottom + 9, 22, 22, Icons.CHECK, -12868260, -12205973, -1, () -> Social.accept(request));
         this.iconButton(g, x + sw - 28, listBottom + 9, 22, 22, Icons.CLOSE, -1228219, -893086, -1, () -> Social.dismiss(request));
      }

      if (State.call != null) {
         int cy = bottom - callH;
         g.fill(x, cy, sw, 1, -14276564);
         g.fill(x, cy + 1, sw, callH - 1, -14474200);
         boolean hoverCall = this.in(x, cy + 1, sw - 60, callH - 1);
         g.text(I18n.t("Voice Connected"), x + 8, cy + 9, hoverCall ? -12205973 : -12868260);
         State.Call call = State.call;
         String with = call.members().size() <= 1
            ? I18n.t("Waiting for others...")
            : (call.members().size() == 2 ? I18n.t("1 other in call") : I18n.tf("{0} others in call", String.valueOf(call.members().size() - 1)));
         g.text(g.clip(with, sw - 66), x + 8, cy + 21, -7038044);
         this.hits.add(new VCraftScreen.Hit(x, cy + 1, sw - 60, callH - 1, () -> this.go(VCraftScreen.Page.CALL), null));
         this.iconButton(g, x + sw - 54, cy + 12, 22, 22, Icons.REFRESH, -13092288, -12565943, -2367775, State::reconnectVoice);
         this.iconButton(g, x + sw - 28, cy + 12, 22, 22, Icons.HANGUP, -1228219, -893086, -1, () -> State.send("call_leave", null, null));
      }

      g.fill(x, bottom, sw, 40, -14474200);
      g.fill(x, sh + y - 1, sw, 1, -14474200);
      String name = State.myName != null ? State.myName : Minecraft.getInstance().getUser().getName();
      boolean hoverMe = this.in(x + 4, bottom + 4, sw - 72, 32);
      if (hoverMe) {
         g.panel(x + 4, bottom + 4, sw - 72, 32, -12565943);
      }

      this.avatar(
         g,
         x + 9,
         bottom + 10,
         20,
         State.myUuid == null ? String.valueOf(Minecraft.getInstance().getUser().getProfileId()) : State.myUuid,
         name,
         State.myUuid == null ? "offline" : State.myStatus
      );
      String shownName = g.clip(name, sw - 110);
      g.text(shownName, x + 35, bottom + 9, -1);
      if (35 + g.width(shownName) + 40 < sw - 66) {
         this.rankBadge(g, x + 39 + g.width(shownName), bottom + 8, State.myRank);
      }

      g.text(g.clip(VCraft.relay().connected() ? State.statusLabel(State.myStatus) : I18n.t("Not connected"), sw - 110), x + 35, bottom + 21, -7038044);
      this.hits.add(new VCraftScreen.Hit(x + 4, bottom + 4, sw - 72, 32, () -> this.openStatusMenu(x + 6, bottom - 74), null));
      int bx = x + sw - 66;
      this.toggleIcon(g, bx, bottom + 9, Icons.MIC, State.muted || State.deafened, State::toggleMute);
      this.toggleIcon(g, bx + 21, bottom + 9, Icons.HEADPHONES, State.deafened, State::toggleDeafen);
      this.iconButton(
         g,
         bx + 42,
         bottom + 9,
         20,
         22,
         Icons.GEAR,
         0,
         -12565943,
         page == VCraftScreen.Page.SETTINGS ? -1 : -7038044,
         () -> this.go(VCraftScreen.Page.SETTINGS)
      );
   }

   private int navItem(Gfx g, int x, int y, int sw, String[] icon, String label, boolean selected, int badge, Runnable action) {
      boolean hover = this.in(x + 6, y, sw - 12, 24);
      if (selected || hover) {
         g.panel(x + 6, y, sw - 12, 24, selected ? -11907755 : -12565943);
      }

      Icons.draw(g, icon, x + 13, y + 8, selected ? -1 : -7038044);
      g.text(label, x + 30, y + 8, !selected && !hover ? -7038044 : -1);
      if (badge > 0) {
         this.badge(g, x + sw - 14, y + 7, badge);
      }

      this.hits.add(new VCraftScreen.Hit(x + 6, y, sw - 12, 24, action, null));
      return y + 26;
   }

   private int sectionHeader(Gfx g, int x, int y, int sw, String label, Runnable plus) {
      g.text(label, x + 12, y + 4, -9604486);
      if (plus != null) {
         boolean hover = this.in(x + sw - 22, y, 14, 14);
         Icons.draw(g, Icons.PLUS, x + sw - 20, y + 3, hover ? -1 : -7038044);
         this.hits.add(new VCraftScreen.Hit(x + sw - 22, y, 14, 14, plus, null));
      }

      return y + 16;
   }

   private int convRow(Gfx g, int x, int y, int sw, String convId, String name, String status, String sub, String id) {
      boolean selected = page == VCraftScreen.Page.CHAT && convId.equals(conv);
      boolean hover = this.in(x + 6, y, sw - 12, 26);
      if (selected || hover) {
         g.panel(x + 6, y, sw - 12, 26, selected ? -11907755 : -12565943);
      }

      boolean group = convId.startsWith("g:");
      if (group) {
         this.groupAvatar(g, x + 11, y + 4, 18, name);
      } else {
         this.avatar(g, x + 11, y + 4, 18, id, name, status);
      }

      int unread = State.unread.getOrDefault(convId, 0);
      boolean dim = Social.groupMuted(convId) || !group && !"online".equals(status) && !"idle".equals(status) && !"dnd".equals(status);
      int nameColor = !selected && unread <= 0 ? (hover ? -2367775 : (dim ? -9604486 : -7038044)) : -1;
      int textW = sw - 46 - (unread > 0 ? 18 : 0);
      if (sub != null) {
         g.text(g.clip(name, textW), x + 35, y + 4, nameColor);
         g.text(g.clip(sub, textW), x + 35, y + 15, -9604486);
      } else {
         String rank = State.rank(id);
         int rw = rank == null ? 0 : g.width(rankLabel(rank)) + 10;
         String shown = g.clip(name, textW - rw);
         g.text(shown, x + 35, y + 9, nameColor);
         this.rankBadge(g, x + 39 + g.width(shown), y + 8, rank);
      }

      if (unread > 0) {
         this.badge(g, x + sw - 16, y + 8, unread);
      }

      this.hits.add(new VCraftScreen.Hit(x + 6, y, sw - 12, 26, () -> this.openChat(convId), () -> this.openConvMenu(convId, this.mouseX, this.mouseY)));
      return y + 28;
   }

   private void drawFriends(Gfx g, int x, int y, int pw, int ph) {
      this.header(g, x, y, pw);
      Icons.draw(g, Icons.PEOPLE, x + 12, y + 12, -7038044);
      g.text(I18n.t("Friends"), x + 28, y + 13, -1);
      int tx = x + 28 + g.width(I18n.t("Friends")) + 14;
      g.fill(tx - 7, y + 10, 1, 14, -14276564);
      String[] tabs = new String[]{I18n.t("Online"), I18n.t("All"), I18n.t("Pending"), I18n.t("Blocked")};

      for (int i = 0; i < tabs.length; i++) {
         String label = tabs[i];
         int tw = g.width(label) + 12;
         boolean sel = friendsTab == i;
         boolean hover = this.in(tx, y + 8, tw, 18);
         if (sel || hover) {
            g.panel(tx, y + 8, tw, 18, sel ? -11907755 : -12565943);
         }

         g.text(label, tx + 6, y + 13, sel ? -1 : -7038044);
         int idx = i;
         this.hits.add(new VCraftScreen.Hit(tx, y + 8, tw, 18, () -> {
            friendsTab = idx;
            this.pageScroll = 0;
         }, null));
         if (i == 2 && !State.incoming.isEmpty()) {
            this.badge(g, tx + tw + 2, y + 12, State.incoming.size());
         }

         tx += tw + (i == 2 && !State.incoming.isEmpty() ? 18 : 4);
      }

      this.drawKofi(g, tx, x + pw - 10, y + 8);
      int cy = y + 44;
      g.text(I18n.t("ADD FRIEND"), x + 16, cy, -1);
      g.text(I18n.t("Send a request with their Minecraft username."), x + 16, cy + 12, -7038044);
      int fieldY = cy + 26;
      g.panel(x + 16, fieldY, pw - 32, 26, -14803166);
      this.placeBox(this.addFriend, x + 24, fieldY + 9, pw - 140);
      boolean can = !this.addFriend.getValue().isBlank();
      this.button(
         g,
         x + pw - 112,
         fieldY + 4,
         92,
         18,
         I18n.t("Send Request"),
         can ? -12868260 : -13803970,
         can ? -12205973 : -13803970,
         can ? -1 : -7038044,
         this::sendFriendRequest
      );
      int codeY = fieldY + 30;
      g.panel(x + 16, codeY, pw - 32, 22, -14803166);
      this.placeBox(this.groupCode, x + 24, codeY + 7, pw - 140);
      boolean canCode = !this.val(this.groupCode).isBlank();
      this.button(g, x + pw - 112, codeY + 2, 92, 18, I18n.t("Join Group"), canCode ? -13092288 : -13803970, canCode ? -12565943 : -13803970, canCode ? -1 : -7038044, this::joinWithCode);
      int listY = codeY + 34;

      String title = switch (friendsTab) {
         case 0 -> "ONLINE";
         case 1 -> "ALL FRIENDS";
         case 2 -> "PENDING";
         default -> I18n.t("BLOCKED");
      };
      new ArrayList();
      List<State.Profile> people = new ArrayList<>();
      if (friendsTab == 0) {
         State.friends.stream().filter(State.Profile::online).forEach(people::add);
      }

      if (friendsTab == 1) {
         people.addAll(State.friends);
      }

      if (friendsTab == 2) {
         people.addAll(State.incoming);
         people.addAll(State.outgoing);
      }

      if (friendsTab == 3) {
         people.addAll(State.blocked);
      }

      g.text(title + " - " + people.size(), x + 16, listY, -9604486);
      listY += 14;
      this.setPageArea(x, listY, pw, y + ph - listY - 4);
      g.scissor(this.pageX, this.pageY, this.pageW, this.pageH);
      int ry = listY - this.pageScroll;

      for (State.Profile p : people) {
         ry = this.friendRow(g, x + 10, ry, pw - 20, p);
      }

      if (people.isEmpty()) {
         String empty = switch (friendsTab) {
            case 0 -> "None of your friends are online right now.";
            case 1 -> "Add a friend above to get started.";
            case 2 -> "No pending friend requests.";
            default -> I18n.t("You haven't blocked anyone.");
         };
         g.centered(empty, x + pw / 2, listY + 30, -9604486);
      }

      g.endScissor();
      this.pageMaxScroll = Math.max(0, ry + this.pageScroll - (this.pageY + this.pageH));
   }

   private void listeningLine(Gfx g, String song, int x, int y, int maxW) {
      Icons.draw(g, Icons.NOTE, x, y, -12868260);
      g.text(g.clip(song, Math.max(20, maxW - 12)), x + 12, y, -7348824);
   }

   private void drawKofi(Gfx g, int minX, int right, int y) {
      String label = I18n.t("Support me");
      int full = g.width(label) + 26;
      boolean compact = right - full < minX + 6;
      int bw = compact ? 20 : full;
      int bx = right - bw;
      if (bx >= minX) {
         boolean hover = this.in(bx, y, bw, 18);
         g.panel(bx, y, bw, 18, hover ? -34185 : -41381);
         Icons.draw(g, Icons.HEART, bx + 6, y + 5, -1);
         if (!compact) {
            g.text(label, bx + 19, y + 5, -1);
         }

         this.hits.add(new VCraftScreen.Hit(bx, y, bw, 18, () -> ConfirmLinkScreen.confirmLinkNow(this, URI.create("https://ko-fi.com/annestinksbad")), null));
      }
   }

   private int friendRow(Gfx g, int x, int y, int rw, State.Profile p) {
      g.fill(x + 6, y, rw - 12, 1, -14276564);
      boolean hover = this.in(x, y + 1, rw, 40);
      if (hover) {
         g.panel(x, y + 2, rw, 38, -12565943);
      }

      boolean incoming = State.incoming.contains(p);
      boolean outgoing = State.outgoing.contains(p);
      boolean blocked = friendsTab == 3;
      this.avatar(g, x + 8, y + 10, 22, p.uuid(), p.name(), !incoming && !outgoing && !blocked ? p.status() : null);
      boolean plain = !incoming && !outgoing && !blocked;
      String shown = plain ? Social.displayName(p.uuid(), p.name()) : p.name();
      g.text(shown, x + 38, y + 10, -1);
      int nameW = g.width(shown);
      if (!shown.equals(p.name())) {
         g.text("(" + p.name() + ")", x + 42 + nameW, y + 10, -9604486);
         nameW += g.width("(" + p.name() + ")") + 4;
      }
      this.rankBadge(g, x + 42 + nameW, y + 9, State.rank(p.uuid()));
      int bx = x + rw - 32;
      boolean joinable = plain && Social.friendInCall(p.uuid()) && State.call == null;
      if (plain && hover && Social.note(p.uuid()) != null) {
         this.tooltip = Social.note(p.uuid());
      }
      String song = !incoming && !outgoing && !blocked ? State.listening.get(p.uuid()) : null;
      if (song != null && Secure.trust(p.uuid()) != Secure.Trust.CHANGED) {
         this.listeningLine(g, song, x + 38, y + 22, bx - 64 - x - 38);
      } else {
         String sub = incoming
            ? I18n.t("Incoming friend request")
            : (outgoing ? I18n.t("Outgoing friend request") : (blocked ? I18n.t("Blocked") : friendLine(p)));
         g.text(sub, x + 38, y + 22, Secure.trust(p.uuid()) == Secure.Trust.CHANGED && !incoming && !outgoing ? -1228219 : -7038044);
      }

      if (incoming) {
         this.iconButton(g, bx, y + 9, 24, 24, Icons.CLOSE, -13092288, -1228219, -7038044, () -> State.send("friend_remove", "uuid", p.uuid()));
         this.iconButton(g, bx - 28, y + 9, 24, 24, Icons.CHECK, -13092288, -12868260, -7038044, () -> State.send("friend_accept", "uuid", p.uuid()));
      } else if (outgoing) {
         this.iconButton(g, bx, y + 9, 24, 24, Icons.CLOSE, -13092288, -1228219, -7038044, () -> State.send("friend_remove", "uuid", p.uuid()));
      } else if (blocked) {
         this.button(g, x + rw - 74, y + 12, 66, 18, I18n.t("Unblock"), -13092288, -12565943, -2367775, () -> State.send("unblock", "uuid", p.uuid()));
      } else {
         this.iconButton(g, bx, y + 9, 24, 24, Icons.DOTS, -13092288, -12565943, -7038044, () -> this.openConvMenu(p.uuid(), this.mouseX, this.mouseY));
         this.iconButton(g, bx - 28, y + 9, 24, 24, Icons.PHONE, -13092288, -12565943, -7038044, () -> State.call(p.uuid()));
         this.iconButton(g, bx - 56, y + 9, 24, 24, Icons.REPLY, -13092288, -12565943, -7038044, () -> this.openChat(p.uuid()));
         if (joinable) {
            this.iconButton(g, bx - 84, y + 9, 24, 24, Icons.PLUS, -12868260, -12205973, -1, () -> Social.askToJoin(p.uuid()));
            if (this.in(bx - 84, y + 9, 24, 24)) {
               this.tooltip = I18n.t("Ask to join their call");
            }
         }

         this.hits.add(new VCraftScreen.Hit(x, y + 1, rw - (joinable ? 118 : 90), 40, () -> this.openChat(p.uuid()), () -> this.openConvMenu(p.uuid(), this.mouseX, this.mouseY)));
      }

      return y + 42;
   }

   /** Status, then what they're doing or when you last saw them. */
   private static String friendLine(State.Profile p) {
      if (Secure.trust(p.uuid()) != Secure.Trust.TRUSTED) {
         return State.statusLabel(p.status()) + trustNote(p.uuid());
      }

      String d = Social.detail(p.uuid(), p.online());
      if (d == null) {
         return State.statusLabel(p.status());
      }

      return p.online() ? State.statusLabel(p.status()) + "  -  " + d : d;
   }

   private void joinWithCode() {
      String code = this.val(this.groupCode).trim();
      if (!code.isEmpty()) {
         String open = Social.joinWithCode(code);
         this.setVal(this.groupCode, "");
         if (open != null) {
            this.openChat(open);
         }
      }
   }

   private void saveNote() {
      if (this.modalSubject != null) {
         Social.setNickname(this.modalSubject, this.val(this.nickBox), this.val(this.noteBox));
      }

      this.modal = null;
   }

   private static String trustNote(String uuid) {
      return switch (Secure.trust(uuid)) {
         case CHANGED -> "  -  " + I18n.t("key changed, verify!");
         case NONE -> "  -  " + I18n.t("no encryption key yet");
         default -> "";
      };
   }

   private void sendFriendRequest() {
      String name = this.addFriend.getValue().trim();
      if (!name.isEmpty()) {
         State.send("friend_request", "name", name);
         this.addFriend.setValue("");
      }
   }

   private void drawChat(Gfx g, int x, int y, int pw, int ph) {
      State.Group group = State.group(conv);
      State.Profile friend = State.friend(conv);
      boolean members = group != null && showMembers && pw > 360;
      int memberW = members ? 130 : 0;
      int cw = pw - memberW;
      this.header(g, x, y, cw);
      boolean lobby = "lobby".equals(conv);
      int ey = y + 34;
      if (lobby) {
         g.panel(x + 10, y + 7, 20, 20, -12868260);
         Icons.draw(g, Icons.GLOBE, x + 15, y + 12, -1);
         g.text(I18n.t("VCraft Community"), x + 36, y + 9, -1);
         g.text(State.lobbyOnline + " online  -  official public chat", x + 36, y + 20, -7038044);
         g.fill(x, ey, cw, 14, 821080626);
         g.text(
            g.clip(
               State.lobbyMuted
                  ? "You're muted here, but you can still read along."
                  : "Public: everyone can read this, and it isn't end-to-end encrypted. Be kind!",
               cw - 20
            ),
            x + 10,
            ey + 3,
            -665462
         );
         this.drawChatBody(g, x, y, cw, ph, ey);
      } else {
         if (group != null) {
            this.groupAvatar(g, x + 10, y + 7, 20, group.name());
            g.text(g.clip(group.name(), cw - 190), x + 36, y + 9, -1);
            g.text(group.members().size() + (group.members().size() == 1 ? " member" : " members"), x + 36, y + 20, -7038044);
         } else if (friend != null) {
            this.avatar(g, x + 10, y + 7, 20, friend.uuid(), friend.name(), friend.status());
            String shown = Social.displayName(friend.uuid(), friend.name());
            g.text(shown, x + 36, y + 9, -1);
            if (this.in(x + 36, y + 7, g.width(shown), 12)) {
               this.tooltip = Social.note(friend.uuid()) == null ? friend.name() : friend.name() + ": " + Social.note(friend.uuid());
            }

            this.rankBadge(g, x + 40 + g.width(shown), y + 8, State.rank(friend.uuid()));
            String song = State.listening.get(friend.uuid());
            if (song != null) {
               this.listeningLine(g, song, x + 36, y + 20, cw - 150);
            } else {
               g.text(g.clip(friendLine(friend), cw - 220), x + 36, y + 20, -7038044);
            }
         }

         int hx = x + cw - 30;
         this.iconButton(g, hx, y + 7, 22, 20, Icons.DOTS, 0, -12565943, -7038044, () -> this.openConvMenu(conv, this.mouseX, this.mouseY + 10));
         int pinCount = Social.pins(conv).size();
         hx -= 24;
         this.iconButton(g, hx, y + 7, 22, 20, PIN, 0, -12565943, showPins ? -1 : (pinCount > 0 ? -2367775 : -9604486), () -> showPins = !showPins);
         if (this.in(hx, y + 7, 22, 20)) {
            this.tooltip = I18n.tf("Your pinned messages ({0})", String.valueOf(pinCount));
         }

         if (friend != null && Social.friendInCall(friend.uuid()) && State.call == null) {
            hx -= 24;
            String fu = friend.uuid();
            this.iconButton(g, hx, y + 7, 22, 20, Icons.PLUS, -12868260, -12205973, -1, () -> Social.askToJoin(fu));
            if (this.in(hx, y + 7, 22, 20)) {
               this.tooltip = I18n.t("Ask to join their call");
            }
         }
         if (group != null) {
            hx -= 24;
            this.iconButton(g, hx, y + 7, 22, 20, Icons.PEOPLE, 0, -12565943, showMembers ? -1 : -7038044, () -> showMembers = !showMembers);
         }

         for (VCraftApi.ChatButton b : AddonHost.INSTANCE.chatButtons) {
            String c = conv;

            try {
               if (b.visible(c)) {
                  hx -= 24;
                  this.iconButton(g, hx, y + 7, 22, 20, b.icon(), 0, -12565943, -7038044, () -> b.click(c));
                  if (this.in(hx, y + 7, 22, 20)) {
                     this.tooltip = b.tooltip(c);
                  }
               }
            } catch (Throwable var18) {
               VCraft.LOGGER.warn("VCraft addon chat button failed", var18);
            }
         }

         hx -= 24;
         this.iconButton(g, hx, y + 7, 22, 20, Icons.SERVER, 0, -12565943, -7038044, () -> State.inviteToServer(conv));
         hx -= 24;
         this.iconButton(g, hx, y + 7, 22, 20, Icons.PHONE, 0, -12565943, -7038044, () -> {
            if (group != null) {
               State.callGroup(group);
            } else {
               State.call(conv);
            }
         });
         Secure.Trust trust = group == null ? Secure.trust(conv) : Secure.Trust.TRUSTED;
         List<String> changed = new ArrayList<>();
         if (group != null) {
            for (State.Profile p : group.members()) {
               if (Secure.trust(p.uuid()) == Secure.Trust.CHANGED) {
                  changed.add(p.name());
               }
            }
         }

         boolean warn = trust != Secure.Trust.TRUSTED || !changed.isEmpty();
         g.fill(x, ey, cw, 14, warn ? 1089290821 : 540779868);
         Icons.draw(g, Icons.LOCK, x + 10, ey + 3, warn ? -1228219 : -12868260);
         String var10000;
         if (group != null) {
            var10000 = changed.isEmpty() ? "End-to-end encrypted" : "Key changed for " + String.join(", ", changed) + " - they won't get messages";
         } else {
            switch (trust) {
               case CHANGED:
                  var10000 = "Their encryption key changed. Click to verify";
                  break;
               case NONE:
                  var10000 = "No encryption key yet - they need to sign in once";
                  break;
               case TRUSTED:
                  var10000 = "End-to-end encrypted. Click to verify safety number";
                  break;
               default:
                  throw new MatchException(null, null);
            }
         }

         String encText = var10000;
         g.text(g.clip(encText, cw - 30), x + 24, ey + 3, warn ? -20304 : -5839944);
         if (group == null) {
            this.hits.add(new VCraftScreen.Hit(x, ey, cw, 14, () -> this.openModal(VCraftScreen.Modal.SAFETY, conv), null));
         }

         this.drawChatBody(g, x, y, cw, ph, ey);
         if (members) {
            this.drawMembers(g, x + cw, y, memberW, ph, group);
         }
      }
   }

   private void drawChatBody(Gfx g, int x, int y, int cw, int ph, int ey) {
      State.Group group = State.group(conv);
      State.Profile friend = State.friend(conv);
      boolean lobby = "lobby".equals(conv);
      int composerH = 26;
      int cy = y + ph - composerH - 10;
      if (replyTo != null) {
         g.panel(x + 12, cy - 20, cw - 24, 22, -13947599);
         g.text(g.clip("Replying to " + replyTo.name() + ":  " + replyTo.text(), cw - 60), x + 20, cy - 14, -7038044);
         this.iconButton(g, x + cw - 30, cy - 18, 14, 14, Icons.CLOSE, 0, -12565943, -7038044, () -> replyTo = null);
      }

      g.panel(x + 12, cy, cw - 24, composerH, -13092288);
      boolean songButton = NowPlaying.installed() && (!lobby || !State.lobbyMuted);
      this.placeBox(this.composer, x + 22, cy + 9, cw - (songButton ? 72 : 50));
      if (songButton) {
         String song = NowPlaying.current();
         this.iconButton(g, x + cw - 56, cy + 4, 18, 18, Icons.NOTE, 0, -12565943, song != null ? -12868260 : -9604486, () -> State.sendSong(conv));
         if (this.in(x + cw - 56, cy + 4, 18, 18)) {
            this.tooltip = song != null ? "Share what you're listening to: " + song : "Nothing is playing";
         }
      }

      this.composer
         .setHint(
            Component.literal(
                  lobby && State.lobbyMuted
                     ? "You're muted in the community chat"
                     : "Message " + (lobby ? I18n.t("VCraft Community") : (group != null ? group.name() : (friend != null ? "@" + friend.name() : "")))
               )
               .withColor(7172730)
         );
      this.composer.setMaxLength(lobby ? 300 : 500);
      this.iconButton(g, x + cw - 34, cy + 4, 18, 18, Icons.RIGHT, 0, -12565943, this.composer.getValue().isBlank() ? -9604486 : -2367775, this::sendComposer);
      int top = ey + 16;
      int bottom = cy - (replyTo != null ? 24 : 4);
      this.chatX = x;
      this.chatY = top;
      this.chatW = cw;
      this.chatH = bottom - top;
      this.drawMessages(g, x, top, cw, bottom - top);
      if (showPins && !lobby) {
         this.drawPins(g, x, top, cw, bottom - top);
      }
   }

   /** Your pinned messages for this conversation, over the top of the chat. */
   private void drawPins(Gfx g, int x, int top, int cw, int areaH) {
      List<Social.Pin> pins = Social.pins(conv);
      int ph = Math.min(Math.max(1, pins.size()) * 24 + 22, areaH - 10);
      int px = x + 12;
      int pw = cw - 24;
      g.panel(px - 1, top + 3, pw + 2, ph + 2, -15658476);
      g.panel(px, top + 4, pw, ph, -14474200);
      this.hits.add(new VCraftScreen.Hit(px, top + 4, pw, ph, () -> {}, null));
      Icons.draw(g, PIN, px + 8, top + 10, -7038044);
      g.text(I18n.t("YOUR PINS"), px + 22, top + 11, -9604486);
      g.text(I18n.t("Only you see these."), px + 30 + g.width(I18n.t("YOUR PINS")), top + 11, -9604486);
      this.iconButton(g, px + pw - 20, top + 7, 16, 14, Icons.CLOSE, 0, -12565943, -7038044, () -> showPins = false);
      g.scissor(px, top + 24, pw, ph - 22);
      int ry = top + 24;
      if (pins.isEmpty()) {
         g.text(I18n.t("Hover a message and click the pin to keep it here."), px + 10, ry + 6, -9604486);
      }

      for (int i = pins.size() - 1; i >= 0; i--) {
         Social.Pin p = pins.get(i);
         String c = conv;
         g.text(p.fromName(), px + 10, ry + 2, -1);
         g.text(time(p.ts()), px + 16 + g.width(p.fromName()), ry + 2, -9604486);
         g.text(g.clip(p.text(), pw - 50), px + 10, ry + 12, -2367775);
         this.iconButton(g, px + pw - 24, ry + 4, 16, 14, Icons.CLOSE, 0, -12565943, -9604486, () -> Social.unpin(c, p.id()));
         ry += 24;
      }

      g.endScissor();
   }

   private void drawMessages(Gfx g, int x, int top, int cw, int areaH) {
      List<State.Message> msgs = State.conversations.getOrDefault(conv, List.of());
      int textW = cw - 70;
      List<VCraftScreen.Item> items = new ArrayList<>();
      State.Message prev = null;
      int total = 0;

      for (State.Message m : msgs) {
         boolean header = !m.system() && (prev == null || prev.system() || !m.from().equals(prev.from()) || m.ts() - prev.ts() > 300000L || m.reply() != null);
         List<VCraftScreen.Line> lines = new ArrayList<>();
         if (m.system()) {
            for (FormattedCharSequence s : this.font.split(Component.literal(m.text()), textW)) {
               lines.add(new VCraftScreen.Line(s, -7038044));
            }
         } else if (m.song() == null) {
            int color = m.secure() ? -2367775 : -30068;

            for (FormattedCharSequence s : this.font.split(Component.literal(m.text()), textW)) {
               lines.add(new VCraftScreen.Line(s, color));
            }
         }

         int gap = (header || m.system()) && prev != null ? 6 : 0;
         int hgt = gap + lines.size() * 10 + (header ? 14 : 0) + (m.reply() != null ? 12 : 0) + (m.invite() != null ? 44 : 0) + (m.song() != null ? 40 : 0) + 4;
         items.add(new VCraftScreen.Item(m, header, gap, lines, hgt));
         total += hgt;
         prev = m;
      }

      this.chatMaxScroll = Math.max(0, total - areaH + 8);
      this.chatScroll = Math.max(0, Math.min(this.chatScroll, this.chatMaxScroll));
      g.scissor(x, top, cw, areaH);
      int yy = top + areaH - 4 - total + this.chatScroll;
      if (total < areaH - 8) {
         String who = State.convName(conv);
         int sy = top + 12;
         if ("lobby".equals(conv)) {
            sy = this.para(g, "Welcome to the VCraft Community, the official chat for everyone using VCraft.", x + 16, sy, cw - 32, -7038044);
            this.para(g, "Say hi, find people to play with, and keep it friendly. Moderators can mute people who aren't.", x + 16, sy + 2, cw - 32, -9604486);
         } else {
            sy = this.para(g, "This is the start of your conversation with " + who + ".", x + 16, sy, cw - 32, -7038044);
            this.para(g, "Messages are end-to-end encrypted: only the people in it can read them.", x + 16, sy + 2, cw - 32, -9604486);
         }
      }

      for (VCraftScreen.Item it : items) {
         if (yy + it.height() >= top && yy <= top + areaH) {
            this.drawMessage(g, x, yy, cw, it, top, areaH);
         }

         yy += it.height();
      }

      g.endScissor();
   }

   private void drawMessage(Gfx g, int x, int y, int cw, VCraftScreen.Item it, int clipTop, int clipH) {
      State.Message m = it.msg();
      int yy = y + it.gap();
      if (m.system()) {
         int ly = yy + 2;

         for (VCraftScreen.Line l : it.lines()) {
            g.text(l.text(), x + 50, ly, l.color());
            ly += 10;
         }

         g.fill(x + 16, yy + 6, 26, 1, -9604486);
      } else {
         boolean mine = m.from().equals(State.myUuid);
         int top = y + it.gap() - 2;
         boolean hover = this.in(x, Math.max(top, clipTop), cw, Math.min(y + it.height(), clipTop + clipH) - Math.max(top, clipTop)) && this.menu == null;
         if (hover) {
            g.fill(x, top, cw, y + it.height() - top, -13750219);
         }

         if (m.reply() != null) {
            g.fill(x + 30, yy + 5, 12, 1, -9604486);
            g.fill(x + 30, yy + 5, 1, 6, -9604486);
            g.text(g.clip(m.reply().name() + "  " + m.reply().text(), cw - 120), x + 50, yy + 1, -9604486);
            yy += 12;
         }

         if (it.showHeader()) {
            this.avatar(g, x + 14, yy + 2, 26, m.from(), m.fromName(), null);
            String author = mine ? m.fromName() : Social.displayName(m.from(), m.fromName());
            g.text(author, x + 50, yy + 2, mine ? -8531040 : -1);
            int after = x + 54 + g.width(author);
            if (Social.isPinned(conv, m.id())) {
               Icons.draw(g, PIN, after, yy + 1, -1002958);
               after += 12;
            }

            after += this.rankBadge(g, after, yy + 1, State.rank(m.from()));
            g.text(time(m.ts()), after + 2, yy + 2, -9604486);
            yy += 14;
         }

         for (VCraftScreen.Line l : it.lines()) {
            g.text(l.text(), x + 50, yy, l.color());
            yy += 10;
         }

         if (m.song() != null) {
            int cardW = Math.min(260, cw - 70);
            g.panel(x + 50, yy + 1, cardW, 36, -13947599);
            g.fill(x + 50, yy + 1, 2, 36, -12868260);
            g.panel(x + 58, yy + 7, 24, 24, -14730710);
            Icons.draw(g, Icons.NOTE, x + 66, yy + 15, -12868260);
            g.text(mine ? "YOU'RE LISTENING TO" : "LISTENING TO", x + 90, yy + 8, -9604486);
            g.text(g.clip(m.song(), cardW - 48), x + 90, yy + 20, -1);
         }

         if (m.invite() != null) {
            int cardW = Math.min(240, cw - 70);
            g.panel(x + 50, yy + 3, cardW, 38, -13947599);
            g.fill(x + 50, yy + 3, 2, 38, -12868260);
            Icons.draw(g, Icons.SERVER, x + 58, yy + 9, -12868260);
            g.text(g.clip(m.invite().name(), cardW - 90), x + 72, yy + 9, -1);
            g.text(g.clip(m.invite().address(), cardW - 90), x + 72, yy + 22, -7038044);
            State.ServerInvite inv = m.invite();
            if (!mine) {
               this.button(g, x + 50 + cardW - 54, yy + 12, 46, 18, "Join", -12868260, -12205973, -1, () -> {
                  this.pendingJoin = inv;
                  this.openModal(VCraftScreen.Modal.JOIN_SERVER, m.fromName());
               });
            } else {
               g.text("Sent", x + 50 + cardW - 34, yy + 16, -9604486);
            }
         }

         if (hover && m.id() != null) {
            boolean lobbyMsg = "lobby".equals(conv);
            boolean canDelete = mine || lobbyMsg && State.isStaff();
            boolean canMenu = lobbyMsg && !mine;
            boolean canPin = !lobbyMsg;
            int bw = 24 + (canDelete ? 22 : 0) + (canMenu ? 22 : 0) + (canPin ? 22 : 0);
            int bx = x + cw - bw - 12;
            int by = Math.max(top - 6, clipTop);
            g.panel(bx - 1, by - 1, bw + 2, 22, -14803166);
            g.panel(bx, by, bw, 20, -13092288);
            State.Reply reply = new State.Reply(m.id(), m.fromName(), m.invite() != null ? "Server invite: " + m.invite().name() : m.text());
            this.iconButton(g, bx + 2, by + 2, 20, 16, Icons.REPLY, 0, -12565943, -7038044, () -> {
               replyTo = reply;
               this.setFocused(this.composer);
            });
            int ax = bx + 24;
            if (canPin) {
               boolean pinned = Social.isPinned(conv, m.id());
               String c = conv;
               this.iconButton(g, ax, by + 2, 20, 16, PIN, 0, -12565943, pinned ? -1002958 : -7038044, () -> Social.togglePin(c, m));
               if (this.in(ax, by + 2, 20, 16)) {
                  this.tooltip = pinned ? I18n.t("Unpin") : I18n.t("Pin for yourself");
               }

               ax += 22;
            }

            if (canDelete) {
               this.iconButton(g, ax, by + 2, 20, 16, Icons.TRASH, 0, -1228219, -30068, () -> State.deleteMessage(conv, m.id()));
               ax += 22;
            }

            if (canMenu) {
               this.iconButton(
                  g, ax, by + 2, 20, 16, Icons.DOTS, 0, -12565943, -7038044, () -> this.openLobbyUserMenu(m.from(), m.fromName(), this.mouseX, this.mouseY)
               );
            }
         }
      }
   }

   private void drawMembers(Gfx g, int x, int y, int mw, int ph, State.Group group) {
      g.fill(x, y + 1, mw - 1, ph - 2, -13947599);
      g.fill(x, y, 1, ph, -14276564);
      g.text("MEMBERS - " + group.members().size(), x + 10, y + 12, -9604486);
      int my = y + 28;

      for (State.Profile p : group.members()) {
         boolean hover = this.in(x + 4, my, mw - 9, 24);
         if (hover) {
            g.panel(x + 4, my, mw - 9, 24, -12565943);
         }

         this.avatar(g, x + 8, my + 4, 16, p.uuid(), p.name(), p.status());
         String label = g.clip(p.name(), mw - 46);
         g.text(label, x + 30, my + 8, p.online() ? -2367775 : -9604486);
         int after = x + 33 + g.width(label);
         if (p.uuid().equals(group.owner())) {
            g.text("*", after, my + 8, -1002958);
            after += 8;
         }

         if (after + 30 < x + mw) {
            this.rankBadge(g, after, my + 7, State.rank(p.uuid()));
         }

         this.hits
            .add(
               new VCraftScreen.Hit(
                  x + 4,
                  my,
                  mw - 9,
                  24,
                  () -> this.openMemberMenu(group, p, this.mouseX, this.mouseY),
                  () -> this.openMemberMenu(group, p, this.mouseX, this.mouseY)
               )
            );
         my += 26;
         if (my > y + ph - 30) {
            break;
         }
      }

      boolean hoverAdd = this.in(x + 4, y + ph - 28, mw - 9, 22);
      if (hoverAdd) {
         g.panel(x + 4, y + ph - 28, mw - 9, 22, -12565943);
      }

      Icons.draw(g, Icons.PLUS, x + 10, y + ph - 21, hoverAdd ? -1 : -7038044);
      g.text(I18n.t("Add people"), x + 26, y + ph - 20, hoverAdd ? -1 : -7038044);
      this.hits.add(new VCraftScreen.Hit(x + 4, y + ph - 28, mw - 9, 22, () -> this.openAddToGroupMenu(group, this.mouseX, this.mouseY - 80), null));
   }

   private void sendComposer() {
      String text = this.composer.getValue().trim();
      if (!text.isEmpty() && conv != null) {
         if (State.sendMessage(conv, text, replyTo, null)) {
            this.composer.setValue("");
            replyTo = null;
            this.chatScroll = 0;
         }
      }
   }

   private void drawNewGroup(Gfx g, int x, int y, int pw, int ph) {
      this.header(g, x, y, pw);
      Icons.draw(g, Icons.PEOPLE, x + 12, y + 12, -7038044);
      g.text("Create a Group", x + 28, y + 13, -1);
      int cy = y + 46;
      g.text("GROUP NAME", x + 16, cy, -7038044);
      g.panel(x + 16, cy + 12, pw - 32, 24, -14803166);
      this.placeBox(this.groupName, x + 24, cy + 20, pw - 48);
      g.text("ADD FRIENDS  (" + this.newGroupMembers.size() + " selected)", x + 16, cy + 48, -7038044);
      this.setPageArea(x, cy + 62, pw, ph - (cy + 62 - y) - 44);
      g.scissor(this.pageX, this.pageY, this.pageW, this.pageH);
      int ry = this.pageY - this.pageScroll;

      for (State.Profile f : State.friends) {
         boolean sel = this.newGroupMembers.contains(f.uuid());
         boolean hover = this.in(x + 12, ry, pw - 24, 26);
         if (hover) {
            g.panel(x + 12, ry, pw - 24, 26, -12565943);
         }

         this.avatar(g, x + 18, ry + 4, 18, f.uuid(), f.name(), f.status());
         g.text(f.name(), x + 44, ry + 9, -2367775);
         int bx = x + pw - 36;
         g.panel(bx, ry + 6, 14, 14, sel ? -12868260 : -14803166);
         g.outline(bx, ry + 6, 14, 14, sel ? -12868260 : -9604486);
         if (sel) {
            Icons.draw(g, Icons.CHECK, bx + 3, ry + 9, -1);
         }

         this.hits.add(new VCraftScreen.Hit(x + 12, ry, pw - 24, 26, () -> {
            if (!this.newGroupMembers.remove(f.uuid())) {
               this.newGroupMembers.add(f.uuid());
            }
         }, null));
         ry += 28;
      }

      if (State.friends.isEmpty()) {
         g.text("Add some friends first.", x + 18, ry + 4, -9604486);
      }

      g.endScissor();
      this.pageMaxScroll = Math.max(0, ry + this.pageScroll - (this.pageY + this.pageH));
      boolean ok = !this.groupName.getValue().isBlank();
      this.button(
         g, x + pw - 116, y + ph - 34, 100, 22, "Create Group", ok ? -12868260 : -13803970, ok ? -12205973 : -13803970, ok ? -1 : -7038044, this::createGroup
      );
      this.button(g, x + pw - 190, y + ph - 34, 66, 22, I18n.t("Cancel"), 0, -12565943, -2367775, () -> this.go(VCraftScreen.Page.FRIENDS));
   }

   private void createGroup() {
      String name = this.groupName.getValue().trim();
      if (!name.isEmpty()) {
         State.createGroup(name, new ArrayList<>(this.newGroupMembers));
         this.groupName.setValue("");
         this.newGroupMembers.clear();
      }
   }

   private void drawCall(Gfx g, int x, int y, int pw, int ph) {
      State.Call call = State.call;
      this.header(g, x, y, pw);
      Icons.draw(g, Icons.PHONE, x + 12, y + 12, -12868260);
      g.text(I18n.t("Voice Call"), x + 28, y + 13, -1);
      int tileW = 104;
      int tileH = 80;
      int gap = 10;
      List<State.CallMember> members = call.members();
      int perRow = Math.max(1, (pw - 32 + gap) / (tileW + gap));
      int rowsUsed = (members.size() + call.ringing().size() + perRow - 1) / perRow;
      int gridW = Math.min(perRow, members.size() + call.ringing().size()) * (tileW + gap) - gap;
      int gx = x + (pw - gridW) / 2;
      int gy = y + 50;
      int i = 0;

      for (State.CallMember m : members) {
         int tx = gx + i % perRow * (tileW + gap);
         int ty = gy + i / perRow * (tileH + gap);
         boolean speaking = State.isSpeaking(m.uuid());
         boolean me = m.uuid().equals(State.myUuid);
         g.panel(tx, ty, tileW, tileH, -14474200);
         if (speaking) {
            g.outline(tx, ty, tileW, tileH, -12868260);
         }

         this.avatar(g, tx + tileW / 2 - 16, ty + 12, 32, m.uuid(), m.name(), null);
         g.centered(g.clip(me ? m.name() + " (" + I18n.t("you") + ")" : Social.displayName(m.uuid(), m.name()), tileW - 8), tx + tileW / 2, ty + 52, -1);
         if (m.deafened()) {
            Icons.drawStruck(g, Icons.HEADPHONES, tx + tileW - 14, ty + 4, -7038044);
         } else if (m.muted()) {
            Icons.drawStruck(g, Icons.MIC, tx + tileW - 14, ty + 4, -7038044);
         }

         boolean mutedForMe = VCraft.config().mutedUsers.contains(m.uuid());
         if (!me) {
            String state = Secure.trust(m.uuid()) != Secure.Trust.TRUSTED
               ? I18n.t("key not trusted")
               : (!Secure.hasVoiceKeyFrom(m.uuid()) ? I18n.t("securing...") : (mutedForMe ? I18n.t("muted for you") : ""));
            if (!state.isEmpty()) {
               g.centered(state, tx + tileW / 2, ty + 64, mutedForMe ? -9604486 : -1002958);
            }

            Runnable memberMenu = () -> this.openMenu(
                  this.mouseX,
                  this.mouseY,
                  List.of(
                     new VCraftScreen.MenuItem(mutedForMe ? I18n.t("Unmute for me") : I18n.t("Mute for me"), -2367775, () -> State.toggleUserMute(m.uuid())),
                     new VCraftScreen.MenuItem(I18n.t("Block"), -1228219, () -> State.send("block", "uuid", m.uuid()))
                  )
               );
            this.hits.add(new VCraftScreen.Hit(tx, ty, tileW, tileH, memberMenu, memberMenu));
         }

         i++;
      }

      for (State.Profile p : call.ringing()) {
         int txx = gx + i % perRow * (tileW + gap);
         int tyx = gy + i / perRow * (tileH + gap);
         g.panel(txx, tyx, tileW, tileH, -14474200);
         this.avatar(g, txx + tileW / 2 - 16, tyx + 12, 32, p.uuid(), p.name(), null);
         g.fill(txx + tileW / 2 - 16, tyx + 12, 32, 32, -2145180632);
         g.centered(g.clip(p.name(), tileW - 8), txx + tileW / 2, tyx + 52, -7038044);
         g.centered(I18n.t("Ringing..."), txx + tileW / 2, tyx + 64, -9604486);
         i++;
      }

      int iy = gy + rowsUsed * (tileH + gap) + 6;
      List<Social.Request> asking = new ArrayList<>();
      for (Social.Request r : Social.requests) {
         if ("call".equals(r.kind())) {
            asking.add(r);
         }
      }

      if (!asking.isEmpty()) {
         g.text(I18n.t("ASKING TO JOIN"), x + 16, iy, -1002958);
         iy += 14;
         for (Social.Request r : asking) {
            g.panel(x + 16, iy, pw - 32, 22, -13092288);
            this.avatar(g, x + 20, iy + 4, 14, r.from(), r.name(), null);
            g.text(r.name(), x + 40, iy + 7, -1);
            this.button(g, x + pw - 140, iy + 2, 60, 18, I18n.t("Let in"), -12868260, -12205973, -1, () -> Social.accept(r));
            this.button(g, x + pw - 76, iy + 2, 56, 18, I18n.t("Ignore"), 0, -12565943, -2367775, () -> Social.dismiss(r));
            iy += 26;
         }

         iy += 4;
      }

      g.text(I18n.t("INVITE TO CALL"), x + 16, iy, -7038044);
      int ix = x + 16;
      int iyy = iy + 14;
      List<State.Profile> candidates = new ArrayList<>();

      for (State.Profile f : State.friends) {
         if (f.online() && !State.inCall(f.uuid())) {
            candidates.add(f);
         }
      }

      for (State.Group gr : State.groups) {
         for (State.Profile p : gr.members()) {
            if (p.online() && !State.inCall(p.uuid()) && !p.uuid().equals(State.myUuid) && candidates.stream().noneMatch(c -> c.uuid().equals(p.uuid()))) {
               candidates.add(p);
            }
         }
      }

      for (State.Profile px : candidates) {
         String pname = Social.displayName(px.uuid(), px.name());
         int cw = g.width(pname) + 34;
         if (ix + cw > x + pw - 16) {
            ix = x + 16;
            iyy += 24;
         }

         if (iyy > y + ph - 70) {
            break;
         }

         boolean hover = this.in(ix, iyy, cw, 20);
         g.panel(ix, iyy, cw, 20, hover ? -12565943 : -13092288);
         this.avatar(g, ix + 4, iyy + 4, 12, px.uuid(), px.name(), null);
         g.text(pname, ix + 20, iyy + 6, -2367775);
         Icons.draw(g, Icons.PLUS, ix + cw - 12, iyy + 6, hover ? -12205973 : -7038044);
         this.hits.add(new VCraftScreen.Hit(ix, iyy, cw, 20, () -> State.call(px.uuid()), null));
         ix += cw + 6;
      }

      if (candidates.isEmpty()) {
         g.text(I18n.t("Nobody else is online."), x + 16, iyy + 4, -9604486);
      }

      int by = y + ph - 46;
      int bx = x + pw / 2 - 87;
      this.bigToggle(g, bx, by, Icons.MIC, State.muted || State.deafened, State::toggleMute);
      this.bigToggle(g, bx + 46, by, Icons.HEADPHONES, State.deafened, State::toggleDeafen);
      this.iconButton(g, bx + 92, by, 40, 32, Icons.REFRESH, -13092288, -12565943, -1, State::reconnectVoice);
      this.iconButton(g, bx + 138, by, 40, 32, Icons.HANGUP, -1228219, -893086, -1, () -> State.send("call_leave", null, null));
      if (this.in(bx + 92, by, 40, 32)) {
         g.centered(I18n.t("Reconnect voice"), bx + 112, by - 12, -7038044);
      }
   }

   private void drawSettings(Gfx g, int x, int y, int pw, int ph) {
      this.header(g, x, y, pw);
      Icons.draw(g, Icons.GEAR, x + 12, y + 12, -7038044);
      g.text(I18n.t("Settings"), x + 28, y + 13, -1);
      Config cfg = VCraft.config();
      this.setPageArea(x, y + 34, pw, ph - 36);
      g.scissor(this.pageX, this.pageY, this.pageW, this.pageH);
      int sy = this.pageY + 10 - this.pageScroll;
      int lx = x + 18;
      int cx = x + pw / 2;
      int cw = pw / 2 - 18;
      sy = this.section(g, lx, sy, pw - 36, I18n.t("ACCOUNT"));
      g.text(I18n.t("Status"), lx, sy + 5, -2367775);
      int segX = cx;
      int segW = cw / 4;
      String[] labels = new String[]{I18n.t("Online"), I18n.t("Idle"), I18n.t("DND"), I18n.t("Offline")};

      for (int i = 0; i < 4; i++) {
         String s = State.STATUSES.get(i);
         boolean sel = s.equals(State.myStatus);
         boolean hover = this.in(segX + i * segW, sy, segW - 2, 18);
         g.panel(segX + i * segW, sy, segW - 2, 18, sel ? -11907755 : (hover ? -12565943 : -13092288));
         g.fill(segX + i * segW + 5, sy + 7, 4, 4, State.statusColor(s));
         g.text(g.clip(labels[i], segW - 16), segX + i * segW + 12, sy + 5, sel ? -1 : -7038044);
         this.hits.add(new VCraftScreen.Hit(segX + i * segW, sy, segW - 2, 18, () -> State.setStatus(s), null));
      }

      sy += 24;
      sy = this.para(g, I18n.t("Do Not Disturb silences pop-ups, sounds and ringing. Offline hides you."), lx, sy, pw - 36, -9604486) + 8;
      g.text(I18n.t("Language"), lx, sy + 5, -2367775);
      this.iconButton(g, cx, sy, 18, 18, Icons.LEFT, -13092288, -12565943, -2367775, () -> this.cycleLanguage(false));
      g.panel(cx + 20, sy, cw - 42, 18, -14803166);
      String lang = "game".equals(cfg.language) ? I18n.t("Same as the game") : I18n.name(cfg.language);
      g.centered(g.clip(lang, cw - 50), cx + 20 + (cw - 42) / 2, sy + 5, -1);
      this.iconButton(g, cx + cw - 20, sy, 18, 18, Icons.RIGHT, -13092288, -12565943, -2367775, () -> this.cycleLanguage(true));
      sy += 26;
      sy = this.section(g, lx, sy, pw - 36, I18n.t("CONNECTION"));
      g.text(I18n.t("Relay address"), lx, sy + 5, -2367775);
      g.panel(cx, sy, cw - 52, 18, -14803166);
      this.placeBox(this.relayUrl, cx + 6, sy + 5, cw - 64);
      this.button(g, cx + cw - 48, sy, 48, 18, I18n.t("Save"), -12868260, -12205973, -1, this::saveRelay);
      sy += 22;
      RelayConnection.Status st = VCraft.relay().status();

      String stText = switch (st) {
         case CONNECTED -> "Connected as " + State.myName;
         case CONNECTING -> "Connecting...";
         case AUTHENTICATING -> "Signing in...";
         default -> VCraft.relay().lastError() != null ? VCraft.relay().lastError() : I18n.t("Not connected");
      };
      g.text(
         g.clip(stText, cw), cx, sy + 2, st == RelayConnection.Status.CONNECTED ? -12868260 : (st == RelayConnection.Status.DISCONNECTED ? -1228219 : -1002958)
      );
      sy += 16;
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Connect on startup"), cfg.autoConnect, () -> {
         cfg.autoConnect = !cfg.autoConnect;
         cfg.save();
      });
      sy = this.section(g, lx, sy + 4, pw - 36, "UPDATES");
      sy = this.toggleRow(g, lx, cx, cw, sy, "Automatic updates", cfg.autoUpdate, () -> {
         cfg.autoUpdate = !cfg.autoUpdate;
         cfg.save();
      });
      g.text("VCraft " + AutoUpdate.currentVersion(), lx, sy + 5, -2367775);
      boolean busy = AutoUpdate.status == AutoUpdate.Status.CHECKING || AutoUpdate.status == AutoUpdate.Status.DOWNLOADING;
      this.button(g, cx, sy, 100, 18, busy ? "Checking..." : "Check now", -13092288, -12565943, -2367775, () -> {
         if (!busy) {
            AutoUpdate.check();
         }
      });
      sy += 22;
      if (AutoUpdate.detail != null) {
         int color = switch (AutoUpdate.status) {
            case READY -> -12868260;
            case FAILED -> -1228219;
            default -> -7038044;
         };
         sy = this.para(g, AutoUpdate.detail, lx, sy, pw - 36, color) + 6;
      }

      sy = this.section(g, lx, sy + 4, pw - 36, I18n.t("VOICE"));
      g.text(I18n.t("Input mode"), lx, sy + 5, -2367775);
      int half = cw / 2;
      this.segment(g, cx, sy, half - 2, I18n.t("Voice Activity"), !cfg.pushToTalk, () -> {
         cfg.pushToTalk = false;
         cfg.save();
      });
      this.segment(g, cx + half, sy, half - 2, I18n.t("Push to Talk"), cfg.pushToTalk, () -> {
         cfg.pushToTalk = true;
         cfg.save();
      });
      sy += 24;
      sy = this.sliderRow(
         g,
         lx,
         cx,
         cw,
         sy,
         I18n.t("Activation threshold"),
         (cfg.voiceThresholdDb + 70.0) / 60.0,
         Math.round(cfg.voiceThresholdDb) + " dB",
         v -> cfg.voiceThresholdDb = (double)Math.round(-70.0 + v * 60.0)
      );
      VoiceEngine voice = VCraft.voice();
      if (voice.running()) {
         double level = (double)(Math.max(-70.0F, Math.min(-10.0F, voice.micLevelDb())) + 70.0F) / 60.0;
         boolean over = (double)voice.micLevelDb() >= cfg.voiceThresholdDb;
         g.fill(cx, sy - 6, (int)((double)cw * level), 3, over ? -12868260 : -9604486);
      }

      sy = this.sliderRow(
         g,
         lx,
         cx,
         cw,
         sy,
         I18n.t("Mic volume"),
         cfg.micVolume / 2.0,
         Math.round(cfg.micVolume * 100.0) + "%",
         v -> cfg.micVolume = (double)Math.round(v * 200.0) / 100.0
      );
      sy = this.sliderRow(
         g,
         lx,
         cx,
         cw,
         sy,
         I18n.t("Voice volume"),
         cfg.outputVolume / 2.0,
         Math.round(cfg.outputVolume * 100.0) + "%",
         v -> cfg.outputVolume = (double)Math.round(v * 200.0) / 100.0
      );
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Noise suppression"), cfg.noiseSuppression, () -> {
         cfg.noiseSuppression = !cfg.noiseSuppression;
         cfg.save();
      });
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Echo cancellation"), cfg.echoCancellation, () -> {
         cfg.echoCancellation = !cfg.echoCancellation;
         cfg.save();
      });
      sy = this.para(g, I18n.t("Turn this on if you use speakers instead of headphones, so others don't hear themselves."), lx, sy - 2, pw - 36, -9604486) + 6;
      g.text(I18n.t("Voice effect"), lx, sy + 5, -2367775);
      this.iconButton(g, cx, sy, 18, 18, Icons.LEFT, -13092288, -12565943, -2367775, () -> {
         cfg.voiceEffect = VoiceFx.next(cfg.voiceEffect, false);
         cfg.save();
      });
      g.panel(cx + 20, sy, cw - 42, 18, -14803166);
      g.centered(I18n.t(VoiceFx.label(cfg.voiceEffect)), cx + 20 + (cw - 42) / 2, sy + 5, "none".equals(cfg.voiceEffect) ? -7038044 : -12868260);
      this.iconButton(g, cx + cw - 20, sy, 18, 18, Icons.RIGHT, -13092288, -12565943, -2367775, () -> {
         cfg.voiceEffect = VoiceFx.next(cfg.voiceEffect, true);
         cfg.save();
      });
      sy += 24;
      g.text(I18n.t("Mic test"), lx, sy + 5, -2367775);
      this.button(
         g,
         cx,
         sy,
         110,
         18,
         voice.micTest() ? I18n.t("Stop testing") : I18n.t("Let's check"),
         voice.micTest() ? -1228219 : -13092288,
         voice.micTest() ? -893086 : -12565943,
         -1,
         () -> voice.setMicTest(!voice.micTest())
      );
      if (voice.error() != null) {
         g.text(g.clip(voice.error(), cw - 116), cx + 116, sy + 5, -1228219);
      }

      sy += 26;
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Mute while paused"), cfg.autoMutePaused, () -> {
         cfg.autoMutePaused = !cfg.autoMutePaused;
         cfg.save();
      });
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Mute in other windows"), cfg.autoMuteUnfocused, () -> {
         cfg.autoMuteUnfocused = !cfg.autoMuteUnfocused;
         cfg.save();
      });
      sy = this.section(g, lx, sy + 2, pw - 36, I18n.t("CALLS"));
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Rejoin calls after a dropped connection"), cfg.autoRejoin, () -> {
         cfg.autoRejoin = !cfg.autoRejoin;
         cfg.save();
      });
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Show friends my server and calls"), cfg.shareServer, () -> {
         cfg.shareServer = !cfg.shareServer;
         cfg.save();
      });
      sy = this.para(g, I18n.t("Friends on VCraft for Horizon see which server you're on and when you're in a call, and can ask to join. End-to-end encrypted; hidden while you're Invisible."), lx, sy - 2, pw - 36, -9604486) + 8;
      sy = this.section(g, lx, sy, pw - 36, I18n.t("NOTIFICATIONS"));
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Pop-up notifications"), cfg.notifications, () -> {
         cfg.notifications = !cfg.notifications;
         cfg.save();
      });
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Message sound"), cfg.messageSound, () -> {
         cfg.messageSound = !cfg.messageSound;
         cfg.save();
         if (cfg.messageSound) {
            VCraft.platform().playDing();
         }
      });
      sy = this.section(g, lx, sy, pw - 36, I18n.t("HUD"));
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Compact call HUD (faces only)"), cfg.hudCompact, () -> {
         cfg.hudCompact = !cfg.hudCompact;
         cfg.save();
      });
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Unread badge while playing"), cfg.hudUnread, () -> {
         cfg.hudUnread = !cfg.hudUnread;
         cfg.save();
      });
      sy = this.section(g, lx, sy, pw - 36, I18n.t("NOW LISTENING"));
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Show friends my song"), cfg.shareListening, () -> {
         cfg.shareListening = !cfg.shareListening;
         cfg.save();
      });
      sy = this.para(
            g,
            NowPlaying.installed()
               ? I18n.t("Friends see what you're playing on your computer, end-to-end encrypted. Hidden while you're Invisible or when Now Playing's sharing is off.")
               : "Install the Now Playing mod to show friends what you're listening to and share songs in chat.",
            lx,
            sy - 2,
            pw - 36,
            -9604486
         )
         + 8;
      g.text(I18n.t("Ringtone"), lx, sy + 5, -2367775);
      List<String> options = new ArrayList<>(this.discs);
      options.add(0, "none");
      int idx = Math.max(0, options.indexOf(cfg.ringtone));
      this.iconButton(g, cx, sy, 18, 18, Icons.LEFT, -13092288, -12565943, -2367775, () -> this.cycleRingtone(options, -1));
      g.panel(cx + 20, sy, cw - 62, 18, -14803166);
      g.centered(g.clip(discName(options.get(idx)), cw - 70), cx + 20 + (cw - 62) / 2, sy + 5, -1);
      this.iconButton(g, cx + cw - 40, sy, 18, 18, Icons.RIGHT, -13092288, -12565943, -2367775, () -> this.cycleRingtone(options, 1));
      this.iconButton(
         g,
         cx + cw - 18,
         sy,
         18,
         18,
         this.previewing ? Icons.STOP : Icons.PLAY,
         -13092288,
         -12565943,
         this.previewing ? -1228219 : -12868260,
         this::togglePreview
      );
      sy += 22;
      sy = this.para(g, "Any music disc works, including ones from mods and resource packs.", lx, sy, pw - 36, -9604486) + 8;
      sy = this.section(g, lx, sy, pw - 36, I18n.t("PRIVACY"));
      g.text(I18n.t("Blocked users"), lx, sy + 5, -2367775);
      this.button(g, cx, sy, Math.min(cw, 130), 18, State.blocked.size() + " blocked - manage", -13092288, -12565943, -2367775, () -> {
         friendsTab = 3;
         this.go(VCraftScreen.Page.FRIENDS);
      });
      sy += 24;
      g.text(I18n.t("Your data"), lx, sy + 5, -2367775);
      int dataW = Math.min(cw / 2 - 2, 120);
      this.button(g, cx, sy, dataW, 18, I18n.t("Download my data"), -13092288, -12565943, -2367775, State::exportData);
      this.button(
         g, cx + dataW + 4, sy, dataW, 18, I18n.t("Delete account"), -10736851, -1228219, -20304, () -> this.openModal(VCraftScreen.Modal.DELETE_ACCOUNT, null)
      );
      sy += 24;
      sy = this.para(
            g,
            I18n.t("Messages and calls are end-to-end encrypted. Your key lives in config/vcraft - back it up to keep your identity on a new PC."),
            lx,
            sy,
            pw - 36,
            -9604486
         )
         + 10;
      sy = this.section(g, lx, sy, pw - 36, I18n.t("CONTROLS"));
      for (String[] k : KEY_LABELS) {
         g.text(I18n.t(k[1]), lx, sy + 3, -2367775);
         String bound = VCraft.platform().keyName(k[0]);
         boolean unbound = bound == null || bound.isEmpty();
         g.panel(cx, sy, Math.min(cw, 120), 14, -14803166);
         g.centered(unbound ? I18n.t("Not bound") : bound, cx + Math.min(cw, 120) / 2, sy + 3, unbound ? -9604486 : -1);
         sy += 17;
      }

      sy += 2;
      sy = this.para(g, "Change them in Options > Controls > Key Binds > VCraft.", lx, sy, pw - 36, -9604486) + 2;
      sy = this.para(g, I18n.t("Commands: /vc msg, call, join, invite, accept, decline, hangup, mute, deafen, status, add, nick"), lx, sy, pw - 36, -9604486) + 10;
      sy = this.section(g, lx, sy, pw - 36, I18n.t("TROUBLESHOOTING"));
      sy = this.toggleRow(g, lx, cx, cw, sy, I18n.t("Debug log"), cfg.debugLog, () -> {
         cfg.debugLog = !cfg.debugLog;
         cfg.save();
      });
      g.text(I18n.t("Log file"), lx, sy + 5, -2367775);
      this.button(g, cx, sy, Math.min(cw, 130), 18, I18n.t("Open log folder"), -13092288, -12565943, -2367775, () -> {
         try {
            DebugLog.openFolder();
         } catch (Exception e) {
            State.notice(String.valueOf(e.getMessage()), true);
         }
      });
      sy += 24;
      sy = this.para(g, I18n.t("config/vcraft/debug.log records connections, audio devices and calls, never what anyone says or types. Attach it to bug reports."), lx, sy, pw - 36, -9604486) + 12;
      if (State.isStaff()) {
         sy = this.para(
               g,
               "Admin ("
                  + ("owner".equals(State.myRank) ? "owner" : "mod")
                  + "): /vc admin stats, list, kick, mute, unmute, clear"
                  + ("owner".equals(State.myRank) ? ", ban, unban, rank, delete" : ""),
               lx,
               sy - 8,
               pw - 36,
               -1002958
            )
            + 12;
      }

      g.endScissor();
      this.pageMaxScroll = Math.max(0, sy + this.pageScroll - (this.pageY + this.pageH));
   }

   private int para(Gfx g, String text, int x, int y, int maxW, int color) {
      for (FormattedCharSequence line : this.font.split(Component.literal(text), Math.max(40, maxW))) {
         g.text(line, x, y, color);
         y += 10;
      }

      return y;
   }

   private int section(Gfx g, int x, int y, int sw, String title) {
      g.text(title, x, y, -9604486);
      g.fill(x + g.width(title) + 6, y + 4, sw - g.width(title) - 6, 1, -14276564);
      return y + 14;
   }

   private int toggleRow(Gfx g, int lx, int cx, int cw, int y, String label, boolean on, Runnable toggle) {
      g.text(label, lx, y + 5, -2367775);
      boolean hover = this.in(cx, y + 2, 30, 14);
      g.panel(cx, y + 2, 30, 14, on ? (hover ? -12205973 : -12868260) : (hover ? -9604486 : -11644840));
      g.panel(on ? cx + 18 : cx + 2, y + 4, 10, 10, -1);
      this.hits.add(new VCraftScreen.Hit(cx, y + 2, 30, 14, toggle, null));
      return y + 22;
   }

   private void segment(Gfx g, int x, int y, int sw, String label, boolean sel, Runnable action) {
      boolean hover = this.in(x, y, sw, 18);
      g.panel(x, y, sw, 18, sel ? -11907755 : (hover ? -12565943 : -13092288));
      g.centered(g.clip(label, sw - 6), x + sw / 2, y + 5, sel ? -1 : -7038044);
      this.hits.add(new VCraftScreen.Hit(x, y, sw, 18, action, null));
   }

   private int sliderRow(Gfx g, int lx, int cx, int cw, int y, String label, double value, String shown, DoubleConsumer set) {
      g.text(label, lx, y + 5, -2367775);
      int trackW = cw - 46;
      value = Math.max(0.0, Math.min(1.0, value));
      g.panel(cx, y + 7, trackW, 4, -11644840);
      g.panel(cx, y + 7, (int)((double)trackW * value), 4, -12868260);
      int kx = cx + (int)((double)trackW * value) - 4;
      g.panel(kx, y + 3, 9, 12, -1);
      g.text(shown, cx + trackW + 8, y + 5, -7038044);
      DoubleConsumer setter = v -> set.accept(Math.max(0.0, Math.min(1.0, v)));
      this.hits.add(new VCraftScreen.Hit(cx - 4, y, trackW + 8, 18, () -> {
         this.dragging = setter;
         this.dragX = cx;
         this.dragW = trackW;
         setter.accept((double)(this.mouseX - cx) / (double)trackW);
      }, null));
      return y + 22;
   }

   private void cycleRingtone(List<String> options, int dir) {
      int idx = Math.max(0, options.indexOf(VCraft.config().ringtone));
      idx = (idx + dir + options.size()) % options.size();
      VCraft.config().ringtone = options.get(idx);
      VCraft.config().save();
      if (this.previewing) {
         VCraft.platform().stopRingtone();
         if (!"none".equals(options.get(idx))) {
            VCraft.platform().startRingtone(options.get(idx));
         } else {
            this.previewing = false;
         }
      }
   }

   private void togglePreview() {
      if (!this.previewing && !"none".equals(VCraft.config().ringtone)) {
         VCraft.platform().startRingtone(VCraft.config().ringtone);
         this.previewing = true;
      } else {
         VCraft.platform().stopRingtone();
         this.previewing = false;
      }
   }

   static String discName(String id) {
      if ("none".equals(id)) {
         return I18n.t("None (silent)");
      } else {
         int colon = id.indexOf(58);
         String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
         String path = id.substring(colon + 1).replace("music_disc.", "");
         StringBuilder sb = new StringBuilder();

         for (String part : path.split("[_.]")) {
            if (!part.isEmpty()) {
               if (!sb.isEmpty()) {
                  sb.append(' ');
               }

               sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
         }

         String name = "Music Disc - " + sb;
         return "minecraft".equals(ns) ? name : name + " (" + ns + ")";
      }
   }

   private static final String[] PIN = new String[]{"...###...", "..#####..", "..#####..", "..#####..", ".#######.", "....#....", "....#....", "....#....", "....#...."};
   private static final String[][] KEY_LABELS = new String[][]{
      {"open", "Open VCraft"}, {"push_to_talk", "Push to Talk"}, {"mute", "Toggle Mute"}, {"deafen", "Toggle Deafen"},
      {"accept_call", "Accept Call"}, {"hang_up", "Hang Up / Decline"}, {"compact_hud", "Toggle Compact HUD"}
   };

   private void cycleLanguage(boolean forward) {
      Config cfg = VCraft.config();
      cfg.language = I18n.cycle(cfg.language, forward);
      cfg.save();
      I18n.set("game".equals(cfg.language) ? VCraft.platform().language() : cfg.language);
   }

   private void saveRelay() {
      VCraft.config().serverUrl = this.relayUrl.getValue().trim();
      VCraft.config().save();
      VCraft.relay().reconnect();
      State.notice("Reconnecting to " + VCraft.config().serverUrl, false);
   }

   private void drawDisconnected(Gfx g, int x, int y, int pw, int ph) {
      RelayConnection.Status st = VCraft.relay().status();
      int cx = x + pw / 2;
      int cy = y + ph / 2 - 40;
      g.panel(cx - 22, cy - 30, 44, 44, -13947599);
      Icons.draw(g, Icons.HEADPHONES, cx - 4, cy - 12, st == RelayConnection.Status.DISCONNECTED ? -1228219 : -1002958);

      String title = switch (st) {
         case CONNECTING -> "Connecting to VCraft...";
         case AUTHENTICATING -> "Signing in with your Minecraft account...";
         default -> I18n.t("You're not connected");
      };
      g.centered(title, cx, cy + 24, -1);
      if (st == RelayConnection.Status.DISCONNECTED) {
         String err = VCraft.relay().lastError();
         if (err != null) {
            g.centered(g.clip(err, pw - 40), cx, cy + 38, -7038044);
         }

         g.centered(g.clip("Relay: " + VCraft.config().serverUrl, pw - 40), cx, cy + 50, -9604486);
         this.button(g, cx - 84, cy + 66, 80, 20, I18n.t("Retry"), -12868260, -12205973, -1, () -> VCraft.relay().reconnect());
         this.button(g, cx + 4, cy + 66, 80, 20, I18n.t("Settings"), -13092288, -12565943, -2367775, () -> this.go(VCraftScreen.Page.SETTINGS));
      }
   }

   private void drawNotice(Gfx g, int mainX, int mainW) {
      if (State.notice != null && System.currentTimeMillis() - State.noticeTime <= 5000L) {
         String text = g.clip(State.notice, mainW - 60);
         int nw = g.width(text) + 20;
         int nx = mainX + (mainW - nw) / 2;
         int ny = this.y0 + 40;
         g.panel(nx, ny, nw, 18, State.noticeIsError ? -256558531 : -266043322);
         g.text(text, nx + 10, ny + 5, -1);
      }
   }

   private void openModal(VCraftScreen.Modal m, String subject) {
      this.modal = m;
      this.modalSubject = subject;
      this.menu = null;
      if (m == VCraftScreen.Modal.FRIEND_NOTE) {
         this.setVal(this.nickBox, VCraft.config().nicknames.getOrDefault(subject, ""));
         this.setVal(this.noteBox, VCraft.config().notes.getOrDefault(subject, ""));
         this.focus(this.nickBox);
      }

      if (m == VCraftScreen.Modal.RENAME_GROUP) {
         State.Group gr = State.group(subject);
         this.renameBox.setValue(gr == null ? "" : gr.name());
         this.setFocused(this.renameBox);
      }
   }

   private void drawModal(Gfx g) {
      g.fill(0, 0, this.width, this.height, -1610612736);
      int mw = Math.min(320, this.width - 40);

      int mh = switch (this.modal) {
         case SAFETY -> 128;
         case JOIN_SERVER, DELETE_ACCOUNT -> 112;
         case FRIEND_NOTE -> 150;
         default -> 96;
      };
      int mx = (this.width - mw) / 2;
      int my = (this.height - mh) / 2;
      g.panel(mx, my, mw, mh, -13552840);
      if (this.modal == VCraftScreen.Modal.JOIN_SERVER && this.pendingJoin != null) {
         State.ServerInvite inv = this.pendingJoin;
         g.text("Join " + g.clip(inv.name(), mw - 70) + "?", mx + 16, my + 14, -1);
         this.para(
            g,
            (this.modalSubject == null ? "" : this.modalSubject + " invited you to this server. ")
               + "Only join servers you trust: the server will see your IP address.",
            mx + 16,
            my + 30,
            mw - 32,
            -7038044
         );
         g.panel(mx + 16, my + 58, mw - 32, 18, -14803166);
         g.text(g.clip(inv.address(), mw - 48), mx + 22, my + 63, -1);
         this.button(g, mx + mw - 86, my + mh - 30, 70, 20, "Join", -12868260, -12205973, -1, () -> {
            this.modal = null;
            Mc.setScreen(null);
            VCraft.platform().connectToServer(inv.name(), inv.address());
         });
         this.button(g, mx + mw - 160, my + mh - 30, 66, 20, I18n.t("Cancel"), 0, -12565943, -2367775, () -> this.modal = null);
      } else if (this.modal == VCraftScreen.Modal.DELETE_ACCOUNT) {
         g.text("Delete your VCraft account?", mx + 16, my + 14, -1);
         this.para(
            g,
            "This permanently removes your friends, groups and message history from the relay. Your friends will no longer see you. This can't be undone.",
            mx + 16,
            my + 30,
            mw - 32,
            -7038044
         );
         this.button(g, mx + mw - 126, my + mh - 30, 110, 20, I18n.t("Delete account"), -1228219, -893086, -1, () -> {
            this.modal = null;
            State.deleteAccount();
         });
         this.button(g, mx + mw - 200, my + mh - 30, 66, 20, I18n.t("Cancel"), 0, -12565943, -2367775, () -> this.modal = null);
      } else if (this.modal == VCraftScreen.Modal.FRIEND_NOTE) {
         State.Profile f = State.friend(this.modalSubject);
         String real = f == null ? "" : f.name();
         g.text(I18n.tf("Nickname and note for {0}", real), mx + 16, my + 14, -1);
         g.text(I18n.t("Only you see these."), mx + 16, my + 26, -9604486);
         g.text(I18n.t("NICKNAME"), mx + 16, my + 42, -7038044);
         g.panel(mx + 16, my + 52, mw - 32, 20, -14803166);
         this.placeBox(this.nickBox, mx + 22, my + 58, mw - 44);
         g.text(I18n.t("NOTE"), mx + 16, my + 78, -7038044);
         g.panel(mx + 16, my + 88, mw - 32, 20, -14803166);
         this.placeBox(this.noteBox, mx + 22, my + 94, mw - 44);
         this.button(g, mx + mw - 86, my + mh - 30, 70, 20, I18n.t("Save"), -12868260, -12205973, -1, this::saveNote);
         this.button(g, mx + mw - 160, my + mh - 30, 66, 20, I18n.t("Cancel"), 0, -12565943, -2367775, () -> this.modal = null);
      } else if (this.modal == VCraftScreen.Modal.RENAME_GROUP) {
         g.text(I18n.t("Rename group"), mx + 16, my + 14, -1);
         g.panel(mx + 16, my + 32, mw - 32, 22, -14803166);
         this.placeBox(this.renameBox, mx + 24, my + 39, mw - 48);
         this.button(g, mx + mw - 86, my + mh - 30, 70, 20, I18n.t("Save"), -12868260, -12205973, -1, this::saveRename);
         this.button(g, mx + mw - 160, my + mh - 30, 66, 20, I18n.t("Cancel"), 0, -12565943, -2367775, () -> this.modal = null);
      } else {
         String name = State.convName(this.modalSubject);
         Secure.Trust trust = Secure.trust(this.modalSubject);
         String number = Secure.safetyNumber(this.modalSubject);
         g.text("Verify " + name, mx + 16, my + 14, -1);
         g.text("Compare this number with " + name + " over voice.", mx + 16, my + 30, -7038044);
         g.text("If it matches on both screens, nobody can listen in.", mx + 16, my + 41, -7038044);
         g.panel(mx + 16, my + 56, mw - 32, 24, -14803166);
         g.centered(number == null ? "No key yet" : number, mx + mw / 2, my + 64, trust == Secure.Trust.CHANGED ? -1002958 : -1);
         if (trust == Secure.Trust.CHANGED) {
            g.text("Their key changed since you last talked.", mx + 16, my + 86, -1228219);
            this.button(g, mx + mw - 126, my + mh - 30, 110, 20, "Trust new key", -12868260, -12205973, -1, () -> {
               State.trustNewKey(this.modalSubject);
               State.notice("Now trusting " + name + "'s new key.", false);
               this.modal = null;
            });
         }

         this.button(g, mx + 16, my + mh - 30, 66, 20, "Close", -13092288, -12565943, -2367775, () -> this.modal = null);
      }
   }

   private void saveRename() {
      State.Group gr = State.group(this.modalSubject);
      String name = this.renameBox.getValue().trim();
      if (gr != null && !name.isEmpty()) {
         State.groupAction("group_rename", gr.id(), "name", name);
      }

      this.modal = null;
   }

   private void openMenu(int x, int y, List<VCraftScreen.MenuItem> items) {
      this.menu = items;
      this.menuX = x;
      this.menuY = y;
   }

   private void openStatusMenu(int x, int y) {
      List<VCraftScreen.MenuItem> items = new ArrayList<>();

      for (String s : State.STATUSES) {
         items.add(new VCraftScreen.MenuItem(State.statusLabel(s).replace(" (invisible)", ""), State.statusColor(s), () -> State.setStatus(s)));
      }

      this.openMenu(x, y, items);
   }

   private void openConvMenu(String convId, int x, int y) {
      List<VCraftScreen.MenuItem> items = new ArrayList<>();
      State.Group gr = State.group(convId);
      if (gr != null) {
         items.add(new VCraftScreen.MenuItem(I18n.t("Open chat"), -2367775, () -> this.openChat(convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Call group"), -2367775, () -> State.callGroup(gr)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Invite to this server"), -2367775, () -> State.inviteToServer(convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Add people"), -2367775, () -> this.openAddToGroupMenu(gr, x, y)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Copy invite code"), -2367775, () -> {
            String code = Social.groupCode(convId);
            if (code == null) {
               State.notice(I18n.t("Connect to VCraft first."), true);
            } else {
               VCraft.platform().copy(code);
               State.notice(I18n.t("Invite code copied. Friends paste it on the Friends page."), false);
            }
         }));
         items.add(new VCraftScreen.MenuItem(Social.groupMuted(convId) ? I18n.t("Unmute group") : I18n.t("Mute group"), -2367775, () -> Social.toggleGroupMute(gr.id())));
         items.add(new VCraftScreen.MenuItem(I18n.t("Rename group"), -2367775, () -> this.openModal(VCraftScreen.Modal.RENAME_GROUP, convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Leave group"), -1228219, () -> State.groupAction("group_leave", gr.id(), null, null)));
      } else {
         State.Profile f = State.friend(convId);
         if (f == null) {
            return;
         }

         boolean muted = VCraft.config().mutedUsers.contains(convId);
         items.add(new VCraftScreen.MenuItem(I18n.t("Message"), -2367775, () -> this.openChat(convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Call"), -2367775, () -> State.call(convId)));
         if (Social.friendInCall(convId) && State.call == null) {
            items.add(new VCraftScreen.MenuItem(I18n.t("Ask to join their call"), -12868260, () -> Social.askToJoin(convId)));
         }

         items.add(new VCraftScreen.MenuItem(I18n.t("Nickname and note"), -2367775, () -> this.openModal(VCraftScreen.Modal.FRIEND_NOTE, convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Invite to this server"), -2367775, () -> State.inviteToServer(convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Verify encryption"), -2367775, () -> this.openModal(VCraftScreen.Modal.SAFETY, convId)));
         items.add(new VCraftScreen.MenuItem(muted ? I18n.t("Unmute") : I18n.t("Mute"), -2367775, () -> State.toggleUserMute(convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Remove friend"), -1228219, () -> State.send("friend_remove", "uuid", convId)));
         items.add(new VCraftScreen.MenuItem(I18n.t("Block"), -1228219, () -> State.send("block", "uuid", convId)));
      }

      this.openMenu(x, y, items);
   }

   private void openMemberMenu(State.Group gr, State.Profile p, int x, int y) {
      if (!p.uuid().equals(State.myUuid)) {
         List<VCraftScreen.MenuItem> items = new ArrayList<>();
         boolean friend = State.friend(p.uuid()) != null;
         if (friend) {
            items.add(new VCraftScreen.MenuItem(I18n.t("Message"), -2367775, () -> this.openChat(p.uuid())));
         } else {
            items.add(new VCraftScreen.MenuItem("Add friend", -2367775, () -> State.send("friend_request", "name", p.name())));
         }

         if (p.online()) {
            items.add(new VCraftScreen.MenuItem(I18n.t("Call"), -2367775, () -> State.call(p.uuid())));
         }

         boolean muted = VCraft.config().mutedUsers.contains(p.uuid());
         items.add(new VCraftScreen.MenuItem(muted ? I18n.t("Unmute") : I18n.t("Mute"), -2367775, () -> State.toggleUserMute(p.uuid())));
         if (State.myUuid != null && State.myUuid.equals(gr.owner())) {
            items.add(new VCraftScreen.MenuItem("Remove from group", -1228219, () -> State.groupAction("group_kick", gr.id(), "uuid", p.uuid())));
         }

         items.add(new VCraftScreen.MenuItem(I18n.t("Block"), -1228219, () -> State.send("block", "uuid", p.uuid())));
         this.openMenu(x, y, items);
      }
   }

   private void openAddToGroupMenu(State.Group gr, int x, int y) {
      List<VCraftScreen.MenuItem> items = new ArrayList<>();

      for (State.Profile f : State.friends) {
         if (!gr.members().stream().anyMatch(m -> m.uuid().equals(f.uuid()))) {
            items.add(new VCraftScreen.MenuItem("Add " + f.name(), -2367775, () -> State.groupAction("group_add", gr.id(), "uuid", f.uuid())));
         }
      }

      if (items.isEmpty()) {
         items.add(new VCraftScreen.MenuItem("All your friends are already here", -9604486, null));
      }

      this.openMenu(x, y, items);
   }

   private void header(Gfx g, int x, int y, int hw) {
      g.fill(x, y + 33, hw, 1, -14276564);
   }

   private void button(Gfx g, int x, int y, int bw, int bh, String label, int color, int hoverColor, int textColor, Runnable action) {
      boolean hover = this.in(x, y, bw, bh);
      int c = hover ? hoverColor : color;
      if (c != 0) {
         g.panel(x, y, bw, bh, c);
      }

      g.centered(g.clip(label, bw - 6), x + bw / 2, y + (bh - 8) / 2, textColor);
      this.hits.add(new VCraftScreen.Hit(x, y, bw, bh, action, null));
   }

   private void iconButton(Gfx g, int x, int y, int bw, int bh, String[] icon, int color, int hoverColor, int iconColor, Runnable action) {
      boolean hover = this.in(x, y, bw, bh);
      int c = hover ? hoverColor : color;
      if (c != 0) {
         g.panel(x, y, bw, bh, c);
      }

      Icons.draw(g, icon, x + (bw - 9) / 2, y + (bh - 9) / 2, hover && color == 0 ? -1 : iconColor);
      this.hits.add(new VCraftScreen.Hit(x, y, bw, bh, action, null));
   }

   private void toggleIcon(Gfx g, int x, int y, String[] icon, boolean off, Runnable action) {
      boolean hover = this.in(x, y, 20, 22);
      if (hover) {
         g.panel(x, y, 20, 22, -12565943);
      }

      if (off) {
         Icons.drawStruck(g, icon, x + 6, y + 7, -1228219);
      } else {
         Icons.draw(g, icon, x + 6, y + 7, hover ? -1 : -7038044);
      }

      this.hits.add(new VCraftScreen.Hit(x, y, 20, 22, action, null));
   }

   private void bigToggle(Gfx g, int x, int y, String[] icon, boolean off, Runnable action) {
      boolean hover = this.in(x, y, 40, 32);
      g.panel(x, y, 40, 32, off ? (hover ? -657931 : -2367775) : (hover ? -12565943 : -13092288));
      if (off) {
         Icons.drawStruck(g, icon, x + 16, y + 12, -14803166);
      } else {
         Icons.draw(g, icon, x + 16, y + 12, -1);
      }

      this.hits.add(new VCraftScreen.Hit(x, y, 40, 32, action, null));
   }

   private int rankBadge(Gfx g, int x, int y, String rank) {
      if (rank == null) {
         return 0;
      } else {
         String label = rankLabel(rank);
         int bw = g.width(label) + 6;

         int color = switch (rank) {
            case "owner" -> -1002958;
            case "mod" -> -10983950;
            case "tester" -> -15024996;
            default -> -9604486;
         };
         g.panel(x, y, bw, 10, color);
         g.text(label, x + 3, y + 1, "owner".equals(rank) ? -13947599 : -1);
         return bw + 4;
      }
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
      List<VCraftScreen.MenuItem> items = new ArrayList<>();
      items.add(new VCraftScreen.MenuItem(name, -9604486, null));
      if (State.friend(uuid) != null) {
         items.add(new VCraftScreen.MenuItem(I18n.t("Message"), -2367775, () -> this.openChat(uuid)));
      } else {
         items.add(new VCraftScreen.MenuItem("Add friend", -2367775, () -> State.send("friend_request", "name", name)));
      }

      if (State.isStaff() && !"owner".equals(State.rank(uuid))) {
         items.add(new VCraftScreen.MenuItem("Mute in community", -1002958, () -> State.lobbyMute(uuid, true)));
         items.add(new VCraftScreen.MenuItem("Unmute in community", -2367775, () -> State.lobbyMute(uuid, false)));
      }

      items.add(new VCraftScreen.MenuItem(I18n.t("Block"), -1228219, () -> State.send("block", "uuid", uuid)));
      this.openMenu(x, y, items);
   }

   private void badge(Gfx g, int rightX, int y, int count) {
      String s = count > 99 ? "99+" : String.valueOf(count);
      int bw = Math.max(12, g.width(s) + 6);
      g.panel(rightX - bw, y, bw, 11, -1228219);
      g.centered(s, rightX - bw / 2, y + 2, -1);
   }

   private void avatar(Gfx g, int x, int y, int size, String id, String name, String status) {
      g.face(Skins.get(id), x, y, size);
      if (status != null) {
         int d = size >= 20 ? 8 : 6;
         int dx = x + size - d + 2;
         int dy = y + size - d + 2;
         g.fill(dx - 1, dy - 1, d + 2, d + 2, -13947599);
         g.fill(dx, dy, d, d, State.statusColor(status));
      }
   }

   private void groupAvatar(Gfx g, int x, int y, int size, String name) {
      int color = AVATAR_COLORS[Math.floorMod(name.hashCode() * 31, AVATAR_COLORS.length)];
      g.panel(x, y, size, size, color);
      Icons.draw(g, Icons.PEOPLE, x + (size - 9) / 2, y + (size - 9) / 2, -1);
   }

   // ---- VCraft 2.5 helpers (text boxes, per game version) ----

   private String val(EditBox b) {
      return b.getValue();
   }

   private void setVal(EditBox b, String s) {
      b.setValue(s);
   }

   private void focus(EditBox b) {
      this.setFocused(b);
   }

   private boolean active(EditBox b) {
      return b.isFocused() && b.visible;
   }

   private void hide(EditBox b) {
      b.visible = false;
   }

   private void placeBox(EditBox b, int x, int y, int bw) {
      b.visible = true;
      b.setX(x);
      b.setY(y);
      b.setWidth(Math.max(10, bw));
   }

   private void setPageArea(int x, int y, int pw, int ph) {
      this.pageX = x;
      this.pageY = y;
      this.pageW = pw;
      this.pageH = Math.max(10, ph);
      this.pageScroll = Math.max(0, Math.min(this.pageScroll, this.pageMaxScroll));
   }

   private boolean in(int x, int y, int bw, int bh) {
      return this.mouseX >= x && this.mouseX < x + bw && this.mouseY >= y && this.mouseY < y + bh;
   }

   private static boolean inside(double mx, double my, int x, int y, int bw, int bh) {
      return mx >= (double)x && mx < (double)(x + bw) && my >= (double)y && my < (double)(y + bh);
   }

   private static List<State.Invite> liveInvites() {
      long now = System.currentTimeMillis();
      List<State.Invite> out = new ArrayList<>();

      for (State.Invite i : State.invites.values()) {
         if (i.expires() > now) {
            out.add(i);
         }
      }

      return out;
   }

   private static String time(long ts) {
      ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault());
      LocalDate today = LocalDate.now();
      if (t.toLocalDate().equals(today)) {
         return I18n.tf("Today at {0}", TIME.format(t));
      } else {
         return t.toLocalDate().equals(today.minusDays(1L)) ? I18n.tf("Yesterday at {0}", TIME.format(t)) : DATE.format(t);
      }
   }

   private void go(VCraftScreen.Page p) {
      page = p;
      this.pageScroll = 0;
      this.menu = null;
      if (p != VCraftScreen.Page.CHAT) {
         State.viewing = null;
      }
   }

   private void openChat(String convId) {
      if (!convId.equals(conv)) {
         replyTo = null;
         this.chatScroll = 0;
      }

      conv = convId;
      this.go(VCraftScreen.Page.CHAT);
      State.openConversation(convId);
   }

   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      double mx = event.x();
      double my = event.y();
      boolean right = event.button() == 1;
      if (this.menu != null) {
         for (int i = this.overlayHits.size() - 1; i >= 0; i--) {
            VCraftScreen.Hit hit = this.overlayHits.get(i);
            if (inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
               this.menu = null;
               click();
               hit.left().run();
               return true;
            }
         }

         this.menu = null;
         return true;
      } else {
         for (int ix = this.hits.size() - 1; ix >= 0; ix--) {
            VCraftScreen.Hit hit = this.hits.get(ix);
            if (inside(mx, my, hit.x(), hit.y(), hit.w(), hit.h())) {
               Runnable action = right && hit.right() != null ? hit.right() : hit.left();
               if (action != null) {
                  if (this.dragging == null) {
                     click();
                  }

                  action.run();
                  return true;
               }
            }
         }

         return super.mouseClicked(event, doubleClick);
      }
   }

   public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
      if (this.dragging != null) {
         this.dragging.accept((event.x() - (double)this.dragX) / (double)this.dragW);
         return true;
      } else {
         return super.mouseDragged(event, dx, dy);
      }
   }

   public boolean mouseReleased(MouseButtonEvent event) {
      if (this.dragging != null) {
         this.dragging = null;
         VCraft.config().save();
         return true;
      } else {
         return super.mouseReleased(event);
      }
   }

   public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
      int step = (int)Math.round(vertical * 24.0);
      if (this.menu != null) {
         return true;
      } else {
         if (inside(mx, my, this.sbX, this.sbY, this.sbW, this.sbH)) {
            this.sidebarScroll = Math.max(0, Math.min(this.sidebarMaxScroll, this.sidebarScroll - step));
         } else if (page == VCraftScreen.Page.CHAT && inside(mx, my, this.chatX, this.chatY, this.chatW, this.chatH)) {
            this.chatScroll = Math.max(0, Math.min(this.chatMaxScroll, this.chatScroll + step));
         } else {
            this.pageScroll = Math.max(0, Math.min(this.pageMaxScroll, this.pageScroll - step));
         }

         return true;
      }
   }

   public boolean keyPressed(KeyEvent event) {
      int key = event.key();
      if (key == 256) {
         if (this.menu != null) {
            this.menu = null;
            return true;
         }

         if (this.modal != null) {
            this.modal = null;
            return true;
         }

         if (replyTo != null && page == VCraftScreen.Page.CHAT) {
            replyTo = null;
            return true;
         }
      }

      if (key == 257 || key == 335) {
         if (this.active(this.groupCode)) {
            this.joinWithCode();
            return true;
         }

         if (this.active(this.nickBox) || this.active(this.noteBox)) {
            this.saveNote();
            return true;
         }

         if (this.composer.isFocused() && this.composer.visible) {
            this.sendComposer();
            return true;
         }

         if (this.addFriend.isFocused() && this.addFriend.visible) {
            this.sendFriendRequest();
            return true;
         }

         if (this.relayUrl.isFocused() && this.relayUrl.visible) {
            this.saveRelay();
            return true;
         }

         if (this.groupName.isFocused() && this.groupName.visible) {
            this.createGroup();
            return true;
         }

         if (this.renameBox.isFocused() && this.renameBox.visible) {
            this.saveRename();
            return true;
         }
      }

      return super.keyPressed(event);
   }

   public boolean charTyped(CharacterEvent event) {
      if (page == VCraftScreen.Page.CHAT && this.modal == null && this.composer.visible && !(this.getFocused() instanceof EditBox)) {
         this.setFocused(this.composer);
      }

      return super.charTyped(event);
   }

   private static void click() {
      Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
   }

   public void removed() {
      State.viewing = null;
      if (this.previewing) {
         VCraft.platform().stopRingtone();
      }

      if (VCraft.voice().micTest()) {
         VCraft.voice().setMicTest(false);
      }
   }

   public void onClose() {
      Mc.setScreen(this.parent);
   }

   public boolean isPauseScreen() {
      return false;
   }

   private static record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
   }

   private static record Item(State.Message msg, boolean showHeader, int gap, List<VCraftScreen.Line> lines, int height) {
   }

   private static record Line(FormattedCharSequence text, int color) {
   }

   private static record MenuItem(String label, int color, Runnable action) {
   }

   private static enum Modal {
      RENAME_GROUP,
      SAFETY,
      JOIN_SERVER,
      DELETE_ACCOUNT,
      FRIEND_NOTE;
   }

   private static enum Page {
      FRIENDS,
      CHAT,
      NEW_GROUP,
      CALL,
      SETTINGS,
      ADDON;
   }

   private final class ScreenCanvas extends GfxCanvas {
      int content;

      ScreenCanvas(Gfx g) {
         super(g);
      }

      @Override
      public boolean hovered(int x, int y, int w, int h) {
         return VCraftScreen.this.in(x, y, w, h)
            && VCraftScreen.inside(
               (double)VCraftScreen.this.mouseX,
               (double)VCraftScreen.this.mouseY,
               VCraftScreen.this.pageX,
               VCraftScreen.this.pageY,
               VCraftScreen.this.pageW,
               VCraftScreen.this.pageH
            );
      }

      @Override
      public void tooltip(String text) {
         VCraftScreen.this.tooltip = text;
      }

      @Override
      public void button(int x, int y, int w, int h, String label, Canvas.Style style, Runnable onClick) {
         boolean hover = this.hovered(x, y, w, h);
         int c = color(style, hover);
         if (c != 0) {
            this.g.panel(x, y, w, h, c);
         }

         this.g.centered(this.g.clip(label, w - 6), x + w / 2, y + (h - 8) / 2, style == Canvas.Style.SUBTLE && !hover ? -7038044 : -1);
         this.addHit(x, y, w, h, onClick);
      }

      @Override
      public void iconButton(int x, int y, int size, String[] icon, int color, Runnable onClick) {
         boolean hover = this.hovered(x, y, size, size);
         if (hover) {
            this.g.panel(x, y, size, size, -12565943);
         }

         this.icon(icon, x + (size - 9) / 2, y + (size - 9) / 2, hover ? -1 : color);
         this.addHit(x, y, size, size, onClick);
      }

      @Override
      public void toggle(int x, int y, boolean on, Runnable onClick) {
         boolean hover = this.hovered(x, y, 30, 14);
         this.g.panel(x, y, 30, 14, on ? (hover ? -12205973 : -12868260) : (hover ? -9604486 : -11644840));
         this.g.panel(on ? x + 18 : x + 2, y + 2, 10, 10, -1);
         this.addHit(x, y, 30, 14, onClick);
      }

      private void addHit(int x, int y, int w, int h, Runnable onClick) {
         if (onClick != null) {
            int top = Math.max(y, VCraftScreen.this.pageY);
            int bottom = Math.min(y + h, VCraftScreen.this.pageY + VCraftScreen.this.pageH);
            if (bottom > top) {
               VCraftScreen.this.hits.add(new VCraftScreen.Hit(x, top, w, bottom - top, () -> {
                  try {
                     onClick.run();
                  } catch (Throwable var2x) {
                     VCraft.LOGGER.warn("VCraft addon button failed", var2x);
                  }
               }, null));
            }
         }
      }

      @Override
      public String input(String id, int x, int y, int w, String hint, int maxLength) {
         EditBox b = VCraftScreen.this.addonBoxes
            .computeIfAbsent(
               id, k -> VCraftScreen.this.box(maxLength, hint, VCraftScreen.addonInputs.getOrDefault(k, ""), s -> VCraftScreen.addonInputs.put(k, s))
            );
         b.setHint(Component.literal(hint).withColor(7172730));
         b.setMaxLength(maxLength);
         this.g.panel(x, y, w, 18, -14803166);
         if (y + 4 >= VCraftScreen.this.pageY && y + 14 <= VCraftScreen.this.pageY + VCraftScreen.this.pageH) {
            VCraftScreen.this.placeBox(b, x + 5, y + 5, w - 10);
         }

         return VCraftScreen.addonInputs.getOrDefault(id, "");
      }

      @Override
      public void setInput(String id, String value) {
         VCraftScreen.addonInputs.put(id, value == null ? "" : value);
         EditBox b = VCraftScreen.this.addonBoxes.get(id);
         if (b != null) {
            b.setValue(value == null ? "" : value);
         }
      }

      @Override
      public int scroll() {
         return VCraftScreen.this.pageScroll;
      }

      @Override
      public void contentHeight(int height) {
         this.content = Math.max(this.content, height);
      }

      @Override
      public void clip(int x, int y, int w, int h) {
         int cx = Math.max(x, VCraftScreen.this.pageX);
         int cy = Math.max(y, VCraftScreen.this.pageY);
         int cr = Math.min(x + w, VCraftScreen.this.pageX + VCraftScreen.this.pageW);
         int cb = Math.min(y + h, VCraftScreen.this.pageY + VCraftScreen.this.pageH);
         this.g.scissor(cx, cy, Math.max(0, cr - cx), Math.max(0, cb - cy));
      }

      @Override
      public void endClip() {
         this.g.endScissor();
         this.g.scissor(VCraftScreen.this.pageX, VCraftScreen.this.pageY, VCraftScreen.this.pageW, VCraftScreen.this.pageH);
      }
   }
}
