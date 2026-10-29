package dev.xcrafttm.ddbeam.client.mixin;

import dev.xcrafttm.ddbeam.Texts;
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
import net.minecraft.network.chat.Component;
import dev.xcrafttm.ddbeam.Ids;
import dev.xcrafttm.ddbeam.client.Mc;
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
    @Shadow
    @Final
    private DisplayScreen displayScreen;

    private DisplayMenuMixin() {
        super(Texts.empty());
    }

    @WrapOperation(method = "init", at = @At(value = "NEW",
            target = "(Ljava/util/List;Ljava/util/List;Ljava/util/List;)Lcom/dreamdisplays/platform/client/ui/menu/SettingsSection;"),
            require = 0)
    private SettingsSection ddbeam$addCastButton(List<SettingsSection.Row> rows, List<IconButton> ownerActions,
                                                 List<Pair<IconButton, Function0<List<Component>>>> tooltips,
                                                 Operation<SettingsSection> original) {
        IconButton cast = addUi(new IconButton(
                () -> Ids.beam(ddbeam$castingHere() ? "cast_stop" : "cast"),
                // Dream Displays' own button look (its sprite type differs before 1.20.2)
                IconButton.Companion.getDEFAULT_SPRITES(), 2, () -> {
                    ddbeam$open();
                    return Unit.INSTANCE;
                }));
        List<IconButton> actions = new ArrayList<>(ownerActions);
        actions.add(cast);
        List<Pair<IconButton, Function0<List<Component>>>> tips = new ArrayList<>(tooltips);
        tips.add(new Pair<>(cast, () -> List.of(
                Texts.tr(ddbeam$castingHere() ? "ddbeam.menu.cast.live" : "ddbeam.menu.cast")
                        .withStyle(s -> s.withColor(ChatFormatting.WHITE).withBold(true)),
                Texts.tr("ddbeam.menu.cast.body").withStyle(ChatFormatting.GRAY))));
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
        if (target != null) Mc.show(new BeamScreen(this, target));
    }
}
