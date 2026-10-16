package com.argon.subgrid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SubGridPlacementHandler {
    private static final Map<BlockPos, Boolean> PISTON_POWER_CACHE = new HashMap<>();
    private static int tickCounter = 0;

    public static void updatePlayerGridOnServer(ServerPlayerEntity player, int newStep) {
        int validStep = switch (newStep) {
            case 1 -> 1;   // Modo Secreto Off-Grid (1/16 libre)
            case 2 -> 2;   // Grid 1/8 (2px)
            case 4 -> 4;   // Grid 1/4 (4px)
            case 8 -> 8;   // Grid 1/2 (8px)
            default -> 16; // Grid 1/1 (16px Normal)
        };
        SubGridMod.setPlayerGridStep(player.getUuid(), validStep);

        String label = switch (validStep) {
            case 1 -> "MODO SECRETO: OFF-GRID LIBRE (1/16 px)";
            case 2 -> "1/8 de Bloque (2 px - Modo Micro-Memoria)";
            case 4 -> "1/4 de Bloque (4 px - Modo Diodo Direccional)";
            case 8 -> "1/2 de Bloque (8 px - Mitad exacta)";
            default -> "1/1 Normal (Grilla original)";
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

        // Si no sostiene un bloque (o sostiene algo que no es BlockItem), permitir interactuar con SubGridBlock
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.PASS;
        }

        Block blockToPlace = blockItem.getBlock();
        if (blockToPlace == SubGridMod.SUBGRID_CONTAINER || blockToPlace == SubGridMod.SUBGRID_ACTUATOR) {
            return ActionResult.PASS;
        }

        // Si el jugador hace clic derecho normal sobre una estación interactuable dentro del SubGridBlock sin agacharse, abrir la estación
        if (!player.isSneaking() && clickedState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            BlockEntity be = world.getBlockEntity(hitPos);
            if (be instanceof SubGridBlockEntity sgbe) {
                Vec3d eye = player.getEyePos();
                SubGridBlockEntity.CellSlice targeted = sgbe.raycastSlice(eye, eye.add(player.getRotationVec(1.0F).multiply(6.0)));
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

        // Si está en Grid 1/1 normal y no está combinando Slabs/Escaleras ni tocando un SubGridBlock, usar colocación vanilla
        if (gridStep == 16 && !combiningWithExistingSlabOrStair && !clickedState.isOf(SubGridMod.SUBGRID_CONTAINER)) {
            return ActionResult.PASS;
        }

        BlockState placementState = getDesiredBlockState(player, world, hand, stack, blockItem, hitResult);
        if (placementState == null) {
            return ActionResult.PASS;
        }

        int effectiveStep = (gridStep == 16 && combiningWithExistingSlabOrStair) ? 8 : gridStep;
        int[] globalOrigin16 = computeGlobalOrigin16(hitResult, placementState, effectiveStep);

        if (!canPlaceAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], placementState, null)) {
            return ActionResult.FAIL;
        }

        if (!world.isClient) {
            placeAtGlobal16(world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2],
                    placementState, UUID.randomUUID(), effectiveStep, false);
            BlockSoundGroup sg = placementState.getSoundGroup();
            world.playSound(null, hitResult.getPos().x, hitResult.getPos().y, hitResult.getPos().z,
                    sg.getPlaceSound(), SoundCategory.BLOCKS, (sg.getVolume() + 1.0f) / 2.0f, sg.getPitch() * 0.8f);
            if (!player.getAbilities().creativeMode) {
                stack.decrement(1);
            }
        }
        return ActionResult.SUCCESS;
    }

    private static boolean isInteractiveBlock(Block b) {
        String name = RegistriesName(b);
        return name.contains("crafting_table") || name.contains("lever") || name.contains("button")
                || name.contains("door") || name.contains("repeater") || name.contains("note_block")
                || name.contains("stonecutter") || name.contains("anvil") || name.contains("grindstone");
    }

    private static String RegistriesName(Block b) {
        return b.getTranslationKey();
    }

    public static BlockState getDesiredBlockState(PlayerEntity player, World world, Hand hand,
                                                  ItemStack stack, BlockItem blockItem, BlockHitResult hit) {
        Block b = blockItem.getBlock();
        if (b instanceof SlabBlock) {
            // Para Slabs en Sub-Grid siempre usamos el modelo BOTTOM y su posición Y exacta la define el offset de Sub-Grid
            return b.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM).with(SlabBlock.WATERLOGGED, false);
        }
        if (b instanceof StairsBlock) {
            Direction horiz = player.getHorizontalFacing();
            // Si el jugador se agacha o apunta a la mitad superior de una pared, coloca la escalera invertida boca abajo (TOP)
            boolean upsideDown = (hit.getSide() == Direction.DOWN)
                    || (hit.getSide().getAxis().isHorizontal() && (hit.getPos().y - Math.floor(hit.getPos().y)) > 0.5)
                    || (player.isSneaking() && hit.getSide() == Direction.UP);
            return b.getDefaultState()
                    .with(StairsBlock.FACING, horiz)
                    .with(StairsBlock.HALF, upsideDown ? BlockHalf.TOP : BlockHalf.BOTTOM)
                    .with(StairsBlock.WATERLOGGED, false);
        }
        ItemPlacementContext ctx = new ItemPlacementContext(player, hand, stack, hit);
        BlockState st = b.getPlacementState(ctx);
        return st != null ? st : b.getDefaultState();
    }

    /**
     * Calcula la coordenada global en dieciseisavos (16 unidades = 1 bloque) donde se apoyará el bloque
     * según el punto exacto de la mira `hitResult.getPos()`, la cara `face` y el paso de Grid `gridStep`.
     */
    public static int[] computeGlobalOrigin16(BlockHitResult hit, BlockState stateToPlace, int gridStep) {
        Vec3d p = hit.getPos();
        Direction face = hit.getSide();

        double px = p.x * 16.0;
        double py = p.y * 16.0;
        double pz = p.z * 16.0;

        int[] dims = getBoundingDimensions16(stateToPlace);
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

    public static int[] getBoundingDimensions16(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof SlabBlock) {
            return new int[]{16, 8, 16};
        }
        if (b instanceof CarpetBlock || b instanceof RedstoneWireBlock) {
            return new int[]{16, 2, 16};
        }
        if (b instanceof TrapdoorBlock && state.contains(TrapdoorBlock.OPEN) && !state.get(TrapdoorBlock.OPEN)) {
            return new int[]{16, 3, 16};
        }
        return new int[]{16, 16, 16};
    }

    /**
     * Devuelve las cajas locales en [0..16]^3 del bloque.
     * Las Escaleras (`StairsBlock`) usan sus 2 cajas reales en forma de "L" para permitir que otra escalera
     * invertida boca abajo o una losa encaje exactamente en su rincón vacío sin solaparse.
     */
    public static List<int[]> getLocalBoxes16(BlockState state) {
        List<int[]> boxes = new ArrayList<>();
        Block b = state.getBlock();

        if (b instanceof SlabBlock) {
            boxes.add(new int[]{0, 0, 0, 16, 8, 16});
            return boxes;
        }

        if (b instanceof StairsBlock) {
            BlockHalf half = state.contains(StairsBlock.HALF) ? state.get(StairsBlock.HALF) : BlockHalf.BOTTOM;
            Direction facing = state.contains(StairsBlock.FACING) ? state.get(StairsBlock.FACING) : Direction.NORTH;

            if (half == BlockHalf.BOTTOM) {
                boxes.add(new int[]{0, 0, 0, 16, 8, 16});
                switch (facing) {
                    case NORTH -> boxes.add(new int[]{0, 8, 0, 16, 16, 8});
                    case SOUTH -> boxes.add(new int[]{0, 8, 8, 16, 16, 16});
                    case WEST -> boxes.add(new int[]{0, 8, 0, 8, 16, 16});
                    case EAST -> boxes.add(new int[]{8, 8, 0, 16, 16, 16});
                    default -> boxes.add(new int[]{0, 8, 0, 16, 16, 8});
                }
            } else {
                boxes.add(new int[]{0, 8, 0, 16, 16, 16});
                switch (facing) {
                    case NORTH -> boxes.add(new int[]{0, 0, 0, 16, 8, 8});
                    case SOUTH -> boxes.add(new int[]{0, 0, 8, 16, 8, 16});
                    case WEST -> boxes.add(new int[]{0, 0, 0, 8, 8, 16});
                    case EAST -> boxes.add(new int[]{8, 0, 0, 16, 8, 16});
                    default -> boxes.add(new int[]{0, 0, 0, 16, 8, 8});
                }
            }
            return boxes;
        }

        int[] dims = getBoundingDimensions16(state);
        boxes.add(new int[]{0, 0, 0, dims[0], dims[1], dims[2]});
        return boxes;
    }

    /**
     * Verifica inteligentemente que ninguna sub-caja del nuevo bloque se solape con ningún bloque
     * existente (sea de otra Grid o un bloque vanilla) en todas las celdas que intersecta.
     */
    public static boolean canPlaceAtGlobal16(World world, int gx, int gy, int gz,
                                             BlockState stateToPlace, UUID ignorePartId) {
        List<int[]> localBoxes = getLocalBoxes16(stateToPlace);

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
                                if (sgbe.hasOverlap(lx0, ly0, lz0, lx1, ly1, lz1, ignorePartId)) {
                                    return false;
                                }
                            }
                            continue;
                        }

                        // Si hay una Slab o Escalera vanilla, verificar si las cajas en 1/16 se solapan (permite combinar Slabs/Escaleras!)
                        if (existing.getBlock() instanceof SlabBlock || existing.getBlock() instanceof StairsBlock) {
                            List<int[]> vanillaBoxes = getVanillaSlabOrStairBoxes16(existing);
                            for (int[] vb : vanillaBoxes) {
                                if (lx0 < vb[3] && lx1 > vb[0] && ly0 < vb[4] && ly1 > vb[1] && lz0 < vb[5] && lz1 > vb[2]) {
                                    return false;
                                }
                            }
                            continue;
                        }

                        // Cualquier otro bloque sólido vanilla ocupa [0..16]^3
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static List<int[]> getVanillaSlabOrStairBoxes16(BlockState existing) {
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
        return getLocalBoxes16(existing);
    }

    /**
     * Inserta el bloque real en todas las celdas que abarca su geometría en la Sub-Grid,
     * convirtiendo automáticamente Slabs o Escaleras vanilla si se están combinando.
     */
    public static void placeAtGlobal16(World world, int gx, int gy, int gz,
                                       BlockState stateToPlace, UUID partId, int gridStep, boolean latch) {
        BlockPos primaryPos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
        int primaryOffX = gx - primaryPos.getX() * 16;
        int primaryOffY = gy - primaryPos.getY() * 16;
        int primaryOffZ = gz - primaryPos.getZ() * 16;

        List<int[]> localBoxes = getLocalBoxes16(stateToPlace);
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
                                primaryPos, gridStep, latch
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

        // Si había una Slab o Escalera vanilla, preservarla dentro del nuevo SubGridBlockEntity
        BlockState preserveVanilla = null;
        int preserveOffsetY = 0;
        if (existing.getBlock() instanceof SlabBlock) {
            SlabType t = existing.get(SlabBlock.TYPE);
            preserveVanilla = existing.with(SlabBlock.TYPE, SlabType.BOTTOM);
            preserveOffsetY = (t == SlabType.TOP) ? 8 : 0;
        } else if (existing.getBlock() instanceof StairsBlock) {
            preserveVanilla = existing;
        }

        world.setBlockState(cellPos, SubGridMod.SUBGRID_CONTAINER.getDefaultState(), Block.NOTIFY_ALL);
        BlockEntity be = world.getBlockEntity(cellPos);
        if (!(be instanceof SubGridBlockEntity sgbe)) return null;

        if (preserveVanilla != null) {
            UUID vanillaId = UUID.randomUUID();
            List<int[]> boxes = getLocalBoxes16(preserveVanilla);
            boolean first = true;
            for (int[] b : boxes) {
                sgbe.addSlice(new SubGridBlockEntity.CellSlice(
                        vanillaId, preserveVanilla,
                        b[0], b[1] + preserveOffsetY, b[2],
                        b[3], b[4] + preserveOffsetY, b[5],
                        first, 0, preserveOffsetY, 0,
                        cellPos, 8, false
                ));
                first = false;
            }
        }
        return sgbe;
    }

    /**
     * Rompe únicamente la pieza apuntada (`partId`) en todas las celdas que abarca.
     * Devuelve `false` para cancelar la destrucción completa del contenedor y manejar la limpieza por celda.
     */
    public static boolean tryBreakTargetedPart(World world, ServerPlayerEntity player, BlockPos pos, SubGridBlockEntity sgbe) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(6.0));
        SubGridBlockEntity.CellSlice target = sgbe.raycastSlice(eye, end);
        if (target == null) return true;

        UUID partId = target.partId;
        BlockPos anchor = target.primaryPos;
        BlockState removedState = target.storedState;

        removePartEverywhere(world, anchor, partId);

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
                                world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Desplaza una pieza ya colocada (`partId`) en `(stepX, stepY, stepZ)` dieciseisavos de bloque
     * (usado por Pistones Adaptativos, el Motor Lineal de Sub-Grid y el Calibrador de Precisión).
     */
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

        if (!canPlaceAtGlobal16(world, newGx, newGy, newGz, state, partId)) {
            return false;
        }

        removePartEverywhere(world, primarySlice.primaryPos, partId);
        placeAtGlobal16(world, newGx, newGy, newGz, state, partId, gridStep, latch);
        return true;
    }

    public static void tickServer(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 3 != 0) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerWorld world = player.getServerWorld();
            BlockPos center = player.getBlockPos();

            // 1. Escanear Pistones y Motores Lineales de Sub-Grid cercanos al jugador
            for (int dx = -6; dx <= 6; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -6; dz <= 6; dz++) {
                        BlockPos bp = center.add(dx, dy, dz);
                        BlockState st = world.getBlockState(bp);
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
