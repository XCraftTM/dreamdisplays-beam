package dev.xcrafttm.ddbeam.client.mixin;

import com.dreamdisplays.libs.kotlin.Pair;
import com.dreamdisplays.libs.kotlin.Unit;
import com.dreamdisplays.libs.kotlin.jvm.functions.Function0;
import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.ui.DisplayMenu;
import com.dreamdisplays.platform.client.ui.kit.UiScreenBase;
import com.dreamdisplays.platform.client.ui.menu.SettingsSection;
import com.dreamdisplays.platform.client.ui.widgets.IconButton;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import dev.xcrafttm.ddbeam.client.cast.LocalCast;
import dev.xcrafttm.ddbeam.client.cast.Targets;
import dev.xcrafttm.ddbeam.client.ui.BeamScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * A "Cast" button in Dream Displays' display menu, next to its delete button, made from its own icon button: opens
 * the cast dialog for this display (its icon turns into "stop" while this player casts here).
 */
@Mixin(value = DisplayMenu.class, remap = false)
public abstract class DisplayMenuMixin extends UiScreenBase {
    @Unique
    private static final WidgetSprites SPRITES = new WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"));

    @Shadow
    @Final
    private DisplayScreen displayScreen;

    private DisplayMenuMixin() {
        super(Component.empty());
    }

    @WrapOperation(method = "init", at = @At(value = "NEW",
            target = "(Ljava/util/List;Ljava/util/List;Ljava/util/List;)Lcom/dreamdisplays/platform/client/ui/menu/SettingsSection;"),
            require = 0)
    private SettingsSection ddbeam$addCastButton(List<SettingsSection.Row> rows, List<IconButton> ownerActions,
                                                 List<Pair<IconButton, Function0<List<Component>>>> tooltips,
                                                 Operation<SettingsSection> original) {
        IconButton cast = addUi(new IconButton(
                () -> Identifier.fromNamespaceAndPath("ddbeam", ddbeam$castingHere() ? "cast_stop" : "cast"),
                SPRITES, 2, () -> {
                    ddbeam$open();
                    return Unit.INSTANCE;
                }));
        List<IconButton> actions = new ArrayList<>(ownerActions);
        actions.add(cast);
        List<Pair<IconButton, Function0<List<Component>>>> tips = new ArrayList<>(tooltips);
        tips.add(new Pair<>(cast, () -> List.of(
                Component.translatable(ddbeam$castingHere() ? "ddbeam.menu.cast.live" : "ddbeam.menu.cast")
                        .withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                Component.translatable("ddbeam.menu.cast.body").withStyle(ChatFormatting.GRAY))));
        return original.call(rows, actions, tips);
    }

    @Unique
    private boolean ddbeam$castingHere() {
        LocalCast cast = Casts.local();
        return cast != null && cast.display.equals(displayScreen.getUuid());
    }

    @Unique
    private void ddbeam$open() {
        Targets.Target target = Targets.byId(displayScreen.getUuid());
        if (target != null) Minecraft.getInstance().setScreenAndShow(new BeamScreen(this, target));
    }
}
