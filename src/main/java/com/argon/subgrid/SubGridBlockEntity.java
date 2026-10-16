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
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SlimeBlock;
import net.minecraft.block.SmithingTableBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SubGridBlockEntity extends BlockEntity {
    public static class CellSlice {
        public UUID partId;
        public BlockState storedState;
        public int minX, minY, minZ;
        public int maxX, maxY, maxZ;
        public boolean renderHere;
        public int offsetX, offsetY, offsetZ; // Desplazamiento en 1/16 respecto a primaryPos
        public BlockPos primaryPos;
        public int gridStepPlaced;
        public boolean toggleLatchPower;
        public float yawDegrees; // Rotación continua en el eje Y para el modo Off-Grid

        public CellSlice(UUID partId, BlockState storedState,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                         boolean renderHere, int offsetX, int offsetY, int offsetZ,
                         BlockPos primaryPos, int gridStepPlaced, boolean toggleLatchPower, float yawDegrees) {
            this.partId = partId;
            this.storedState = storedState;
            this.minX = Math.max(0, Math.min(16, minX));
            this.minY = Math.max(0, Math.min(16, minY));
            this.minZ = Math.max(0, Math.min(16, minZ));
            this.maxX = Math.max(0, Math.min(16, maxX));
            this.maxY = Math.max(0, Math.min(16, maxY));
            this.maxZ = Math.max(0, Math.min(16, maxZ));
            this.renderHere = renderHere;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.primaryPos = primaryPos;
            this.gridStepPlaced = gridStepPlaced;
            this.toggleLatchPower = toggleLatchPower;
            this.yawDegrees = yawDegrees;
        }

        public boolean intersects(int ax0, int ay0, int az0, int ax1, int ay1, int az1) {
            return this.minX < ax1 && this.maxX > ax0
                    && this.minY < ay1 && this.maxY > ay0
                    && this.minZ < az1 && this.maxZ > az0;
        }
    }

    private final List<CellSlice> slices = new ArrayList<>();
    private VoxelShape cachedCollisionShape = null;
    private boolean lastExternalPowered = false;

    public SubGridBlockEntity(BlockPos pos, BlockState state) {
        super(SubGridMod.SUBGRID_BLOCK_ENTITY, pos, state);
    }

    public List<CellSlice> getSlices() {
        return slices;
    }

    public void addSlice(CellSlice slice) {
        slices.add(slice);
        cachedCollisionShape = null;
        recalculateBlockProperties();
        markDirtyAndSync();
    }

    public boolean removeSlicesByPartId(UUID targetPartId) {
        boolean removed = slices.removeIf(s -> s.partId.equals(targetPartId));
        if (removed) {
            cachedCollisionShape = null;
            recalculateBlockProperties();
            markDirtyAndSync();
        }
        return removed;
    }

    public boolean hasOverlap(BlockView world, int x0, int y0, int z0, int x1, int y1, int z1,
                              @Nullable UUID ignorePartId) {
        for (CellSlice s : slices) {
            if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
            if (s.intersects(x0, y0, z0, x1, y1, z1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Devuelve el Outline COMPLETO y SIN CORTAR del bloque apuntado (o la unión de los bloques en esta celda),
     * desplazado desde `primaryPos` respecto a `pos`, para que el borde negro nativo de Minecraft
     * envuelva al bloque entero aunque esté desplazado fuera de la grilla normal.
     */
    public VoxelShape getUnclippedOutlineShape(BlockView world, BlockPos pos, ShapeContext context) {
        if (slices.isEmpty()) {
            return VoxelShapes.empty();
        }

        if (context instanceof EntityShapeContext esc && esc.getEntity() instanceof PlayerEntity player) {
            Vec3d eye = player.getEyePos();
            Vec3d look = player.getRotationVec(1.0F);
            CellSlice targeted = raycastSlice(eye, eye.add(look.multiply(6.5)));
            if (targeted != null) {
                VoxelShape fullTargeted = buildFullShiftedOutlineForSlice(world, pos, targeted);
                if (!fullTargeted.isEmpty()) {
                    return fullTargeted;
                }
            }
        }

        VoxelShape combined = VoxelShapes.empty();
        for (CellSlice s : slices) {
            VoxelShape fullSliceShape = buildFullShiftedOutlineForSlice(world, pos, s);
            if (!fullSliceShape.isEmpty()) {
                combined = VoxelShapes.union(combined, fullSliceShape);
            }
        }
        return combined.isEmpty() ? Block.createCuboidShape(0, 0, 0, 16, 16, 16) : combined;
    }

    private VoxelShape buildFullShiftedOutlineForSlice(BlockView world, BlockPos currentCellPos, CellSlice s) {
        double relX = (s.primaryPos.getX() - currentCellPos.getX()) + (s.offsetX / 16.0);
        double relY = (s.primaryPos.getY() - currentCellPos.getY()) + (s.offsetY / 16.0);
        double relZ = (s.primaryPos.getZ() - currentCellPos.getZ()) + (s.offsetZ / 16.0);

        VoxelShape origOutline = SubGridPlacementHandler.getSafeOriginalOutlineShape(s.storedState, world, currentCellPos);
        if (origOutline.isEmpty()) {
            return VoxelShapes.empty();
        }
        return origOutline.offset(relX, relY, relZ);
    }

    /**
     * Consulta directamente `s.storedState.getCollisionShape(world, pos)` del bloque vanilla original.
     * - Si el bloque original no tiene colisión (Caña de azúcar, Pasto, Flores, Antorchas, Fuego, etc.),
     *   su colisión es vacía (`VoxelShapes.empty()`), igual que en vanilla.
     * - Si el bloque original sí tiene colisión, desplaza su `VoxelShape` real y lo recorta a `[0..1]^3` de esta celda.
     */
    public VoxelShape getDelegatedCollisionShape(BlockView world, BlockPos currentCellPos) {
        if (slices.isEmpty()) return VoxelShapes.empty();
        if (cachedCollisionShape != null) return cachedCollisionShape;

        VoxelShape totalCollision = VoxelShapes.empty();
        VoxelShape cellBounds = VoxelShapes.fullCube();

        for (CellSlice s : slices) {
            VoxelShape origCollision = SubGridPlacementHandler.getSafeOriginalCollisionShape(s.storedState, world, currentCellPos);
            if (origCollision.isEmpty()) {
                continue;
            }
            double relX = (s.primaryPos.getX() - currentCellPos.getX()) + (s.offsetX / 16.0);
            double relY = (s.primaryPos.getY() - currentCellPos.getY()) + (s.offsetY / 16.0);
            double relZ = (s.primaryPos.getZ() - currentCellPos.getZ()) + (s.offsetZ / 16.0);

            VoxelShape shifted = origCollision.offset(relX, relY, relZ);
            VoxelShape clippedToCell = VoxelShapes.combineAndSimplify(shifted, cellBounds, BooleanBiFunction.AND);
            if (!clippedToCell.isEmpty()) {
                totalCollision = VoxelShapes.union(totalCollision, clippedToCell);
            }
        }

        cachedCollisionShape = totalCollision;
        return cachedCollisionShape;
    }

    @Nullable
    public CellSlice raycastSlice(Vec3d eyePos, Vec3d endPos) {
        CellSlice closest = null;
        double minDistSq = Double.MAX_VALUE;
        for (CellSlice s : slices) {
            double baseGx = s.primaryPos.getX() + s.offsetX / 16.0;
            double baseGy = s.primaryPos.getY() + s.offsetY / 16.0;
            double baseGz = s.primaryPos.getZ() + s.offsetZ / 16.0;

            VoxelShape origOutline = SubGridPlacementHandler.getSafeOriginalOutlineShape(s.storedState, world, pos);
            for (Box localBox : origOutline.getBoundingBoxes()) {
                Box worldBox = localBox.offset(baseGx, baseGy, baseGz).expand(0.004);
                Optional<Vec3d> hit = worldBox.raycast(eyePos, endPos);
                if (hit.isPresent()) {
                    double d = eyePos.squaredDistanceTo(hit.get());
                    if (d < minDistSq) {
                        minDistSq = d;
                        closest = s;
                    }
                }
            }
        }
        if (closest == null && !slices.isEmpty()) {
            return slices.get(slices.size() - 1);
        }
        return closest;
    }

    public ActionResult interactWithTargetedSlice(World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        CellSlice target = raycastSlice(eye, eye.add(look.multiply(6.5)));
        if (target == null) return ActionResult.PASS;

        Block b = target.storedState.getBlock();

        // 1. Estaciones de trabajo con interfaz nativa
        if (b instanceof CraftingTableBlock || b instanceof StonecutterBlock || b instanceof LoomBlock
                || b instanceof CartographyTableBlock || b instanceof SmithingTableBlock
                || b instanceof AnvilBlock || b instanceof GrindstoneBlock) {
            if (!world.isClient) {
                NamedScreenHandlerFactory factory = target.storedState.createScreenHandlerFactory(world, pos);
                if (factory != null) {
                    player.openHandledScreen(factory);
                }
            }
            return ActionResult.SUCCESS;
        }

        // 2. Palancas, Botones y bloques con POWERED
        if (b instanceof LeverBlock || b instanceof ButtonBlock || target.storedState.contains(Properties.POWERED)) {
            if (!world.isClient) {
                boolean newPowered;
                if (target.gridStepPlaced <= 2 && b instanceof ButtonBlock) {
                    target.toggleLatchPower = !target.toggleLatchPower;
                    newPowered = target.toggleLatchPower;
                    player.sendMessage(Text.literal("Micro-Memoria 1/8 (T-Flip-Flop): ")
                            .formatted(Formatting.GRAY)
                            .append(Text.literal(newPowered ? "ACTIVO" : "INACTIVO")
                                    .formatted(newPowered ? Formatting.GREEN : Formatting.RED)), true);
                } else {
                    boolean cur = target.storedState.get(Properties.POWERED);
                    newPowered = !cur;
                }
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.POWERED, newPowered), target.toggleLatchPower);
                world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.7f, newPowered ? 0.6f : 0.5f);
                propagateInternalRedstone(world);
                world.updateNeighborsAlways(pos, SubGridMod.SUBGRID_CONTAINER);
            }
            return ActionResult.SUCCESS;
        }

        // 3. Trampillas, Puertas y Portones de Valla (OPEN)
        if (b instanceof TrapdoorBlock || b instanceof DoorBlock || b instanceof FenceGateBlock || target.storedState.contains(Properties.OPEN)) {
            if (!world.isClient && target.storedState.contains(Properties.OPEN)) {
                boolean open = !target.storedState.get(Properties.OPEN);
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.OPEN, open), target.toggleLatchPower);
                world.playSound(null, pos, open ? SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN : SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE,
                        SoundCategory.BLOCKS, 0.8f, 1.0f);
            }
            return ActionResult.SUCCESS;
        }

        // 4. Repetidores y Comparadores de Redstone
        if (b instanceof RepeaterBlock && target.storedState.contains(Properties.DELAY)) {
            if (!world.isClient) {
                int nextDelay = (target.storedState.get(Properties.DELAY) % 4) + 1;
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.DELAY, nextDelay), target.toggleLatchPower);
                world.playSound(null, pos, SoundEvents.BLOCK_COMPARATOR_CLICK, SoundCategory.BLOCKS, 0.6f, 1.2f);
            }
            return ActionResult.SUCCESS;
        }
        if (b instanceof ComparatorBlock && target.storedState.contains(Properties.COMPARATOR_MODE)) {
            if (!world.isClient) {
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.cycle(Properties.COMPARATOR_MODE), target.toggleLatchPower);
                world.playSound(null, pos, SoundEvents.BLOCK_COMPARATOR_CLICK, SoundCategory.BLOCKS, 0.6f, 1.2f);
            }
            return ActionResult.SUCCESS;
        }
        if (b instanceof DaylightDetectorBlock && target.storedState.contains(Properties.INVERTED)) {
            if (!world.isClient) {
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.cycle(Properties.INVERTED), target.toggleLatchPower);
            }
            return ActionResult.SUCCESS;
        }

        // 5. Bloques Musicales y Campanas
        if (b instanceof NoteBlock && target.storedState.contains(Properties.NOTE)) {
            if (!world.isClient) {
                int nextNote = (target.storedState.get(Properties.NOTE) + 1) % 25;
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.NOTE, nextNote), target.toggleLatchPower);
                float pitch = (float) Math.pow(2.0, (nextNote - 12) / 12.0);
                world.playSound(null, pos, SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(), SoundCategory.BLOCKS, 1.0f, pitch);
            }
            return ActionResult.SUCCESS;
        }
        if (b instanceof BellBlock) {
            if (!world.isClient) {
                world.playSound(null, pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 1.5f, 1.0f);
            }
            return ActionResult.SUCCESS;
        }

        // 6. Lámpara de Redstone, Fogatas y Velas
        if ((b instanceof RedstoneLampBlock || b instanceof CampfireBlock || b instanceof CandleBlock)
                && target.storedState.contains(Properties.LIT)) {
            if (!world.isClient && player.getStackInHand(hand).isEmpty()) {
                boolean lit = !target.storedState.get(Properties.LIT);
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.LIT, lit), target.toggleLatchPower);
                world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.5f, 1.4f);
                return ActionResult.SUCCESS;
            }
        }

        return ActionResult.PASS;
    }

    public void onExternalRedstoneUpdate(World world, BlockPos pos, boolean externalPowered) {
        if (externalPowered && !lastExternalPowered) {
            checkPhaseLaunchPad(world, pos);
        }
        lastExternalPowered = externalPowered;
        propagateInternalRedstone(world);
    }

    private void checkPhaseLaunchPad(World world, BlockPos pos) {
        boolean hasSlime = false;
        Vec3d launchDir = new Vec3d(0, 1.25, 0);
        for (CellSlice s : slices) {
            if (s.storedState.getBlock() instanceof SlimeBlock || s.storedState.isOf(Blocks.GOLD_BLOCK)) {
                hasSlime = true;
                launchDir = new Vec3d(s.offsetX / 12.0, 1.15 + Math.abs(s.offsetY) / 16.0, s.offsetZ / 12.0);
            }
        }
        if (hasSlime) {
            Box above = new Box(pos).expand(0.4, 1.2, 0.4);
            final Vec3d boost = launchDir;
            world.getOtherEntities(null, above).forEach(e -> {
                e.setVelocity(e.getVelocity().add(boost));
                e.velocityModified = true;
            });
            world.playSound(null, pos, SoundEvents.ENTITY_SLIME_JUMP, SoundCategory.BLOCKS, 1.0f, 1.4f);
        }
    }

    public void propagateInternalRedstone(World world) {
        int internalPower = getEmittedRedstonePower(null);
        boolean anyPower = internalPower > 0 || (world != null && world.isReceivingRedstonePower(pos));
        boolean changed = false;

        for (CellSlice s : slices) {
            if (s.storedState.getBlock() instanceof RedstoneLampBlock && s.storedState.contains(Properties.LIT)) {
                if (s.storedState.get(Properties.LIT) != anyPower) {
                    s.storedState = s.storedState.with(Properties.LIT, anyPower);
                    changed = true;
                }
            }
        }
        if (changed) {
            recalculateBlockProperties();
            markDirtyAndSync();
        }
    }

    public int getEmittedRedstonePower(@Nullable Direction targetSide) {
        int maxPower = 0;
        for (CellSlice s : slices) {
            int p = 0;
            Block b = s.storedState.getBlock();
            if (b == Blocks.REDSTONE_BLOCK || b == Blocks.REDSTONE_TORCH) {
                p = 15;
            } else if (s.storedState.contains(Properties.POWERED) && s.storedState.get(Properties.POWERED)) {
                p = 15;
            } else if (s.storedState.contains(Properties.POWER)) {
                p = s.storedState.get(Properties.POWER);
            } else if (s.toggleLatchPower) {
                p = 15;
            }

            if (p > 0 && targetSide != null && s.gridStepPlaced == 4) {
                int dx = targetSide.getOffsetX();
                int dy = targetSide.getOffsetY();
                int dz = targetSide.getOffsetZ();
                if ((dx > 0 && s.offsetX < 0) || (dx < 0 && s.offsetX > 0)
                        || (dy > 0 && s.offsetY < 0) || (dy < 0 && s.offsetY > 0)
                        || (dz > 0 && s.offsetZ < 0) || (dz < 0 && s.offsetZ > 0)) {
                    p = 0;
                }
            }
            if (p > maxPower) maxPower = p;
        }
        return maxPower;
    }

    public void syncPartStateAcrossCells(World world, UUID partId, BlockPos anchor, BlockState newState, boolean latch) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = anchor.add(dx, dy, dz);
                    BlockEntity be = world.getBlockEntity(p);
                    if (be instanceof SubGridBlockEntity sg) {
                        boolean mod = false;
                        for (CellSlice s : sg.slices) {
                            if (s.partId.equals(partId)) {
                                s.storedState = newState;
                                s.toggleLatchPower = latch;
                                mod = true;
                            }
                        }
                        if (mod) {
                            sg.cachedCollisionShape = null;
                            sg.recalculateBlockProperties();
                            sg.markDirtyAndSync();
                        }
                    }
                }
            }
        }
    }

    public void recalculateBlockProperties() {
        if (world == null || world.isClient) return;
        BlockState current = world.getBlockState(pos);
        if (!current.isOf(SubGridMod.SUBGRID_CONTAINER)) return;

        int maxLight = 0;
        for (CellSlice s : slices) {
            int l = s.storedState.getLuminance();
            if (l > maxLight) maxLight = l;
        }
        int power = getEmittedRedstonePower(null);

        if (current.get(SubGridBlock.LIGHT_LEVEL) != maxLight || current.get(SubGridBlock.REDSTONE_POWER) != power) {
            world.setBlockState(pos, current
                    .with(SubGridBlock.LIGHT_LEVEL, Math.min(15, maxLight))
                    .with(SubGridBlock.REDSTONE_POWER, Math.min(15, power)), Block.NOTIFY_ALL);
        }
    }

    public void markDirtyAndSync() {
        cachedCollisionShape = null;
        markDirty();
        if (world != null && !world.isClient) {
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, Block.NOTIFY_ALL);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        NbtList list = new NbtList();
        for (CellSlice s : slices) {
            NbtCompound tag = new NbtCompound();
            tag.putUuid("PartId", s.partId);
            tag.putInt("StateId", Block.getRawIdFromState(s.storedState));
            tag.putInt("MinX", s.minX);
            tag.putInt("MinY", s.minY);
            tag.putInt("MinZ", s.minZ);
            tag.putInt("MaxX", s.maxX);
            tag.putInt("MaxY", s.maxY);
            tag.putInt("MaxZ", s.maxZ);
            tag.putBoolean("RenderHere", s.renderHere);
            tag.putInt("OffX", s.offsetX);
            tag.putInt("OffY", s.offsetY);
            tag.putInt("OffZ", s.offsetZ);
            tag.putLong("PrimaryPos", s.primaryPos.asLong());
            tag.putInt("GridStep", s.gridStepPlaced);
            tag.putBoolean("Latch", s.toggleLatchPower);
            tag.putFloat("Yaw", s.yawDegrees);
            list.add(tag);
        }
        nbt.put("Slices", list);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        slices.clear();
        NbtList list = nbt.getList("Slices", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound tag = list.getCompound(i);
            UUID partId = tag.containsUuid("PartId") ? tag.getUuid("PartId") : UUID.randomUUID();
            BlockState st = Block.getStateFromRawId(tag.getInt("StateId"));
            if (st.isAir()) st = Blocks.STONE.getDefaultState();
            slices.add(new CellSlice(
                    partId, st,
                    tag.getInt("MinX"), tag.getInt("MinY"), tag.getInt("MinZ"),
                    tag.getInt("MaxX"), tag.getInt("MaxY"), tag.getInt("MaxZ"),
                    tag.getBoolean("RenderHere"),
                    tag.getInt("OffX"), tag.getInt("OffY"), tag.getInt("OffZ"),
                    BlockPos.fromLong(tag.getLong("PrimaryPos")),
                    tag.contains("GridStep") ? tag.getInt("GridStep") : 8,
                    tag.getBoolean("Latch"),
                    tag.contains("Yaw") ? tag.getFloat("Yaw") : 0.0f
            ));
        }
        cachedCollisionShape = null;
    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }
}
