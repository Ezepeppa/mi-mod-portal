package com.argon.subgrid.mixin;

import com.argon.subgrid.SubGridBlockEntity;
import com.argon.subgrid.SubGridMod;
import com.argon.subgrid.SubGridWorldContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public class WorldMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void subgrid$onGetBlockStateHead(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        World self = (World) (Object) this;
        BlockState redirected = SubGridWorldContext.redirectGetBlockState(self, pos);
        if (redirected != null) {
            cir.setReturnValue(redirected);
        }
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void subgrid$onGetBlockStateReturn(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        BlockState ret = cir.getReturnValue();
        if (ret != null && ret.isOf(SubGridMod.SUBGRID_CONTAINER) && !SubGridWorldContext.isActive()) {
            // Compatibilidad transparente con Portal Gun / Gravity Gun / Architect Gun / Grapple Gun
            if (SubGridWorldContext.isPortalGravityModCaller()) {
                World self = (World) (Object) this;
                BlockEntity be = self.getBlockEntity(pos);
                if (be instanceof SubGridBlockEntity sgbe && !sgbe.getSlices().isEmpty()) {
                    cir.setReturnValue(sgbe.getSlices().get(sgbe.getSlices().size() - 1).storedState);
                }
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void subgrid$onGetFluidStateHead(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        World self = (World) (Object) this;
        FluidState redirected = SubGridWorldContext.redirectGetFluidState(self, pos);
        if (redirected != null) {
            cir.setReturnValue(redirected);
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void subgrid$onGetBlockEntityHead(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        World self = (World) (Object) this;
        BlockEntity redirected = SubGridWorldContext.redirectGetBlockEntity(self, pos);
        if (redirected != null) {
            cir.setReturnValue(redirected);
        }
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void subgrid$onSetBlockStateHead(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                             CallbackInfoReturnable<Boolean> cir) {
        World self = (World) (Object) this;
        if (SubGridWorldContext.redirectSetBlockState(self, pos, state, flags)) {
            cir.setReturnValue(true);
        }
    }
}
