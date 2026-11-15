package dev.vcraft.relay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class Store {
    static final int HISTORY_LIMIT = 300;

    static final class User {
        String name;
        String key;
        String status = "online";
        long lastSeen;
        Set<String> friends = new LinkedHashSet<>();
        Set<String> incoming = new LinkedHashSet<>();
        Set<String> outgoing = new LinkedHashSet<>();
        Set<String> blocked = new LinkedHashSet<>();
    }

    static final class Group {
        String id;
        String name;
        String owner;
        Set<String> members = new LinkedHashSet<>();
        /** Set by the Group Rules addon; null for ordinary groups. */
        Rules rules;
    }

    /**
     * Group rules. The relay enforces the parts that don't need message content. The keyword filter and rules
     * text are only in {@code blobs}: signed by the owner and sealed to each member, so the relay can't read them.
     */
    static final class Rules {
        int version;
        int acceptVersion;
        int slowSeconds;
        boolean adminsOnly;
        boolean requireAccept;
        Set<String> admins = new LinkedHashSet<>();
        Map<String, Long> mutes = new LinkedHashMap<>();
        Map<String, String> blobs = new LinkedHashMap<>();
        Map<String, Integer> accepted = new LinkedHashMap<>();
    }

    /** Public, signed by the owner who posted it. Not end-to-end encrypted. */
    static final class Announcement {
        String id;
        String author;
        String title;
        String body;
        Long when;
        String serverName;
        String serverAddress;
        long created;
        String pub;
        String sig;
        Set<String> going = new LinkedHashSet<>();
    }

    record Message(String id, String from, String to, String text, long ts) {}

    record LobbyMessage(String id, String from, String text, String replyId, String replyName, String replyText, long ts) {}

    /** The public community chat, plus ranks. Not end-to-end encrypted: anyone can read it. */
    static final class Community {
        Map<String, String> ranks = new LinkedHashMap<>();
        Set<String> muted = new LinkedHashSet<>();
        List<LobbyMessage> messages = new ArrayList<>();
    }

    static final int LOBBY_LIMIT = 500;
    // dufeaver, who runs VCraft.
    private static final String DEFAULT_OWNER = "f5ebd532-199a-463e-83e6-a3e1983d398c";

    /** Group messages hold one ciphertext per recipient. */
    record GroupMessage(String id, String from, String group, Map<String, String> payloads, long ts) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    Map<String, User> users = new HashMap<>();
    Map<String, Group> groups = new LinkedHashMap<>();
    private Map<String, List<Message>> messages = new HashMap<>();
    private Map<String, List<GroupMessage>> groupMessages = new HashMap<>();
    private Set<String> bans = new LinkedHashSet<>();
    Community community = new Community();
    List<Announcement> announcements = new ArrayList<>();
    private boolean dirty;

    Store(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        users = read("users.json", new TypeToken<Map<String, User>>() {}.getType(), users);
        groups = read("groups.json", new TypeToken<LinkedHashMap<String, Group>>() {}.getType(), groups);
        messages = read("messages.json", new TypeToken<Map<String, List<Message>>>() {}.getType(), messages);
        groupMessages = read("group-messages.json", new TypeToken<Map<String, List<GroupMessage>>>() {}.getType(), groupMessages);
        bans = read("bans.json", new TypeToken<LinkedHashSet<String>>() {}.getType(), bans);
        announcements = read("announcements.json", new TypeToken<ArrayList<Announcement>>() {}.getType(), announcements);
        if (Files.exists(dir.resolve("community.json"))) {
            community = read("community.json", Community.class, community);
        } else {
            community.ranks.put(DEFAULT_OWNER, "owner");
            dirty = true;
        }
    }

    private <T> T read(String file, Type type, T fallback) throws IOException {
        Path p = dir.resolve(file);
        if (!Files.exists(p)) return fallback;
        try (Reader r = Files.newBufferedReader(p)) {
            T loaded = GSON.fromJson(r, type);
            return loaded == null ? fallback : loaded;
        }
    }

    User user(String uuid) {
        return users.get(uuid);
    }

    User touch(String uuid, String name) {
        User u = users.computeIfAbsent(uuid, k -> new User());
        if (u.blocked == null) u.blocked = new LinkedHashSet<>();
        if (!name.equals(u.name)) {
            u.name = name;
            dirty = true;
        }
        return u;
    }

    String findUuidByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, User> e : users.entrySet()) {
            if (e.getValue().name != null && e.getValue().name.toLowerCase(Locale.ROOT).equals(lower)) return e.getKey();
        }
        return null;
    }

    String nameOf(String uuid) {
        User u = users.get(uuid);
        return u == null || u.name == null ? "Unknown" : u.name;
    }

    void markDirty() {
        dirty = true;
    }


    static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    Message addMessage(String id, String from, String to, String text) {
        Message m = new Message(id, from, to, text, System.currentTimeMillis());
        List<Message> list = messages.computeIfAbsent(pairKey(from, to), k -> new ArrayList<>());
        list.add(m);
        trim(list);
        dirty = true;
        return m;
    }

    List<Message> history(String a, String b, int limit) {
        List<Message> list = messages.getOrDefault(pairKey(a, b), List.of());
        return list.subList(Math.max(0, list.size() - limit), list.size());
    }

    boolean deleteMessage(String a, String b, String id, String requester) {
        List<Message> list = messages.get(pairKey(a, b));
        if (list == null) return false;
        boolean removed = list.removeIf(m -> m.id().equals(id) && m.from().equals(requester));
        dirty |= removed;
        return removed;
    }


    GroupMessage addGroupMessage(String id, String from, String group, Map<String, String> payloads) {
        GroupMessage m = new GroupMessage(id, from, group, payloads, System.currentTimeMillis());
        List<GroupMessage> list = groupMessages.computeIfAbsent(group, k -> new ArrayList<>());
        list.add(m);
        trim(list);
        dirty = true;
        return m;
    }

    List<GroupMessage> groupHistory(String group, int limit) {
        List<GroupMessage> list = groupMessages.getOrDefault(group, List.of());
        return list.subList(Math.max(0, list.size() - limit), list.size());
    }

    boolean deleteGroupMessage(String group, String id, String requester) {
        List<GroupMessage> list = groupMessages.get(group);
        if (list == null) return false;
        boolean removed = list.removeIf(m -> m.id().equals(id) && m.from().equals(requester));
        dirty |= removed;
        return removed;
    }

    void deleteGroup(String id) {
        groups.remove(id);
        groupMessages.remove(id);
        dirty = true;
    }


    /** Conversation id -> messages received after {@code since}. Groups are keyed "g:<id>". */
    Map<String, Integer> unreadSince(String uuid, long since) {
        Map<String, Integer> out = new HashMap<>();
        User u = users.get(uuid);
        if (u == null) return out;
        for (String friend : u.friends) {
            int n = count(messages.getOrDefault(pairKey(uuid, friend), List.of()), m -> m.to().equals(uuid) && m.ts() > since);
            if (n > 0) out.put(friend, n);
        }
        for (Group g : groups.values()) {
            if (!g.members.contains(uuid)) continue;
            int n = count(groupMessages.getOrDefault(g.id, List.of()), m -> !m.from().equals(uuid) && m.ts() > since);
            if (n > 0) out.put("g:" + g.id, n);
        }
        return out;
    }

    private static <T> int count(List<T> list, Predicate<T> p) {
        int n = 0;
        for (T t : list) if (p.test(t)) n++;
        return n;
    }

    private static void trim(List<?> list) {
        if (list.size() > HISTORY_LIMIT) list.subList(0, list.size() - HISTORY_LIMIT).clear();
    }


    String rank(String uuid) {
        return uuid == null ? null : community.ranks.get(uuid);
    }

    void setRank(String uuid, String rank) {
        if (rank == null) community.ranks.remove(uuid);
        else community.ranks.put(uuid, rank);
        dirty = true;
    }

    LobbyMessage addLobbyMessage(LobbyMessage m) {
        community.messages.add(m);
        if (community.messages.size() > LOBBY_LIMIT) community.messages.subList(0, community.messages.size() - LOBBY_LIMIT).clear();
        dirty = true;
        return m;
    }

    LobbyMessage lobbyMessage(String id) {
        for (LobbyMessage m : community.messages) if (m.id().equals(id)) return m;
        return null;
    }

    boolean deleteLobbyMessage(String id) {
        boolean removed = community.messages.removeIf(m -> m.id().equals(id));
        dirty |= removed;
        return removed;
    }

    List<String> clearLobbyMessagesFrom(String uuid) {
        List<String> ids = new ArrayList<>();
        for (LobbyMessage m : community.messages) if (m.from().equals(uuid)) ids.add(m.id());
        community.messages.removeIf(m -> m.from().equals(uuid));
        if (!ids.isEmpty()) dirty = true;
        return ids;
    }

    boolean isBanned(String uuid) {
        return bans.contains(uuid);
    }

    boolean ban(String uuid) {
        dirty = true;
        return bans.add(uuid);
    }

    boolean unban(String uuid) {
        dirty = true;
        return bans.remove(uuid);
    }

    /** Removes every trace of an account: relations, group membership, and messages it sent or received. */
    void deleteUser(String uuid) {
        users.remove(uuid);
        for (User other : users.values()) {
            other.friends.remove(uuid);
            other.incoming.remove(uuid);
            other.outgoing.remove(uuid);
            other.blocked.remove(uuid);
        }
        messages.keySet().removeIf(k -> k.startsWith(uuid + "|") || k.endsWith("|" + uuid));
        for (Group g : new ArrayList<>(groups.values())) {
            if (!g.members.remove(uuid)) continue;
            if (g.members.isEmpty()) {
                deleteGroup(g.id);
            } else if (uuid.equals(g.owner)) {
                g.owner = g.members.iterator().next();
            }
        }
        for (List<GroupMessage> list : groupMessages.values()) {
            list.removeIf(m -> m.from().equals(uuid));
            for (GroupMessage m : list) m.payloads().remove(uuid);
        }
        community.messages.removeIf(m -> m.from().equals(uuid));
        community.muted.remove(uuid);
        community.ranks.remove(uuid);
        dirty = true;
    }

    int pruneMessagesBefore(long cutoff) {
        int removed = 0;
        for (List<Message> list : messages.values()) {
            int before = list.size();
            list.removeIf(m -> m.ts() < cutoff);
            removed += before - list.size();
        }
        for (List<GroupMessage> list : groupMessages.values()) {
            int before = list.size();
            list.removeIf(m -> m.ts() < cutoff);
            removed += before - list.size();
        }
        int lobbyBefore = community.messages.size();
        community.messages.removeIf(m -> m.ts() < cutoff);
        removed += lobbyBefore - community.messages.size();
        messages.values().removeIf(List::isEmpty);
        if (removed > 0) dirty = true;
        return removed;
    }

    /** Drops accounts that only exist because someone looked their name up and nothing points at them. */
    int pruneOrphans() {
        Set<String> referenced = new HashSet<>();
        for (User u : users.values()) {
            referenced.addAll(u.friends);
            referenced.addAll(u.incoming);
            referenced.addAll(u.outgoing);
            referenced.addAll(u.blocked);
        }
        for (Group g : groups.values()) referenced.addAll(g.members);
        referenced.addAll(community.ranks.keySet());
        int before = users.size();
        users.entrySet().removeIf(e -> e.getValue().key == null && e.getValue().lastSeen == 0 && !referenced.contains(e.getKey()));
        int removed = before - users.size();
        if (removed > 0) dirty = true;
        return removed;
    }

    int messageCount() {
        int n = 0;
        for (List<Message> l : messages.values()) n += l.size();
        for (List<GroupMessage> l : groupMessages.values()) n += l.size();
        return n;
    }

    void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try {
            write("users.json", users);
            write("groups.json", groups);
            write("messages.json", messages);
            write("group-messages.json", groupMessages);
            write("bans.json", bans);
            write("community.json", community);
            write("announcements.json", announcements);
        } catch (IOException e) {
            dirty = true;
            System.err.println("[relay] Failed to save data: " + e);
        }
    }

    private void write(String file, Object data) throws IOException {
        Path target = dir.resolve(file);
        Path tmp = dir.resolve(file + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) {
            GSON.toJson(data, w);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
