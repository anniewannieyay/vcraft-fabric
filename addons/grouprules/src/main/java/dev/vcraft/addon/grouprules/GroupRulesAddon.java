package dev.vcraft.addon.grouprules;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import dev.vcraft.api.VCraftAddon;
import dev.vcraft.api.VCraftApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for group chats. Group messages are end-to-end encrypted, so the relay can't read them: the keyword
 * filter and rules text travel signed by the owner and sealed to each member, and every member's client applies
 * them (the sender's client refuses to send, and receivers hide or mask anything that slipped through).
 * Slow mode, admins-only posting, mutes, kicks and accepting the rules are enforced by the relay.
 */
public final class GroupRulesAddon implements VCraftAddon {
    static final String ID = "vcraft-grouprules";
    private static final Logger LOG = LoggerFactory.getLogger("VCraft Group Rules");
    private static final Gson GSON = new Gson();
    static final int MAX_KEYWORDS = 100;

    enum Mode { HIDE, MASK, BLOCK }

    /** What the relay enforces; it can see this part. */
    record Policy(int version, int acceptVersion, int slowSeconds, boolean adminsOnly, boolean requireAccept,
                  Set<String> admins, Map<String, Long> mutes, boolean accepted) {}

    /** What only members can read: signed by the owner. */
    record Content(String group, int version, int acceptVersion, String owner, Mode mode, List<String> keywords, String text, String pub, String sig) {}

    static final class Rules {
        Policy policy;
        Content content;
        String signedJson;
        String problem;
    }

    VCraftApi api;
    private Path dir;
    final Map<String, Rules> rules = new ConcurrentHashMap<>();
    private Map<String, String> ownerKeys = new HashMap<>();
    private final Set<String> fetched = new HashSet<>();
    private final Set<String> refreshed = new HashSet<>();

    @Override
    public void onInitialize(VCraftApi api) {
        this.api = api;
        dir = api.dataDir(ID);
        try {
            Path p = dir.resolve("owner-keys.json");
            if (Files.exists(p)) {
                Map<String, String> m = GSON.fromJson(Files.readString(p), new TypeToken<HashMap<String, String>>() {}.getType());
                if (m != null) ownerKeys = m;
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read owner keys", e);
        }

        api.events().onRelayConnected(() -> {
            fetched.clear();
            refreshed.clear();
        });
        api.events().onTick(this::fetchNewGroups);
        api.relay().on("grule.update", this::onUpdate);
        api.relay().on("grule.need", this::onNeed);
        api.messages().addFilter(this::filter);
        api.messages().addSendCheck(this::checkSend);
        api.ui().addChatButton(new VCraftApi.ChatButton() {
            public String[] icon() {
                return GroupRulesPage.ICON;
            }

            public String tooltip(String conv) {
                Rules r = rules.get(groupId(conv));
                return r != null && r.policy != null && r.policy.requireAccept() && !r.policy.accepted() ? "Group rules: accept them to post" : "Group rules";
            }

            public boolean visible(String conv) {
                return conv != null && conv.startsWith("g:");
            }

            public void click(String conv) {
                api.ui().openPage(ID, conv);
            }
        });
        api.ui().addPage(new GroupRulesPage(this));
    }

    static String groupId(String conv) {
        return conv != null && conv.startsWith("g:") ? conv.substring(2) : null;
    }

    private void fetchNewGroups() {
        if (!api.connected()) return;
        for (VCraftApi.GroupInfo g : api.groups()) {
            if (fetched.add(g.id())) get(g.id(), false);
        }
    }

    void get(String group, boolean refresh) {
        JsonObject o = new JsonObject();
        o.addProperty("group", group);
        if (refresh) o.addProperty("refresh", true);
        api.relay().send("grule.get", o);
    }

    // ---- From the relay ----

    private void onUpdate(JsonObject o) {
        String group = o.get("group").getAsString();
        JsonElement re = o.get("rules");
        if (re == null || re.isJsonNull()) {
            rules.remove(group);
            return;
        }
        JsonObject p = re.getAsJsonObject();
        Set<String> admins = new LinkedHashSet<>();
        for (JsonElement e : p.getAsJsonArray("admins")) admins.add(e.getAsString());
        Map<String, Long> mutes = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("mutes").entrySet()) mutes.put(e.getKey(), e.getValue().getAsLong());
        Policy policy = new Policy(p.get("version").getAsInt(), p.get("acceptVersion").getAsInt(), p.get("slowSeconds").getAsInt(),
                p.get("adminsOnly").getAsBoolean(), p.get("requireAccept").getAsBoolean(), admins, mutes, p.get("accepted").getAsBoolean());
        Rules r = rules.computeIfAbsent(group, k -> new Rules());
        r.policy = policy;
        String blob = p.has("blob") ? p.get("blob").getAsString() : null;
        if (blob == null) {
            if (r.content == null || r.content.version() != policy.version()) r.problem = policy.version() == 0 ? null : "Waiting for a member to share the rules with you...";
            return;
        }
        if (r.content != null && r.content.version() == policy.version()) return;
        String problem = openBlob(group, policy, blob, r);
        r.problem = problem;
        if (problem != null && refreshed.add(group + "|" + policy.version())) get(group, true);
    }

    /** Opens and checks a sealed copy of the rules. Returns a problem, or null if they're genuine. */
    private String openBlob(String group, Policy policy, String blob, Rules r) {
        int split = blob.indexOf(':');
        if (split <= 0) return "The rules couldn't be read.";
        String sealer = blob.substring(0, split);
        String json = api.crypto().open(sealer, "grule|" + group + "|" + policy.version(), blob.substring(split + 1));
        if (json == null) return "The rules couldn't be decrypted. Check the encryption keys of the group's members.";
        Content c;
        try {
            c = parse(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            return "The rules couldn't be read.";
        }
        VCraftApi.GroupInfo g = api.group("g:" + group);
        if (g == null || !c.group().equals(group) || c.version() != policy.version() || !c.owner().equals(g.owner())) {
            return "These rules weren't made by the group's owner.";
        }
        if (!api.signing().verify(c.pub(), signedText(c), c.sig())) return "The rules' signature doesn't match.";
        String pinned = ownerKeys.get(c.owner());
        if (pinned != null && !pinned.equals(c.pub())) return "The owner's signing key changed. Ask them if they moved computers.";
        if (pinned == null) {
            ownerKeys.put(c.owner(), c.pub());
            saveOwnerKeys();
        }
        r.content = c;
        r.signedJson = json;
        return null;
    }

    /** Another member joined after the rules were set: seal our copy for them. The owner's signature still proves who wrote them. */
    private void onNeed(JsonObject o) {
        String group = o.get("group").getAsString();
        String to = o.get("uuid").getAsString();
        Rules r = rules.get(group);
        if (r == null || r.content == null || r.signedJson == null || r.policy == null || r.content.version() != r.policy.version()) return;
        String sealed = api.crypto().seal(to, "grule|" + group + "|" + r.content.version(), r.signedJson);
        if (sealed == null) return;
        JsonObject out = new JsonObject();
        out.addProperty("group", group);
        out.addProperty("to", to);
        out.addProperty("blob", api.myUuid() + ":" + sealed);
        api.relay().send("grule.share", out);
    }

    // ---- Enforcing content rules ----

    private VCraftApi.Verdict filter(VCraftApi.ChatMessage m) {
        Rules r = rules.get(groupId(m.conv()) == null ? "" : groupId(m.conv()));
        if (r == null || r.content == null || r.content.keywords().isEmpty()) return VCraftApi.Verdict.SHOW;
        Pattern p = pattern(r.content.keywords());
        if (!p.matcher(m.text()).find()) return VCraftApi.Verdict.SHOW;
        if (r.content.mode() == Mode.MASK) {
            Matcher mm = p.matcher(m.text());
            StringBuilder sb = new StringBuilder();
            while (mm.find()) mm.appendReplacement(sb, "*".repeat(Math.max(3, mm.group().length())));
            mm.appendTail(sb);
            return VCraftApi.Verdict.replace(sb.toString());
        }
        return VCraftApi.Verdict.HIDE;
    }

    private String checkSend(String conv, String text) {
        String group = groupId(conv);
        if (group == null) return null;
        Rules r = rules.get(group);
        if (r == null) return null;
        if (r.policy != null && r.policy.requireAccept() && !r.policy.accepted() && !api.myUuid().equals(r.content == null ? null : r.content.owner())) {
            api.ui().openPage(ID, conv);
            return "Accept this group's rules before posting.";
        }
        if (r.content == null || r.content.keywords().isEmpty() || r.content.mode() == Mode.MASK) return null;
        Matcher m = pattern(r.content.keywords()).matcher(text);
        if (m.find()) return "Not sent: this group doesn't allow \"" + m.group() + "\".";
        return null;
    }

    private final Map<List<String>, Pattern> patterns = new ConcurrentHashMap<>();

    Pattern pattern(List<String> keywords) {
        return patterns.computeIfAbsent(keywords, k -> {
            List<String> parts = new ArrayList<>();
            for (String w : k) if (!w.isBlank()) parts.add(Pattern.quote(w.trim()));
            return Pattern.compile(parts.isEmpty() ? "(?!)" : String.join("|", parts), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        });
    }

    // ---- Changing rules (owner) ----

    static String signedText(Content c) {
        return "vcraft-grouprules\n" + c.group() + "\n" + c.version() + "\n" + c.acceptVersion() + "\n" + c.owner() + "\n"
                + c.mode() + "\n" + String.join("\u0001", c.keywords()) + "\n" + c.text() + "\n" + c.pub();
    }

    private static JsonObject toJson(Content c) {
        JsonObject o = new JsonObject();
        o.addProperty("group", c.group());
        o.addProperty("version", c.version());
        o.addProperty("acceptVersion", c.acceptVersion());
        o.addProperty("owner", c.owner());
        o.addProperty("mode", c.mode().name());
        JsonArray k = new JsonArray();
        c.keywords().forEach(k::add);
        o.add("keywords", k);
        o.addProperty("text", c.text());
        o.addProperty("pub", c.pub());
        o.addProperty("sig", c.sig());
        return o;
    }

    private static Content parse(JsonObject o) {
        List<String> keywords = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("keywords")) if (keywords.size() < MAX_KEYWORDS) keywords.add(e.getAsString());
        return new Content(o.get("group").getAsString(), o.get("version").getAsInt(), o.get("acceptVersion").getAsInt(),
                o.get("owner").getAsString(), Mode.valueOf(o.get("mode").getAsString()), keywords, o.get("text").getAsString(),
                o.get("pub").getAsString(), o.get("sig").getAsString());
    }

    static List<String> parseKeywords(String typed) {
        List<String> out = new ArrayList<>();
        for (String w : typed.split(",")) {
            String t = w.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && t.length() <= 40 && !out.contains(t) && out.size() < MAX_KEYWORDS) out.add(t);
        }
        return out;
    }

    /** Signs the rules, seals them to every member and sends them with the settings the relay enforces. */
    void save(VCraftApi.GroupInfo g, Mode mode, List<String> keywords, String text, int slowSeconds, boolean adminsOnly,
              boolean requireAccept, Set<String> admins) {
        Rules old = rules.get(g.id());
        int version = (old != null && old.policy != null ? old.policy.version() : 0) + 1;
        boolean sameText = old != null && old.content != null && old.content.text().equals(text);
        int acceptVersion = sameText ? old.content.acceptVersion() : version;
        String pub = api.signing().publicKey();
        Content unsigned = new Content(g.id(), version, acceptVersion, api.myUuid(), mode, keywords, text, pub, "");
        String sig = api.signing().sign(signedText(unsigned));
        if (pub == null || sig == null) {
            api.notice("Couldn't sign the rules.", true);
            return;
        }
        Content c = new Content(g.id(), version, acceptVersion, api.myUuid(), mode, keywords, text, pub, sig);
        String json = toJson(c).toString();
        JsonObject blobs = new JsonObject();
        List<String> missed = new ArrayList<>();
        for (VCraftApi.Member m : g.members()) {
            String sealed = api.crypto().seal(m.uuid(), "grule|" + g.id() + "|" + version, json);
            if (sealed != null) blobs.addProperty(m.uuid(), api.myUuid() + ":" + sealed);
            else missed.add(m.name());
        }
        JsonObject o = new JsonObject();
        o.addProperty("group", g.id());
        o.addProperty("version", version);
        o.addProperty("acceptVersion", acceptVersion);
        o.addProperty("slowSeconds", slowSeconds);
        o.addProperty("adminsOnly", adminsOnly);
        o.addProperty("requireAccept", requireAccept);
        JsonArray a = new JsonArray();
        admins.forEach(a::add);
        o.add("admins", a);
        o.add("blobs", blobs);
        api.relay().send("grule.set", o);
        ownerKeys.put(api.myUuid(), pub);
        saveOwnerKeys();
        api.notice(missed.isEmpty() ? "Group rules saved." : "Saved. " + String.join(", ", missed) + " will get the rules once their encryption key is trusted.", !missed.isEmpty());
    }

    void accept(String group) {
        Rules r = rules.get(group);
        if (r == null || r.policy == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("group", group);
        o.addProperty("version", r.policy.acceptVersion());
        api.relay().send("grule.accept", o);
    }

    void mute(String group, String uuid, int minutes) {
        JsonObject o = new JsonObject();
        o.addProperty("group", group);
        o.addProperty("uuid", uuid);
        o.addProperty("minutes", minutes);
        api.relay().send("grule.mute", o);
    }

    void kick(String group, String uuid) {
        JsonObject o = new JsonObject();
        o.addProperty("group", group);
        o.addProperty("uuid", uuid);
        api.relay().send("grule.kick", o);
    }

    boolean isAdmin(VCraftApi.GroupInfo g, String uuid) {
        if (uuid == null) return false;
        if (uuid.equals(g.owner())) return true;
        Rules r = rules.get(g.id());
        return r != null && r.policy != null && r.policy.admins().contains(uuid);
    }

    private void saveOwnerKeys() {
        try {
            Files.writeString(dir.resolve("owner-keys.json"), GSON.toJson(ownerKeys));
        } catch (Exception e) {
            LOG.warn("Couldn't save owner keys", e);
        }
    }
}
