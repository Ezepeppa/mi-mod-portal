package com.argon.subgrid;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SlimeBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
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

        @Nullable
        public BlockEntity innerBlockEntity;
        @Nullable
        public NbtCompound savedInnerBeNbt;

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

        /**
         * Instancia y devuelve el `BlockEntity` REAL nativo del bloque almacenado (ej. `EnchantingTableBlockEntity`,
         * `FurnaceBlockEntity`, `ChestBlockEntity`, `BellBlockEntity`, `CampfireBlockEntity`, o cualquier bloque de otro mod).
         */
        @Nullable
        public BlockEntity getOrCreateInnerBlockEntity(@Nullable World world) {
            if (!(this.storedState.getBlock() instanceof BlockEntityProvider bep)) {
                return null;
            }
            if (this.innerBlockEntity == null) {
                try {
                    this.innerBlockEntity = bep.createBlockEntity(this.primaryPos, this.storedState);
                    if (this.innerBlockEntity != null) {
                        if (world != null) {
                            this.innerBlockEntity.setWorld(world);
                        }
                        if (this.savedInnerBeNbt != null && !this.savedInnerBeNbt.isEmpty()) {
                            this.innerBlockEntity.readNbt(this.savedInnerBeNbt);
                        }
                    }
                } catch (Exception ignored) {
                }
            } else if (world != null && this.innerBlockEntity.getWorld() == null) {
                this.innerBlockEntity.setWorld(world);
            }
            return this.innerBlockEntity;
        }

        @SuppressWarnings("unchecked")
        public void tickInnerBlockEntity(World world) {
            if (!this.renderHere) return;
            BlockEntity be = getOrCreateInnerBlockEntity(world);
            if (be == null) return;
            try {
                BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>) this.storedState.getBlockEntityTicker(
                        world, (BlockEntityType<BlockEntity>) be.getType()
                );
                if (ticker != null) {
                    int gx = this.primaryPos.getX() * 16 + this.offsetX;
                    int gy = this.primaryPos.getY() * 16 + this.offsetY;
                    int gz = this.primaryPos.getZ() * 16 + this.offsetZ;
                    SubGridWorldContext.runWithVirtualContextVoid(world, gx, gy, gz, this.storedState, this, this.partId, () -> {
                        ticker.tick(world, this.primaryPos, this.storedState, be);
                    });
                }
            } catch (Exception ignored) {
            }
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

    public VoxelShape getUnclippedOutlineShape(BlockView world, BlockPos pos, ShapeContext context) {
        if (slices.isEmpty()) {
            return VoxelShapes.empty();
        }

        if (context instanceof EntityShapeContext esc && esc.getEntity() instanceof PlayerEntity player) {
            Vec3d eye = player.getEyePos();
            Vec3d look = player.getRotationVec(1.0F);
            CellSlice targeted = raycastSlice(eye, eye.add(look.multiply(6.5)));
            if (targeted != null) {
                VoxelShape fullTargeted = buildFullShiftedShapeForSlice(world, pos, targeted, false);
                if (!fullTargeted.isEmpty()) {
                    return fullTargeted;
                }
            }
        }

        VoxelShape combined = VoxelShapes.empty();
        for (CellSlice s : slices) {
            VoxelShape fullSliceShape = buildFullShiftedShapeForSlice(world, pos, s, false);
            if (!fullSliceShape.isEmpty()) {
                combined = VoxelShapes.union(combined, fullSliceShape);
            }
        }
        return combined.isEmpty() ? Block.createCuboidShape(0, 0, 0, 16, 16, 16) : combined;
    }

    public VoxelShape buildFullShiftedShapeForSlice(BlockView world, BlockPos currentCellPos, CellSlice s, boolean forCollision) {
        double relX = (s.primaryPos.getX() - currentCellPos.getX()) + (s.offsetX / 16.0);
        double relY = (s.primaryPos.getY() - currentCellPos.getY()) + (s.offsetY / 16.0);
        double relZ = (s.primaryPos.getZ() - currentCellPos.getZ()) + (s.offsetZ / 16.0);

        VoxelShape origShape = forCollision
                ? SubGridPlacementHandler.getSafeOriginalCollisionShape(s.storedState, world, currentCellPos)
                : SubGridPlacementHandler.getSafeOriginalOutlineShape(s.storedState, world, currentCellPos);
        if (origShape.isEmpty()) {
            return VoxelShapes.empty();
        }

        float normYaw = Math.abs(s.yawDegrees % 90.0f);
        if (normYaw < 0.5f || normYaw > 89.5f) {
            return origShape.offset(relX, relY, relZ);
        }

        // Sub-grilla 6x6 rotada exactamente por `s.yawDegrees` para colisiones y raycast de bloques en Off-Grid
        VoxelShape rotatedShape = VoxelShapes.empty();
        double rad = Math.toRadians(-s.yawDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        for (Box lb : origShape.getBoundingBoxes()) {
            int steps = 6;
            double stepSize = 1.42 / steps;
            for (int ix = 0; ix < steps; ix++) {
                for (int iz = 0; iz < steps; iz++) {
                    double wx = -0.21 + (ix + 0.5) * stepSize;
                    double wz = -0.21 + (iz + 0.5) * stepSize;
                    double dx = wx - 0.5;
                    double dz = wz - 0.5;
                    double lx = 0.5 + dx * cos - dz * sin;
                    double lz = 0.5 + dx * sin + dz * cos;
                    if (lx >= lb.minX && lx <= lb.maxX && lz >= lb.minZ && lz <= lb.maxZ) {
                        double half = stepSize * 0.5;
                        rotatedShape = VoxelShapes.union(rotatedShape, VoxelShapes.cuboid(
                                relX + wx - half, relY + lb.minY, relZ + wz - half,
                                relX + wx + half, relY + lb.maxY, relZ + wz + half
                        ));
                    }
                }
            }
        }
        return rotatedShape;
    }

    public VoxelShape getDelegatedCollisionShape(BlockView world, BlockPos currentCellPos) {
        if (slices.isEmpty()) return VoxelShapes.empty();
        if (cachedCollisionShape != null) return cachedCollisionShape;

        VoxelShape totalCollision = VoxelShapes.empty();
        VoxelShape cellBounds = VoxelShapes.fullCube();

        for (CellSlice s : slices) {
            VoxelShape fullCollision = buildFullShiftedShapeForSlice(world, currentCellPos, s, true);
            if (fullCollision.isEmpty()) continue;

            VoxelShape clippedToCell = VoxelShapes.combineAndSimplify(fullCollision, cellBounds, BooleanBiFunction.AND);
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
            double pivotX = baseGx + 0.5;
            double pivotZ = baseGz + 0.5;

            Vec3d localEye = rotatePointAroundY(eyePos, pivotX, pivotZ, -s.yawDegrees);
            Vec3d localEnd = rotatePointAroundY(endPos, pivotX, pivotZ, -s.yawDegrees);

            VoxelShape origOutline = SubGridPlacementHandler.getSafeOriginalOutlineShape(s.storedState, world, pos);
            for (Box localBox : origOutline.getBoundingBoxes()) {
                Box worldBox = localBox.offset(baseGx, baseGy, baseGz).expand(0.004);
                Optional<Vec3d> hit = worldBox.raycast(localEye, localEnd);
                if (hit.isPresent()) {
                    double d = localEye.squaredDistanceTo(hit.get());
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

    public static Vec3d rotatePointAroundY(Vec3d pt, double pivotX, double pivotZ, float yawDeg) {
        if (Math.abs(yawDeg) < 0.01f) return pt;
        double rad = Math.toRadians(yawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double dx = pt.x - pivotX;
        double dz = pt.z - pivotZ;
        double rx = pivotX + dx * cos + dz * sin;
        double rz = pivotZ - dx * sin + dz * cos;
        return new Vec3d(rx, pt.y, rz);
    }

    /**
     * Ejecuta el método `onUse` NATIVO del `BlockState` original dentro de `SubGridWorldContext`
     * (de modo que Mesas de Encantamientos, Mesas de Crafteo, Yunques, Cortapiedras, Hornos, Cofres,
     * Barriles, Campanas, Bloques Musicales, Repetidores, Comparadores, Tortas, Compostadores y
     * bloques de otros mods ejecuten su propio código vanilla sin recrearlo a mano).
     */
    public ActionResult interactWithTargetedSlice(World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        CellSlice target = raycastSlice(eye, eye.add(look.multiply(6.5)));
        if (target == null) return ActionResult.PASS;

        Block b = target.storedState.getBlock();

        // 1. Puertas (2 bloques de alto), Trampillas y Portones: alternar OPEN y reconstruir geometría en todas sus celdas
        if (b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock) {
            if (!world.isClient && target.storedState.contains(Properties.OPEN)) {
                boolean open = !target.storedState.get(Properties.OPEN);
                SubGridPlacementHandler.toggleOpenAndRebuildGeometry(world, target.primaryPos, target.partId, open);
                world.playSound(null, pos, open ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
                        SoundCategory.BLOCKS, 0.85f, 1.0f);
            }
            return ActionResult.SUCCESS;
        }

        // 2. Micro-Memoria T-Flip-Flop exclusiva de Botones en Grid 1/8
        if (b instanceof ButtonBlock && target.gridStepPlaced <= 2 && target.storedState.contains(Properties.POWERED)) {
            if (!world.isClient) {
                target.toggleLatchPower = !target.toggleLatchPower;
                boolean newPowered = target.toggleLatchPower;
                player.sendMessage(Text.literal("Micro-Memoria 1/8 (T-Flip-Flop): ")
                        .formatted(Formatting.GRAY)
                        .append(Text.literal(newPowered ? "ACTIVO" : "INACTIVO")
                                .formatted(newPowered ? Formatting.GREEN : Formatting.RED)), true);
                SubGridPlacementHandler.rebuildPartWithNewState(
                        world, target.primaryPos, target.partId,
                        target.storedState.with(Properties.POWERED, newPowered), target.toggleLatchPower
                );
                world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.7f, newPowered ? 0.6f : 0.5f);
                propagateInternalRedstone(world);
                world.updateNeighborsAlways(pos, SubGridMod.SUBGRID_CONTAINER);
            }
            return ActionResult.SUCCESS;
        }

        // 3. Delegación 100% Nativa a `target.storedState.onUse(...)` mediante `SubGridWorldContext` + `WorldMixin`!
        int gx = target.primaryPos.getX() * 16 + target.offsetX;
        int gy = target.primaryPos.getY() * 16 + target.offsetY;
        int gz = target.primaryPos.getZ() * 16 + target.offsetZ;

        // Asegurar que la instancia primaria tenga su `innerBlockEntity` creado si corresponde
        CellSlice primarySlice = SubGridPlacementHandler.findPrimarySlice(world, target.primaryPos, target.partId);
        CellSlice contextSlice = (primarySlice != null) ? primarySlice : target;
        BlockHitResult adjustedHit = new BlockHitResult(hit.getPos(), hit.getSide(), contextSlice.primaryPos, hit.isInsideBlock());

        ActionResult nativeResult = SubGridWorldContext.runWithVirtualContext(
                world, gx, gy, gz, contextSlice.storedState, contextSlice, contextSlice.partId,
                () -> contextSlice.storedState.onUse(world, player, hand, adjustedHit)
        );

        if (nativeResult.isAccepted()) {
            if (!world.isClient) {
                propagateInternalRedstone(world);
                world.updateNeighborsAlways(pos, SubGridMod.SUBGRID_CONTAINER);
                markDirtyAndSync();
            }
            return nativeResult;
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
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
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
            if (s.renderHere && s.innerBlockEntity != null) {
                try {
                    tag.put("InnerBe", s.innerBlockEntity.createNbt());
                } catch (Exception ignored) {
                }
            } else if (s.savedInnerBeNbt != null) {
                tag.put("InnerBe", s.savedInnerBeNbt);
            }
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
            CellSlice slice = new CellSlice(
                    partId, st,
                    tag.getInt("MinX"), tag.getInt("MinY"), tag.getInt("MinZ"),
                    tag.getInt("MaxX"), tag.getInt("MaxY"), tag.getInt("MaxZ"),
                    tag.getBoolean("RenderHere"),
                    tag.getInt("OffX"), tag.getInt("OffY"), tag.getInt("OffZ"),
                    BlockPos.fromLong(tag.getLong("PrimaryPos")),
                    tag.contains("GridStep") ? tag.getInt("GridStep") : 8,
                    tag.getBoolean("Latch"),
                    tag.contains("Yaw") ? tag.getFloat("Yaw") : 0.0f
            );
            if (tag.contains("InnerBe", NbtElement.COMPOUND_TYPE)) {
                slice.savedInnerBeNbt = tag.getCompound("InnerBe");
            }
            slices.add(slice);
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
