package com.argon.portalgravity;

import net.minecraft.entity.Entity;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public class SingularityManager {
    public static class BlackHoleVortex {
        public final RegistryKey<World> dimension;
        public Vec3d position;
        public Vec3d velocity;
        public int ticksRemaining;

        public BlackHoleVortex(RegistryKey<World> dimension, Vec3d position, Vec3d velocity, int ticksRemaining) {
            this.dimension = dimension;
            this.position = position;
            this.velocity = velocity;
            this.ticksRemaining = ticksRemaining;
        }
    }

    private static final List<BlackHoleVortex> ACTIVE_VORTICES = new ArrayList<>();

    public static List<BlackHoleVortex> getActiveVortices() {
        return ACTIVE_VORTICES;
    }

    public static void spawnBlackHoleVortex(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d start = player.getEyePos().add(player.getRotationVec(1.0F).multiply(1.2));
        Vec3d vel = player.getRotationVec(1.0F).multiply(0.45);
        ACTIVE_VORTICES.add(new BlackHoleVortex(world.getRegistryKey(), start, vel, 85));

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.7f, 1.5f);
        player.sendMessage(Text.literal("¡Singularidad Arcana desplegada!").formatted(Formatting.LIGHT_PURPLE), true);
    }

    public static void quantumSwap(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d endPos = eyePos.add(lookVec.multiply(45.0));

        Box searchBox = player.getBoundingBox().stretch(lookVec.multiply(45.0)).expand(1.8);
        List<Entity> candidates = world.getOtherEntities(player, searchBox,
                e -> e.isAlive() && !e.isSpectator() && !e.getType().toString().contains("portal"));

        Entity target = null;
        double closestDist = 45.0 * 45.0;
        for (Entity c : candidates) {
            Optional<Vec3d> hit = c.getBoundingBox().expand(0.4).raycast(eyePos, endPos);
            if (hit.isPresent()) {
                double d = eyePos.squaredDistanceTo(hit.get());
                if (d < closestDist) {
                    closestDist = d;
                    target = c;
                }
            }
        }

        if (target != null) {
            Vec3d playerOld = player.getPos();
            Vec3d targetOld = target.getPos();
            float playerYaw = player.getYaw();
            float targetYaw = target.getYaw();

            player.teleport(world, targetOld.x, targetOld.y, targetOld.z, targetYaw, player.getPitch());
            target.teleport(playerOld.x, playerOld.y, playerOld.z);
            target.setYaw(playerYaw);

            PortalManager.getActiveShotBeams().add(
                    new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), playerOld.add(0, 1.0, 0), targetOld.add(0, 1.0, 0), 0, 10)
            );
            world.playSound(null, targetOld.x, targetOld.y, targetOld.z,
                    SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.75f);
            player.sendMessage(Text.literal("¡Intercambio Cuántico completado!").formatted(Formatting.AQUA), true);
            return;
        }

        BlockHitResult hit = world.raycast(new RaycastContext(
                eyePos, endPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            Vec3d dest = hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(0.8));
            PortalManager.getActiveShotBeams().add(
                    new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), player.getPos().add(0, 1.0, 0), dest.add(0, 1.0, 0), 1, 8)
            );
            player.teleport(world, dest.x, dest.y, dest.z, player.getYaw(), player.getPitch());
            world.playSound(null, dest.x, dest.y, dest.z,
                    SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.4f);
        }
    }

    public static void tick(MinecraftServer server) {
        Iterator<BlackHoleVortex> it = ACTIVE_VORTICES.iterator();
        while (it.hasNext()) {
            BlackHoleVortex v = it.next();
            ServerWorld world = server.getWorld(v.dimension);
            if (world == null) {
                it.remove();
                continue;
            }

            v.ticksRemaining--;
            v.position = v.position.add(v.velocity);
            v.velocity = v.velocity.multiply(0.92);

            Box pullBox = new Box(v.position.subtract(9, 9, 9), v.position.add(9, 9, 9));
            List<Entity> nearby = world.getOtherEntities(null, pullBox,
                    e -> e.isAlive() && !e.isSpectator() && !(e instanceof PlayerEntity)
                            && !e.getType().toString().contains("portal"));

            if (v.ticksRemaining > 1) {
                for (Entity e : nearby) {
                    Vec3d toCenter = v.position.subtract(e.getPos().add(0, e.getHeight() * 0.5, 0));
                    double dist = Math.max(0.5, toCenter.length());
                    Vec3d dir = toCenter.normalize();
                    Vec3d tangent = new Vec3d(-dir.z, 0.25, dir.x).normalize();
                    Vec3d swirlVel = dir.multiply(Math.min(0.65, 2.2 / dist)).add(tangent.multiply(0.35));
                    e.setVelocity(swirlVel);
                    e.velocityModified = true;
                    e.fallDistance = 0.0f;
                    e.setYaw(e.getYaw() + 18.0f);
                }
            } else {
                for (Entity e : nearby) {
                    Vec3d away = e.getPos().subtract(v.position).normalize().multiply(2.4).add(0, 0.85, 0);
                    e.setVelocity(away);
                    e.velocityModified = true;
                    if (e instanceof LivingEntity living) {
                        living.damage(world.getDamageSources().magic(), 8.0f);
                    }
                }
                world.playSound(null, v.position.x, v.position.y, v.position.z,
                        SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.0f, 0.8f);
                it.remove();
            }
        }
    }
}
