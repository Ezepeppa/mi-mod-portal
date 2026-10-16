package com.argon.subgrid;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Puente de Mundo Virtual (activado mediante `WorldMixin`) que permite que cualquier método original
 * de Minecraft Vanilla o de otros mods (`BlockState.canPlaceAt`, `BlockState.onUse`, `BlockEntityTicker.tick`,
 * `Block.onEntityCollision`, `Block.onSteppedOn`) se ejecute viendo los vecinos reales en la Sub-Grid,
 * su propio `BlockState` y su propio `BlockEntity` nativo sin recrear reglas a mano.
 */
public class SubGridWorldContext {
    public static class ActiveContext {
        public final World world;
        public final int gx, gy, gz;
        public final BlockPos basePos;
        public final BlockState selfState;
        @Nullable
        public final SubGridBlockEntity.CellSlice slice;
        @Nullable
        public final UUID ignorePartId;
        public boolean reentrantGuard = false;

        public ActiveContext(World world, int gx, int gy, int gz, BlockState selfState,
                             @Nullable SubGridBlockEntity.CellSlice slice, @Nullable UUID ignorePartId) {
            this.world = world;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.basePos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
            this.selfState = selfState;
            this.slice = slice;
            this.ignorePartId = ignorePartId;
        }
    }

    private static final ThreadLocal<ActiveContext> CURRENT = new ThreadLocal<>();

    public static boolean isActive() {
        ActiveContext ctx = CURRENT.get();
        return ctx != null && !ctx.reentrantGuard;
    }

    public static <T> T runWithVirtualContext(World world, int gx, int gy, int gz, BlockState selfState,
                                              @Nullable SubGridBlockEntity.CellSlice slice,
                                              @Nullable UUID ignorePartId, Supplier<T> action) {
        ActiveContext prev = CURRENT.get();
        ActiveContext next = new ActiveContext(world, gx, gy, gz, selfState, slice, ignorePartId);
        CURRENT.set(next);
        try {
            return action.get();
        } finally {
            CURRENT.set(prev);
        }
    }

    public static void runWithVirtualContextVoid(World world, int gx, int gy, int gz, BlockState selfState,
                                                 @Nullable SubGridBlockEntity.CellSlice slice,
                                                 @Nullable UUID ignorePartId, Runnable action) {
        runWithVirtualContext(world, gx, gy, gz, selfState, slice, ignorePartId, () -> {
            action.run();
            return ActionResult.PASS;
        });
    }

    @Nullable
    public static BlockState redirectGetBlockState(World world, BlockPos pos) {
        ActiveContext ctx = CURRENT.get();
        if (ctx == null || ctx.reentrantGuard || ctx.world != world) return null;

        int dx = pos.getX() - ctx.basePos.getX();
        int dy = pos.getY() - ctx.basePos.getY();
        int dz = pos.getZ() - ctx.basePos.getZ();

        if (dx == 0 && dy == 0 && dz == 0) {
            return ctx.selfState;
        }

        if (Math.abs(dx) <= 2 && Math.abs(dy) <= 2 && Math.abs(dz) <= 2) {
            ctx.reentrantGuard = true;
            try {
                // Caso especial para Cactus: verificar si hay cualquier pieza o bloque tocando sus 4 caras laterales a cualquier sub-grid
                if (dy == 0 && (Math.abs(dx) + Math.abs(dz) == 1) && ctx.selfState.getBlock() instanceof CactusBlock) {
                    BlockState touchingSide = SubGridPlacementHandler.getTouchingHorizontalStateAt16(
                            world, ctx.gx, ctx.gy, ctx.gz, dx, dz, ctx.ignorePartId
                    );
                    if (!touchingSide.isAir()) {
                        return touchingSide;
                    }
                }
                return SubGridPlacementHandler.getRelativeSubGridState16(
                        world, ctx.gx, ctx.gy, ctx.gz, dx, dy, dz, ctx.ignorePartId
                );
            } finally {
                ctx.reentrantGuard = false;
            }
        }
        return null;
    }

    @Nullable
    public static FluidState redirectGetFluidState(World world, BlockPos pos) {
        ActiveContext ctx = CURRENT.get();
        if (ctx == null || ctx.reentrantGuard || ctx.world != world) return null;

        int dx = pos.getX() - ctx.basePos.getX();
        int dy = pos.getY() - ctx.basePos.getY();
        int dz = pos.getZ() - ctx.basePos.getZ();

        if (Math.abs(dx) <= 2 && Math.abs(dy) <= 2 && Math.abs(dz) <= 2) {
            ctx.reentrantGuard = true;
            try {
                int targetGx = ctx.gx + dx * 16;
                int targetGy = ctx.gy + dy * 16;
                int targetGz = ctx.gz + dz * 16;
                BlockPos realCell = new BlockPos(
                        Math.floorDiv(targetGx + 8, 16),
                        Math.floorDiv(targetGy + 8, 16),
                        Math.floorDiv(targetGz + 8, 16)
                );
                FluidState fs = world.getFluidState(realCell);
                if (!fs.isEmpty()) return fs;
                return Fluids.EMPTY.getDefaultState();
            } finally {
                ctx.reentrantGuard = false;
            }
        }
        return null;
    }

    @Nullable
    public static BlockEntity redirectGetBlockEntity(World world, BlockPos pos) {
        ActiveContext ctx = CURRENT.get();
        if (ctx == null || ctx.reentrantGuard || ctx.world != world) return null;

        if (pos.equals(ctx.basePos) && ctx.slice != null) {
            ctx.reentrantGuard = true;
            try {
                return ctx.slice.getOrCreateInnerBlockEntity(world);
            } finally {
                ctx.reentrantGuard = false;
            }
        }
        return null;
    }

    public static boolean redirectSetBlockState(World world, BlockPos pos, BlockState newState, int flags) {
        ActiveContext ctx = CURRENT.get();
        if (ctx == null || ctx.reentrantGuard || ctx.world != world) return false;

        if (pos.equals(ctx.basePos) && ctx.slice != null) {
            ctx.reentrantGuard = true;
            try {
                if (newState.isAir()) {
                    SubGridPlacementHandler.removePartEverywhere(world, ctx.slice.primaryPos, ctx.slice.partId);
                } else {
                    SubGridPlacementHandler.rebuildPartWithNewState(
                            world, ctx.slice.primaryPos, ctx.slice.partId, newState, ctx.slice.toggleLatchPower
                    );
                }
                return true;
            } finally {
                ctx.reentrantGuard = false;
            }
        }
        return false;
    }

    public static boolean isPortalGravityModCaller() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        int limit = Math.min(trace.length, 10);
        for (int i = 2; i < limit; i++) {
            String cls = trace[i].getClassName();
            if (cls.startsWith("com.argon.portalgravity.")) {
                return true;
            }
        }
        return false;
    }
}
