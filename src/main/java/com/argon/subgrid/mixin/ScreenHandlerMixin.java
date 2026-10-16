package com.argon.subgrid.mixin;

import com.argon.subgrid.SubGridMod;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScreenHandler.class)
public class ScreenHandlerMixin {
    @Inject(
            method = "canUse(Lnet/minecraft/screen/ScreenHandlerContext;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/block/Block;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void subgrid$allowCanUseOnSubGrid(ScreenHandlerContext context, PlayerEntity player, Block block,
                                                     CallbackInfoReturnable<Boolean> cir) {
        Boolean allowed = context.get((world, pos) -> {
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
