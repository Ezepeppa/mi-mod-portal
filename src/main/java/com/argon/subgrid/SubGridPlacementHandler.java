package com.argon.subgrid;

import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BrewingStandBlock;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.EnchantingTableBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.HorizontalConnectingBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SmithingTableBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.WallShape;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.FireChargeItem;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SubGridPlacementHandler {
    private static final Map<BlockPos, Boolean> PISTON_POWER_CACHE = new HashMap<>();

    public static void updatePlayerGridOnServer(ServerPlayerEntity player, int newStep) {
        int validStep = switch (newStep) {
            case 1 -> 1;   // Modo Secreto Off-Grid (1/16 libre + Rotación Y Off-Grid)
            case 2 -> 2;   // Grid 1/8 (2px)
            case 4 -> 4;   // Grid 1/4 (4px)
            case 8 -> 8;   // Grid 1/2 (8px)
            default -> 16; // Grid 1/1 (16px Normal Vanilla)
        };
        SubGridMod.setPlayerGridStep(player.getUuid(), validStep);

        String label = switch (validStep) {
            case 1 -> "MODO SECRETO: OFF-GRID LIBRE (1/16 px + Rotación Y Libre)";
            case 2 -> "1/8 de Bloque (2 px - Modo Micro-Memoria)";
            case 4 -> "1/4 de Bloque (4 px - Modo Diodo Direccional)";
            case 8 -> "1/2 de Bloque (8 px - Mitad exacta)";
            default -> "1/1 Normal (Grilla original Vanilla)";
        };
        Formatting color = (validStep == 1) ? Formatting.LIGHT_PURPLE : (validStep < 16 ? Formatting.AQUA : Formatting.GREEN);
        player.sendMessage(Text.literal("Tamaño de Grid: ").formatted(Formatting.GRAY)
                .append(Text.literal(label).formatted(color)), true);
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                validStep == 1 ? SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME : SoundEvents.UI_BUTTON_CLICK.value(),
                SoundCategory.PLAYERS, 0.45f, validStep == 1 ? 1.6f : (1.0f + (16 - validStep) * 0.04f));
    }

    public static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isOf(SubGridMod.PRECISION_CHISEL)) {
            return ActionResult.PASS;
        }

        BlockPos hitPos = hitResult.getBlockPos();
        BlockState clickedState = world.getBlockState(hitPos);

        // 1. Si el jugador hace clic derecho sobre un bloque funcional dentro del SubGridBlock sin agacharse, USAR el bloque funcional!
        if (!player.isSneaking() && clickedState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(hitPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                Vec3d eye = player.getEyePos();
                SubGridBlockEntity.CellSlice targeted = sgbe.raycastSlice(eye, eye.add(player.getRotationVec(1.0F).multiply(6.5)));
                if (targeted != null && isInteractiveBlock(targeted.storedState.getBlock())) {
                    return sgbe.interactWithTargetedSlice(world, hitPos, player, hand, hitResult);
                }
            }
        }

        // 2. Soporte para Mechero y Carga Ígnea sobre bloques en Sub-Grid / Off-Grid
        if (stack.getItem() instanceof FlintAndSteelItem || stack.getItem() instanceof FireChargeItem) {
            if (clickedState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                return handleFireIgnitionOnSubGrid(player, world, hand, stack, hitPos, hitResult);
            }
            return ActionResult.PASS;
        }

        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.PASS;
        }

        Block blockToPlace = blockItem.getBlock();
        if (blockToPlace == SubGridMod.SUBGRID_CONTAINER || blockToPlace == SubGridMod.SUBGRID_ACTUATOR) {
            return ActionResult.PASS;
        }

        int gridStep = world.isClient
                ? SubGridClientState.currentGridStep
                : SubGridMod.getPlayerGridStep(player.getUuid());

        boolean combiningWithExistingSlabOrStair = (clickedState.getBlock() instanceof SlabBlock
                || clickedState.getBlock() instanceof StairsBlock
                || clickedState.isOf(SubGridMod.SUBGRID_CONTAINER))
                && (blockToPlace instanceof SlabBlock || blockToPlace instanceof StairsBlock);

        // En Grid 1/1 normal (si no está combinando dos Slabs/Escaleras en la misma celda), dejar que Vanilla coloque el bloque original puro!
        if (gridStep == 16 && !combiningWithExistingSlabOrStair) {
            return ActionResult.PASS;
        }

        int effectiveStep = (gridStep == 16 && combiningWithExistingSlabOrStair) ? 8 : gridStep;
        BlockState placementState = getDesiredBlockState(player, world, hand, stack, blockItem, hitResult, effectiveStep);
        if (placementState == null) {
            return ActionResult.FAIL;
        }

        int[] globalOrigin16 = computeGlobalOrigin16(hitResult, placementState, effectiveStep, world, player);
        float yawDegrees = (effectiveStep == 1) ? MathHelper.wrapDegrees(180.0f - player.getYaw()) : 0.0f;

        // Verificar mediante `SubGridWorldContext` + `WorldMixin` si el método NATIVO `placementState.canPlaceAt(world, basePos)` lo permite!
        if (!canBlockSurviveNative(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, null)) {
            return ActionResult.FAIL;
        }

        boolean isDoubleTall = (placementState.getBlock() instanceof DoorBlock) || (placementState.getBlock() instanceof TallPlantBlock);
        if (isDoubleTall) {
            BlockState upperState = placementState.contains(Properties.DOUBLE_BLOCK_HALF)
                    ? placementState.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                    : placementState;
            if (!canPlaceAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, yawDegrees, null, true)
                    || !canPlaceAtGlobal16(world, globalOrigin16[0], globalOrigin16[1] + 16, globalOrigin16[2], upperState, yawDegrees, null, true)) {
                return ActionResult.FAIL;
            }
        } else {
            if (!canPlaceAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, yawDegrees, null, true)) {
                return ActionResult.FAIL;
            }
        }

        if (!world.isClient) {
            UUID newPartId = UUID.randomUUID();
            BlockState connectedState = updateConnectionsForState(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, newPartId);
            placeAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2],
                    connectedState, newPartId, effectiveStep, false, yawDegrees);

            if (isDoubleTall && connectedState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                BlockState upperState = connectedState.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
                placeAtGlobal16(world, globalOrigin16[0], globalOrigin16[1] + 16, globalOrigin16[2],
                        upperState, newPartId, effectiveStep, false, yawDegrees);
            }

            updateNeighborConnectionsAround(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2]);

            BlockSoundGroup sg = connectedState.getSoundGroup();
            world.playSound(null, hitResult.getPos().x, hitResult.getPos().y, hitResult.getPos().z,
                    sg.getPlaceSound(), SoundCategory.BLOCKS, (sg.getVolume() + 1.0f) / 2.0f, sg.getPitch() * 0.8f);
            if (!player.getAbilities().creativeMode) {
                stack.decrement(1);
            }
        }
        return ActionResult.SUCCESS;
    }

    /**
     * Ejecuta el método NATIVO `state.canPlaceAt(world, basePos)` de Minecraft Vanilla dentro de `SubGridWorldContext`
     * (de modo que `WorldMixin` traduzca las consultas a `basePos.down()`, `basePos.north()`, fluidos, etc. a la Sub-Grid real).
     * Incluye además una verificación explícita de soporte inferior/lateral cuando el bloque está desplazado dentro de una celda.
     */
    public static boolean canBlockSurviveNative(World world, int gx, int gy, int gz,
                                                BlockState state, @Nullable UUID ignorePartId) {
        BlockPos basePos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
        boolean nativeOk = SubGridWorldContext.runWithVirtualContext(world, gx, gy, gz, state, null, ignorePartId, () -> {
            try {
                return state.canPlaceAt(world, basePos);
            } catch (Exception e) {
                return true;
            }
        });
        if (!nativeOk) return false;

        // Si es mitad superior de una puerta o planta doble, verificar que la mitad inferior exista en gy - 16
        if (state.contains(Properties.DOUBLE_BLOCK_HALF) && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = getRelativeSubGridState16(world, gx, gy, gz, 0, -1, 0, null);
            return below.isOf(state.getBlock());
        }
        return true;
    }

    /**
     * Traduce una consulta relativa `(dx, dy, dz)` desde `WorldMixin` a las coordenadas reales de Sub-Grid `(gx, gy, gz)`.
     * - Para `dy == -1, dx == 0, dz == 0` (`pos.down()`), verifica que exista un bloque cuya cara superior toque exactamente `gy`!
     *   Si el bloque está flotando en el aire (ej. `gy % 16 != 0` sin pieza justo debajo), devuelve `Blocks.AIR`.
     */
    public static BlockState getRelativeSubGridState16(World world, int gx, int gy, int gz,
                                                       int dx, int dy, int dz, @Nullable UUID ignorePartId) {
        if (dx == 0 && dy == -1 && dz == 0) {
            return getSupportingStateDirectlyBelow16(world, gx, gy, gz, ignorePartId);
        }
        if (dy == 0 && (Math.abs(dx) + Math.abs(dz) == 1)) {
            return getFlushHorizontalNeighborState16(world, gx, gy, gz, dx, dz, ignorePartId);
        }

        int targetGx = gx + dx * 16 + 8;
        int targetGy = gy + dy * 16 + 8;
        int targetGz = gz + dz * 16 + 8;
        BlockPos cellPos = new BlockPos(Math.floorDiv(targetGx, 16), Math.floorDiv(targetGy, 16), Math.floorDiv(targetGz, 16));
        int lx = targetGx - cellPos.getX() * 16;
        int ly = targetGy - cellPos.getY() * 16;
        int lz = targetGz - cellPos.getZ() * 16;

        BlockState cellState = world.getBlockState(cellPos);
        if (cellState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(cellPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                    if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
                    if (lx >= s.minX && lx <= s.maxX && ly >= s.minY && ly <= s.maxY && lz >= s.minZ && lz <= s.maxZ) {
                        return s.storedState;
                    }
                }
            }
            return Blocks.AIR.getDefaultState();
        }
        return cellState;
    }

    /**
     * Devuelve el `BlockState` cuya superficie superior toca físicamente la base `gy` del bloque en `(gx, gy, gz)`.
     * Si no hay ningún bloque tocando exactamente `gy - 1`, devuelve `Blocks.AIR` (así rieles, plantas, antorchas,
     * alfombras, cañas y cactus flotando en el aire ven `Blocks.AIR` en `canPlaceAt` y se rompen!).
     */
    public static BlockState getSupportingStateDirectlyBelow16(World world, int gx, int gy, int gz, @Nullable UUID ignorePartId) {
        int checkY = gy - 1;
        int cy = Math.floorDiv(checkY, 16);
        int ly = checkY - cy * 16;

        int centerGx = gx + 8;
        int centerGz = gz + 8;
        int cx = Math.floorDiv(centerGx, 16);
        int cz = Math.floorDiv(centerGz, 16);
        int lx = centerGx - cx * 16;
        int lz = centerGz - cz * 16;

        BlockPos supportCell = new BlockPos(cx, cy, cz);
        BlockState cellState = world.getBlockState(supportCell);
        if (cellState.isAir()) return Blocks.AIR.getDefaultState();

        if (cellState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(supportCell);
            if (be instanceof SubGridBlockEntity sgbe) {
                for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                    if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
                    if (lx >= s.minX && lx <= s.maxX && lz >= s.minZ && lz <= s.maxZ && ly >= s.minY && ly < s.maxY) {
                        return s.storedState;
                    }
                }
            }
            return Blocks.AIR.getDefaultState();
        }

        if (ly == 15) {
            return cellState;
        }
        if (cellState.getBlock() instanceof SlabBlock && cellState.get(SlabBlock.TYPE) == SlabType.BOTTOM && ly == 7) {
            return cellState;
        }
        return Blocks.AIR.getDefaultState();
    }

    /**
     * Devuelve el `BlockState` que toca físicamente la cara horizontal `(dx, dz)` del bloque en `(gx, gy, gz)`
     * (usado por escaleras de mano `LadderBlock`, antorchas de pared, botones y palancas en `canPlaceAt`).
     */
    public static BlockState getFlushHorizontalNeighborState16(World world, int gx, int gy, int gz,
                                                               int dx, int dz, @Nullable UUID ignorePartId) {
        int sampleGx = (dx > 0) ? (gx + 16) : (dx < 0 ? (gx - 1) : (gx + 8));
        int sampleGy = gy + 8;
        int sampleGz = (dz > 0) ? (gz + 16) : (dz < 0 ? (gz - 1) : (gz + 8));

        int cx = Math.floorDiv(sampleGx, 16);
        int cy = Math.floorDiv(sampleGy, 16);
        int cz = Math.floorDiv(sampleGz, 16);
        int lx = sampleGx - cx * 16;
        int ly = sampleGy - cy * 16;
        int lz = sampleGz - cz * 16;

        BlockPos cellPos = new BlockPos(cx, cy, cz);
        BlockState cellState = world.getBlockState(cellPos);
        if (cellState.isAir()) return Blocks.AIR.getDefaultState();

        if (cellState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(cellPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                    if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
                    if (lx >= s.minX && lx < s.maxX && ly >= s.minY && ly < s.maxY && lz >= s.minZ && lz < s.maxZ) {
                        return s.storedState;
                    }
                }
            }
            return Blocks.AIR.getDefaultState();
        }

        if ((dx > 0 && lx == 0) || (dx < 0 && lx == 15) || (dz > 0 && lz == 0) || (dz < 0 && lz == 15)) {
            return cellState;
        }
        return Blocks.AIR.getDefaultState();
    }

    /**
     * Para `CactusBlock.canPlaceAt`: detecta si hay cualquier bloque o pieza a los costados del cactus
     * (incluso si está a distancia de Sub-Grid de 1 a 16 dieciseisavos).
     */
    public static BlockState getTouchingHorizontalStateAt16(World world, int gx, int gy, int gz,
                                                            int dx, int dz, @Nullable UUID ignorePartId) {
        for (int dist = 1; dist <= 16; dist += 3) {
            int sampleGx = (dx > 0) ? (gx + 15 + dist) : (dx < 0 ? (gx - dist) : (gx + 8));
            int sampleGy = gy + 8;
            int sampleGz = (dz > 0) ? (gz + 15 + dist) : (dz < 0 ? (gz - dist) : (gz + 8));
            int cx = Math.floorDiv(sampleGx, 16);
            int cy = Math.floorDiv(sampleGy, 16);
            int cz = Math.floorDiv(sampleGz, 16);
            int lx = sampleGx - cx * 16;
            int ly = sampleGy - cy * 16;
            int lz = sampleGz - cz * 16;

            BlockPos cellPos = new BlockPos(cx, cy, cz);
            BlockState cellState = world.getBlockState(cellPos);
            if (cellState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                BlockEntity be = world.getBlockEntity(cellPos);
                if (be instanceof SubGridBlockEntity sgbe) {
                    for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                        if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
                        if (lx >= s.minX && lx < s.maxX && ly >= s.minY && ly < s.maxY && lz >= s.minZ && lz < s.maxZ) {
                            return s.storedState;
                        }
                    }
                }
            } else if (!cellState.isAir()) {
                return cellState;
            }
        }
        return Blocks.AIR.getDefaultState();
    }

    @Nullable
    public static SubGridBlockEntity.CellSlice findPrimarySlice(World world, BlockPos anchor, UUID partId) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockEntity be = world.getBlockEntity(anchor.add(dx, dy, dz));
                    if (be instanceof SubGridBlockEntity sgbe) {
                        for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                            if (s.partId.equals(partId) && s.renderHere) {
                                return s;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public static void rebuildPartWithNewState(World world, BlockPos anchor, UUID partId, BlockState newState, boolean latch) {
        SubGridBlockEntity.CellSlice primary = findPrimarySlice(world, anchor, partId);
        if (primary == null) return;
        int gx = primary.primaryPos.getX() * 16 + primary.offsetX;
        int gy = primary.primaryPos.getY() * 16 + primary.offsetY;
        int gz = primary.primaryPos.getZ() * 16 + primary.offsetZ;
        int step = primary.gridStepPlaced;
        float yaw = primary.yawDegrees;
        NbtCompound beNbt = (primary.innerBlockEntity != null) ? primary.innerBlockEntity.createNbt() : primary.savedInnerBeNbt;

        removePartEverywhere(world, anchor, partId);
        placeAtGlobal16WithNbt(world, gx, gy, gz, newState, partId, step, latch, yaw, beNbt);
    }

    public static void toggleOpenAndRebuildGeometry(World world, BlockPos anchor, UUID partId, boolean open) {
        List<SubGridBlockEntity.CellSlice> primaryParts = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockEntity be = world.getBlockEntity(anchor.add(dx, dy, dz));
                    if (be instanceof SubGridBlockEntity sgbe) {
                        for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                            if (s.partId.equals(partId) && s.renderHere && s.storedState.contains(Properties.OPEN)) {
                                primaryParts.add(s);
                            }
                        }
                    }
                }
            }
        }
        if (primaryParts.isEmpty()) return;

        removePartEverywhere(world, anchor, partId);
        for (SubGridBlockEntity.CellSlice s : primaryParts) {
            int gx = s.primaryPos.getX() * 16 + s.offsetX;
            int gy = s.primaryPos.getY() * 16 + s.offsetY;
            int gz = s.primaryPos.getZ() * 16 + s.offsetZ;
            BlockState updated = s.storedState.with(Properties.OPEN, open);
            placeAtGlobal16(world, gx, gy, gz, updated, partId, s.gridStepPlaced, s.toggleLatchPower, s.yawDegrees);
        }
    }

    private static ActionResult handleFireIgnitionOnSubGrid(PlayerEntity player, World world, Hand hand,
                                                            ItemStack stack, BlockPos hitPos, BlockHitResult hitResult) {
        BlockEntity be = world.getBlockEntity(hitPos);
        if (!(be instanceof SubGridBlockEntity sgbe)) return ActionResult.PASS;

        Vec3d eye = player.getEyePos();
        SubGridBlockEntity.CellSlice target = sgbe.raycastSlice(eye, eye.add(player.getRotationVec(1.0F).multiply(6.5)));
        if (target == null) return ActionResult.PASS;

        if (target.storedState.isOf(Blocks.TNT)) {
            if (!world.isClient) {
                double tx = target.primaryPos.getX() + (target.offsetX / 16.0) + 0.5;
                double ty = target.primaryPos.getY() + (target.offsetY / 16.0);
                double tz = target.primaryPos.getZ() + (target.offsetZ / 16.0) + 0.5;
                removePartEverywhere(world, target.primaryPos, target.partId);
                TntEntity tnt = new TntEntity(world, tx, ty, tz, player);
                world.spawnEntity(tnt);
                world.playSound(null, tx, ty, tz, SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.BLOCKS, 1.0f, 1.0f);
                damageOrConsumeIgniter(player, hand, stack);
            }
            return ActionResult.SUCCESS;
        }

        if ((target.storedState.getBlock() instanceof CampfireBlock || target.storedState.getBlock() instanceof CandleBlock)
                && target.storedState.contains(Properties.LIT) && !target.storedState.get(Properties.LIT)) {
            if (!world.isClient) {
                sgbe.syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.LIT, true), target.toggleLatchPower);
                world.playSound(null, hitPos, SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 1.0f, 1.0f);
                damageOrConsumeIgniter(player, hand, stack);
            }
            return ActionResult.SUCCESS;
        }

        BlockState fireState = Blocks.FIRE.getDefaultState();
        int step = Math.min(8, Math.max(1, target.gridStepPlaced));
        int[] fireOrigin16 = computeFireOriginOnSlice(target, hitResult.getSide());

        if (!canPlaceAtGlobal16(world, fireOrigin16[0], fireOrigin16[1], fireOrigin16[2], fireState, 0.0f, null, false)) {
            return ActionResult.PASS;
        }

        if (!world.isClient) {
            placeAtGlobal16(world, fireOrigin16[0], fireOrigin16[1], fireOrigin16[2],
                    fireState, UUID.randomUUID(), step, false, target.yawDegrees);
            world.playSound(null, hitPos, SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 1.0f,
                    world.getRandom().nextFloat() * 0.4f + 0.8f);
            damageOrConsumeIgniter(player, hand, stack);
        }
        return ActionResult.SUCCESS;
    }

    private static int[] computeFireOriginOnSlice(SubGridBlockEntity.CellSlice target, Direction side) {
        int baseGx = target.primaryPos.getX() * 16 + target.offsetX;
        int baseGy = target.primaryPos.getY() * 16 + target.offsetY;
        int baseGz = target.primaryPos.getZ() * 16 + target.offsetZ;
        int height16 = Math.max(1, target.maxY - target.minY);
        return switch (side) {
            case UP -> new int[]{baseGx, baseGy + height16, baseGz};
            case DOWN -> new int[]{baseGx, baseGy - 16, baseGz};
            case EAST -> new int[]{baseGx + 16, baseGy, baseGz};
            case WEST -> new int[]{baseGx - 16, baseGy, baseGz};
            case SOUTH -> new int[]{baseGx, baseGy, baseGz + 16};
            case NORTH -> new int[]{baseGx, baseGy, baseGz - 16};
        };
    }

    private static void damageOrConsumeIgniter(PlayerEntity player, Hand hand, ItemStack stack) {
        if (player.getAbilities().creativeMode) return;
        if (stack.getItem() instanceof FlintAndSteelItem) {
            stack.damage(1, player, p -> p.sendToolBreakStatus(hand));
        } else {
            stack.decrement(1);
        }
    }

    public static boolean isInteractiveBlock(Block b) {
        return b instanceof BlockEntityProvider
                || b instanceof CraftingTableBlock || b instanceof EnchantingTableBlock
                || b instanceof StonecutterBlock || b instanceof LoomBlock
                || b instanceof CartographyTableBlock || b instanceof SmithingTableBlock
                || b instanceof AnvilBlock || b instanceof GrindstoneBlock
                || b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock
                || b instanceof EnderChestBlock || b instanceof DispenserBlock
                || b instanceof HopperBlock || b instanceof AbstractFurnaceBlock || b instanceof BrewingStandBlock
                || b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof DaylightDetectorBlock
                || b instanceof NoteBlock || b instanceof BellBlock || b instanceof RedstoneLampBlock
                || b instanceof CakeBlock || b instanceof ComposterBlock || b instanceof BedBlock;
    }

    @Nullable
    public static BlockState getDesiredBlockState(PlayerEntity player, World world, Hand hand,
                                                  ItemStack stack, BlockItem blockItem, BlockHitResult hit, int effectiveStep) {
        Block b = blockItem.getBlock();
        if (b instanceof SlabBlock) {
            return b.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM).with(SlabBlock.WATERLOGGED, false);
        }
        if (b instanceof StairsBlock) {
            Direction horiz = (effectiveStep == 1) ? Direction.NORTH : player.getHorizontalFacing();
            boolean upsideDown = (hit.getSide() == Direction.DOWN)
                    || (hit.getSide().getAxis().isHorizontal() && (hit.getPos().y - Math.floor(hit.getPos().y)) > 0.5)
                    || (player.isSneaking() && hit.getSide() == Direction.UP);
            return b.getDefaultState()
                    .with(StairsBlock.FACING, horiz)
                    .with(StairsBlock.HALF, upsideDown ? BlockHalf.TOP : BlockHalf.BOTTOM)
                    .with(StairsBlock.WATERLOGGED, false);
        }
        if (b instanceof LadderBlock) {
            if (!hit.getSide().getAxis().isHorizontal()) {
                return null;
            }
            return b.getDefaultState().with(LadderBlock.FACING, hit.getSide()).with(LadderBlock.WATERLOGGED, false);
        }
        if (b instanceof DoorBlock) {
            Direction horiz = (effectiveStep == 1) ? Direction.NORTH : player.getHorizontalFacing();
            return b.getDefaultState()
                    .with(DoorBlock.FACING, horiz)
                    .with(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .with(DoorBlock.OPEN, false);
        }

        BlockState st = null;
        try {
            ItemPlacementContext ctx = new ItemPlacementContext(player, hand, stack, hit);
            st = b.getPlacementState(ctx);
        } catch (Exception ignored) {
        }
        if (st == null) {
            st = b.getDefaultState();
            if (st.contains(Properties.HORIZONTAL_FACING)) {
                st = st.with(Properties.HORIZONTAL_FACING, player.getHorizontalFacing().getOpposite());
            }
        }
        if (effectiveStep == 1 && st.contains(Properties.HORIZONTAL_FACING) && !(b instanceof LadderBlock)) {
            st = st.with(Properties.HORIZONTAL_FACING, Direction.NORTH);
        }
        return st;
    }

    public static VoxelShape getSafeOriginalOutlineShape(BlockState state, @Nullable BlockView world, @Nullable BlockPos pos) {
        if (state == null || state.isAir()) return VoxelShapes.empty();
        if (state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)) {
            return Block.createCuboidShape(0, 0, 0, 16, 4, 16);
        }
        try {
            BlockView safeView = (world != null) ? world : EmptyBlockView.INSTANCE;
            BlockPos safePos = (pos != null) ? pos : BlockPos.ORIGIN;
            VoxelShape shape = state.getOutlineShape(safeView, safePos);
            if (shape != null && !shape.isEmpty()) {
                return shape;
            }
        } catch (Exception ignored) {
        }
        return VoxelShapes.fullCube();
    }

    public static VoxelShape getSafeOriginalCollisionShape(BlockState state, @Nullable BlockView world, @Nullable BlockPos pos) {
        if (state == null || state.isAir()) return VoxelShapes.empty();
        try {
            BlockView safeView = (world != null) ? world : EmptyBlockView.INSTANCE;
            BlockPos safePos = (pos != null) ? pos : BlockPos.ORIGIN;
            VoxelShape collision = state.getCollisionShape(safeView, safePos);
            return collision != null ? collision : VoxelShapes.empty();
        } catch (Exception ignored) {
            return VoxelShapes.fullCube();
        }
    }

    public static int[] computeGlobalOrigin16(BlockHitResult hit, BlockState stateToPlace, int gridStep,
                                              @Nullable World world, @Nullable PlayerEntity player) {
        Vec3d p = hit.getPos();
        Direction face = hit.getSide();
        BlockPos hitPos = hit.getBlockPos();

        double px = p.x * 16.0;
        double py = p.y * 16.0;
        double pz = p.z * 16.0;

        int[] dims = getBoundingDimensions16(stateToPlace, world);
        int sizeX = dims[0];
        int sizeY = dims[1];
        int sizeZ = dims[2];

        if (world != null && world.getBlockState(hitPos).isOf(SubGridMod.SUBGRID_CONTAINER) && player != null) {
            BlockEntity be = world.getBlockEntity(hitPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                Vec3d eye = player.getEyePos();
                SubGridBlockEntity.CellSlice target = sgbe.raycastSlice(eye, eye.add(player.getRotationVec(1.0F).multiply(6.5)));
                if (target != null) {
                    int baseGx = target.primaryPos.getX() * 16 + target.offsetX;
                    int baseGy = target.primaryPos.getY() * 16 + target.offsetY;
                    int baseGz = target.primaryPos.getZ() * 16 + target.offsetZ;
                    int[] targetDims = getBoundingDimensions16(target.storedState, world);

                    int gx = snapCoord(px - sizeX * 0.5, gridStep);
                    int gy = snapCoord(py - sizeY * 0.5, gridStep);
                    int gz = snapCoord(pz - sizeZ * 0.5, gridStep);

                    switch (face) {
                        case UP -> gy = baseGy + targetDims[1];
                        case DOWN -> gy = baseGy - sizeY;
                        case EAST -> gx = baseGx + 16;
                        case WEST -> gx = baseGx - sizeX;
                        case SOUTH -> gz = baseGz + 16;
                        case NORTH -> gz = baseGz - sizeZ;
                    }
                    return new int[]{gx, gy, gz};
                }
            }
        }

        int gx = snapCoord(px - sizeX * 0.5, gridStep);
        int gy = snapCoord(py - sizeY * 0.5, gridStep);
        int gz = snapCoord(pz - sizeZ * 0.5, gridStep);

        if (world != null && !world.getBlockState(hitPos).isAir() && !world.getBlockState(hitPos).isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockState clickedVanilla = world.getBlockState(hitPos);
            int[] clickedDims = getBoundingDimensions16(clickedVanilla, world);
            int cellGx = hitPos.getX() * 16;
            int cellGy = hitPos.getY() * 16;
            int cellGz = hitPos.getZ() * 16;
            switch (face) {
                case UP -> gy = cellGy + clickedDims[1];
                case DOWN -> gy = cellGy - sizeY;
                case EAST -> gx = cellGx + 16;
                case WEST -> gx = cellGx - sizeX;
                case SOUTH -> gz = cellGz + 16;
                case NORTH -> gz = cellGz - sizeZ;
            }
            return new int[]{gx, gy, gz};
        }

        switch (face) {
            case UP -> gy = (int) Math.round(py);
            case DOWN -> gy = (int) Math.round(py) - sizeY;
            case EAST -> gx = (int) Math.round(px);
            case WEST -> gx = (int) Math.round(px) - sizeX;
            case SOUTH -> gz = (int) Math.round(pz);
            case NORTH -> gz = (int) Math.round(pz) - sizeZ;
        }

        return new int[]{gx, gy, gz};
    }

    private static int snapCoord(double val16, int step) {
        if (step <= 1) return (int) Math.round(val16);
        return (int) Math.round(val16 / step) * step;
    }

    public static int[] getBoundingDimensions16(BlockState state, @Nullable BlockView world) {
        Block b = state.getBlock();
        if (b instanceof SlabBlock) {
            return new int[]{16, 8, 16};
        }
        if (b instanceof CarpetBlock || b instanceof AbstractRailBlock || b instanceof RedstoneWireBlock || b instanceof AbstractPressurePlateBlock) {
            return new int[]{16, 2, 16};
        }
        if (b instanceof TrapdoorBlock && state.contains(TrapdoorBlock.OPEN) && !state.get(TrapdoorBlock.OPEN)) {
            return new int[]{16, 3, 16};
        }
        return new int[]{16, 16, 16};
    }

    public static List<int[]> getLocalBoxes16(BlockState state, @Nullable BlockView world) {
        List<int[]> boxes = new ArrayList<>();
        Block b = state.getBlock();

        if (b instanceof SlabBlock) {
            boxes.add(new int[]{0, 0, 0, 16, 8, 16});
            return boxes;
        }

        VoxelShape outline = getSafeOriginalOutlineShape(state, world, BlockPos.ORIGIN);
        for (Box bb : outline.getBoundingBoxes()) {
            int x0 = Math.max(0, Math.min(15, (int) Math.floor(bb.minX * 16.0 + 1e-4)));
            int y0 = Math.max(0, Math.min(15, (int) Math.floor(bb.minY * 16.0 + 1e-4)));
            int z0 = Math.max(0, Math.min(15, (int) Math.floor(bb.minZ * 16.0 + 1e-4)));
            int x1 = Math.max(x0 + 1, Math.min(16, (int) Math.ceil(bb.maxX * 16.0 - 1e-4)));
            int y1 = Math.max(y0 + 1, Math.min(16, (int) Math.ceil(bb.maxY * 16.0 - 1e-4)));
            int z1 = Math.max(z0 + 1, Math.min(16, (int) Math.ceil(bb.maxZ * 16.0 - 1e-4)));
            boxes.add(new int[]{x0, y0, z0, x1, y1, z1});
        }

        if (boxes.isEmpty()) {
            boxes.add(new int[]{0, 0, 0, 16, 16, 16});
        }
        return boxes;
    }

    public static boolean obbOverlapXZ(double cxA, double czA, double hwA, double hzA, float yawDegA,
                                       double cxB, double czB, double hwB, double hzB, float yawDegB) {
        double eps = 0.025;
        double effHwA = Math.max(0.01, hwA - eps);
        double effHzA = Math.max(0.01, hzA - eps);
        double effHwB = Math.max(0.01, hwB - eps);
        double effHzB = Math.max(0.01, hzB - eps);

        double radA = Math.toRadians(yawDegA);
        double radB = Math.toRadians(yawDegB);

        double[][] axes = {
                {Math.cos(radA), -Math.sin(radA)},
                {Math.sin(radA), Math.cos(radA)},
                {Math.cos(radB), -Math.sin(radB)},
                {Math.sin(radB), Math.cos(radB)}
        };

        double dx = cxB - cxA;
        double dz = czB - czA;

        for (double[] axis : axes) {
            double ax = axis[0];
            double az = axis[1];
            double dist = Math.abs(dx * ax + dz * az);

            double rA = effHwA * Math.abs(axes[0][0] * ax + axes[0][1] * az)
                    + effHzA * Math.abs(axes[1][0] * ax + axes[1][1] * az);
            double rB = effHwB * Math.abs(axes[2][0] * ax + axes[2][1] * az)
                    + effHzB * Math.abs(axes[3][0] * ax + axes[3][1] * az);

            if (dist >= rA + rB) {
                return false;
            }
        }
        return true;
    }

    public static boolean canPlaceAtGlobal16(World world, int gx, int gy, int gz,
                                             BlockState stateToPlace, float yawDegrees,
                                             @Nullable UUID ignorePartId, boolean checkEntities) {
        if (checkEntities) {
            VoxelShape origCollision = getSafeOriginalCollisionShape(stateToPlace, world, BlockPos.ORIGIN);
            if (!origCollision.isEmpty()) {
                for (Box localBox : origCollision.getBoundingBoxes()) {
                    Box worldBox = localBox.offset(gx / 16.0, gy / 16.0, gz / 16.0).contract(0.002);
                    if (!world.getOtherEntities(null, worldBox, EntityPredicates.EXCEPT_SPECTATOR).isEmpty()) {
                        return false;
                    }
                }
            }
        }

        List<int[]> localBoxes = getLocalBoxes16(stateToPlace, world);
        double pivotX = gx + 8.0;
        double pivotZ = gz + 8.0;
        double rad = Math.toRadians(yawDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (int[] lb : localBoxes) {
            double minYA = gy + lb[1];
            double maxY2A = gy + lb[4];

            double lcx = (lb[0] + lb[3]) * 0.5;
            double lcz = (lb[2] + lb[5]) * 0.5;
            double hwA = (lb[3] - lb[0]) * 0.5;
            double hzA = (lb[5] - lb[2]) * 0.5;

            double rdx = lcx - 8.0;
            double rdz = lcz - 8.0;
            double cxA = pivotX + rdx * cos + rdz * sin;
            double czA = pivotZ - rdx * sin + rdz * cos;

            double extX = hwA * Math.abs(cos) + hzA * Math.abs(sin);
            double extZ = hwA * Math.abs(sin) + hzA * Math.abs(cos);
            int cMinX = Math.floorDiv((int) Math.floor(cxA - extX), 16);
            int cMaxX = Math.floorDiv((int) Math.ceil(cxA + extX) - 1, 16);
            int cMinY = Math.floorDiv((int) minYA, 16);
            int cMaxY = Math.floorDiv((int) maxY2A - 1, 16);
            int cMinZ = Math.floorDiv((int) Math.floor(czA - extZ), 16);
            int cMaxZ = Math.floorDiv((int) Math.ceil(czA + extZ) - 1, 16);

            for (int cx = cMinX; cx <= cMaxX; cx++) {
                for (int cy = cMinY; cy <= cMaxY; cy++) {
                    for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                        BlockPos cellPos = new BlockPos(cx, cy, cz);
                        BlockState existing = world.getBlockState(cellPos);
                        if (existing.isAir() || existing.isReplaceable()) {
                            continue;
                        }

                        if (existing.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                            BlockEntity be = world.getBlockEntity(cellPos);
                            if (be instanceof SubGridBlockEntity sgbe) {
                                for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                                    if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
                                    int sBaseGx = s.primaryPos.getX() * 16 + s.offsetX;
                                    int sBaseGy = s.primaryPos.getY() * 16 + s.offsetY;
                                    int sBaseGz = s.primaryPos.getZ() * 16 + s.offsetZ;

                                    List<int[]> sBoxes = getLocalBoxes16(s.storedState, world);
                                    double sPivotX = sBaseGx + 8.0;
                                    double sPivotZ = sBaseGz + 8.0;
                                    double sRad = Math.toRadians(s.yawDegrees);
                                    double sCos = Math.cos(sRad);
                                    double sSin = Math.sin(sRad);

                                    for (int[] sb : sBoxes) {
                                        double minYB = sBaseGy + sb[1];
                                        double maxYB = sBaseGy + sb[4];
                                        if (maxY2A <= minYB + 0.02 || minYA >= maxYB - 0.02) continue;

                                        double slcx = (sb[0] + sb[3]) * 0.5;
                                        double slcz = (sb[2] + sb[5]) * 0.5;
                                        double hwB = (sb[3] - sb[0]) * 0.5;
                                        double hzB = (sb[5] - sb[2]) * 0.5;
                                        double srdx = slcx - 8.0;
                                        double srdz = slcz - 8.0;
                                        double cxB = sPivotX + srdx * sCos + srdz * sSin;
                                        double czB = sPivotZ - srdx * sSin + srdz * sCos;

                                        if (obbOverlapXZ(cxA, czA, hwA, hzA, yawDegrees, cxB, czB, hwB, hzB, s.yawDegrees)) {
                                            return false;
                                        }
                                    }
                                }
                            }
                            continue;
                        }

                        VoxelShape existingShape = getSafeOriginalOutlineShape(existing, world, cellPos);
                        for (Box eb : existingShape.getBoundingBoxes()) {
                            double minYB = cy * 16.0 + eb.minY * 16.0;
                            double maxYB = cy * 16.0 + eb.maxY * 16.0;
                            if (maxY2A <= minYB + 0.02 || minYA >= maxYB - 0.02) continue;

                            double cxB = cx * 16.0 + (eb.minX + eb.maxX) * 8.0;
                            double czB = cz * 16.0 + (eb.minZ + eb.maxZ) * 8.0;
                            double hwB = (eb.maxX - eb.minX) * 8.0;
                            double hzB = (eb.maxZ - eb.minZ) * 8.0;

                            if (obbOverlapXZ(cxA, czA, hwA, hzA, yawDegrees, cxB, czB, hwB, hzB, 0.0f)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    public static BlockState updateConnectionsForState(World world, int gx, int gy, int gz,
                                                       BlockState state, @Nullable UUID selfPartId) {
        Block b = state.getBlock();
        if (b instanceof HorizontalConnectingBlock) {
            boolean n = canConnectInSubGrid(world, gx, gy, gz - 16, Direction.SOUTH, b, selfPartId);
            boolean s = canConnectInSubGrid(world, gx, gy, gz + 16, Direction.NORTH, b, selfPartId);
            boolean w = canConnectInSubGrid(world, gx - 16, gy, gz, Direction.EAST, b, selfPartId);
            boolean e = canConnectInSubGrid(world, gx + 16, gy, gz, Direction.WEST, b, selfPartId);
            if (state.contains(Properties.NORTH)) state = state.with(Properties.NORTH, n);
            if (state.contains(Properties.SOUTH)) state = state.with(Properties.SOUTH, s);
            if (state.contains(Properties.WEST)) state = state.with(Properties.WEST, w);
            if (state.contains(Properties.EAST)) state = state.with(Properties.EAST, e);
        } else if (b instanceof WallBlock) {
            boolean n = canConnectInSubGrid(world, gx, gy, gz - 16, Direction.SOUTH, b, selfPartId);
            boolean s = canConnectInSubGrid(world, gx, gy, gz + 16, Direction.NORTH, b, selfPartId);
            boolean w = canConnectInSubGrid(world, gx - 16, gy, gz, Direction.EAST, b, selfPartId);
            boolean e = canConnectInSubGrid(world, gx + 16, gy, gz, Direction.WEST, b, selfPartId);
            if (state.contains(WallBlock.NORTH_SHAPE)) state = state.with(WallBlock.NORTH_SHAPE, n ? WallShape.LOW : WallShape.NONE);
            if (state.contains(WallBlock.SOUTH_SHAPE)) state = state.with(WallBlock.SOUTH_SHAPE, s ? WallShape.LOW : WallShape.NONE);
            if (state.contains(WallBlock.WEST_SHAPE)) state = state.with(WallBlock.WEST_SHAPE, w ? WallShape.LOW : WallShape.NONE);
            if (state.contains(WallBlock.EAST_SHAPE)) state = state.with(WallBlock.EAST_SHAPE, e ? WallShape.LOW : WallShape.NONE);
            if (state.contains(WallBlock.UP)) state = state.with(WallBlock.UP, true);
        }
        return state;
    }

    private static boolean canConnectInSubGrid(World world, int targetGx, int targetGy, int targetGz,
                                               Direction sideTowardsSelf, Block selfBlock, @Nullable UUID selfPartId) {
        BlockPos cellPos = new BlockPos(Math.floorDiv(targetGx, 16), Math.floorDiv(targetGy, 16), Math.floorDiv(targetGz, 16));
        int offX = targetGx - cellPos.getX() * 16;
        int offY = targetGy - cellPos.getY() * 16;
        int offZ = targetGz - cellPos.getZ() * 16;

        BlockState cellState = world.getBlockState(cellPos);
        if (cellState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(cellPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                    if (selfPartId != null && s.partId.equals(selfPartId)) continue;
                    int sGx = s.primaryPos.getX() * 16 + s.offsetX;
                    int sGy = s.primaryPos.getY() * 16 + s.offsetY;
                    int sGz = s.primaryPos.getZ() * 16 + s.offsetZ;
                    if (Math.abs(sGx - targetGx) <= 4 && Math.abs(sGy - targetGy) <= 4 && Math.abs(sGz - targetGz) <= 4) {
                        if (isConnectableTarget(selfBlock, s.storedState, world, cellPos, sideTowardsSelf)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        if (offX == 0 && offY == 0 && offZ == 0 && !cellState.isAir()) {
            return isConnectableTarget(selfBlock, cellState, world, cellPos, sideTowardsSelf);
        }
        return false;
    }

    private static boolean isConnectableTarget(Block selfBlock, BlockState neighborState, World world, BlockPos pos, Direction side) {
        Block nb = neighborState.getBlock();
        if (selfBlock instanceof PaneBlock && nb instanceof PaneBlock) return true;
        if (selfBlock instanceof FenceBlock && (nb instanceof FenceBlock || nb instanceof FenceGateBlock)) return true;
        if (selfBlock instanceof WallBlock && (nb instanceof WallBlock || nb instanceof FenceGateBlock)) return true;
        return !neighborState.isAir() && neighborState.isSideSolidFullSquare(world, pos, side);
    }

    public static void updateNeighborConnectionsAround(World world, int gx, int gy, int gz) {
        int[][] dirs = {{0, 0, -16}, {0, 0, 16}, {-16, 0, 0}, {16, 0, 0}};
        for (int[] d : dirs) {
            int nx = gx + d[0], ny = gy + d[1], nz = gz + d[2];
            BlockPos cPos = new BlockPos(Math.floorDiv(nx, 16), Math.floorDiv(ny, 16), Math.floorDiv(nz, 16));
            BlockEntity be = world.getBlockEntity(cPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                List<SubGridBlockEntity.CellSlice> copy = new ArrayList<>(sgbe.getSlices());
                for (SubGridBlockEntity.CellSlice s : copy) {
                    if (!s.renderHere) continue;
                    if (s.storedState.getBlock() instanceof HorizontalConnectingBlock || s.storedState.getBlock() instanceof WallBlock) {
                        int sGx = s.primaryPos.getX() * 16 + s.offsetX;
                        int sGy = s.primaryPos.getY() * 16 + s.offsetY;
                        int sGz = s.primaryPos.getZ() * 16 + s.offsetZ;
                        BlockState updated = updateConnectionsForState(world, sGx, sGy, sGz, s.storedState, s.partId);
                        if (!updated.equals(s.storedState)) {
                            removePartEverywhere(world, s.primaryPos, s.partId);
                            placeAtGlobal16(world, sGx, sGy, sGz, updated, s.partId, s.gridStepPlaced, s.toggleLatchPower, s.yawDegrees);
                        }
                    }
                }
            }
        }
    }

    public static void placeAtGlobal16(World world, int gx, int gy, int gz,
                                       BlockState stateToPlace, UUID partId, int gridStep, boolean latch, float yawDegrees) {
        placeAtGlobal16WithNbt(world, gx, gy, gz, stateToPlace, partId, gridStep, latch, yawDegrees, null);
    }

    public static void placeAtGlobal16WithNbt(World world, int gx, int gy, int gz,
                                              BlockState stateToPlace, UUID partId, int gridStep,
                                              boolean latch, float yawDegrees, @Nullable net.minecraft.nbt.NbtCompound savedBeNbt) {
        BlockPos primaryPos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
        int primaryOffX = gx - primaryPos.getX() * 16;
        int primaryOffY = gy - primaryPos.getY() * 16;
        int primaryOffZ = gz - primaryPos.getZ() * 16;

        List<int[]> localBoxes = getLocalBoxes16(stateToPlace, world);
        boolean renderedAssigned = false;

        double rad = Math.toRadians(yawDegrees);
        double absCos = Math.abs(Math.cos(rad));
        double absSin = Math.abs(Math.sin(rad));

        for (int[] lb : localBoxes) {
            double hw = (lb[3] - lb[0]) * 0.5;
            double hz = (lb[5] - lb[2]) * 0.5;
            double lcx = gx + (lb[0] + lb[3]) * 0.5;
            double lcz = gz + (lb[2] + lb[5]) * 0.5;
            double extX = hw * absCos + hz * absSin;
            double extZ = hw * absSin + hz * absCos;

            int gMinX = (int) Math.floor(lcx - extX);
            int gMaxX = (int) Math.ceil(lcx + extX);
            int gMinY = gy + lb[1];
            int gMaxY = gy + lb[4];
            int gMinZ = (int) Math.floor(lcz - extZ);
            int gMaxZ = (int) Math.ceil(lcz + extZ);

            int cMinX = Math.floorDiv(gMinX, 16);
            int cMaxX = Math.floorDiv(gMaxX - 1, 16);
            int cMinY = Math.floorDiv(gMinY, 16);
            int cMaxY = Math.floorDiv(gMaxY - 1, 16);
            int cMinZ = Math.floorDiv(gMinZ, 16);
            int cMaxZ = Math.floorDiv(gMaxZ - 1, 16);

            for (int cx = cMinX; cx <= cMaxX; cx++) {
                for (int cy = cMinY; cy <= cMaxY; cy++) {
                    for (int cz = cMinZ; cz <= cMaxZ; cz++) {
                        BlockPos cellPos = new BlockPos(cx, cy, cz);
                        int lx0 = Math.max(0, gMinX - cx * 16);
                        int ly0 = Math.max(0, gMinY - cy * 16);
                        int lz0 = Math.max(0, gMinZ - cz * 16);
                        int lx1 = Math.min(16, gMaxX - cx * 16);
                        int ly1 = Math.min(16, gMaxY - cy * 16);
                        int lz1 = Math.min(16, gMaxZ - cz * 16);

                        if (lx1 <= lx0 || ly1 <= ly0 || lz1 <= lz0) continue;

                        SubGridBlockEntity sgbe = getOrConvertCellToSubGrid(world, cellPos);
                        if (sgbe == null) continue;

                        boolean isRenderSlice = (!renderedAssigned && cellPos.equals(primaryPos));
                        if (isRenderSlice) {
                            renderedAssigned = true;
                        }

                        SubGridBlockEntity.CellSlice slice = new SubGridBlockEntity.CellSlice(
                                partId, stateToPlace,
                                lx0, ly0, lz0, lx1, ly1, lz1,
                                isRenderSlice,
                                primaryOffX, primaryOffY, primaryOffZ,
                                primaryPos, gridStep, latch, yawDegrees
                        );
                        if (isRenderSlice) {
                            slice.savedInnerBeNbt = savedBeNbt;
                            slice.getOrCreateInnerBlockEntity(world);
                        }
                        sgbe.addSlice(slice);
                    }
                }
            }
        }
    }

    private static SubGridBlockEntity getOrConvertCellToSubGrid(World world, BlockPos cellPos) {
        BlockState existing = world.getBlockState(cellPos);
        if (existing.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(cellPos);
            if (be instanceof SubGridBlockEntity sgbe) return sgbe;
        }

        boolean hadWater = existing.getFluidState().isIn(FluidTags.WATER) && existing.getFluidState().isStill();

        BlockState preserveVanilla = null;
        int preserveOffsetY = 0;
        if (existing.getBlock() instanceof SlabBlock) {
            SlabType t = existing.get(SlabBlock.TYPE);
            preserveVanilla = existing.with(SlabBlock.TYPE, SlabType.BOTTOM);
            preserveOffsetY = (t == SlabType.TOP) ? 8 : 0;
        } else if (existing.getBlock() instanceof StairsBlock) {
            preserveVanilla = existing;
        }

        BlockState containerState = SubGridMod.SUBGRID_CONTAINER.getDefaultState()
                .with(SubGridBlock.WATERLOGGED, hadWater);
        world.setBlockState(cellPos, containerState, Block.NOTIFY_ALL);
        BlockEntity be = world.getBlockEntity(cellPos);
        if (!(be instanceof SubGridBlockEntity sgbe)) return null;

        if (preserveVanilla != null) {
            UUID vanillaId = UUID.randomUUID();
            List<int[]> boxes = getLocalBoxes16(preserveVanilla, world);
            boolean first = true;
            for (int[] b : boxes) {
                sgbe.addSlice(new SubGridBlockEntity.CellSlice(
                        vanillaId, preserveVanilla,
                        b[0], b[1] + preserveOffsetY, b[2],
                        b[3], b[4] + preserveOffsetY, b[5],
                        first, 0, preserveOffsetY, 0,
                        cellPos, 8, false, 0.0f
                ));
                first = false;
            }
        }
        return sgbe;
    }

    public static boolean tryBreakTargetedPart(World world, ServerPlayerEntity player, BlockPos pos, SubGridBlockEntity sgbe) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(6.5));
        SubGridBlockEntity.CellSlice target = sgbe.raycastSlice(eye, end);
        if (target == null) return true;

        UUID partId = target.partId;
        BlockPos anchor = target.primaryPos;
        BlockState removedState = target.storedState;
        int gx = anchor.getX() * 16 + target.offsetX;
        int gy = anchor.getY() * 16 + target.offsetY;
        int gz = anchor.getZ() * 16 + target.offsetZ;

        removePartEverywhere(world, anchor, partId);
        updateNeighborConnectionsAround(world, gx, gy, gz);

        BlockSoundGroup soundGroup = removedState.getSoundGroup();
        world.playSound(null, pos, soundGroup.getBreakSound(), SoundCategory.BLOCKS, 1.0f, soundGroup.getPitch());
        if (!player.isCreative()) {
            if (removedState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                Block.dropStacks(removedState.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), world, pos);
            } else {
                Block.dropStacks(removedState, world, pos);
            }
        }
        return false;
    }

    public static void removePartEverywhere(World world, BlockPos anchor, UUID partId) {
        for (int dx = -2; dx <= 3; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 3; dz++) {
                    BlockPos p = anchor.add(dx, dy, dz);
                    BlockEntity be = world.getBlockEntity(p);
                    if (be instanceof SubGridBlockEntity cellBe) {
                        if (cellBe.removeSlicesByPartId(partId)) {
                            if (cellBe.getSlices().isEmpty()) {
                                BlockState st = world.getBlockState(p);
                                boolean water = st.isOf(SubGridMod.SUBGRID_CONTAINER) && st.get(SubGridBlock.WATERLOGGED);
                                world.setBlockState(p, water ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void removePartEverywhereOnBreak(World world, BlockPos anchor, UUID partId, BlockPos brokenPos) {
        for (int dx = -2; dx <= 3; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -2; dz <= 3; dz++) {
                    BlockPos p = anchor.add(dx, dy, dz);
                    if (p.equals(brokenPos)) continue;
                    BlockEntity be = world.getBlockEntity(p);
                    if (be instanceof SubGridBlockEntity cellBe) {
                        if (cellBe.removeSlicesByPartId(partId)) {
                            if (cellBe.getSlices().isEmpty()) {
                                BlockState st = world.getBlockState(p);
                                boolean water = st.isOf(SubGridMod.SUBGRID_CONTAINER) && st.get(SubGridBlock.WATERLOGGED);
                                world.setBlockState(p, water ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                            }
                        }
                    }
                }
            }
        }
    }

    public static boolean shiftPartBy16(World world, BlockPos anchor, UUID partId, int stepX16, int stepY16, int stepZ16) {
        SubGridBlockEntity.CellSlice primarySlice = findPrimarySlice(world, anchor, partId);
        if (primarySlice == null) return false;

        int newGx = primarySlice.primaryPos.getX() * 16 + primarySlice.offsetX + stepX16;
        int newGy = primarySlice.primaryPos.getY() * 16 + primarySlice.offsetY + stepY16;
        int newGz = primarySlice.primaryPos.getZ() * 16 + primarySlice.offsetZ + stepZ16;

        BlockState state = primarySlice.storedState;
        int gridStep = primarySlice.gridStepPlaced;
        boolean latch = primarySlice.toggleLatchPower;
        float yaw = primarySlice.yawDegrees;
        net.minecraft.nbt.NbtCompound savedNbt = (primarySlice.innerBlockEntity != null)
                ? primarySlice.innerBlockEntity.createNbt()
                : primarySlice.savedInnerBeNbt;

        if (!canPlaceAtGlobal16(world, newGx, newGy, newGz, state, yaw, partId, false)) {
            return false;
        }

        removePartEverywhere(world, primarySlice.primaryPos, partId);
        BlockState updatedState = updateConnectionsForState(world, newGx, newGy, newGz, state, partId);
        placeAtGlobal16WithNbt(world, newGx, newGy, newGz, updatedState, partId, gridStep, latch, yaw, savedNbt);
        return true;
    }

    public static void tickServer(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getServerWorld();
            BlockPos center = player.getBlockPos();
            Set<UUID> processedParts = new HashSet<>();

            for (int dx = -7; dx <= 7; dx++) {
                for (int dy = -5; dy <= 5; dy++) {
                    for (int dz = -7; dz <= 7; dz++) {
                        BlockPos bp = center.add(dx, dy, dz);
                        BlockState st = world.getBlockState(bp);

                        if (st.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                            BlockEntity be = world.getBlockEntity(bp);
                            if (be instanceof SubGridBlockEntity sgbe) {
                                tickSurvivalGravityAndBlockEntitiesInCell(world, bp, sgbe, processedParts);
                            }
                        }

                        if (st.getBlock() instanceof PistonBlock && st.contains(Properties.FACING)) {
                            boolean powered = world.isReceivingRedstonePower(bp);
                            boolean wasPowered = PISTON_POWER_CACHE.getOrDefault(bp, false);
                            if (powered != wasPowered) {
                                PISTON_POWER_CACHE.put(bp.toImmutable(), powered);
                                Direction facing = st.get(Properties.FACING);
                                boolean isSticky = (st.getBlock() == Blocks.STICKY_PISTON);
                                if (powered) {
                                    pushOrPullSubGridInFrontOfPiston(world, bp.offset(facing), facing);
                                } else if (isSticky) {
                                    pushOrPullSubGridInFrontOfPiston(world, bp.offset(facing), facing.getOpposite());
                                }
                            }
                        } else if (st.isOf(SubGridMod.SUBGRID_ACTUATOR)) {
                            if (world.isReceivingRedstonePower(bp)) {
                                Direction dir = player.getHorizontalFacing();
                                pushOrPullSubGridInFrontOfPiston(world, bp.up(), dir);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void tickSurvivalGravityAndBlockEntitiesInCell(ServerWorld world, BlockPos cellPos,
                                                                  SubGridBlockEntity sgbe, Set<UUID> processedParts) {
        List<SubGridBlockEntity.CellSlice> copy = new ArrayList<>(sgbe.getSlices());
        for (SubGridBlockEntity.CellSlice s : copy) {
            if (!s.renderHere) {
                BlockEntity primaryBe = world.getBlockEntity(s.primaryPos);
                boolean primaryExists = false;
                if (primaryBe instanceof SubGridBlockEntity pSgbe) {
                    for (SubGridBlockEntity.CellSlice ps : pSgbe.getSlices()) {
                        if (ps.partId.equals(s.partId) && ps.renderHere) {
                            primaryExists = true;
                            break;
                        }
                    }
                }
                if (!primaryExists) {
                    sgbe.removeSlicesByPartId(s.partId);
                    if (sgbe.getSlices().isEmpty()) {
                        world.setBlockState(cellPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
                continue;
            }

            if (processedParts.contains(s.partId)) continue;
            processedParts.add(s.partId);

            // 1. Tick del BlockEntity nativo en servidor (Hornos cocinando, Soportes de pociones, Tolvas, Fogatas, etc.)
            s.tickInnerBlockEntity(world);

            int gx = s.primaryPos.getX() * 16 + s.offsetX;
            int gy = s.primaryPos.getY() * 16 + s.offsetY;
            int gz = s.primaryPos.getZ() * 16 + s.offsetZ;

            // 2. Supervivencia Vanilla Nativa (`s.storedState.canPlaceAt(world, basePos)` dentro de `SubGridWorldContext`):
            // Si perdió el bloque que lo sostenía (ej. riel o escalera en el aire, cactus con bloque al lado), se rompe y suelta su ítem!
            if (!canBlockSurviveNative(world, gx, gy, gz, s.storedState, s.partId)) {
                BlockState brokenState = s.storedState;
                removePartEverywhere(world, s.primaryPos, s.partId);
                BlockSoundGroup sg = brokenState.getSoundGroup();
                world.playSound(null, cellPos, sg.getBreakSound(), SoundCategory.BLOCKS, 1.0f, sg.getPitch());
                if (!(brokenState.contains(Properties.DOUBLE_BLOCK_HALF) && brokenState.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)) {
                    Block.dropStacks(brokenState, world, cellPos);
                }
                continue;
            }

            // 3. Gravedad real para bloques FallingBlock (Arena, Grava, Polvo de Hormigón, Yunques, Huevo de Dragón)
            if (s.storedState.getBlock() instanceof FallingBlock) {
                int fallSteps = 0;
                for (int step = 1; step <= 4; step++) {
                    if (canPlaceAtGlobal16(world, gx, gy - step, gz, s.storedState, s.yawDegrees, s.partId, false)) {
                        fallSteps = step;
                    } else {
                        break;
                    }
                }
                if (fallSteps > 0) {
                    shiftPartBy16(world, s.primaryPos, s.partId, 0, -fallSteps, 0);
                }
            }
        }
    }

    private static void pushOrPullSubGridInFrontOfPiston(ServerWorld world, BlockPos targetPos, Direction moveDir) {
        BlockEntity be = world.getBlockEntity(targetPos);
        if (!(be instanceof SubGridBlockEntity sgbe)) return;

        List<SubGridBlockEntity.CellSlice> copy = new ArrayList<>(sgbe.getSlices());
        boolean movedAny = false;
        for (SubGridBlockEntity.CellSlice s : copy) {
            if (!s.renderHere) continue;
            int step = Math.max(1, Math.min(8, s.gridStepPlaced));
            int sx = moveDir.getOffsetX() * step;
            int sy = moveDir.getOffsetY() * step;
            int sz = moveDir.getOffsetZ() * step;
            if (shiftPartBy16(world, s.primaryPos, s.partId, sx, sy, sz)) {
                movedAny = true;
            }
        }
        if (movedAny) {
            world.playSound(null, targetPos, SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.BLOCKS, 0.6f, 1.3f);
        }
    }
}
