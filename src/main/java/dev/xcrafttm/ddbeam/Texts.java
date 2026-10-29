package dev.xcrafttm.ddbeam;

import net.minecraft.network.chat.MutableComponent;
//#if MC < 1.19
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
//#else
import net.minecraft.network.chat.Component;
//#endif

/** Text components on every version: {@code Component.literal/translatable} from 1.19, their own classes before. */
public final class Texts {
    private Texts() {
    }

    public static MutableComponent tr(String key, Object... args) {
        //#if MC < 1.19
        return new TranslatableComponent(key, args);
        //#else
        return Component.translatable(key, args);
        //#endif
    }

    public static MutableComponent lit(String text) {
        //#if MC < 1.19
        return new TextComponent(text);
        //#else
        return Component.literal(text);
        //#endif
    }

    public static MutableComponent empty() {
        return lit("");
    }
}
