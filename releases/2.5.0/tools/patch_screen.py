"""Ports VCraft 2.5's screen features into both decompiled VCraftScreen.java
files (26.3 Mojang names, 1.21.11 intermediary names). The game-specific bits
(EditBox calls) go through small helpers added per version; everything else is
the same text in both files. Fails loudly when a piece doesn't match."""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src" / "dev" / "vcraft" / "mc"
VERSIONS = {
    "v26_3": dict(box="EditBox", get="getValue", set="setValue", focus="setFocused", focused="isFocused", visible="visible"),
    "v1_21_11": dict(box="class_342", get="method_1882", set="method_1852", focus="method_25395", focused="method_25370", visible="field_22764"),
}

def helpers(v):
    b = v["box"]
    return f"""
   // ---- VCraft 2.5 helpers (text boxes, per game version) ----

   private String val({b} b) {{
      return b.{v['get']}();
   }}

   private void setVal({b} b, String s) {{
      b.{v['set']}(s);
   }}

   private void focus({b} b) {{
      this.{v['focus']}(b);
   }}

   private boolean active({b} b) {{
      return b.{v['focused']}() && b.{v['visible']};
   }}

   private void hide({b} b) {{
      b.{v['visible']} = false;
   }}
"""

# (old, new) pairs: the same in both files.
PAIRS = [
    # Fields and boxes.
    ("   private static boolean showMembers = true;\n",
     "   private static boolean showMembers = true;\n   private static boolean showPins;\n"),
    ("      this.renameBox = this.box(32, \"New name\", \"\", null);\n",
     "      this.renameBox = this.box(32, \"New name\", \"\", null);\n"
     "      this.groupCode = this.box(400, I18n.t(\"Paste a group invite code (vc1...)\"), \"\", null);\n"
     "      this.nickBox = this.box(24, \"\", \"\", null);\n"
     "      this.noteBox = this.box(160, I18n.t(\"Anything you want to remember\"), \"\", null);\n"),
    ("      this.hits.clear();\n      this.tooltip = null;\n",
     "      this.hits.clear();\n      this.tooltip = null;\n      this.hide(this.groupCode);\n      this.hide(this.nickBox);\n      this.hide(this.noteBox);\n"),

    # Sidebar: nicknames, muted groups, requests.
    ("      int inviteH = live.isEmpty() ? 0 : 40;\n",
     "      Social.Request request = Social.latestRequest();\n      int inviteH = live.isEmpty() && request == null ? 0 : 40;\n"),
    ("         ly = this.convRow(g, x, ly, sw, f.uuid(), f.name(), f.status(), null, f.uuid());\n",
     "         ly = this.convRow(g, x, ly, sw, f.uuid(), Social.displayName(f.uuid(), f.name()), f.status(), null, f.uuid());\n"),
    ("         ly = this.convRow(g, x, ly, sw, gr.conv(), gr.name(), null, on + \" of \" + gr.members().size() + \" online\", gr.id());\n",
     "         String sub = Social.groupMuted(gr.conv()) ? I18n.t(\"Muted\") : I18n.tf(\"{0} of {1} online\", String.valueOf(on), String.valueOf(gr.members().size()));\n"
     "         ly = this.convRow(g, x, ly, sw, gr.conv(), gr.name(), null, sub, gr.id());\n"),
    ("         this.iconButton(g, x + sw - 28, listBottom + 9, 22, 22, Icons.HANGUP, -1228219, -893086, -1, () -> State.declineInvite(inv));\n      }\n",
     "         this.iconButton(g, x + sw - 28, listBottom + 9, 22, 22, Icons.HANGUP, -1228219, -893086, -1, () -> State.declineInvite(inv));\n"
     "      } else if (request != null) {\n"
     "         g.fill(x, listBottom, sw, inviteH, -14474200);\n"
     "         g.fill(x, listBottom, 2, inviteH, -1002958);\n"
     "         g.text(g.clip(request.name(), sw - 70), x + 8, listBottom + 6, -1);\n"
     "         State.Group rg = \"group\".equals(request.kind()) ? State.group(\"g:\" + request.group()) : null;\n"
     "         String what = rg == null ? I18n.t(\"wants to join your call\") : I18n.tf(\"wants to join {0}\", rg.name());\n"
     "         g.text(g.clip(what, sw - 70), x + 8, listBottom + 18, -7038044);\n"
     "         this.iconButton(g, x + sw - 54, listBottom + 9, 22, 22, Icons.CHECK, -12868260, -12205973, -1, () -> Social.accept(request));\n"
     "         this.iconButton(g, x + sw - 28, listBottom + 9, 22, 22, Icons.CLOSE, -1228219, -893086, -1, () -> Social.dismiss(request));\n"
     "      }\n"),
    ("         String with = call.members().size() <= 1\n            ? \"Waiting for others...\"\n            : call.members().size() - 1 + \" other\" + (call.members().size() == 2 ? \"\" : \"s\") + \" in call\";\n",
     "         String with = call.members().size() <= 1\n            ? I18n.t(\"Waiting for others...\")\n"
     "            : (call.members().size() == 2 ? I18n.t(\"1 other in call\") : I18n.tf(\"{0} others in call\", String.valueOf(call.members().size() - 1)));\n"),
    ("      boolean dim = !group && !\"online\".equals(status) && !\"idle\".equals(status) && !\"dnd\".equals(status);\n",
     "      boolean dim = Social.groupMuted(convId) || !group && !\"online\".equals(status) && !\"idle\".equals(status) && !\"dnd\".equals(status);\n"),

    # Friends page: join a group with a code.
    ("      int listY = fieldY + 40;\n",
     "      int codeY = fieldY + 30;\n"
     "      g.panel(x + 16, codeY, pw - 32, 22, -14803166);\n"
     "      this.placeBox(this.groupCode, x + 24, codeY + 7, pw - 140);\n"
     "      boolean canCode = !this.val(this.groupCode).isBlank();\n"
     "      this.button(g, x + pw - 112, codeY + 2, 92, 18, I18n.t(\"Join Group\"), canCode ? -13092288 : -13803970, canCode ? -12565943 : -13803970, canCode ? -1 : -7038044, this::joinWithCode);\n"
     "      int listY = codeY + 34;\n"),

    # Friend rows: nickname, what they're doing, ask to join, note.
    ("      g.text(p.name(), x + 38, y + 10, -1);\n      this.rankBadge(g, x + 42 + g.width(p.name()), y + 9, State.rank(p.uuid()));\n      int bx = x + rw - 32;\n",
     "      boolean plain = !incoming && !outgoing && !blocked;\n"
     "      String shown = plain ? Social.displayName(p.uuid(), p.name()) : p.name();\n"
     "      g.text(shown, x + 38, y + 10, -1);\n"
     "      int nameW = g.width(shown);\n"
     "      if (!shown.equals(p.name())) {\n"
     "         g.text(\"(\" + p.name() + \")\", x + 42 + nameW, y + 10, -9604486);\n"
     "         nameW += g.width(\"(\" + p.name() + \")\") + 4;\n"
     "      }\n"
     "      this.rankBadge(g, x + 42 + nameW, y + 9, State.rank(p.uuid()));\n"
     "      int bx = x + rw - 32;\n"
     "      boolean joinable = plain && Social.friendInCall(p.uuid()) && State.call == null;\n"
     "      if (plain && hover && Social.note(p.uuid()) != null) {\n"
     "         this.tooltip = Social.note(p.uuid());\n"
     "      }\n"),
    ("            : (outgoing ? \"Outgoing friend request\" : (blocked ? \"Blocked\" : State.statusLabel(p.status()) + trustNote(p.uuid())));\n",
     "            : (outgoing ? \"Outgoing friend request\" : (blocked ? \"Blocked\" : friendLine(p)));\n"),
    ("         this.hits.add(new VCraftScreen.Hit(x, y + 1, rw - 90, 40, () -> this.openChat(p.uuid()), () -> this.openConvMenu(p.uuid(), this.mouseX, this.mouseY)));\n",
     "         if (joinable) {\n"
     "            this.iconButton(g, bx - 84, y + 9, 24, 24, Icons.PLUS, -12868260, -12205973, -1, () -> Social.askToJoin(p.uuid()));\n"
     "            if (this.in(bx - 84, y + 9, 24, 24)) {\n"
     "               this.tooltip = I18n.t(\"Ask to join their call\");\n"
     "            }\n"
     "         }\n\n"
     "         this.hits.add(new VCraftScreen.Hit(x, y + 1, rw - (joinable ? 118 : 90), 40, () -> this.openChat(p.uuid()), () -> this.openConvMenu(p.uuid(), this.mouseX, this.mouseY)));\n"),
    ("   private static String trustNote(String uuid) {\n",
     "   /** Status, then what they're doing or when you last saw them. */\n"
     "   private static String friendLine(State.Profile p) {\n"
     "      if (Secure.trust(p.uuid()) != Secure.Trust.TRUSTED) {\n"
     "         return State.statusLabel(p.status()) + trustNote(p.uuid());\n"
     "      }\n\n"
     "      String d = Social.detail(p.uuid(), p.online());\n"
     "      if (d == null) {\n"
     "         return State.statusLabel(p.status());\n"
     "      }\n\n"
     "      return p.online() ? State.statusLabel(p.status()) + \"  -  \" + d : d;\n"
     "   }\n\n"
     "   private void joinWithCode() {\n"
     "      String code = this.val(this.groupCode).trim();\n"
     "      if (!code.isEmpty()) {\n"
     "         String open = Social.joinWithCode(code);\n"
     "         this.setVal(this.groupCode, \"\");\n"
     "         if (open != null) {\n"
     "            this.openChat(open);\n"
     "         }\n"
     "      }\n"
     "   }\n\n"
     "   private void saveNote() {\n"
     "      if (this.modalSubject != null) {\n"
     "         Social.setNickname(this.modalSubject, this.val(this.nickBox), this.val(this.noteBox));\n"
     "      }\n\n"
     "      this.modal = null;\n"
     "   }\n\n"
     "   private static String trustNote(String uuid) {\n"),
    ("         case CHANGED -> \"  -  key changed, verify!\";\n         case NONE -> \"  -  no encryption key yet\";\n",
     "         case CHANGED -> \"  -  \" + I18n.t(\"key changed, verify!\");\n         case NONE -> \"  -  \" + I18n.t(\"no encryption key yet\");\n"),

    # Chat header: nickname, what they're doing, pins, ask to join.
    ("            g.text(friend.name(), x + 36, y + 9, -1);\n            this.rankBadge(g, x + 40 + g.width(friend.name()), y + 8, State.rank(friend.uuid()));\n",
     "            String shown = Social.displayName(friend.uuid(), friend.name());\n"
     "            g.text(shown, x + 36, y + 9, -1);\n"
     "            if (this.in(x + 36, y + 7, g.width(shown), 12)) {\n"
     "               this.tooltip = Social.note(friend.uuid()) == null ? friend.name() : friend.name() + \": \" + Social.note(friend.uuid());\n"
     "            }\n\n"
     "            this.rankBadge(g, x + 40 + g.width(shown), y + 8, State.rank(friend.uuid()));\n"),
    ("               g.text(State.statusLabel(friend.status()), x + 36, y + 20, -7038044);\n",
     "               g.text(g.clip(friendLine(friend), cw - 220), x + 36, y + 20, -7038044);\n"),
    ("         this.iconButton(g, hx, y + 7, 22, 20, Icons.DOTS, 0, -12565943, -7038044, () -> this.openConvMenu(conv, this.mouseX, this.mouseY + 10));\n",
     "         this.iconButton(g, hx, y + 7, 22, 20, Icons.DOTS, 0, -12565943, -7038044, () -> this.openConvMenu(conv, this.mouseX, this.mouseY + 10));\n"
     "         int pinCount = Social.pins(conv).size();\n"
     "         hx -= 24;\n"
     "         this.iconButton(g, hx, y + 7, 22, 20, PIN, 0, -12565943, showPins ? -1 : (pinCount > 0 ? -2367775 : -9604486), () -> showPins = !showPins);\n"
     "         if (this.in(hx, y + 7, 22, 20)) {\n"
     "            this.tooltip = I18n.tf(\"Your pinned messages ({0})\", String.valueOf(pinCount));\n"
     "         }\n\n"
     "         if (friend != null && Social.friendInCall(friend.uuid()) && State.call == null) {\n"
     "            hx -= 24;\n"
     "            String fu = friend.uuid();\n"
     "            this.iconButton(g, hx, y + 7, 22, 20, Icons.PLUS, -12868260, -12205973, -1, () -> Social.askToJoin(fu));\n"
     "            if (this.in(hx, y + 7, 22, 20)) {\n"
     "               this.tooltip = I18n.t(\"Ask to join their call\");\n"
     "            }\n"
     "         }\n"),
    ("      this.drawMessages(g, x, top, cw, bottom - top);\n   }\n",
     "      this.drawMessages(g, x, top, cw, bottom - top);\n"
     "      if (showPins && !lobby) {\n"
     "         this.drawPins(g, x, top, cw, bottom - top);\n"
     "      }\n"
     "   }\n\n"
     "   /** Your pinned messages for this conversation, over the top of the chat. */\n"
     "   private void drawPins(Gfx g, int x, int top, int cw, int areaH) {\n"
     "      List<Social.Pin> pins = Social.pins(conv);\n"
     "      int ph = Math.min(Math.max(1, pins.size()) * 24 + 22, areaH - 10);\n"
     "      int px = x + 12;\n"
     "      int pw = cw - 24;\n"
     "      g.panel(px - 1, top + 3, pw + 2, ph + 2, -15658476);\n"
     "      g.panel(px, top + 4, pw, ph, -14474200);\n"
     "      this.hits.add(new VCraftScreen.Hit(px, top + 4, pw, ph, () -> {}, null));\n"
     "      Icons.draw(g, PIN, px + 8, top + 10, -7038044);\n"
     "      g.text(I18n.t(\"YOUR PINS\"), px + 22, top + 11, -9604486);\n"
     "      g.text(I18n.t(\"Only you see these.\"), px + 30 + g.width(I18n.t(\"YOUR PINS\")), top + 11, -9604486);\n"
     "      this.iconButton(g, px + pw - 20, top + 7, 16, 14, Icons.CLOSE, 0, -12565943, -7038044, () -> showPins = false);\n"
     "      g.scissor(px, top + 24, pw, ph - 22);\n"
     "      int ry = top + 24;\n"
     "      if (pins.isEmpty()) {\n"
     "         g.text(I18n.t(\"Hover a message and click the pin to keep it here.\"), px + 10, ry + 6, -9604486);\n"
     "      }\n\n"
     "      for (int i = pins.size() - 1; i >= 0; i--) {\n"
     "         Social.Pin p = pins.get(i);\n"
     "         String c = conv;\n"
     "         g.text(p.fromName(), px + 10, ry + 2, -1);\n"
     "         g.text(time(p.ts()), px + 16 + g.width(p.fromName()), ry + 2, -9604486);\n"
     "         g.text(g.clip(p.text(), pw - 50), px + 10, ry + 12, -2367775);\n"
     "         this.iconButton(g, px + pw - 24, ry + 4, 16, 14, Icons.CLOSE, 0, -12565943, -9604486, () -> Social.unpin(c, p.id()));\n"
     "         ry += 24;\n"
     "      }\n\n"
     "      g.endScissor();\n"
     "   }\n"),

    # Messages: nickname and pin mark on the author line, pin in the toolbar.
    ("            g.text(m.fromName(), x + 50, yy + 2, mine ? -8531040 : -1);\n            int after = x + 54 + g.width(m.fromName());\n",
     "            String author = mine ? m.fromName() : Social.displayName(m.from(), m.fromName());\n"
     "            g.text(author, x + 50, yy + 2, mine ? -8531040 : -1);\n"
     "            int after = x + 54 + g.width(author);\n"
     "            if (Social.isPinned(conv, m.id())) {\n"
     "               Icons.draw(g, PIN, after, yy + 1, -1002958);\n"
     "               after += 12;\n"
     "            }\n\n"),
    ("            boolean canMenu = lobbyMsg && !mine;\n            int bw = 24 + (canDelete ? 22 : 0) + (canMenu ? 22 : 0);\n",
     "            boolean canMenu = lobbyMsg && !mine;\n            boolean canPin = !lobbyMsg;\n"
     "            int bw = 24 + (canDelete ? 22 : 0) + (canMenu ? 22 : 0) + (canPin ? 22 : 0);\n"),
    ("            int ax = bx + 24;\n            if (canDelete) {\n",
     "            int ax = bx + 24;\n"
     "            if (canPin) {\n"
     "               boolean pinned = Social.isPinned(conv, m.id());\n"
     "               String c = conv;\n"
     "               this.iconButton(g, ax, by + 2, 20, 16, PIN, 0, -12565943, pinned ? -1002958 : -7038044, () -> Social.togglePin(c, m));\n"
     "               if (this.in(ax, by + 2, 20, 16)) {\n"
     "                  this.tooltip = pinned ? I18n.t(\"Unpin\") : I18n.t(\"Pin for yourself\");\n"
     "               }\n\n"
     "               ax += 22;\n"
     "            }\n\n"
     "            if (canDelete) {\n"),

    # Call page: who asks to join, nicknames.
    ("         g.centered(g.clip(m.name() + (me ? \" (you)\" : \"\"), tileW - 8), tx + tileW / 2, ty + 52, -1);\n",
     "         g.centered(g.clip(me ? m.name() + \" (\" + I18n.t(\"you\") + \")\" : Social.displayName(m.uuid(), m.name()), tileW - 8), tx + tileW / 2, ty + 52, -1);\n"),
    ("      int iy = gy + rowsUsed * (tileH + gap) + 6;\n",
     "      int iy = gy + rowsUsed * (tileH + gap) + 6;\n"
     "      List<Social.Request> asking = new ArrayList<>();\n"
     "      for (Social.Request r : Social.requests) {\n"
     "         if (\"call\".equals(r.kind())) {\n"
     "            asking.add(r);\n"
     "         }\n"
     "      }\n\n"
     "      if (!asking.isEmpty()) {\n"
     "         g.text(I18n.t(\"ASKING TO JOIN\"), x + 16, iy, -1002958);\n"
     "         iy += 14;\n"
     "         for (Social.Request r : asking) {\n"
     "            g.panel(x + 16, iy, pw - 32, 22, -13092288);\n"
     "            this.avatar(g, x + 20, iy + 4, 14, r.from(), r.name(), null);\n"
     "            g.text(r.name(), x + 40, iy + 7, -1);\n"
     "            this.button(g, x + pw - 140, iy + 2, 60, 18, I18n.t(\"Let in\"), -12868260, -12205973, -1, () -> Social.accept(r));\n"
     "            this.button(g, x + pw - 76, iy + 2, 56, 18, I18n.t(\"Ignore\"), 0, -12565943, -2367775, () -> Social.dismiss(r));\n"
     "            iy += 26;\n"
     "         }\n\n"
     "         iy += 4;\n"
     "      }\n\n"),
    ("         int cw = g.width(px.name()) + 34;\n",
     "         String pname = Social.displayName(px.uuid(), px.name());\n         int cw = g.width(pname) + 34;\n"),
    ("         g.text(px.name(), ix + 20, iyy + 6, -2367775);\n",
     "         g.text(pname, ix + 20, iyy + 6, -2367775);\n"),

    # Settings.
    ("      sy = this.section(g, lx, sy, pw - 36, \"CONNECTION\");\n",
     "      g.text(I18n.t(\"Language\"), lx, sy + 5, -2367775);\n"
     "      this.iconButton(g, cx, sy, 18, 18, Icons.LEFT, -13092288, -12565943, -2367775, () -> this.cycleLanguage(false));\n"
     "      g.panel(cx + 20, sy, cw - 42, 18, -14803166);\n"
     "      String lang = \"game\".equals(cfg.language) ? I18n.t(\"Same as the game\") : I18n.name(cfg.language);\n"
     "      g.centered(g.clip(lang, cw - 50), cx + 20 + (cw - 42) / 2, sy + 5, -1);\n"
     "      this.iconButton(g, cx + cw - 20, sy, 18, 18, Icons.RIGHT, -13092288, -12565943, -2367775, () -> this.cycleLanguage(true));\n"
     "      sy += 26;\n"
     "      sy = this.section(g, lx, sy, pw - 36, \"CONNECTION\");\n"),
    ("      g.text(\"Mic test\", lx, sy + 5, -2367775);\n",
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Noise suppression\", cfg.noiseSuppression, () -> {\n"
     "         cfg.noiseSuppression = !cfg.noiseSuppression;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Echo cancellation\", cfg.echoCancellation, () -> {\n"
     "         cfg.echoCancellation = !cfg.echoCancellation;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.para(g, \"Turn this on if you use speakers instead of headphones, so others don't hear themselves.\", lx, sy - 2, pw - 36, -9604486) + 6;\n"
     "      g.text(I18n.t(\"Voice effect\"), lx, sy + 5, -2367775);\n"
     "      this.iconButton(g, cx, sy, 18, 18, Icons.LEFT, -13092288, -12565943, -2367775, () -> {\n"
     "         cfg.voiceEffect = VoiceFx.next(cfg.voiceEffect, false);\n"
     "         cfg.save();\n"
     "      });\n"
     "      g.panel(cx + 20, sy, cw - 42, 18, -14803166);\n"
     "      g.centered(I18n.t(VoiceFx.label(cfg.voiceEffect)), cx + 20 + (cw - 42) / 2, sy + 5, \"none\".equals(cfg.voiceEffect) ? -7038044 : -12868260);\n"
     "      this.iconButton(g, cx + cw - 20, sy, 18, 18, Icons.RIGHT, -13092288, -12565943, -2367775, () -> {\n"
     "         cfg.voiceEffect = VoiceFx.next(cfg.voiceEffect, true);\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy += 24;\n"
     "      g.text(\"Mic test\", lx, sy + 5, -2367775);\n"),
    ("      sy += 26;\n      sy = this.section(g, lx, sy, pw - 36, \"NOTIFICATIONS\");\n",
     "      sy += 26;\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Mute while paused\", cfg.autoMutePaused, () -> {\n"
     "         cfg.autoMutePaused = !cfg.autoMutePaused;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Mute in other windows\", cfg.autoMuteUnfocused, () -> {\n"
     "         cfg.autoMuteUnfocused = !cfg.autoMuteUnfocused;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.section(g, lx, sy + 2, pw - 36, \"CALLS\");\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Rejoin calls after a dropped connection\", cfg.autoRejoin, () -> {\n"
     "         cfg.autoRejoin = !cfg.autoRejoin;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Show friends my server and calls\", cfg.shareServer, () -> {\n"
     "         cfg.shareServer = !cfg.shareServer;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.para(g, \"Friends on VCraft for Horizon see which server you're on and when you're in a call, and can ask to join. End-to-end encrypted; hidden while you're Invisible.\", lx, sy - 2, pw - 36, -9604486) + 8;\n"
     "      sy = this.section(g, lx, sy, pw - 36, \"NOTIFICATIONS\");\n"),
    ("      sy = this.section(g, lx, sy, pw - 36, \"NOW LISTENING\");\n",
     "      sy = this.section(g, lx, sy, pw - 36, \"HUD\");\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Compact call HUD (faces only)\", cfg.hudCompact, () -> {\n"
     "         cfg.hudCompact = !cfg.hudCompact;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Unread badge while playing\", cfg.hudUnread, () -> {\n"
     "         cfg.hudUnread = !cfg.hudUnread;\n"
     "         cfg.save();\n"
     "      });\n"
     "      sy = this.section(g, lx, sy, pw - 36, \"NOW LISTENING\");\n"),
    ("      sy = this.para(g, \"Open VCraft: O    Push to talk: V    Mute: M    Deafen: N\", lx, sy, pw - 36, -7038044);\n",
     "      for (String[] k : KEY_LABELS) {\n"
     "         g.text(I18n.t(k[1]), lx, sy + 3, -2367775);\n"
     "         String bound = VCraft.platform().keyName(k[0]);\n"
     "         boolean unbound = bound == null || bound.isEmpty();\n"
     "         g.panel(cx, sy, Math.min(cw, 120), 14, -14803166);\n"
     "         g.centered(unbound ? I18n.t(\"Not bound\") : bound, cx + Math.min(cw, 120) / 2, sy + 3, unbound ? -9604486 : -1);\n"
     "         sy += 17;\n"
     "      }\n\n"
     "      sy += 2;\n"),
    ("      sy = this.para(g, \"Commands: /vc msg, call, invite, accept, decline, hangup, status, add\", lx, sy, pw - 36, -9604486) + 12;\n",
     "      sy = this.para(g, \"Commands: /vc msg, call, join, invite, accept, decline, hangup, mute, deafen, status, add, nick\", lx, sy, pw - 36, -9604486) + 10;\n"
     "      sy = this.section(g, lx, sy, pw - 36, \"TROUBLESHOOTING\");\n"
     "      sy = this.toggleRow(g, lx, cx, cw, sy, \"Debug log\", cfg.debugLog, () -> {\n"
     "         cfg.debugLog = !cfg.debugLog;\n"
     "         cfg.save();\n"
     "      });\n"
     "      g.text(I18n.t(\"Log file\"), lx, sy + 5, -2367775);\n"
     "      this.button(g, cx, sy, Math.min(cw, 130), 18, I18n.t(\"Open log folder\"), -13092288, -12565943, -2367775, () -> {\n"
     "         try {\n"
     "            java.nio.file.Path dir = DebugLog.path().getParent();\n"
     "            java.nio.file.Files.createDirectories(dir);\n"
     "            net.minecraft.class_156.method_668().method_672(dir.toFile());\n"
     "         } catch (Exception e) {\n"
     "            State.notice(String.valueOf(e.getMessage()), true);\n"
     "         }\n"
     "      });\n"
     "      sy += 24;\n"
     "      sy = this.para(g, \"config/vcraft/debug.log records connections, audio devices and calls, never what anyone says or types. Attach it to bug reports.\", lx, sy, pw - 36, -9604486) + 12;\n"),

    # Modal: nickname and note.
    ("         case JOIN_SERVER, DELETE_ACCOUNT -> 112;\n",
     "         case JOIN_SERVER, DELETE_ACCOUNT -> 112;\n         case FRIEND_NOTE -> 150;\n"),
    ("      } else if (this.modal == VCraftScreen.Modal.RENAME_GROUP) {\n",
     "      } else if (this.modal == VCraftScreen.Modal.FRIEND_NOTE) {\n"
     "         State.Profile f = State.friend(this.modalSubject);\n"
     "         String real = f == null ? \"\" : f.name();\n"
     "         g.text(I18n.tf(\"Nickname and note for {0}\", real), mx + 16, my + 14, -1);\n"
     "         g.text(I18n.t(\"Only you see these.\"), mx + 16, my + 26, -9604486);\n"
     "         g.text(I18n.t(\"NICKNAME\"), mx + 16, my + 42, -7038044);\n"
     "         g.panel(mx + 16, my + 52, mw - 32, 20, -14803166);\n"
     "         this.placeBox(this.nickBox, mx + 22, my + 58, mw - 44);\n"
     "         g.text(I18n.t(\"NOTE\"), mx + 16, my + 78, -7038044);\n"
     "         g.panel(mx + 16, my + 88, mw - 32, 20, -14803166);\n"
     "         this.placeBox(this.noteBox, mx + 22, my + 94, mw - 44);\n"
     "         this.button(g, mx + mw - 86, my + mh - 30, 70, 20, \"Save\", -12868260, -12205973, -1, this::saveNote);\n"
     "         this.button(g, mx + mw - 160, my + mh - 30, 66, 20, \"Cancel\", 0, -12565943, -2367775, () -> this.modal = null);\n"
     "      } else if (this.modal == VCraftScreen.Modal.RENAME_GROUP) {\n"),
    ("      DELETE_ACCOUNT;\n",
     "      DELETE_ACCOUNT,\n      FRIEND_NOTE;\n"),

    # Menus.
    ("         items.add(new VCraftScreen.MenuItem(\"Add people\", -2367775, () -> this.openAddToGroupMenu(gr, x, y)));\n",
     "         items.add(new VCraftScreen.MenuItem(\"Add people\", -2367775, () -> this.openAddToGroupMenu(gr, x, y)));\n"
     "         items.add(new VCraftScreen.MenuItem(\"Copy invite code\", -2367775, () -> {\n"
     "            String code = Social.groupCode(convId);\n"
     "            if (code == null) {\n"
     "               State.notice(I18n.t(\"Connect to VCraft first.\"), true);\n"
     "            } else {\n"
     "               VCraft.platform().copy(code);\n"
     "               State.notice(I18n.t(\"Invite code copied. Friends paste it on the Friends page.\"), false);\n"
     "            }\n"
     "         }));\n"
     "         items.add(new VCraftScreen.MenuItem(Social.groupMuted(convId) ? \"Unmute group\" : \"Mute group\", -2367775, () -> Social.toggleGroupMute(gr.id())));\n"),
    ("         items.add(new VCraftScreen.MenuItem(\"Call\", -2367775, () -> State.call(convId)));\n",
     "         items.add(new VCraftScreen.MenuItem(\"Call\", -2367775, () -> State.call(convId)));\n"
     "         if (Social.friendInCall(convId) && State.call == null) {\n"
     "            items.add(new VCraftScreen.MenuItem(\"Ask to join their call\", -12868260, () -> Social.askToJoin(convId)));\n"
     "         }\n\n"
     "         items.add(new VCraftScreen.MenuItem(\"Nickname and note\", -2367775, () -> this.openModal(VCraftScreen.Modal.FRIEND_NOTE, convId)));\n"),
    ("      if (m == VCraftScreen.Modal.RENAME_GROUP) {\n",
     "      if (m == VCraftScreen.Modal.FRIEND_NOTE) {\n"
     "         this.setVal(this.nickBox, VCraft.config().nicknames.getOrDefault(subject, \"\"));\n"
     "         this.setVal(this.noteBox, VCraft.config().notes.getOrDefault(subject, \"\"));\n"
     "         this.focus(this.nickBox);\n"
     "      }\n\n"
     "      if (m == VCraftScreen.Modal.RENAME_GROUP) {\n"),

    # Time stamps.
    ("         return \"Today at \" + TIME.format(t);\n",
     "         return I18n.tf(\"Today at {0}\", TIME.format(t));\n"),
    ("         return t.toLocalDate().equals(today.minusDays(1L)) ? \"Yesterday at \" + TIME.format(t) : DATE.format(t);\n",
     "         return t.toLocalDate().equals(today.minusDays(1L)) ? I18n.tf(\"Yesterday at {0}\", TIME.format(t)) : DATE.format(t);\n"),

    # Settings: language cycle + key labels + pin icon.
    ("   private void saveRelay() {\n",
     "   private static final String[] PIN = new String[]{\"...###...\", \"..#####..\", \"..#####..\", \"..#####..\", \".#######.\", \"....#....\", \"....#....\", \"....#....\", \"....#....\"};\n"
     "   private static final String[][] KEY_LABELS = new String[][]{\n"
     "      {\"open\", \"Open VCraft\"}, {\"push_to_talk\", \"Push to Talk\"}, {\"mute\", \"Toggle Mute\"}, {\"deafen\", \"Toggle Deafen\"},\n"
     "      {\"accept_call\", \"Accept Call\"}, {\"hang_up\", \"Hang Up / Decline\"}, {\"compact_hud\", \"Toggle Compact HUD\"}\n"
     "   };\n\n"
     "   private void cycleLanguage(boolean forward) {\n"
     "      Config cfg = VCraft.config();\n"
     "      cfg.language = I18n.cycle(cfg.language, forward);\n"
     "      cfg.save();\n"
     "      I18n.set(\"game\".equals(cfg.language) ? VCraft.platform().language() : cfg.language);\n"
     "   }\n\n"
     "   private void saveRelay() {\n"),
]

# The Open-log-folder call uses Minecraft's Util: per version.
UTIL = {"v26_3": "net.minecraft.util.Util.getPlatform().openFile(dir.toFile());",
        "v1_21_11": "net.minecraft.class_156.method_668().method_672(dir.toFile());"}

IMPORTS = "import dev.vcraft.core.DebugLog;\nimport dev.vcraft.core.I18n;\nimport dev.vcraft.core.Social;\nimport dev.vcraft.core.VoiceFx;\n"

def load_keys():
    i18n = (ROOT.parent / "core" / "I18n.java").read_text(encoding="utf-8")
    return set(re.findall(r'^\s*r\("((?:[^"\\]|\\.)*)",', i18n, re.M))

def wrap_literals(text, keys):
    """Wraps UI string literals that have a translation in I18n.t(...)."""
    out, n = [], 0
    for line in text.split("\n"):
        s = line.lstrip()
        if s.startswith(("case ", "import ", "//", "*", "@", "{\"")) or "new String[][]" in line:
            out.append(line)
            continue
        def rep(m):
            nonlocal n
            body = m.group(1)
            start = m.start()
            before = line[max(0, start - 8):start]
            if body in keys and not before.endswith(("I18n.t(", "I18n.tf(", ".equals(")):
                n += 1
                return f'I18n.t("{body}")'
            return m.group(0)
        out.append(re.sub(r'(?<![\w\\])"((?:[^"\\]|\\.)*)"', rep, line))
    return "\n".join(out), n

def main():
    keys = load_keys()
    failed = False
    for ver, v in VERSIONS.items():
        f = ROOT / ver / "ui" / "VCraftScreen.java"
        t = f.read_text(encoding="utf-8").replace("\r\n", "\n")
        if "Social.displayName" in t:
            print(ver, "already patched"); continue
        for old, new in PAIRS:
            new = new.replace("net.minecraft.class_156.method_668().method_672(dir.toFile());", UTIL[ver])
            c = t.count(old)
            if c != 1:
                print(f"{ver}: expected 1 match, found {c}:\n{old[:160]!r}")
                failed = True
                continue
            t = t.replace(old, new)
        b = v["box"]
        t = t.replace(f"   private {b} renameBox;\n", f"   private {b} renameBox;\n   private {b} groupCode;\n   private {b} nickBox;\n   private {b} noteBox;\n", 1)
        # Enter in the new boxes.
        t = t.replace("      if (key == 257 || key == 335) {\n",
                      "      if (key == 257 || key == 335) {\n"
                      "         if (this.active(this.groupCode)) {\n            this.joinWithCode();\n            return true;\n         }\n\n"
                      "         if (this.active(this.nickBox) || this.active(this.noteBox)) {\n            this.saveNote();\n            return true;\n         }\n\n", 1)
        # Helpers after placeBox.
        i = t.index("   private void placeBox(")
        t = t[:i] + helpers(v).lstrip("\n") + "\n" + t[i:]
        t = t.replace("import dev.vcraft.core.Config;\n", "import dev.vcraft.core.Config;\n" + IMPORTS, 1)
        t, n = wrap_literals(t, keys)
        f.write_text(t, encoding="utf-8")
        print(f"{ver}: patched, {n} strings translated")
    sys.exit(1 if failed else 0)

main()
