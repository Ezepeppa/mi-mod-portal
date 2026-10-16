package com.argon.subgrid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SubGridBlock extends Block implements BlockEntityProvider, Waterloggable {
    public static final IntProperty LIGHT_LEVEL = Properties.LEVEL_15;
    public static final IntProperty REDSTONE_POWER = Properties.POWER;
    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    public SubGridBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LIGHT_LEVEL, 0)
                .with(REDSTONE_POWER, 0)
                .with(POWERED, false)
                .with(WATERLOGGED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LIGHT_LEVEL, REDSTONE_POWER, POWERED, WATERLOGGED);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SubGridBlockEntity(pos, state);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    /**
     * Delega la velocidad de rotura y requerimiento de herramienta directamente al bloque vanilla original apuntado.
     * Bloques instantáneos como Caña de azúcar, Pasto, Flores, Antorchas o Fuego se rompen al instante.
     */
    @Override
    public float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            Vec3d eye = player.getEyePos();
            Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(6.5));
            SubGridBlockEntity.CellSlice target = sgbe.raycastSlice(eye, end);
            if (target != null) {
                float delta = target.storedState.calcBlockBreakingDelta(player, world, pos);
                if (delta > 0.0f) return delta;
            }
        }
        return super.calcBlockBreakingDelta(state, player, world, pos);
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe && !sgbe.getSlices().isEmpty()) {
            return new ItemStack(sgbe.getSlices().get(sgbe.getSlices().size() - 1).storedState.getBlock());
        }
        return ItemStack.EMPTY;
    }

    /**
     * Devuelve el contorno (Outline) COMPLETO y SIN RECORTAR del bloque original almacenado,
     * de modo que al apuntar con la mano vacía (o cualquier ítem) se marque el bloque entero 1x1x1
     * aunque sobresalga a celdas vecinas, igual que un bloque vanilla común.
     */
    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            VoxelShape shape = sgbe.getUnclippedOutlineShape(world, pos, context);
            if (!shape.isEmpty()) {
                return shape;
            }
        }
        return VoxelShapes.fullCube();
    }

    /**
     * Delega la colisión física directamente a `storedState.getCollisionShape(world, pos)` de cada pieza.
     * Si el bloque original no tiene colisión (Caña de azúcar, Pasto, Flores, Antorchas, Fuego, etc.),
     * devuelve `VoxelShapes.empty()` automáticamente.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof SubGridBlockEntity sgbe) {
            return sgbe.getDelegatedCollisionShape(world, pos);
        }
        return VoxelShapes.empty();
    }

    /**
     * Si el bloque contenedor es reemplazado por cualquier causa externa (explosiones, comandos, agua, etc.),
     * limpia automáticamente todas las porciones vinculadas (`partId`) en las celdas vecinas para que
     * JAMÁS queden hitboxes invisibles huérfanas.
     */
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof SubGridBlockEntity sgbe && !world.isClient) {
                List<SubGridBlockEntity.CellSlice> copy = new ArrayList<>(sgbe.getSlices());
                for (SubGridBlockEntity.CellSlice s : copy) {
                    UUID pid = s.partId;
                    BlockPos anchor = s.primaryPos;
                    SubGridPlacementHandler.removePartEverywhereOnBreak(world, anchor, pid, pos);
                }
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
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
