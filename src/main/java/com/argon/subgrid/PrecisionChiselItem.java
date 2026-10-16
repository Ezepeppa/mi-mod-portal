package com.argon.subgrid;

import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class PrecisionChiselItem extends Item {
    public PrecisionChiselItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        PlayerEntity player = context.getPlayer();
        if (player == null) return ActionResult.PASS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof SubGridBlockEntity sgbe)) {
            return ActionResult.PASS;
        }

        if (!world.isClient) {
            Vec3d eye = player.getEyePos();
            Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(6.5));
            SubGridBlockEntity.CellSlice slice = sgbe.raycastSlice(eye, end);
            if (slice != null) {
                int step = SubGridMod.getPlayerGridStep(player.getUuid());
                if (step >= 16) step = 4;

                Direction dir = player.isSneaking() ? context.getSide() : context.getSide().getOpposite();
                boolean moved = SubGridPlacementHandler.shiftPartBy16(
                        world, slice.primaryPos, slice.partId,
                        dir.getOffsetX() * step, dir.getOffsetY() * step, dir.getOffsetZ() * step
                );
                if (moved) {
                    world.playSound(null, pos, SoundEvents.BLOCK_COPPER_STEP, SoundCategory.BLOCKS, 0.8f, 1.5f);
                } else if (slice.storedState.getBlock() instanceof StairsBlock) {
                    BlockHalf cur = slice.storedState.get(StairsBlock.HALF);
                    BlockState flipped = slice.storedState.with(StairsBlock.HALF, cur == BlockHalf.BOTTOM ? BlockHalf.TOP : BlockHalf.BOTTOM);
                    sgbe.syncPartStateAcrossCells(world, slice.partId, slice.primaryPos, flipped, slice.toggleLatchPower);
                    world.playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.8f, 1.3f);
                }
            }
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("Clic Derecho en bloque Sub-Grid: ").formatted(Formatting.GRAY)
                .append(Text.literal("Empujar 1 paso de Grid (o girar escalera)").formatted(Formatting.AQUA)));
        tooltip.add(Text.literal("Shift + Clic Derecho: ").formatted(Formatting.GRAY)
                .append(Text.literal("Traer 1 paso de Grid hacia vos").formatted(Formatting.GREEN)));
    }
}
