package com.argon.portalgravity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class GravityGunManager {
    public static class HeldObjectState {
        public final UUID entityUuid;
        public final boolean isBlockDisplay;
        public final BlockState capturedBlockState;
        public float rotX;
        public float rotY;
        public float rotZ;
        public float angVelX;
        public float angVelY;
        public float angVelZ;
        public int ageTicks;
        public int orbitSlot; // -1 = agarre frontal único, >=0 = modo escudo orbital

        public HeldObjectState(UUID entityUuid, boolean isBlockDisplay, BlockState capturedBlockState, int orbitSlot) {
            this.entityUuid = entityUuid;
            this.isBlockDisplay = isBlockDisplay;
            this.capturedBlockState = capturedBlockState;
            this.orbitSlot = orbitSlot;
            this.angVelX = 2.8f;
            this.angVelY = 4.2f;
            this.angVelZ = 1.9f;
        }
    }

    private static final Map<UUID, List<HeldObjectState>> PLAYER_HELD = new HashMap<>();
    private static final double HOLD_DISTANCE = 3.3;
    private static final double MAX_GRAB_RANGE = 13.0;

    public static Map<UUID, List<HeldObjectState>> getPlayerHeld() {
        return PLAYER_HELD;
    }

    public static void toggleGrabOrRelease(ServerPlayerEntity player) {
        List<HeldObjectState> list = PLAYER_HELD.get(player.getUuid());
        if (list != null && !list.isEmpty()) {
            releaseAll(player, true);
            return;
        }
        tryGrabSingleTarget(player);
    }

    /**
     * Modo sorpresa: Escudo Orbital (Shift + Clic Derecho)
     * Levanta hasta 5 bloques o criaturas cercanas y los hace orbitar alrededor del jugador con rayos de plasma 3D.
     */
    public static void grabOrbitalShield(ServerPlayerEntity player) {
        List<HeldObjectState> existing = PLAYER_HELD.get(player.getUuid());
        if (existing != null && !existing.isEmpty()) {
            releaseAll(player, true);
            return;
        }

        ServerWorld world = player.getServerWorld();
        List<HeldObjectState> grabbed = new ArrayList<>();

        // 1. Buscar criaturas cercanas
        Box area = player.getBoundingBox().expand(8.0);
        List<Entity> entities = world.getOtherEntities(player, area,
                e -> e.isAlive() && !e.isSpectator() && !(e instanceof PlayerEntity) && !isPortalOrDisplay(e));

        int slot = 0;
        for (Entity e : entities) {
            if (slot >= 5) break;
            e.setNoGravity(true);
            e.fallDistance = 0.0f;
            grabbed.add(new HeldObjectState(e.getUuid(), false, null, slot++));
        }

        // 2. Completar hasta 5 con bloques del suelo cercanos si faltan
        BlockPos center = player.getBlockPos();
        int[][] offsets = {{2, 0, 2}, {-2, 0, 2}, {2, 0, -2}, {-2, 0, -2}, {3, 0, 0}, {-3, 0, 0}, {0, 0, 3}};
        for (int[] off : offsets) {
            if (slot >= 5) break;
            for (int dy = 1; dy >= -2; dy--) {
                BlockPos bp = center.add(off[0], dy, off[2]);
                BlockState st = world.getBlockState(bp);
                if (canLiftBlock(world, bp, st)) {
                    Entity display = createRotatingBlockDisplay(world, Vec3d.ofCenter(bp), st);
                    if (display != null) {
                        world.setBlockState(bp, Blocks.AIR.getDefaultState(), 3);
                        grabbed.add(new HeldObjectState(display.getUuid(), true, st, slot++));
                        break;
                    }
                }
            }
        }

        if (!grabbed.isEmpty()) {
            PLAYER_HELD.put(player.getUuid(), grabbed);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.9f, 1.4f);
            player.sendMessage(Text.literal("¡Escudo Orbital de Plasma activado (" + grabbed.size() + " objetos)!").formatted(Formatting.GOLD), true);
        }
    }

    private static void tryGrabSingleTarget(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d endPos = eyePos.add(lookVec.multiply(MAX_GRAB_RANGE));

        Box searchBox = player.getBoundingBox().stretch(lookVec.multiply(MAX_GRAB_RANGE)).expand(1.5);
        List<Entity> candidates = world.getOtherEntities(player, searchBox,
                e -> e.isAlive() && !e.isSpectator() && !(e instanceof PlayerEntity) && !isPortalOrDisplay(e));

        Entity closestEntity = null;
        double closestDistSq = MAX_GRAB_RANGE * MAX_GRAB_RANGE;

        for (Entity candidate : candidates) {
            Box hitBox = candidate.getBoundingBox().expand(0.35);
            Optional<Vec3d> hitOpt = hitBox.raycast(eyePos, endPos);
            if (hitOpt.isPresent()) {
                double dSq = eyePos.squaredDistanceTo(hitOpt.get());
                if (dSq < closestDistSq) {
                    closestDistSq = dSq;
                    closestEntity = candidate;
                }
            }
        }

        if (closestEntity != null) {
            closestEntity.setNoGravity(true);
            closestEntity.fallDistance = 0.0f;
            List<HeldObjectState> list = new ArrayList<>();
            list.add(new HeldObjectState(closestEntity.getUuid(), false, null, -1));
            PLAYER_HELD.put(player.getUuid(), list);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.8f);
            return;
        }

        BlockHitResult blockHit = world.raycast(new RaycastContext(
                eyePos, endPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            if (canLiftBlock(world, pos, state)) {
                Entity display = createRotatingBlockDisplay(world, Vec3d.ofCenter(pos), state);
                if (display != null) {
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    List<HeldObjectState> list = new ArrayList<>();
                    list.add(new HeldObjectState(display.getUuid(), true, state, -1));
                    PLAYER_HELD.put(player.getUuid(), list);
                    world.playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.8f);
                    return;
                }
            }
        }

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    private static boolean canLiftBlock(ServerWorld world, BlockPos pos, BlockState state) {
        float hardness = state.getHardness(world, pos);
        return !state.isAir() && hardness >= 0.0f && hardness < 45.0f
                && !state.isOf(Blocks.BEDROCK) && world.getBlockEntity(pos) == null;
    }

    private static Entity createRotatingBlockDisplay(ServerWorld world, Vec3d pos, BlockState state) {
        Entity display = EntityType.BLOCK_DISPLAY.create(world);
        if (display == null) return null;
        display.setPosition(pos.x, pos.y, pos.z);
        display.setNoGravity(true);

        NbtCompound nbt = new NbtCompound();
        display.writeNbt(nbt);

        NbtCompound blockStateNbt = new NbtCompound();
        blockStateNbt.putString("Name", Registries.BLOCK.getId(state.getBlock()).toString());
        nbt.put("block_state", blockStateNbt);
        nbt.putInt("interpolation_duration", 2);
        nbt.putInt("teleport_duration", 2);

        updateDisplayRotationNbt(nbt, 0f, 0f, 0f);
        display.readNbt(nbt);
        world.spawnEntity(display);
        return display;
    }

    private static void updateDisplayRotationNbt(NbtCompound nbt, float rxDeg, float ryDeg, float rzDeg) {
        Quaternionf q = new Quaternionf()
                .rotateY((float) Math.toRadians(ryDeg))
                .rotateX((float) Math.toRadians(rxDeg))
                .rotateZ((float) Math.toRadians(rzDeg));

        // Calcular translation = - R * (0.5, 0.5, 0.5) para que el bloque rote exactamente sobre su centro
        Vector3f center = new Vector3f(0.5f, 0.5f, 0.5f);
        q.transform(center);

        NbtCompound transform = new NbtCompound();

        NbtList translation = new NbtList();
        translation.add(NbtFloat.of(-center.x));
        translation.add(NbtFloat.of(-center.y));
        translation.add(NbtFloat.of(-center.z));
        transform.put("translation", translation);

        NbtList leftRot = new NbtList();
        leftRot.add(NbtFloat.of(q.x));
        leftRot.add(NbtFloat.of(q.y));
        leftRot.add(NbtFloat.of(q.z));
        leftRot.add(NbtFloat.of(q.w));
        transform.put("left_rotation", leftRot);

        NbtList scale = new NbtList();
        scale.add(NbtFloat.of(1.0f));
        scale.add(NbtFloat.of(1.0f));
        scale.add(NbtFloat.of(1.0f));
        transform.put("scale", scale);

        NbtList rightRot = new NbtList();
        rightRot.add(NbtFloat.of(0.0f));
        rightRot.add(NbtFloat.of(0.0f));
        rightRot.add(NbtFloat.of(0.0f));
        rightRot.add(NbtFloat.of(1.0f));
        transform.put("right_rotation", rightRot);

        nbt.put("transformation", transform);
        nbt.putInt("start_interpolation", 0);
        nbt.putInt("interpolation_duration", 2);
    }

    private static boolean isPortalOrDisplay(Entity e) {
        String typeName = e.getType().toString();
        return typeName.contains("portal") || typeName.contains("block_display");
    }

    public static void releaseAll(ServerPlayerEntity player, boolean playSound) {
        List<HeldObjectState> list = PLAYER_HELD.remove(player.getUuid());
        if (list == null || list.isEmpty()) return;
        ServerWorld world = player.getServerWorld();

        for (HeldObjectState state : list) {
            Entity entity = world.getEntity(state.entityUuid);
            if (entity == null) continue;
            if (state.isBlockDisplay && state.capturedBlockState != null) {
                Vec3d pos = entity.getPos();
                Vec3d vel = entity.getVelocity().multiply(0.35);
                entity.discard();
                spawnPhysicalFallingBlock(world, pos, vel, state.capturedBlockState, 2.0f);
            } else {
                entity.setNoGravity(false);
                entity.setVelocity(entity.getVelocity().multiply(0.35));
                entity.velocityModified = true;
            }
        }

        if (playSound) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
    }

    public static void launchHeldObjects(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        List<HeldObjectState> list = PLAYER_HELD.remove(player.getUuid());
        Vec3d lookVec = player.getRotationVec(1.0F);

        if (list != null && !list.isEmpty()) {
            for (HeldObjectState state : list) {
                Entity entity = world.getEntity(state.entityUuid);
                if (entity == null) continue;

                Vec3d launchVel = lookVec.multiply(2.85).add(0, 0.12, 0);
                if (state.isBlockDisplay && state.capturedBlockState != null) {
                    Vec3d pos = entity.getPos();
                    entity.discard();
                    spawnPhysicalFallingBlock(world, pos, launchVel, state.capturedBlockState, 6.0f);
                } else {
                    entity.setNoGravity(false);
                    entity.setVelocity(launchVel);
                    entity.velocityModified = true;
                }
            }
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.7f, 1.55f);
            return;
        }

        // Si no tiene nada agarrado, dispara un pulso gravitatorio que empuja enemigos cercanos
        Box pushBox = player.getBoundingBox().stretch(lookVec.multiply(7.5)).expand(1.6);
        List<Entity> targets = world.getOtherEntities(player, pushBox,
                e -> e.isAlive() && !e.isSpectator() && !isPortalOrDisplay(e));

        boolean pushedAny = false;
        for (Entity t : targets) {
            t.setVelocity(lookVec.multiply(2.2).add(0, 0.4, 0));
            t.velocityModified = true;
            if (t instanceof LivingEntity living) {
                living.damage(world.getDamageSources().playerAttack(player), 5.0f);
            }
            pushedAny = true;
        }

        if (pushedAny) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
    }

    private static void spawnPhysicalFallingBlock(ServerWorld world, Vec3d pos, Vec3d velocity,
                                                  BlockState blockState, float damage) {
        BlockPos bp = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        BlockState existing = world.getBlockState(bp);
        boolean restoredAfter = false;
        if (existing.isAir()) {
            world.setBlockState(bp, blockState, 2);
            restoredAfter = true;
        }
        FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, bp, blockState);
        if (!restoredAfter) {
            world.setBlockState(bp, existing, 2);
        }
        falling.setPosition(pos.x, pos.y - 0.5, pos.z);
        falling.setVelocity(velocity);
        falling.velocityModified = true;
        falling.setHurtEntities(damage, 40);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, List<HeldObjectState>>> it = PLAYER_HELD.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, List<HeldObjectState>> entry = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) {
                it.remove();
                continue;
            }

            boolean holdingGravityGun = player.getMainHandStack().isOf(PortalGravityMod.GRAVITY_GUN)
                    || player.getOffHandStack().isOf(PortalGravityMod.GRAVITY_GUN);

            ServerWorld world = player.getServerWorld();
            List<HeldObjectState> list = entry.getValue();

            if (!holdingGravityGun) {
                releaseAll(player, false);
                continue;
            }

            Vec3d eyePos = player.getEyePos();
            Vec3d lookVec = player.getRotationVec(1.0F);

            Iterator<HeldObjectState> objIt = list.iterator();
            while (objIt.hasNext()) {
                HeldObjectState state = objIt.next();
                Entity held = world.getEntity(state.entityUuid);
                if (held == null || !held.isAlive()) {
                    objIt.remove();
                    continue;
                }

                state.ageTicks++;

                // Calcular punto objetivo con oscilación natural de peso/gravedad ("agarre imperfecto mágico")
                Vec3d targetPos;
                if (state.orbitSlot >= 0) {
                    double orbitAngle = (state.ageTicks * 0.09) + (state.orbitSlot * (2.0 * Math.PI / Math.max(1, list.size())));
                    double radius = 2.8;
                    double bob = Math.sin(state.ageTicks * 0.14 + state.orbitSlot) * 0.32;
                    targetPos = player.getPos().add(Math.cos(orbitAngle) * radius, 1.35 + bob, Math.sin(orbitAngle) * radius);
                } else {
                    double heavyBobY = Math.sin(state.ageTicks * 0.11) * 0.18 - 0.12;
                    double swaySide = Math.cos(state.ageTicks * 0.08) * 0.11;
                    Vec3d rightVec = lookVec.crossProduct(new Vec3d(0, 1, 0)).normalize();
                    targetPos = eyePos.add(lookVec.multiply(HOLD_DISTANCE))
                            .add(rightVec.multiply(swaySide))
                            .add(0, heavyBobY - held.getHeight() * 0.35, 0);
                }

                Vec3d delta = targetPos.subtract(held.getPos());
                if (delta.lengthSquared() > 100.0) {
                    held.setNoGravity(false);
                    objIt.remove();
                    continue;
                }

                // Inercia elástica: el objeto pesado tarda un poco en alcanzar la mira y se inclina según su aceleración
                Vec3d newVel = held.getVelocity().multiply(0.52).add(delta.multiply(0.28));
                held.setPosition(held.getX() + newVel.x, held.getY() + newVel.y, held.getZ() + newVel.z);
                held.setVelocity(newVel);
                held.velocityModified = true;
                held.fallDistance = 0.0f;

                // Rotación natural en 3 ejes según la velocidad de arrastre y gravedad
                state.angVelX = (float) (newVel.z * 28.0 + Math.sin(state.ageTicks * 0.09) * 2.2);
                state.angVelZ = (float) (-newVel.x * 28.0 + Math.cos(state.ageTicks * 0.07) * 2.2);
                state.angVelY = (float) (2.5 + newVel.length() * 14.0);

                state.rotX = (state.rotX + state.angVelX) % 360.0f;
                state.rotY = (state.rotY + state.angVelY) % 360.0f;
                state.rotZ = (state.rotZ + state.angVelZ) % 360.0f;

                if (state.isBlockDisplay) {
                    NbtCompound nbt = new NbtCompound();
                    held.writeNbt(nbt);
                    updateDisplayRotationNbt(nbt, state.rotX, state.rotY, state.rotZ);
                    held.readNbt(nbt);
                } else {
                    held.setYaw(state.rotY);
                    held.setPitch((float) Math.sin(state.ageTicks * 0.15) * 18.0f);
                    held.setBodyYaw(state.rotY);
                    held.setHeadYaw(state.rotY);
                }
            }

            if (list.isEmpty()) {
                it.remove();
            }
        }
    }
}
