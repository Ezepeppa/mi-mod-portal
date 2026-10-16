package com.argon.subgrid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class SubGridBlock extends Block implements BlockEntityProvider {
    public static final IntProperty LIGHT_LEVEL = Properties.LEVEL_15;
    public static final IntProperty REDSTONE_POWER = Properties.POWER;
    public static final BooleanProperty POWERED = Properties.POWERED;

    public SubGridBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LIGHT_LEVEL, 0)
                .with(REDSTONE_POWER, 0)
                .with(POWERED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LIGHT_LEVEL, REDSTONE_POWER, POWERED);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SubGridBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            VoxelShape shape = sgbe.getCombinedShape();
            if (!shape.isEmpty()) {
                return shape;
            }
        }
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            return sgbe.getCombinedCollisionShape();
        }
        return VoxelShapes.empty();
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        // Si el jugador tiene un bloque o el calibrador en la mano y quiere construir sobre una cara, UseBlockCallback lo maneja
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            return sgbe.interactWithTargetedSlice(world, pos, player, hand, hit);
        }
        return ActionResult.PASS;
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (!world.isClient) {
            boolean receiving = world.isReceivingRedstonePower(pos);
            if (receiving != state.get(POWERED)) {
                world.setBlockState(pos, state.with(POWERED, receiving), Block.NOTIFY_ALL);
            }
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof SubGridBlockEntity sgbe) {
                sgbe.onExternalRedstoneUpdate(world, pos, receiving);
            }
        }
    }

    @Override
    public boolean emitsRedstonePower(BlockState state) {
        return true;
    }

    @Override
    public int getWeakRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            return sgbe.getEmittedRedstonePower(direction.getOpposite());
        }
        return state.get(REDSTONE_POWER);
    }

    @Override
    public int getStrongRedstonePower(BlockState state, BlockView world, BlockPos pos, Direction direction) {
        return getWeakRedstonePower(state, world, pos, direction);
    }
}
