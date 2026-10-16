package com.argon.subgrid;

import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CartographyTableBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.RepeaterBlock;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
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
        public int offsetX, offsetY, offsetZ; // Desplazamiento en 1/16 respecto a esta celda
        public BlockPos primaryPos;
        public int gridStepPlaced;
        public boolean toggleLatchPower; // Memoria T-Flip-Flop para micro-circuitos en Grid 1/8

        public CellSlice(UUID partId, BlockState storedState,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                         boolean renderHere, int offsetX, int offsetY, int offsetZ,
                         BlockPos primaryPos, int gridStepPlaced, boolean toggleLatchPower) {
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
        }

        public boolean intersects(int ax0, int ay0, int az0, int ax1, int ay1, int az1) {
            return this.minX < ax1 && this.maxX > ax0
                    && this.minY < ay1 && this.maxY > ay0
                    && this.minZ < az1 && this.maxZ > az0;
        }
    }

    private final List<CellSlice> slices = new ArrayList<>();
    private VoxelShape cachedShape = null;
    private boolean lastExternalPowered = false;

    public SubGridBlockEntity(BlockPos pos, BlockState state) {
        super(SubGridMod.SUBGRID_BLOCK_ENTITY, pos, state);
    }

    public List<CellSlice> getSlices() {
        return slices;
    }

    public void addSlice(CellSlice slice) {
        slices.add(slice);
        cachedShape = null;
        recalculateBlockProperties();
        markDirtyAndSync();
    }

    public boolean removeSlicesByPartId(UUID targetPartId) {
        boolean removed = slices.removeIf(s -> s.partId.equals(targetPartId));
        if (removed) {
            cachedShape = null;
            recalculateBlockProperties();
            markDirtyAndSync();
        }
        return removed;
    }

    public boolean hasOverlap(int x0, int y0, int z0, int x1, int y1, int z1, @Nullable UUID ignorePartId) {
        for (CellSlice s : slices) {
            if (ignorePartId != null && s.partId.equals(ignorePartId)) continue;
            if (s.intersects(x0, y0, z0, x1, y1, z1)) {
                return true;
            }
        }
        return false;
    }

    public VoxelShape getCombinedShape() {
        if (cachedShape != null) {
            return cachedShape;
        }
        VoxelShape shape = VoxelShapes.empty();
        for (CellSlice s : slices) {
            if (s.maxX > s.minX && s.maxY > s.minY && s.maxZ > s.minZ) {
                shape = VoxelShapes.union(shape, Block.createCuboidShape(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ));
            }
        }
        cachedShape = shape.isEmpty() ? Block.createCuboidShape(0, 0, 0, 16, 16, 16) : shape;
        return cachedShape;
    }

    public VoxelShape getCombinedCollisionShape() {
        if (slices.isEmpty()) return VoxelShapes.empty();
        return getCombinedShape();
    }

    @Nullable
    public CellSlice raycastSlice(Vec3d eyePos, Vec3d endPos) {
        CellSlice closest = null;
        double minDistSq = Double.MAX_VALUE;
        for (CellSlice s : slices) {
            Box box = new Box(
                    pos.getX() + s.minX / 16.0, pos.getY() + s.minY / 16.0, pos.getZ() + s.minZ / 16.0,
                    pos.getX() + s.maxX / 16.0, pos.getY() + s.maxY / 16.0, pos.getZ() + s.maxZ / 16.0
            ).expand(0.004);
            Optional<Vec3d> hit = box.raycast(eyePos, endPos);
            if (hit.isPresent()) {
                double d = eyePos.squaredDistanceTo(hit.get());
                if (d < minDistSq) {
                    minDistSq = d;
                    closest = s;
                }
            }
        }
        if (closest == null && !slices.isEmpty()) {
            return slices.get(slices.size() - 1);
        }
        return closest;
    }

    /**
     * Permite que todos los bloques interactuables (Mesas de trabajo, Palancas, Botones, Puertas,
     * Trampillas, Lámparas, Repetidores, Bloques musicales, Cortapiedras, etc.) funcionen con normalidad
     * dentro de la Sub-Grid, y aplica la lógica especial de Redstone según la Grid.
     */
    public ActionResult interactWithTargetedSlice(World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        CellSlice target = raycastSlice(eye, eye.add(look.multiply(6.0)));
        if (target == null) return ActionResult.PASS;

        // Si el jugador sostiene un bloque colocable y no está agachado, solo interceptar si el bloque apuntado es interactuable
        Block b = target.storedState.getBlock();

        // 1. Estaciones de trabajo con interfaz (Crafting Table, Stonecutter, Loom, Cartography, Smithing, Anvil, Grindstone)
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

        // 2. Palancas (Lever) y Botones (Button) con Micro-Ingeniería en Sub-Grid
        if (b instanceof LeverBlock || b instanceof ButtonBlock || target.storedState.contains(Properties.POWERED)) {
            if (!world.isClient) {
                boolean newPowered;
                if (target.gridStepPlaced <= 2 && b instanceof ButtonBlock) {
                    // JUSTIFICACIÓN DE GRID 1/8: Cualquier botón colocado en Grid 1/8 actúa como un T-Flip-Flop (interruptor con memoria permanente)
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

        // 3. Trampillas, Puertas y Vallas (OPEN)
        if (b instanceof TrapdoorBlock || b instanceof DoorBlock || target.storedState.contains(Properties.OPEN)) {
            if (!world.isClient && target.storedState.contains(Properties.OPEN)) {
                boolean open = !target.storedState.get(Properties.OPEN);
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.OPEN, open), target.toggleLatchPower);
                world.playSound(null, pos, open ? SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN : SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE,
                        SoundCategory.BLOCKS, 0.8f, 1.0f);
            }
            return ActionResult.SUCCESS;
        }

        // 4. Repetidores de Redstone (DELAY)
        if (b instanceof RepeaterBlock && target.storedState.contains(Properties.DELAY)) {
            if (!world.isClient) {
                int nextDelay = (target.storedState.get(Properties.DELAY) % 4) + 1;
                syncPartStateAcrossCells(world, target.partId, target.primaryPos,
                        target.storedState.with(Properties.DELAY, nextDelay), target.toggleLatchPower);
                world.playSound(null, pos, SoundEvents.BLOCK_COMPARATOR_CLICK, SoundCategory.BLOCKS, 0.6f, 1.2f);
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

        // 6. Lámpara de Redstone o Reenvío genérico
        if (b instanceof RedstoneLampBlock && target.storedState.contains(Properties.LIT)) {
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
            // Si hay un bloque de Slime + Redstone en Sub-Grid ("Catapulta de Fase"), lanza a las entidades encima
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

    /**
     * Calcula la potencia de Redstone emitida por las piezas en Sub-Grid:
     * - Grid 1/2 (8): Potencia completa omnidireccional (15).
     * - Grid 1/4 (4): Diodo direccional (solo emite hacia la dirección hacia la que está desplazada la pieza).
     * - Grid 1/8 (2): Emite potencia 15 si su memoria T-Flip-Flop está activa.
     */
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
                // En Grid 1/4 actúa como Diodo Direccional hacia el lado donde está desplazado el bloque
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

    private void syncPartStateAcrossCells(World world, UUID partId, BlockPos anchor, BlockState newState, boolean latch) {
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
                    tag.getBoolean("Latch")
            ));
        }
        cachedShape = null;
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
