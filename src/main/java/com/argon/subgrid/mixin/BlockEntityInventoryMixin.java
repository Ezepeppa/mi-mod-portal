package com.argon.subgrid.mixin;

import com.argon.subgrid.SubGridMod;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({LootableContainerBlockEntity.class, AbstractFurnaceBlockEntity.class, BrewingStandBlockEntity.class})
public class BlockEntityInventoryMixin {
    @Inject(method = "canPlayerUse(Lnet/minecraft/entity/player/PlayerEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void subgrid$allowContainerUseInSubGrid(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (self.getWorld() != null && self.getWorld().getBlockState(self.getPos()).isOf(SubGridMod.SUBGRID_CONTAINER)) {
            double distSq = player.squaredDistanceTo(
                    self.getPos().getX() + 0.5,
                    self.getPos().getY() + 0.5,
                    self.getPos().getZ() + 0.5
            );
            cir.setReturnValue(distSq <= 64.0);
        }
    }
}
