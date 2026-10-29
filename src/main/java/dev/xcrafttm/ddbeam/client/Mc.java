package dev.xcrafttm.ddbeam.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client calls whose names changed between Minecraft versions. */
public final class Mc {
    private Mc() {
    }

    public static void show(Screen screen) {
        //#if MC >= 1.21.11
        Minecraft.getInstance().setScreenAndShow(screen);
        //#else
        Minecraft.getInstance().setScreen(screen);
        //#endif
    }

    public static Screen screen() {
        //#if MC >= 26.2
        return Minecraft.getInstance().gui.screen();
        //#else
        return Minecraft.getInstance().screen;
        //#endif
    }

    /** A line in the player's chat. */
    public static void chat(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        //#if MC >= 26.1
        mc.player.sendSystemMessage(text);
        //#else
        mc.player.displayClientMessage(text, false);
        //#endif
    }
}
