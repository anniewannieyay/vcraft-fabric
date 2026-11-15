"""Ports VCraft 2.5's game-side pieces into both versions: new keys and
auto-mute (VCraftMc), what the platform reports (McPlatform), the HUD
(CallHud) and /vc join and nick (Commands). Copies each file from dec23,
then patches it; fails loudly on a miss."""
import pathlib, shutil, sys

HERE = pathlib.Path(__file__).resolve().parent.parent
DEC = HERE.parent / "dec23" / "dev" / "vcraft" / "mc"
SRC = HERE / "src" / "dev" / "vcraft" / "mc"

N = {
    "v26_3": dict(
        Key="KeyMapping", consume="consumeClick", unbound="isUnbound", keyMsg="getTranslatedKeyMessage",
        level="level", screen="screen", Pause="net.minecraft.client.gui.screens.PauseScreen", active="isWindowActive",
        lang="options.languageCode", clip="keyboardHandler.setClipboard",
        Cmd="ClientCommands", lit="Component.literal",
        hudSig="static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {"),
    "v1_21_11": dict(
        Key="class_304", consume="method_1436", unbound="method_1415", keyMsg="method_16007",
        level="field_1687", screen="field_1755", Pause="net.minecraft.class_433", active="method_1569",
        lang="field_1690.field_1883", clip="field_1774.method_1455",
        Cmd="ClientCommandManager", lit="class_2561.method_43470",
        hudSig="static void draw(class_332 graphics, class_9779 delta) {"),
}

def sub(t, old, new, what):
    c = t.count(old)
    if c != 1:
        raise SystemExit(f"{what}: expected 1 match, found {c}: {old[:120]!r}")
    return t.replace(old, new)

def vcraftmc(ver, n, t):
    K = n["Key"]
    t = sub(t, f"   public static {K} deafenKey;\n",
            f"   public static {K} deafenKey;\n   public static {K} acceptKey;\n   public static {K} hangUpKey;\n   public static {K} compactKey;\n\n"
            f"   /** One of VCraft's keys by name (the settings page lists them). */\n"
            f"   public static {K} key(String name) {{\n"
            f"      return switch (name) {{\n"
            f"         case \"open\" -> openKey;\n         case \"push_to_talk\" -> pushToTalkKey;\n         case \"mute\" -> muteKey;\n"
            f"         case \"deafen\" -> deafenKey;\n         case \"accept_call\" -> acceptKey;\n         case \"hang_up\" -> hangUpKey;\n"
            f"         case \"compact_hud\" -> compactKey;\n         default -> null;\n      }};\n   }}\n", "fields")
    t = sub(t, f"      deafenKey = Mc.registerKey(new {K}(\"key.vcraft.deafen\", 78, category));\n",
            f"      deafenKey = Mc.registerKey(new {K}(\"key.vcraft.deafen\", 78, category));\n"
            f"      acceptKey = Mc.registerKey(new {K}(\"key.vcraft.accept_call\", -1, category));\n"
            f"      hangUpKey = Mc.registerKey(new {K}(\"key.vcraft.hang_up\", -1, category));\n"
            f"      compactKey = Mc.registerKey(new {K}(\"key.vcraft.compact_hud\", -1, category));\n", "keys")
    c = n["consume"]
    t = sub(t, "         VCraft.voice().setPushToTalkHeld(",
            f"         while (acceptKey.{c}()) {{\n"
            f"            State.Invite inv = State.latestInvite();\n"
            f"            if (inv != null) {{\n               State.acceptInvite(inv);\n            }}\n         }}\n\n"
            f"         while (hangUpKey.{c}()) {{\n"
            f"            State.Invite inv = State.latestInvite();\n"
            f"            if (inv != null) {{\n               State.declineInvite(inv);\n            }} else if (State.call != null) {{\n"
            f"               State.send(\"call_leave\", null, null);\n            }}\n         }}\n\n"
            f"         while (compactKey.{c}()) {{\n"
            f"            VCraft.config().hudCompact = !VCraft.config().hudCompact;\n            VCraft.config().save();\n         }}\n\n"
            f"         dev.vcraft.core.Config cfg = VCraft.config();\n"
            f"         if (\"game\".equals(cfg.language)) {{\n            dev.vcraft.core.I18n.set(VCraft.platform().language());\n         }}\n\n"
            f"         State.setAutoMuted(cfg.autoMutePaused && VCraft.platform().paused() || cfg.autoMuteUnfocused && !VCraft.platform().focused());\n"
            f"         VCraft.voice().setPushToTalkHeld(", "tick")
    return t

def mcplatform(ver, n, t):
    K = n["Key"]
    i = t.rindex("}")
    extra = (
        f"\n   @Override\n   public boolean inWorld() {{\n      return mc().{n['level']} != null;\n   }}\n"
        f"\n   @Override\n   public boolean paused() {{\n      return Mc.screen() instanceof {n['Pause']};\n   }}\n"
        f"\n   @Override\n   public boolean focused() {{\n      return mc().{n['active']}();\n   }}\n"
        # Null-safe: VCraft starts before Minecraft has loaded its options.
        f"\n   @Override\n   public String language() {{\n"
        f"      try {{\n         String code = mc().{n['lang']};\n         return code == null ? \"en_us\" : code;\n"
        f"      }} catch (NullPointerException e) {{\n         return \"en_us\";\n      }}\n   }}\n"
        f"\n   @Override\n   public String keyName(String key) {{\n"
        f"      {K} k = VCraftMc.key(key);\n"
        f"      return k == null || k.{n['unbound']}() ? \"\" : k.{n['keyMsg']}().getString();\n   }}\n"
        f"\n   @Override\n   public void copy(String text) {{\n      mc().{n['clip']}(text);\n   }}\n")
    t = t[:i].rstrip() + "\n" + extra + "}\n"
    if ver == "v26_3":
        t = sub(t, "import net.minecraft.client.Minecraft;\n", "import net.minecraft.client.KeyMapping;\nimport net.minecraft.client.Minecraft;\n", "import")
    else:
        t = sub(t, "import net.minecraft.class_2960;\n", "import net.minecraft.class_2960;\nimport net.minecraft.class_304;\n", "import")
    # The decompiler's null check before the anonymous class's fields.
    t = t.replace("      {\n         Objects.requireNonNull(McPlatform.this);\n      }\n\n", "")
    return t

def callhud(ver, n, t):
    t = sub(t, "import dev.vcraft.core.AddonHost;\n", "import dev.vcraft.core.AddonHost;\nimport dev.vcraft.core.I18n;\nimport dev.vcraft.core.Social;\n", "import")
    t = sub(t, "         if (State.deafened || State.muted) {\n            String s = State.deafened ? \"Deafened\" : \"Muted\";\n",
            "         if (State.deafened || State.muted || State.autoMuted) {\n"
            "            String s = State.deafened\n               ? I18n.t(\"Deafened\")\n"
            "               : (State.muted ? I18n.t(\"Muted\") : (VCraft.platform().paused() ? I18n.t(\"Muted while paused\") : I18n.t(\"Muted while in another window\")));\n", "muted")
    t = sub(t, "         if (call != null) {\n            y = drawCall(g, call, x, y);\n         }\n",
            "         if (call != null) {\n            y = VCraft.config().hudCompact ? drawCompact(g, call, x, y) : drawCall(g, call, x, y);\n         }\n\n"
            "         if (VCraft.config().hudUnread && !(Mc.screen() instanceof " + ("dev.vcraft.mc." + ver + ".ui.VCraftScreen") + ")) {\n"
            "            int unread = Social.unreadTotal();\n"
            "            if (unread > 0) {\n"
            "               String s = unread == 1 ? I18n.t(\"1 unread message\") : I18n.tf(\"{0} unread messages\", String.valueOf(Math.min(unread, 999)));\n"
            "               g.panel(x - 2, y - 2, g.width(s) + 16, 13, -1610612736);\n"
            "               g.fill(x, y + 2, 5, 5, -1228219);\n"
            "               g.text(s, x + 10, y, -1);\n"
            "               y += 15;\n"
            "            }\n         }\n\n"
            "         Social.Request r = Social.latestRequest();\n"
            "         if (r != null) {\n"
            "            String s = \"call\".equals(r.kind()) ? I18n.tf(\"{0} wants to join your call\", r.name()) : I18n.tf(\"{0} wants to join a group\", r.name());\n"
            "            g.panel(x - 2, y - 2, g.width(s) + 6, 13, -1610612736);\n"
            "            g.text(s, x + 1, y, -1002958);\n"
            "            y += 15;\n"
            "         }\n", "call")
    t = sub(t, "         String tag = m.deafened() ? \"  deafened\" : (m.muted() ? \"  muted\" : (mutedForMe ? \"  muted for you\" : \"\"));\n         int w = g.width(m.name()) + g.width(tag) + 18;\n",
            "         String tag = m.deafened() ? \"  \" + I18n.t(\"deafened\") : (m.muted() ? \"  \" + I18n.t(\"muted\") : (mutedForMe ? \"  \" + I18n.t(\"muted for you\") : \"\"));\n"
            "         String name = Social.displayName(m.uuid(), m.name());\n"
            "         int w = g.width(name) + g.width(tag) + 18;\n", "tag")
    t = sub(t, "         g.text(m.name(), x + 13, y, speaking ? -1 : -3880498);\n         g.text(tag, x + 13 + g.width(m.name()), y, -7038044);\n",
            "         g.text(name, x + 13, y, speaking ? -1 : -3880498);\n         g.text(tag, x + 13 + g.width(name), y, -7038044);\n", "names")
    t = sub(t, "         String line = \"Ringing \" + p.name() + \"...\";\n",
            "         String line = I18n.tf(\"Ringing {0}...\", Social.displayName(p.uuid(), p.name()));\n", "ringing")
    t = sub(t, "   private CallHud() {\n",
            "   /** The call as a row of faces: green ring while talking, red corner when muted. */\n"
            "   private static int drawCompact(Gfx g, State.Call call, int x, int y) {\n"
            "      int n = call.members().size() + call.ringing().size();\n"
            "      g.panel(x - 2, y - 2, n * 14 + 2, 15, -1610612736);\n"
            "      int fx = x;\n"
            "      for (State.CallMember m : call.members()) {\n"
            "         if (State.isSpeaking(m.uuid())) {\n            g.outline(fx - 1, y - 1, 13, 13, -12868260);\n         }\n\n"
            "         g.face(Skins.get(m.uuid()), fx, y, 11);\n"
            "         if (m.muted() || m.deafened() || VCraft.config().mutedUsers.contains(m.uuid())) {\n"
            "            g.fill(fx + 8, y + 8, 4, 4, -1228219);\n         }\n\n"
            "         fx += 14;\n      }\n\n"
            "      for (State.Profile p : call.ringing()) {\n"
            "         g.face(Skins.get(p.uuid()), fx, y, 11);\n"
            "         g.fill(fx, y, 11, 11, -1876745176);\n"
            "         fx += 14;\n      }\n\n"
            "      return y + 17;\n   }\n\n"
            "   private CallHud() {\n", "compact")
    return t

def commands(ver, n, t):
    C, L = n["Cmd"], n["lit"]
    t = sub(t, "   private static State.Profile friend(CommandContext<FabricClientCommandSource> ctx) {\n",
            "   /** /vc join and /vc nick (2.5.0): merged into /vc by Brigadier. */\n"
            "   static void register25(CommandDispatcher<FabricClientCommandSource> dispatcher) {\n"
            f"      dispatcher.register({C}.literal(\"vc\")\n"
            f"         .then({C}.literal(\"join\").then({C}.argument(\"friend\", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> {{\n"
            "            State.Profile f = friend(ctx);\n"
            "            if (f != null) {\n               dev.vcraft.core.Social.askToJoin(f.uuid());\n            }\n\n"
            "            return 1;\n         })))\n"
            f"         .then({C}.literal(\"nick\").then({C}.argument(\"friend\", StringArgumentType.word()).suggests(FRIENDS).executes(ctx -> nick(ctx, \"\"))\n"
            f"            .then({C}.argument(\"nickname\", StringArgumentType.greedyString()).executes(ctx -> nick(ctx, StringArgumentType.getString(ctx, \"nickname\")))))));\n"
            "   }\n\n"
            "   private static int nick(CommandContext<FabricClientCommandSource> ctx, String nick) {\n"
            "      State.Profile f = friend(ctx);\n"
            "      if (f != null) {\n"
            "         String note = dev.vcraft.core.VCraft.config().notes.getOrDefault(f.uuid(), \"\");\n"
            "         dev.vcraft.core.Social.setNickname(f.uuid(), nick, note);\n"
            "         String msg = nick.isBlank() ? dev.vcraft.core.I18n.tf(\"Cleared your nickname for {0}.\", f.name()) : dev.vcraft.core.I18n.tf(\"{0} is now {1} for you.\", f.name(), nick.trim());\n"
            f"         ((FabricClientCommandSource)ctx.getSource()).sendFeedback({L}(msg));\n"
            "      }\n\n"
            "      return 1;\n"
            "   }\n\n"
            "   private static State.Profile friend(CommandContext<FabricClientCommandSource> ctx) {\n", "register25")
    # Nicknames work as names too.
    t = sub(t, "      State.Profile f = State.friendByName(name);\n",
            "      State.Profile f = State.friendByName(name);\n"
            "      if (f == null) {\n"
            "         for (State.Profile p : State.friends) {\n"
            "            String nn = dev.vcraft.core.VCraft.config().nicknames.get(p.uuid());\n"
            "            if (nn != null && nn.equalsIgnoreCase(name)) {\n               f = p;\n            }\n"
            "         }\n      }\n\n", "nickname lookup")
    return t

def vcraftmc_register(t):
    return sub(t, "(dispatcher, context) -> Commands.register(dispatcher));",
               "(dispatcher, context) -> {\n         Commands.register(dispatcher);\n         Commands.register25(dispatcher);\n      });", "register call")

for ver, n in N.items():
    (SRC / ver).mkdir(parents=True, exist_ok=True)
    for name, fn in [("VCraftMc", vcraftmc), ("McPlatform", mcplatform), ("CallHud", callhud), ("Commands", commands)]:
        t = (DEC / ver / f"{name}.java").read_text(encoding="utf-8").replace("\r\n", "\n")
        t = fn(ver, n, t)
        if name == "VCraftMc":
            t = vcraftmc_register(t)
        (SRC / ver / f"{name}.java").write_text(t, encoding="utf-8", newline="\n")
        print(ver, name, "ok")
