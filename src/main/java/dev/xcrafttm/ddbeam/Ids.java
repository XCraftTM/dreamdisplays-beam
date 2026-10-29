package dev.xcrafttm.ddbeam;

//#if MC >= 1.21.11
import net.minecraft.resources.Identifier;
//#else
import net.minecraft.resources.ResourceLocation;
//#endif

/**
 * Minecraft's resource ids, which are {@code Identifier} from 1.21.11 and {@code ResourceLocation} before. Callers
 * keep them in {@code var}s, so their code is the same on every version.
 */
public final class Ids {
    private Ids() {
    }

    //#if MC >= 1.21.11
    public static Identifier of(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
    //#elif MC >= 1.21
    public static ResourceLocation of(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
    //#else
    public static ResourceLocation of(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
    //#endif

    //#if MC >= 1.21.11
    public static Identifier beam(String path) {
    //#else
    public static ResourceLocation beam(String path) {
    //#endif
        return of("ddbeam", path);
    }

    //#if MC >= 1.21.11
    public static Identifier vanilla(String path) {
    //#else
    public static ResourceLocation vanilla(String path) {
    //#endif
        return of("minecraft", path);
    }
}
