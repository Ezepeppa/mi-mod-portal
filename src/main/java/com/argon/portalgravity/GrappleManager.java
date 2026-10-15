package com.argon.portalgravity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class GrappleManager {
    public static class PlayerGrappleHook {
        public final RegistryKey<World> dimension;
        public final Vec3d anchorPoint;
        public int ticksActive;

        public PlayerGrappleHook(RegistryKey<World> dimension, Vec3d anchorPoint) {
            this.dimension = dimension;
            this.anchorPoint = anchorPoint;
            this.ticksActive = 0;
        }
    }

    public static class TetherEndpoint {
        public final UUID entityUuid;
        public final Vec3d fixedPoint;

        public TetherEndpoint(UUID entityUuid, Vec3d fixedPoint) {
            this.entityUuid = entityUuid;
            this.fixedPoint = fixedPoint;
        }

        public Vec3d resolvePos(ServerWorld world) {
            if (entityUuid != null) {
                Entity e = world.getEntity(entityUuid);
                if (e != null && e.isAlive()) {
                    return e.getPos().add(0, e.getHeight() * 0.5, 0);
                }
                return null;
            }
            return fixedPoint;
        }
    }

    public static class ActiveTether {
        public final RegistryKey<World> dimension;
        public final UUID ownerUuid;
        public final TetherEndpoint pointA;
        public final TetherEndpoint pointB;
        public int ticksRemaining;

        public ActiveTether(RegistryKey<World> dimension, UUID ownerUuid, TetherEndpoint pointA, TetherEndpoint pointB, int ticksRemaining) {
            this.dimension = dimension;
            this.ownerUuid = ownerUuid;
            this.pointA = pointA;
            this.pointB = pointB;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private static final Map<UUID, PlayerGrappleHook> PLAYER_GRAPPLES = new HashMap<>();
    private static final Map<UUID, TetherEndpoint> PENDING_TETHER_START = new HashMap<>();
    private static final List<ActiveTether> ACTIVE_TETHERS = new ArrayList<>();

    public static Map<UUID, PlayerGrappleHook> getPlayerGrapples() {
        return PLAYER_GRAPPLES;
    }

    public static List<ActiveTether> getActiveTethers() {
        return ACTIVE_TETHERS;
    }

    public static void clearPlayer(ServerPlayerEntity player) {
        PLAYER_GRAPPLES.remove(player.getUuid());
        PENDING_TETHER_START.remove(player.getUuid());
    }

    public static void togglePlayerGrapple(ServerPlayerEntity player) {
        if (PLAYER_GRAPPLES.containsKey(player.getUuid())) {
            PLAYER_GRAPPLES.remove(player.getUuid());
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 0.7f, 1.5f);
            return;
        }

        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(75.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player
        ));

        if (hit.getType() == HitResult.Type.BLOCK) {
            Vec3d anchor = hit.getPos();
            PLAYER_GRAPPLES.put(player.getUuid(), new PlayerGrappleHook(world.getRegistryKey(), anchor));
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 1.0f, 0.7f);
            world.playSound(null, anchor.x, anchor.y, anchor.z,
                    SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 0.9f, 1.6f);
        } else {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.PLAYERS, 0.7f, 1.3f);
        }
    }

    public static void shootTetherPoint(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        TetherEndpoint hitEndpoint = raycastTetherEndpoint(player, true);
        if (hitEndpoint == null) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.PLAYERS, 0.7f, 1.3f);
            return;
        }

        Vec3d hitPos = hitEndpoint.resolvePos(world);
        if (hitPos != null) {
            PortalManager.getActiveShotBeams().add(
                    new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(),
                            PortalManager.computeServerMuzzlePos(player), hitPos, 3, 8)
            );
        }

        TetherEndpoint first = PENDING_TETHER_START.remove(player.getUuid());
        if (first == null) {
            PENDING_TETHER_START.put(player.getUuid(), hitEndpoint);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.7f);
            player.sendMessage(Text.literal("Ancla de Lazo 1 fijada. Hacé Clic Izquierdo en otro objetivo para unirlos con plasma.").formatted(Formatting.LIGHT_PURPLE), true);
        } else {
            ACTIVE_TETHERS.add(new ActiveTether(world.getRegistryKey(), player.getUuid(), first, hitEndpoint, 160));
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.8f);
            player.sendMessage(Text.literal("¡Lazo Magnético de Plasma activado!").formatted(Formatting.AQUA), true);
        }
    }

    public static void detonateAndClearTethers(ServerPlayerEntity player) {
        PLAYER_GRAPPLES.remove(player.getUuid());
        PENDING_TETHER_START.remove(player.getUuid());
        ServerWorld world = player.getServerWorld();

        int removed = 0;
        Iterator<ActiveTether> it = ACTIVE_TETHERS.iterator();
        while (it.hasNext()) {
            ActiveTether t = it.next();
            if (t.ownerUuid.equals(player.getUuid())) {
                if (t.pointA.entityUuid != null) {
                    Entity eA = world.getEntity(t.pointA.entityUuid);
                    if (eA != null) {
                        eA.setVelocity(0, 1.1, 0);
                        eA.velocityModified = true;
                    }
                }
                if (t.pointB.entityUuid != null) {
                    Entity eB = world.getEntity(t.pointB.entityUuid);
                    if (eB != null) {
                        eB.setVelocity(0, 1.1, 0);
                        eB.velocityModified = true;
                    }
                }
                it.remove();
                removed++;
            }
        }
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.7f, 1.5f);
        player.sendMessage(Text.literal("Lazos de plasma detonados (" + removed + ")").formatted(Formatting.GOLD), true);
    }

    private static TetherEndpoint raycastTetherEndpoint(ServerPlayerEntity player, boolean convertBlockToFallingIfFirst) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(45.0));

        Box searchBox = player.getBoundingBox().stretch(look.multiply(45.0)).expand(1.5);
        List<Entity> candidates = world.getOtherEntities(player, searchBox,
                e -> e.isAlive() && !e.isSpectator() && !e.getType().toString().contains("portal"));

        Entity closest = null;
        double closestDist = 45.0 * 45.0;
        for (Entity c : candidates) {
            Optional<Vec3d> hit = c.getBoundingBox().expand(0.4).raycast(eye, end);
            if (hit.isPresent()) {
                double d = eye.squaredDistanceTo(hit.get());
                if (d < closestDist) {
                    closestDist = d;
                    closest = c;
                }
            }
        }

        if (closest != null) {
            return new TetherEndpoint(closest.getUuid(), null);
        }

        BlockHitResult bHit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player
        ));
        if (bHit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bHit.getBlockPos();
            BlockState st = world.getBlockState(bp);
            // Si es el primer punto y apunta a un bloque movible, lo convierte en bloque físico para arrancarlo y lanzarlo hacia el segundo punto
            if (convertBlockToFallingIfFirst && !PENDING_TETHER_START.containsKey(player.getUuid())
                    && GravityGunManager.canLiftBlock(world, bp, st)) {
                FallingBlockEntity falling = FallingBlockEntity.spawnFromBlock(world, bp, st);
                falling.setHurtEntities(10.0f, 40);
                GravityGunManager.registerLaunchedProjectile(world.getRegistryKey(), falling.getUuid(), player.getUuid(), 12.0f, 160);
                return new TetherEndpoint(falling.getUuid(), null);
            }
            return new TetherEndpoint(null, bHit.getPos());
        }
        return null;
    }

    public static void tick(MinecraftServer server) {
        // 1. Gancho de movilidad aérea del jugador
        Iterator<Map.Entry<UUID, PlayerGrappleHook>> gIt = PLAYER_GRAPPLES.entrySet().iterator();
        while (gIt.hasNext()) {
            Map.Entry<UUID, PlayerGrappleHook> entry = gIt.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            PlayerGrappleHook hook = entry.getValue();
            if (player == null || !player.isAlive()
                    || !player.getServerWorld().getRegistryKey().equals(hook.dimension)
                    || !player.getMainHandStack().isOf(PortalGravityMod.GRAPPLE_GUN)) {
                gIt.remove();
                continue;
            }

            hook.ticksActive++;
            Vec3d toAnchor = hook.anchorPoint.subtract(player.getPos().add(0, 1.0, 0));
            double dist = toAnchor.length();
            if (dist < 1.8 || hook.ticksActive > 140) {
                gIt.remove();
                continue;
            }

            Vec3d pullDir = toAnchor.normalize();
            Vec3d look = player.getRotationVec(1.0F);
            // Combinar tracción hacia el ancla con dirección de cámara para poder balancearse en arco
            Vec3d newVel = player.getVelocity().multiply(0.75)
                    .add(pullDir.multiply(0.38))
                    .add(look.multiply(0.08))
                    .add(0, 0.05, 0);
            if (newVel.length() > 1.95) {
                newVel = newVel.normalize().multiply(1.95);
            }

            player.setVelocity(newVel);
            player.velocityModified = true;
            player.fallDistance = 0.0f;
        }

        // 2. Lazos Magnéticos Duales (Just Cause Tethers)
        Iterator<ActiveTether> tIt = ACTIVE_TETHERS.iterator();
        while (tIt.hasNext()) {
            ActiveTether t = tIt.next();
            t.ticksRemaining--;
            if (t.ticksRemaining <= 0) {
                tIt.remove();
                continue;
            }
            ServerWorld world = server.getWorld(t.dimension);
            if (world == null) {
                tIt.remove();
                continue;
            }

            Vec3d posA = t.pointA.resolvePos(world);
            Vec3d posB = t.pointB.resolvePos(world);
            if (posA == null || posB == null) {
                tIt.remove();
                continue;
            }

            Vec3d delta = posB.subtract(posA);
            double dist = delta.length();
            if (dist > 0.8) {
                Vec3d dir = delta.normalize();
                double pullStrength = Math.min(0.65, 0.14 + dist * 0.06);

                if (t.pointA.entityUuid != null) {
                    Entity eA = world.getEntity(t.pointA.entityUuid);
                    if (eA != null) {
                        eA.setVelocity(eA.getVelocity().multiply(0.7).add(dir.multiply(pullStrength)).add(0, 0.08, 0));
                        eA.velocityModified = true;
                        eA.fallDistance = 0.0f;
                    }
                }
                if (t.pointB.entityUuid != null) {
                    Entity eB = world.getEntity(t.pointB.entityUuid);
                    if (eB != null) {
                        eB.setVelocity(eB.getVelocity().multiply(0.7).subtract(dir.multiply(pullStrength)).add(0, 0.08, 0));
                        eB.velocityModified = true;
                        eB.fallDistance = 0.0f;
                    }
                }
            } else if (t.pointA.entityUuid != null && t.pointB.entityUuid != null) {
                // Choque entre dos entidades atadas
                Entity eA = world.getEntity(t.pointA.entityUuid);
                Entity eB = world.getEntity(t.pointB.entityUuid);
                if (eA instanceof LivingEntity livA) livA.damage(world.getDamageSources().magic(), 6.0f);
                if (eB instanceof LivingEntity livB) livB.damage(world.getDamageSources().magic(), 6.0f);
                world.playSound(null, posA.x, posA.y, posA.z,
                        SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 0.9f, 1.2f);
                tIt.remove();
            }
        }
    }
}
