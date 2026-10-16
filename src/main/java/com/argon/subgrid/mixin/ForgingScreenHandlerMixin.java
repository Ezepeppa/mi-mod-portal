package com.argon.subgrid.mixin;

import com.argon.subgrid.SubGridMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ForgingScreenHandler.class)
public class ForgingScreenHandlerMixin {
    @Shadow
    @Final
    protected ScreenHandlerContext context;

    @Inject(method = "canUse(Lnet/minecraft/entity/player/PlayerEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void subgrid$allowForgingOnSubGrid(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        Boolean allowed = this.context.get((world, pos) -> {
            if (world.getBlockState(pos).isOf(SubGridMod.SUBGRID_CONTAINER)) {
                return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
            }
            return false;
        }, false);
        if (Boolean.TRUE.equals(allowed)) {
            cir.setReturnValue(true);
        }
    }
}
