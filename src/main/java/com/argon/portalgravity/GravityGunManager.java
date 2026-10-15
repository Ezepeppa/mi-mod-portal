package com.argon.portalgravity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
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
import net.minecraft.world.World;
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
        public int orbitSlot;
        public boolean collidingSurface;

        public HeldObjectState(UUID entityUuid, boolean isBlockDisplay, BlockState capturedBlockState, int orbitSlot) {
            this.entityUuid = entityUuid;
            this.isBlockDisplay = isBlockDisplay;
            this.capturedBlockState = capturedBlockState;
            this.orbitSlot = orbitSlot;
            this.angVelX = 2.0f;
            this.angVelY = 3.2f;
            this.angVelZ = 1.5f;
        }
    }

    public static class LaunchedProjectile {
        public final RegistryKey<World> dimension;
        public final UUID projectileUuid;
        public final UUID shooterUuid;
        public final float impactDamage;
        public int ticksRemaining;

        public LaunchedProjectile(RegistryKey<World> dimension, UUID projectileUuid, UUID shooterUuid, float impactDamage, int ticksRemaining) {
            this.dimension = dimension;
            this.projectileUuid = projectileUuid;
            this.shooterUuid = shooterUuid;
            this.impactDamage = impactDamage;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private static final Map<UUID, List<HeldObjectState>> PLAYER_HELD = new HashMap<>();
    private static final Map<UUID, Double> PLAYER_HOLD_DISTANCE = new HashMap<>();
    private static final List<LaunchedProjectile> LAUNCHED_PROJECTILES = new ArrayList<>();

    private static final double DEFAULT_HOLD_DISTANCE = 3.5;
    private static final double MIN_HOLD_DISTANCE = 1.8;
    private static final double MAX_HOLD_DISTANCE = 13.0;
    private static final double MAX_GRAB_RANGE = 13.5;

    public static Map<UUID, List<HeldObjectState>> getPlayerHeld() {
        return PLAYER_HELD;
    }

    public static double getHoldDistance(UUID playerUuid) {
        return PLAYER_HOLD_DISTANCE.getOrDefault(playerUuid, DEFAULT_HOLD_DISTANCE);
    }

    public static void adjustHoldDistance(ServerPlayerEntity player, int scrollDelta) {
        List<HeldObjectState> list = PLAYER_HELD.get(player.getUuid());
        if (list == null || list.isEmpty()) return;

        double current = getHoldDistance(player.getUuid());
        double updated = Math.max(MIN_HOLD_DISTANCE, Math.min(MAX_HOLD_DISTANCE, current + scrollDelta * 0.65));
        PLAYER_HOLD_DISTANCE.put(player.getUuid(), updated);

        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.35f, scrollDelta > 0 ? 1.5f : 1.1f);
    }

    public static void toggleGrabOrRelease(ServerPlayerEntity player) {
        List<HeldObjectState> list = PLAYER_HELD.get(player.getUuid());
        if (list != null && !list.isEmpty()) {
            releaseAll(player, true);
            return;
        }
        tryGrabSingleTarget(player);
    }

    public static void grabOrbitalShield(ServerPlayerEntity player) {
        List<HeldObjectState> existing = PLAYER_HELD.get(player.getUuid());
        if (existing != null && !existing.isEmpty()) {
            releaseAll(player, true);
            return;
        }

        ServerWorld world = player.getServerWorld();
        List<HeldObjectState> grabbed = new ArrayList<>();

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
            PLAYER_HOLD_DISTANCE.put(player.getUuid(), DEFAULT_HOLD_DISTANCE);
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
            double initDist = Math.max(MIN_HOLD_DISTANCE, Math.min(7.5, eyePos.distanceTo(closestEntity.getPos())));
            PLAYER_HOLD_DISTANCE.put(player.getUuid(), initDist);
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
                Vec3d spawnCenter = Vec3d.ofCenter(pos);
                Entity display = createRotatingBlockDisplay(world, spawnCenter, state);
                if (display != null) {
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                    double initDist = Math.max(MIN_HOLD_DISTANCE, Math.min(7.5, eyePos.distanceTo(spawnCenter)));
                    PLAYER_HOLD_DISTANCE.put(player.getUuid(), initDist);
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

    public static boolean canLiftBlock(ServerWorld world, BlockPos pos, BlockState state) {
        float hardness = state.getHardness(world, pos);
        return !state.isAir() && hardness >= 0.0f && hardness < 45.0f
                && !state.isOf(Blocks.BEDROCK) && world.getBlockEntity(pos) == null;
    }

    public static Entity createRotatingBlockDisplay(ServerWorld world, Vec3d pos, BlockState state) {
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

    public static void updateDisplayRotationNbt(NbtCompound nbt, float rxDeg, float ryDeg, float rzDeg) {
        Quaternionf q = new Quaternionf()
                .rotateY((float) Math.toRadians(ryDeg))
                .rotateX((float) Math.toRadians(rxDeg))
                .rotateZ((float) Math.toRadians(rzDeg));

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
        nbt.putInt("teleport_duration", 2);
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
                FallingBlockEntity f = spawnPhysicalFallingBlock(world, pos, vel, state.capturedBlockState, 4.0f);
                if (f != null) {
                    LAUNCHED_PROJECTILES.add(new LaunchedProjectile(world.getRegistryKey(), f.getUuid(), player.getUuid(), 5.0f, 40));
                }
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

                Vec3d launchVel = lookVec.multiply(2.9).add(0, 0.14, 0);
                if (state.isBlockDisplay && state.capturedBlockState != null) {
                    Vec3d pos = entity.getPos();
                    entity.discard();
                    FallingBlockEntity f = spawnPhysicalFallingBlock(world, pos, launchVel, state.capturedBlockState, 12.0f);
                    if (f != null) {
                        LAUNCHED_PROJECTILES.add(new LaunchedProjectile(world.getRegistryKey(), f.getUuid(), player.getUuid(), 14.0f, 65));
                    }
                } else {
                    entity.setNoGravity(false);
                    entity.setVelocity(launchVel);
                    entity.velocityModified = true;
                    LAUNCHED_PROJECTILES.add(new LaunchedProjectile(world.getRegistryKey(), entity.getUuid(), player.getUuid(), 12.0f, 65));
                }
            }
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.7f, 1.55f);
            return;
        }

        // Pulso gravitatorio frontal si no sostiene nada
        Box pushBox = player.getBoundingBox().stretch(lookVec.multiply(7.5)).expand(1.6);
        List<Entity> targets = world.getOtherEntities(player, pushBox,
                e -> e.isAlive() && !e.isSpectator() && !isPortalOrDisplay(e));

        boolean pushedAny = false;
        for (Entity t : targets) {
            t.setVelocity(lookVec.multiply(2.2).add(0, 0.4, 0));
            t.velocityModified = true;
            if (t instanceof LivingEntity living) {
                living.damage(world.getDamageSources().playerAttack(player), 6.0f);
            }
            pushedAny = true;
        }

        if (pushedAny) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
    }

    public static FallingBlockEntity spawnPhysicalFallingBlock(ServerWorld world, Vec3d pos, Vec3d velocity,
                                                               BlockState blockState, float damage) {
        BlockPos bp = BlockPos.ofFloored(pos.x, pos.y, pos.z);
        BlockState existing = world.getBlockState(bp);
        boolean wasAir = existing.isAir();
        if (wasAir) {
            world.setBlockState(bp, blockState, 2);
        }
        FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, bp, blockState);
        if (!wasAir) {
            world.setBlockState(bp, existing, 2);
        }
        falling.setPosition(pos.x, pos.y - 0.5, pos.z);
        falling.setVelocity(velocity);
        falling.velocityModified = true;
        falling.setHurtEntities(damage, 40);
        return falling;
    }

    /**
     * Resuelve colisiones eje por eje (Y, X, Z) contra el mundo para una caja centrada en `currentPos`
     * de semiancho `halfW` y semialto `halfH`, evitando que atraviese pisos o paredes.
     */
    public static Vec3d resolveBoxCollision(World world, Vec3d currentPos, Vec3d desiredDelta,
                                            double halfW, double halfH, boolean[] outCollidedAxes) {
        Box box = new Box(
                currentPos.x - halfW, currentPos.y - halfH, currentPos.z - halfW,
                currentPos.x + halfW, currentPos.y + halfH, currentPos.z + halfW
        );

        double dx = desiredDelta.x;
        double dy = desiredDelta.y;
        double dz = desiredDelta.z;

        // 1. Eje Y (Suelo y Techo)
        if (Math.abs(dy) > 1e-5) {
            if (!world.isSpaceEmpty(box.offset(0, dy, 0))) {
                outCollidedAxes[1] = true;
                dy *= 0.25;
                if (!world.isSpaceEmpty(box.offset(0, dy, 0))) {
                    dy = 0.0;
                }
            }
            box = box.offset(0, dy, 0);
        }

        // 2. Eje X
        if (Math.abs(dx) > 1e-5) {
            if (!world.isSpaceEmpty(box.offset(dx, 0, 0))) {
                outCollidedAxes[0] = true;
                dx *= 0.25;
                if (!world.isSpaceEmpty(box.offset(dx, 0, 0))) {
                    dx = 0.0;
                }
            }
            box = box.offset(dx, 0, 0);
        }

        // 3. Eje Z
        if (Math.abs(dz) > 1e-5) {
            if (!world.isSpaceEmpty(box.offset(0, 0, dz))) {
                outCollidedAxes[2] = true;
                dz *= 0.25;
                if (!world.isSpaceEmpty(box.offset(0, 0, dz))) {
                    dz = 0.0;
                }
            }
        }

        return new Vec3d(dx, dy, dz);
    }

    public static void tick(MinecraftServer server) {
        // 1. Procesar daño por impacto de proyectiles lanzados por la Gravity Gun
        Iterator<LaunchedProjectile> projIt = LAUNCHED_PROJECTILES.iterator();
        while (projIt.hasNext()) {
            LaunchedProjectile lp = projIt.next();
            lp.ticksRemaining--;
            if (lp.ticksRemaining <= 0) {
                projIt.remove();
                continue;
            }
            ServerWorld world = server.getWorld(lp.dimension);
            if (world == null) {
                projIt.remove();
                continue;
            }
            Entity proj = world.getEntity(lp.projectileUuid);
            if (proj == null || !proj.isAlive() || proj.getVelocity().lengthSquared() < 0.06) {
                projIt.remove();
                continue;
            }

            Box hitBox = proj.getBoundingBox().expand(0.65);
            List<Entity> victims = world.getOtherEntities(proj, hitBox,
                    e -> e instanceof LivingEntity && e.isAlive() && !e.getUuid().equals(lp.shooterUuid));

            if (!victims.isEmpty()) {
                ServerPlayerEntity shooter = server.getPlayerManager().getPlayer(lp.shooterUuid);
                for (Entity v : victims) {
                    if (v instanceof LivingEntity living) {
                        if (shooter != null) {
                            living.damage(world.getDamageSources().playerAttack(shooter), lp.impactDamage);
                        } else {
                            living.damage(world.getDamageSources().fallingBlock(proj), lp.impactDamage);
                        }
                        Vec3d knock = proj.getVelocity().normalize().multiply(1.35).add(0, 0.35, 0);
                        living.setVelocity(knock);
                        living.velocityModified = true;
                    }
                }
                world.playSound(null, proj.getX(), proj.getY(), proj.getZ(),
                        SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.75f, 1.35f);
                projIt.remove();
            }
        }

        // 2. Actualizar objetos sostenidos por la Gravity Gun con colisiones de hitbox reales
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
            double holdDist = getHoldDistance(player.getUuid());

            Iterator<HeldObjectState> objIt = list.iterator();
            while (objIt.hasNext()) {
                HeldObjectState state = objIt.next();
                Entity held = world.getEntity(state.entityUuid);
                if (held == null || !held.isAlive()) {
                    objIt.remove();
                    continue;
                }

                state.ageTicks++;

                // Calcular punto deseado pero frenar con raycast si hay suelo/pared antes de holdDist
                Vec3d targetPos;
                if (state.orbitSlot >= 0) {
                    double orbitAngle = (state.ageTicks * 0.09) + (state.orbitSlot * (2.0 * Math.PI / Math.max(1, list.size())));
                    double radius = 2.8;
                    double bob = Math.sin(state.ageTicks * 0.14 + state.orbitSlot) * 0.25;
                    targetPos = player.getPos().add(Math.cos(orbitAngle) * radius, 1.45 + bob, Math.sin(orbitAngle) * radius);
                } else {
                    Vec3d rawEnd = eyePos.add(lookVec.multiply(holdDist));
                    BlockHitResult obstacleHit = world.raycast(new RaycastContext(
                            eyePos, rawEnd, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player
                    ));
                    Vec3d clampedPoint = rawEnd;
                    if (obstacleHit.getType() == HitResult.Type.BLOCK) {
                        Vec3d hitNormal = Vec3d.of(obstacleHit.getSide().getVector());
                        clampedPoint = obstacleHit.getPos().add(hitNormal.multiply(0.55));
                    }

                    double heavyBobY = Math.sin(state.ageTicks * 0.11) * 0.12 - 0.06;
                    double swaySide = Math.cos(state.ageTicks * 0.08) * 0.08;
                    Vec3d rightVec = lookVec.crossProduct(new Vec3d(0, 1, 0)).normalize();
                    targetPos = clampedPoint.add(rightVec.multiply(swaySide))
                            .add(0, heavyBobY - (state.isBlockDisplay ? 0.0 : held.getHeight() * 0.4), 0);
                }

                Vec3d currentPos = held.getPos();
                Vec3d delta = targetPos.subtract(currentPos);
                if (delta.lengthSquared() > 196.0) {
                    held.setNoGravity(false);
                    objIt.remove();
                    continue;
                }

                Vec3d desiredStep = held.getVelocity().multiply(0.45).add(delta.multiply(0.38));
                boolean[] collidedAxes = new boolean[3]; // [X, Y, Z]
                Vec3d actualStep;

                if (state.isBlockDisplay) {
                    actualStep = resolveBoxCollision(world, currentPos, desiredStep, 0.48, 0.48, collidedAxes);
                    held.setPosition(currentPos.x + actualStep.x, currentPos.y + actualStep.y, currentPos.z + actualStep.z);
                } else {
                    Vec3d before = held.getPos();
                    held.move(MovementType.SELF, desiredStep);
                    actualStep = held.getPos().subtract(before);
                    collidedAxes[0] = held.horizontalCollision;
                    collidedAxes[1] = held.verticalCollision;
                    collidedAxes[2] = held.horizontalCollision;
                }

                held.setVelocity(actualStep);
                held.velocityModified = true;
                held.fallDistance = 0.0f;
                state.collidingSurface = (collidedAxes[0] || collidedAxes[1] || collidedAxes[2]);

                // Rotación natural afectada por el peso y por el roce/colisión contra pisos o paredes
                if (collidedAxes[1]) {
                    // Al rozar contra el piso, rueda según el arrastre horizontal y amortigua su inclinación
                    state.angVelX = (float) (actualStep.z * 65.0);
                    state.angVelZ = (float) (-actualStep.x * 65.0);
                    state.angVelY = (float) (actualStep.length() * 25.0);
                } else if (collidedAxes[0] || collidedAxes[2]) {
                    // Al rozar contra una pared, gira sobre el eje vertical e inclina
                    state.angVelY = (float) (actualStep.y * 55.0 + 8.0);
                    state.angVelX = (float) (actualStep.z * 40.0);
                } else {
                    state.angVelX = (float) (actualStep.z * 26.0 + Math.sin(state.ageTicks * 0.09) * 1.8);
                    state.angVelZ = (float) (-actualStep.x * 26.0 + Math.cos(state.ageTicks * 0.07) * 1.8);
                    state.angVelY = (float) (2.0 + actualStep.length() * 14.0);
                }

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
                    held.setPitch((float) Math.sin(state.ageTicks * 0.15) * 15.0f);
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
