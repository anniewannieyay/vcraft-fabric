package dev.vcraft.api;

/**
 * Entrypoint for VCraft addons. Declare it in the addon's fabric.mod.json:
 * <pre>"entrypoints": { "vcraft": ["com.example.MyAddon"] }</pre>
 * and depend on "vcraft". Called once on the client, after VCraft has loaded.
 */
public interface VCraftAddon {
    void onInitialize(VCraftApi api);
}
