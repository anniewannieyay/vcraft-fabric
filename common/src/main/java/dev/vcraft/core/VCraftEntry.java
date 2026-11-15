package dev.vcraft.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class VCraftEntry implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        String mc = FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("");
        String impl = mc.startsWith("1.21") ? "v1_21_11" : "v26_3";
        try {
            Class<?> cls = Class.forName("dev.vcraft.mc." + impl + ".VCraftMc");
            ((ClientModInitializer) cls.getDeclaredConstructor().newInstance()).onInitializeClient();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("VCraft does not support Minecraft " + mc, e);
        }
    }
}
