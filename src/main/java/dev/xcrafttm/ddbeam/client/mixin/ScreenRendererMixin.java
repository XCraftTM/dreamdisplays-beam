package dev.xcrafttm.ddbeam.client.mixin;

import com.dreamdisplays.libs.kotlin.Unit;
import com.dreamdisplays.libs.kotlin.jvm.functions.Function2;
import com.dreamdisplays.platform.client.displays.DisplayScreen;
import com.dreamdisplays.platform.client.render.DisplayPoseStack;
import com.dreamdisplays.platform.client.render.ScreenRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.xcrafttm.ddbeam.client.cast.Casts;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a cast on its display instead of the display's own picture, through Dream Displays' renderer: same place,
 * rotation, unlit pipeline and shader pack handling as its videos. A display without a video gets drawn too while
 * it has a cast.
 */
@Mixin(value = ScreenRenderer.class, remap = false)
public abstract class ScreenRendererMixin {
    @Unique
    private static final float[] BASE_U = {0f, 1f, 1f, 0f};
    @Unique
    private static final float[] BASE_V = {1f, 1f, 0f, 0f};
    /** Dream Displays lifts its second (unshaded) draw by this much. */
    @Unique
    private static final float REPLAY_LIFT = 0.01f;

    @WrapOperation(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;ZLcom/dreamdisplays/libs/kotlin/jvm/functions/Function2;)V",
            at = @At(value = "INVOKE", target = "Lcom/dreamdisplays/platform/client/displays/DisplayScreen;getHasTexture()Z"),
            require = 0)
    private boolean ddbeam$drawWithCast(DisplayScreen screen, Operation<Boolean> original) {
        return original.call(screen) || Casts.covers(screen.getUuid());
    }

    @Inject(method = "renderScreenTexture", at = @At("HEAD"), cancellable = true, require = 0)
    private void ddbeam$drawCast(DisplayScreen screen, PoseStack stack, boolean replay,
                                 Function2<RenderType, Function2<PoseStack.Pose, VertexConsumer, Unit>, Unit> drawQuad,
                                 CallbackInfo ci) {
        RenderType type = Casts.renderTypeFor(screen.getUuid());
        if (type == null) return;
        // the display's own video keeps going underneath (and its uploads with it)
        if (!replay) screen.fitTexture();
        int turns = screen.getRotation().getQuarterTurns() & 3;
        int c = (int) (Math.clamp(screen.getBrightness(), 0f, 1f) * 255f);
        stack.pushPose();
        if (replay) DisplayPoseStack.INSTANCE.liftTowardViewer(stack, screen.getFacing(), REPLAY_LIFT);
        DisplayPoseStack.INSTANCE.applyScreenTransform(stack, screen.getFacing(), screen.getWidth(), screen.getHeight());
        drawQuad.invoke(type, (pose, buffer) -> {
            float[][] corners = {{0f, 0f}, {1f, 0f}, {1f, 1f}, {0f, 1f}};
            for (int i = 0; i < 4; i++) {
                buffer.addVertex(pose, corners[i][0], corners[i][1], 0f)
                        .setUv(BASE_U[(i + turns) & 3], BASE_V[(i + turns) & 3])
                        .setColor(c, c, c, 255);
            }
            return Unit.INSTANCE;
        });
        stack.popPose();
        ci.cancel();
    }
}
