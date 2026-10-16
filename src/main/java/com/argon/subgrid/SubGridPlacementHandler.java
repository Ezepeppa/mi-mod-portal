package com.argon.subgrid;

import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.HorizontalConnectingBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SmithingTableBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.BlockHalf;
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

        // 1. Soporte para Mechero (Flint and Steel) y Carga Ígnea (Fire Charge) sobre bloques en Sub-Grid / Off-Grid
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

        // Si el jugador hace clic derecho normal sobre una pieza interactuable dentro del SubGridBlock sin agacharse, usarla
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

        int gridStep = world.isClient
                ? SubGridClientState.currentGridStep
                : SubGridMod.getPlayerGridStep(player.getUuid());

        boolean combiningWithExistingSlabOrStair = (clickedState.getBlock() instanceof SlabBlock
                || clickedState.getBlock() instanceof StairsBlock
                || clickedState.isOf(SubGridMod.SUBGRID_CONTAINER))
                && (blockToPlace instanceof SlabBlock || blockToPlace instanceof StairsBlock);

        // Si está en Grid 1/1 normal y no está combinando Slabs/Escaleras, dejar que Minecraft Vanilla coloque el bloque original puro!
        if (gridStep == 16 && !combiningWithExistingSlabOrStair) {
            return ActionResult.PASS;
        }

        int effectiveStep = (gridStep == 16 && combiningWithExistingSlabOrStair) ? 8 : gridStep;
        BlockState placementState = getDesiredBlockState(player, world, hand, stack, blockItem, hitResult, effectiveStep);
        if (placementState == null) {
            return ActionResult.PASS;
        }

        int[] globalOrigin16 = computeGlobalOrigin16(hitResult, placementState, effectiveStep, world);
        float yawDegrees = (effectiveStep == 1) ? MathHelper.wrapDegrees(180.0f - player.getYaw()) : 0.0f;

        // Si las coordenadas calculadas caen exactamente en la grilla vanilla (múltiplo de 16 en X, Y, Z y sin rotación Off-Grid)
        // y la celda de destino está libre, colocar directamente el bloque Vanilla original en el mundo!
        if (globalOrigin16[0] % 16 == 0 && globalOrigin16[1] % 16 == 0 && globalOrigin16[2] % 16 == 0
                && Math.abs(yawDegrees) < 0.01f && !combiningWithExistingSlabOrStair) {
            BlockPos exactPos = new BlockPos(globalOrigin16[0] / 16, globalOrigin16[1] / 16, globalOrigin16[2] / 16);
            BlockState existingAtExact = world.getBlockState(exactPos);
            if ((existingAtExact.isAir() || existingAtExact.isReplaceable()) && !existingAtExact.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                if (!canPlaceAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, null, true)) {
                    return ActionResult.FAIL;
                }
                if (!world.isClient) {
                    BlockState connectedVanilla = updateConnectionsForState(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, null);
                    world.setBlockState(exactPos, connectedVanilla, Block.NOTIFY_ALL);
                    connectedVanilla.getBlock().onPlaced(world, exactPos, connectedVanilla, player, stack);
                    BlockSoundGroup sg = connectedVanilla.getSoundGroup();
                    world.playSound(null, exactPos, sg.getPlaceSound(), SoundCategory.BLOCKS, (sg.getVolume() + 1.0f) / 2.0f, sg.getPitch() * 0.8f);
                    if (!player.getAbilities().creativeMode) {
                        stack.decrement(1);
                    }
                }
                return ActionResult.SUCCESS;
            }
        }

        // Verificar que no haya solapamiento con bloques ni con el jugador/entidades paradas en ese lugar
        if (!canPlaceAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, null, true)) {
            return ActionResult.FAIL;
        }

        if (!world.isClient) {
            UUID newPartId = UUID.randomUUID();
            BlockState connectedState = updateConnectionsForState(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, newPartId);
            placeAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2],
                    connectedState, newPartId, effectiveStep, false, yawDegrees);
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

        if (!canPlaceAtGlobal16(world, fireOrigin16[0], fireOrigin16[1], fireOrigin16[2], fireState, null, false)) {
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

    private static boolean isInteractiveBlock(Block b) {
        return b instanceof CraftingTableBlock || b instanceof StonecutterBlock || b instanceof LoomBlock
                || b instanceof CartographyTableBlock || b instanceof SmithingTableBlock
                || b instanceof AnvilBlock || b instanceof GrindstoneBlock
                || b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof DaylightDetectorBlock
                || b instanceof NoteBlock || b instanceof BellBlock || b instanceof RedstoneLampBlock;
    }

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
        BlockState st = null;
        try {
            ItemPlacementContext ctx = new ItemPlacementContext(player, hand, stack, hit);
            st = b.getPlacementState(ctx);
        } catch (Exception ignored) {
        }
        if (st == null) {
            st = b.getDefaultState();
        }
        // En Modo Off-Grid (effectiveStep == 1), normalizar HORIZONTAL_FACING a NORTH para que la rotación continua Y (yawDegrees) no se duplique
        if (effectiveStep == 1 && st.contains(Properties.HORIZONTAL_FACING)) {
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

    public static int[] computeGlobalOrigin16(BlockHitResult hit, BlockState stateToPlace, int gridStep, @Nullable BlockView world) {
        Vec3d p = hit.getPos();
        Direction face = hit.getSide();

        double px = p.x * 16.0;
        double py = p.y * 16.0;
        double pz = p.z * 16.0;

        int[] dims = getBoundingDimensions16(stateToPlace, world);
        int sizeX = dims[0];
        int sizeY = dims[1];
        int sizeZ = dims[2];

        int gx = snapCoord(px - sizeX * 0.5, gridStep);
        int gy = snapCoord(py - sizeY * 0.5, gridStep);
        int gz = snapCoord(pz - sizeZ * 0.5, gridStep);

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
        VoxelShape outline = getSafeOriginalOutlineShape(state, world, BlockPos.ORIGIN);
        if (!outline.isEmpty()) {
            Box box = outline.getBoundingBox();
            int sx = Math.max(2, Math.min(16, (int) Math.round((box.maxX - box.minX) * 16.0)));
            int sy = Math.max(2, Math.min(16, (int) Math.round((box.maxY - box.minY) * 16.0)));
            int sz = Math.max(2, Math.min(16, (int) Math.round((box.maxZ - box.minZ) * 16.0)));
            if (sx >= 12 && sz >= 12) {
                return new int[]{16, sy, 16};
            }
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

    public static boolean canPlaceAtGlobal16(World world, int gx, int gy, int gz,
                                             BlockState stateToPlace, @Nullable UUID ignorePartId, boolean checkEntities) {
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

        for (int[] lb : localBoxes) {
            int gMinX = gx + lb[0], gMinY = gy + lb[1], gMinZ = gz + lb[2];
            int gMaxX = gx + lb[3], gMaxY = gy + lb[4], gMaxZ = gz + lb[5];

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

                        BlockState existing = world.getBlockState(cellPos);
                        if (existing.isAir() || existing.isReplaceable()) {
                            continue;
                        }

                        if (existing.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                            BlockEntity be = world.getBlockEntity(cellPos);
                            if (be instanceof SubGridBlockEntity sgbe) {
                                if (sgbe.hasOverlap(world, lx0, ly0, lz0, lx1, ly1, lz1, ignorePartId)) {
                                    return false;
                                }
                            }
                            continue;
                        }

                        if (existing.getBlock() instanceof SlabBlock || existing.getBlock() instanceof StairsBlock) {
                            List<int[]> vanillaBoxes = getVanillaSlabOrStairBoxes16(existing, world);
                            for (int[] vb : vanillaBoxes) {
                                if (lx0 < vb[3] && lx1 > vb[0] && ly0 < vb[4] && ly1 > vb[1] && lz0 < vb[5] && lz1 > vb[2]) {
                                    return false;
                                }
                            }
                            continue;
                        }

                        VoxelShape existingShape = getSafeOriginalOutlineShape(existing, world, cellPos);
                        for (Box eb : existingShape.getBoundingBoxes()) {
                            int ex0 = (int) Math.floor(eb.minX * 16.0 + 1e-4);
                            int ey0 = (int) Math.floor(eb.minY * 16.0 + 1e-4);
                            int ez0 = (int) Math.floor(eb.minZ * 16.0 + 1e-4);
                            int ex1 = (int) Math.ceil(eb.maxX * 16.0 - 1e-4);
                            int ey1 = (int) Math.ceil(eb.maxY * 16.0 - 1e-4);
                            int ez1 = (int) Math.ceil(eb.maxZ * 16.0 - 1e-4);
                            if (lx0 < ex1 && lx1 > ex0 && ly0 < ey1 && ly1 > ey0 && lz0 < ez1 && lz1 > ez0) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    private static List<int[]> getVanillaSlabOrStairBoxes16(BlockState existing, BlockView world) {
        if (existing.getBlock() instanceof SlabBlock) {
            List<int[]> list = new ArrayList<>();
            SlabType type = existing.get(SlabBlock.TYPE);
            if (type == SlabType.BOTTOM) {
                list.add(new int[]{0, 0, 0, 16, 8, 16});
            } else if (type == SlabType.TOP) {
                list.add(new int[]{0, 8, 0, 16, 16, 16});
            } else {
                list.add(new int[]{0, 0, 0, 16, 16, 16});
            }
            return list;
        }
        return getLocalBoxes16(existing, world);
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
                    if (Math.abs(sGx - targetGx) <= 2 && Math.abs(sGy - targetGy) <= 4 && Math.abs(sGz - targetGz) <= 2) {
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
        BlockPos primaryPos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
        int primaryOffX = gx - primaryPos.getX() * 16;
        int primaryOffY = gy - primaryPos.getY() * 16;
        int primaryOffZ = gz - primaryPos.getZ() * 16;

        List<int[]> localBoxes = getLocalBoxes16(stateToPlace, world);
        boolean renderedAssigned = false;

        for (int[] lb : localBoxes) {
            int gMinX = gx + lb[0], gMinY = gy + lb[1], gMinZ = gz + lb[2];
            int gMaxX = gx + lb[3], gMaxY = gy + lb[4], gMaxZ = gz + lb[5];

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
            Block.dropStacks(removedState, world, pos);
        }
        return false;
    }

    public static void removePartEverywhere(World world, BlockPos anchor, UUID partId) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
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
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
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
        SubGridBlockEntity.CellSlice primarySlice = null;
        for (int dx = -1; dx <= 1 && primarySlice == null; dx++) {
            for (int dy = -1; dy <= 1 && primarySlice == null; dy++) {
                for (int dz = -1; dz <= 1 && primarySlice == null; dz++) {
                    BlockEntity be = world.getBlockEntity(anchor.add(dx, dy, dz));
                    if (be instanceof SubGridBlockEntity sgbe) {
                        for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                            if (s.partId.equals(partId) && s.renderHere) {
                                primarySlice = s;
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (primarySlice == null) return false;

        int newGx = primarySlice.primaryPos.getX() * 16 + primarySlice.offsetX + stepX16;
        int newGy = primarySlice.primaryPos.getY() * 16 + primarySlice.offsetY + stepY16;
        int newGz = primarySlice.primaryPos.getZ() * 16 + primarySlice.offsetZ + stepZ16;

        BlockState state = primarySlice.storedState;
        int gridStep = primarySlice.gridStepPlaced;
        boolean latch = primarySlice.toggleLatchPower;
        float yaw = primarySlice.yawDegrees;

        if (!canPlaceAtGlobal16(world, newGx, newGy, newGz, state, partId, false)) {
            return false;
        }

        removePartEverywhere(world, primarySlice.primaryPos, partId);
        BlockState updatedState = updateConnectionsForState(world, newGx, newGy, newGz, state, partId);
        placeAtGlobal16(world, newGx, newGy, newGz, updatedState, partId, gridStep, latch, yaw);
        return true;
    }

    public static void tickServer(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getServerWorld();
            BlockPos center = player.getBlockPos();
            Set<UUID> processedFallingParts = new HashSet<>();

            for (int dx = -7; dx <= 7; dx++) {
                for (int dy = -5; dy <= 5; dy++) {
                    for (int dz = -7; dz <= 7; dz++) {
                        BlockPos bp = center.add(dx, dy, dz);
                        BlockState st = world.getBlockState(bp);

                        if (st.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                            BlockEntity be = world.getBlockEntity(bp);
                            if (be instanceof SubGridBlockEntity sgbe) {
                                tickGravityAndOrphansInCell(world, bp, sgbe, processedFallingParts);
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

    private static void tickGravityAndOrphansInCell(ServerWorld world, BlockPos cellPos,
                                                    SubGridBlockEntity sgbe, Set<UUID> processedFallingParts) {
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

            if (s.storedState.getBlock() instanceof FallingBlock && !processedFallingParts.contains(s.partId)) {
                processedFallingParts.add(s.partId);
                int gx = s.primaryPos.getX() * 16 + s.offsetX;
                int gy = s.primaryPos.getY() * 16 + s.offsetY;
                int gz = s.primaryPos.getZ() * 16 + s.offsetZ;

                int fallSteps = 0;
                for (int step = 1; step <= 4; step++) {
                    if (canPlaceAtGlobal16(world, gx, gy - step, gz, s.storedState, s.partId, false)) {
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
