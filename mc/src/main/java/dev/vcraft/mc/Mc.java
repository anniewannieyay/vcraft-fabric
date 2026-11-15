package dev.vcraft.mc;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
//#if v26
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif

/** The handful of game APIs that moved between Minecraft versions. */
public final class Mc {
    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static Screen screen() {
        //#if v26
        return mc().gui.screen();
        //#else
        return mc().screen;
        //#endif
    }

    public static void setScreen(Screen screen) {
        //#if v26
        mc().gui.setScreen(screen);
        //#else
        mc().setScreen(screen);
        //#endif
    }

    public static ToastManager toasts() {
        //#if v26
        return mc().gui.toastManager();
        //#else
        return mc().getToastManager();
        //#endif
    }

    public static void chat(Component message) {
        if (mc().level == null) return;
        //#if v26
        mc().gui.hud.getChat().addClientSystemMessage(message);
        //#else
        mc().gui.getChat().addMessage(message);
        //#endif
    }

    public static boolean hudHidden() {
        //#if v26
        return mc().gui.hud.isHidden();
        //#else
        return mc().options.hideGui;
        //#endif
    }

    public static KeyMapping registerKey(KeyMapping key) {
        //#if v26
        return KeyMappingHelper.registerKeyMapping(key);
        //#else
        return KeyBindingHelper.registerKeyBinding(key);
        //#endif
    }

    public static void addWidget(Screen screen, AbstractWidget widget) {
        //#if v26
        Screens.getWidgets(screen).add(widget);
        //#else
        Screens.getButtons(screen).add(widget);
        //#endif
    }

    private Mc() {
    }
}
