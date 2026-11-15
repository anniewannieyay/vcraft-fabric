package dev.vcraft.mc;

import com.mojang.authlib.GameProfile;
import dev.vcraft.core.VCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Player skins by uuid, fetched once in the background. Falls back to the default skin. */
public final class Skins {
    private static final Map<String, PlayerSkin> loaded = new ConcurrentHashMap<>();
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();
    private static final ExecutorService fetcher = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "VCraft Skins");
        t.setDaemon(true);
        return t;
    });

    public static PlayerSkin get(String uuid) {
        PlayerSkin skin = loaded.get(uuid);
        if (skin != null) return skin;
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException | NullPointerException e) {
            return DefaultPlayerSkin.getDefaultSkin();
        }
        if (requested.add(uuid)) load(uuid, id);
        return DefaultPlayerSkin.get(id);
    }

    private static void load(String key, UUID id) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
                    var result = mc.services().sessionService().fetchProfile(id, false);
                    return result == null ? null : result.profile();
                }, fetcher)
                .thenCompose(profile -> profile == null
                        ? CompletableFuture.completedFuture(java.util.Optional.<PlayerSkin>empty())
                        : mc.getSkinManager().get((GameProfile) profile))
                .thenAccept(skin -> skin.ifPresent(s -> loaded.put(key, s)))
                .exceptionally(e -> {
                    VCraft.LOGGER.debug("No skin for {}", id, e);
                    return null;
                });
    }

    private Skins() {
    }
}
