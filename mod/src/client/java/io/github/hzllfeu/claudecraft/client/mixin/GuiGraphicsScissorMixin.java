package io.github.hzllfeu.claudecraft.client.mixin;

import io.github.hzllfeu.claudecraft.client.ScissorScale;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * GuiGraphics.enableScissor ignores the pose matrix. The Claude screen draws with its own scale, so while it renders
 * (ScissorScale.k != 1) we scale scissor rectangles the same way — for our message list and for the vanilla text box.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsScissorMixin {
	@ModifyVariable(method = "enableScissor", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private int claudecraft$x1(int v) {
		return ScissorScale.min(v);
	}

	@ModifyVariable(method = "enableScissor", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private int claudecraft$y1(int v) {
		return ScissorScale.min(v);
	}

	@ModifyVariable(method = "enableScissor", at = @At("HEAD"), ordinal = 2, argsOnly = true)
	private int claudecraft$x2(int v) {
		return ScissorScale.max(v);
	}

	@ModifyVariable(method = "enableScissor", at = @At("HEAD"), ordinal = 3, argsOnly = true)
	private int claudecraft$y2(int v) {
		return ScissorScale.max(v);
	}
}
