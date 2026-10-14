package com.argon.portalgravity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class GravityGunManager {
    private static final Map<UUID, UUID> HELD_ENTITIES = new HashMap<>();
    private static final DustParticleEffect AMBER_BEAM = new DustParticleEffect(new Vector3f(1.0f, 0.68f, 0.12f), 0.9f);
    private static final double HOLD_DISTANCE = 3.2;
    private static final double MAX_GRAB_RANGE = 12.0;

    public static void toggleGrabOrRelease(ServerPlayerEntity player) {
        if (HELD_ENTITIES.containsKey(player.getUuid())) {
            releaseObject(player, true);
            return;
        }
        tryGrabTarget(player);
    }

    private static void tryGrabTarget(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d endPos = eyePos.add(lookVec.multiply(MAX_GRAB_RANGE));

        // 1. Buscar primero si apunta a una entidad
        Box searchBox = player.getBoundingBox().stretch(lookVec.multiply(MAX_GRAB_RANGE)).expand(1.5);
        List<Entity> candidates = world.getOtherEntities(player, searchBox,
                e -> e.isAlive() && !e.isSpectator() && !(e instanceof PlayerEntity)
                        && !RegistriesContainsPortal(e));

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
            grabEntity(player, closestEntity);
            return;
        }

        // 2. Si no apunta a una entidad, verificar si apunta a un bloque levantable
        BlockHitResult blockHit = world.raycast(new RaycastContext(
                eyePos, endPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            float hardness = state.getHardness(world, pos);
            if (!state.isAir() && hardness >= 0.0f && hardness < 45.0f
                    && !state.isOf(Blocks.BEDROCK) && world.getBlockEntity(pos) == null) {
                FallingBlockEntity fallingBlock = FallingBlockEntity.spawnFromBlock(world, pos, state);
                fallingBlock.setHurtEntities(2.0f, 40);
                grabEntity(player, fallingBlock);
                return;
            }
        }

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.PLAYERS, 0.6f, 0.7f);
    }

    private static boolean RegistriesContainsPortal(Entity e) {
        String typeName = e.getType().toString();
        return typeName.contains("portal");
    }

    private static void grabEntity(ServerPlayerEntity player, Entity target) {
        target.setNoGravity(true);
        target.fallDistance = 0.0f;
        HELD_ENTITIES.put(player.getUuid(), target.getUuid());
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.8f);
    }

    public static void releaseObject(ServerPlayerEntity player, boolean playSound) {
        UUID entityId = HELD_ENTITIES.remove(player.getUuid());
        if (entityId == null) return;
        ServerWorld world = player.getServerWorld();
        Entity entity = world.getEntity(entityId);
        if (entity != null) {
            entity.setNoGravity(false);
            entity.setVelocity(entity.getVelocity().multiply(0.35));
            entity.velocityModified = true;
        }
        if (playSound) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
    }

    public static void launchHeldObject(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        UUID entityId = HELD_ENTITIES.remove(player.getUuid());
        Vec3d lookVec = player.getRotationVec(1.0F);

        if (entityId != null) {
            Entity entity = world.getEntity(entityId);
            if (entity != null) {
                entity.setNoGravity(false);
                entity.setVelocity(lookVec.multiply(2.65));
                entity.velocityModified = true;
                if (entity instanceof FallingBlockEntity fallingBlock) {
                    fallingBlock.setHurtEntities(4.0f, 40);
                }

                Vec3d burstPos = entity.getPos().add(0, entity.getHeight() * 0.5, 0);
                world.spawnParticles(ParticleTypes.FLASH, burstPos.x, burstPos.y, burstPos.z, 1, 0, 0, 0, 0);
                world.spawnParticles(ParticleTypes.CRIT, burstPos.x, burstPos.y, burstPos.z, 15, 0.25, 0.25, 0.25, 0.15);
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.65f, 1.65f);
                return;
            }
        }

        // Si no tiene un objeto sostenido, hace un pulso de empuje gravitatorio hacia adelante
        Vec3d eyePos = player.getEyePos();
        Box pushBox = player.getBoundingBox().stretch(lookVec.multiply(7.0)).expand(1.5);
        List<Entity> targets = world.getOtherEntities(player, pushBox,
                e -> e.isAlive() && !e.isSpectator() && !RegistriesContainsPortal(e));

        boolean pushedAny = false;
        for (Entity t : targets) {
            t.setVelocity(lookVec.multiply(2.1).add(0, 0.35, 0));
            t.velocityModified = true;
            if (t instanceof LivingEntity living) {
                living.damage(world.getDamageSources().playerAttack(player), 4.0f);
            }
            pushedAny = true;
        }

        if (pushedAny) {
            Vec3d front = eyePos.add(lookVec.multiply(2.0));
            world.spawnParticles(ParticleTypes.SONIC_BOOM, front.x, front.y, front.z, 1, 0, 0, 0, 0);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.5f, 1.5f);
        }
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, UUID>> it = HELD_ENTITIES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) {
                it.remove();
                continue;
            }

            boolean holdingGravityGun = player.getMainHandStack().isOf(PortalGravityMod.GRAVITY_GUN)
                    || player.getOffHandStack().isOf(PortalGravityMod.GRAVITY_GUN);

            ServerWorld world = player.getServerWorld();
            Entity held = world.getEntity(entry.getValue());
            if (held == null || !held.isAlive() || !holdingGravityGun) {
                if (held != null) {
                    held.setNoGravity(false);
                }
                it.remove();
                continue;
            }

            // Mantener vivo el FallingBlockEntity para que no desaparezca mientras flota
            if (held instanceof FallingBlockEntity) {
                NbtCompound nbt = new NbtCompound();
                held.writeNbt(nbt);
                nbt.putInt("Time", 1);
                held.readNbt(nbt);
                held.setNoGravity(true);
            }

            Vec3d eyePos = player.getEyePos();
            Vec3d lookVec = player.getRotationVec(1.0F);
            Vec3d targetPos = eyePos.add(lookVec.multiply(HOLD_DISTANCE)).subtract(0, held.getHeight() * 0.5, 0);

            // Física suave tipo resorte amortiguado hacia la posición objetivo
            Vec3d delta = targetPos.subtract(held.getPos());
            if (delta.lengthSquared() > 64.0) {
                held.setNoGravity(false);
                it.remove();
                continue;
            }

            Vec3d smoothVel = delta.multiply(0.45);
            held.setVelocity(smoothVel);
            held.velocityModified = true;
            held.fallDistance = 0.0f;

            // Haz de energía ámbar entre el jugador y el objeto flotante
            Vec3d beamStart = eyePos.add(lookVec.multiply(0.8)).subtract(0, 0.25, 0);
            Vec3d beamEnd = held.getPos().add(0, held.getHeight() * 0.5, 0);
            for (int i = 1; i <= 4; i++) {
                double t = i / 5.0;
                Vec3d p = beamStart.lerp(beamEnd, t);
                world.spawnParticles(AMBER_BEAM, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
            }
        }
    }
}
