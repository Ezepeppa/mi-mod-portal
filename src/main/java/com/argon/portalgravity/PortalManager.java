package com.argon.portalgravity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PortalManager {
    private static final Identifier IMM_PTL_ID = new Identifier("immersive_portals", "portal");
    private static final DustParticleEffect BLUE_PARTICLE = new DustParticleEffect(new Vector3f(0.05f, 0.58f, 1.0f), 1.15f);
    private static final DustParticleEffect ORANGE_PARTICLE = new DustParticleEffect(new Vector3f(1.0f, 0.48f, 0.05f), 1.15f);

    public static class PortalRecord {
        public final RegistryKey<World> dimension;
        public final Vec3d origin;
        public final Vec3d axisW;
        public final Vec3d axisH;
        public final Vec3d normal;
        public UUID forwardUuid;
        public UUID flippedUuid;

        public PortalRecord(RegistryKey<World> dimension, Vec3d origin, Vec3d axisW, Vec3d axisH, Vec3d normal) {
            this.dimension = dimension;
            this.origin = origin;
            this.axisW = axisW;
            this.axisH = axisH;
            this.normal = normal;
        }
    }

    public static class PlayerPortalPair {
        public PortalRecord bluePortal;
        public PortalRecord orangePortal;
    }

    private static final Map<UUID, PlayerPortalPair> PLAYER_PORTALS = new HashMap<>();
    private static int tickCounter = 0;

    public static void shootPortal(ServerPlayerEntity player, boolean isOrange) {
        ServerWorld world = player.getServerWorld();
        if (!Registries.ENTITY_TYPE.containsId(IMM_PTL_ID)) {
            player.sendMessage(Text.literal("¡Falta el mod Immersive Portals en tu carpeta mods!").formatted(Formatting.RED), true);
            return;
        }

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d endPos = eyePos.add(lookVec.multiply(80.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                eyePos, endPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        // Dibujar el rayo de partículas del disparo
        DustParticleEffect beamParticle = isOrange ? ORANGE_PARTICLE : BLUE_PARTICLE;
        Vec3d targetPoint = hit.getPos();
        double dist = eyePos.distanceTo(targetPoint);
        int steps = Math.min(60, (int) (dist * 2.5));
        for (int i = 2; i <= steps; i++) {
            double t = (double) i / (double) Math.max(1, steps);
            Vec3d p = eyePos.lerp(targetPoint, t);
            world.spawnParticles(beamParticle, p.x, p.y, p.z, 1, 0.01, 0.01, 0.01, 0.0);
        }

        if (hit.getType() != HitResult.Type.BLOCK) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.PLAYERS, 0.8f, 1.4f);
            return;
        }

        Direction face = hit.getSide();
        BlockPos hitPos = hit.getBlockPos();
        Vec3d normal = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());

        Vec3d axisW;
        Vec3d axisH;
        Vec3d origin;

        if (face.getAxis().isHorizontal()) {
            axisH = new Vec3d(0, 1, 0);
            // normal = axisW x axisH  =>  axisW = axisH x normal
            axisW = axisH.crossProduct(normal).normalize();

            BlockPos frontPos = hitPos.offset(face);
            double baseY = frontPos.getY();
            if (!world.getBlockState(frontPos.up()).isAir() && world.getBlockState(frontPos.down()).isAir()) {
                baseY -= 1.0;
            }
            origin = new Vec3d(
                    hitPos.getX() + 0.5 + normal.x * 0.54,
                    baseY + 1.0,
                    hitPos.getZ() + 0.5 + normal.z * 0.54
            );
        } else {
            Direction horiz = player.getHorizontalFacing();
            if (face == Direction.UP) {
                Direction opp = horiz.getOpposite();
                axisH = new Vec3d(opp.getOffsetX(), 0, opp.getOffsetZ());
            } else {
                axisH = new Vec3d(horiz.getOffsetX(), 0, horiz.getOffsetZ());
            }
            axisW = axisH.crossProduct(normal).normalize();
            origin = new Vec3d(
                    hitPos.getX() + 0.5 + normal.x * 0.54,
                    hitPos.getY() + 0.5 + normal.y * 0.54,
                    hitPos.getZ() + 0.5 + normal.z * 0.54
            );
        }

        PlayerPortalPair pair = PLAYER_PORTALS.computeIfAbsent(player.getUuid(), k -> new PlayerPortalPair());
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        if (isOrange) {
            removePortalEntities(server, pair.orangePortal);
            pair.orangePortal = new PortalRecord(world.getRegistryKey(), origin, axisW, axisH, normal);
        } else {
            removePortalEntities(server, pair.bluePortal);
            pair.bluePortal = new PortalRecord(world.getRegistryKey(), origin, axisW, axisH, normal);
        }

        rebuildPortalEntities(server, pair);

        world.playSound(null, origin.x, origin.y, origin.z,
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.9f, isOrange ? 1.25f : 1.55f);
    }

    public static void clearPlayerPortals(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        PlayerPortalPair pair = PLAYER_PORTALS.remove(player.getUuid());
        if (pair != null) {
            removePortalEntities(server, pair.bluePortal);
            removePortalEntities(server, pair.orangePortal);
        }
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        player.sendMessage(Text.literal("Portales cerrados").formatted(Formatting.GRAY), true);
    }

    private static void removePortalEntities(MinecraftServer server, PortalRecord record) {
        if (record == null) return;
        ServerWorld w = server.getWorld(record.dimension);
        if (w == null) return;
        if (record.forwardUuid != null) {
            Entity e = w.getEntity(record.forwardUuid);
            if (e != null) e.discard();
            record.forwardUuid = null;
        }
        if (record.flippedUuid != null) {
            Entity e = w.getEntity(record.flippedUuid);
            if (e != null) e.discard();
            record.flippedUuid = null;
        }
    }

    private static void rebuildPortalEntities(MinecraftServer server, PlayerPortalPair pair) {
        removePortalEntities(server, pair.bluePortal);
        removePortalEntities(server, pair.orangePortal);

        boolean bothExist = (pair.bluePortal != null && pair.orangePortal != null);
        if (!bothExist) {
            // Si solo hay un portal disparado, el anillo de partículas ya muestra su posición
            return;
        }

        pair.bluePortal.forwardUuid = spawnLinkedPortal(server, pair.bluePortal, pair.orangePortal);
        pair.orangePortal.forwardUuid = spawnLinkedPortal(server, pair.orangePortal, pair.bluePortal);

        if (pair.bluePortal.forwardUuid != null && pair.orangePortal.forwardUuid != null) {
            ServerCommandSource silentSource = server.getCommandSource().withLevel(4).withSilent();
            String blueId = pair.bluePortal.forwardUuid.toString();
            String orangeId = pair.orangePortal.forwardUuid.toString();

            // Ajustar rotación espacial exacta y forma elíptica usando los comandos nativos de Immersive Portals
            server.getCommandManager().executeWithPrefix(silentSource,
                    "portal adjust_rotation_to_connect " + blueId + " " + orangeId);
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + blueId + " run portal make_portal_round");
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + orangeId + " run portal make_portal_round");
        }
    }

    private static UUID spawnLinkedPortal(MinecraftServer server, PortalRecord from, PortalRecord to) {
        ServerWorld fromWorld = server.getWorld(from.dimension);
        if (fromWorld == null) return null;

        EntityType<?> portalType = Registries.ENTITY_TYPE.get(IMM_PTL_ID);
        Entity portal = portalType.create(fromWorld);
        if (portal == null) return null;

        portal.setPosition(from.origin.x, from.origin.y, from.origin.z);

        NbtCompound nbt = new NbtCompound();
        portal.writeNbt(nbt);

        nbt.putDouble("axisWX", from.axisW.x);
        nbt.putDouble("axisWY", from.axisW.y);
        nbt.putDouble("axisWZ", from.axisW.z);
        nbt.putDouble("axisHX", from.axisH.x);
        nbt.putDouble("axisHY", from.axisH.y);
        nbt.putDouble("axisHZ", from.axisH.z);

        nbt.putDouble("width", 1.15);
        nbt.putDouble("height", 2.05);

        nbt.putString("dimensionTo", to.dimension.getValue().toString());
        nbt.putDouble("destinationX", to.origin.x);
        nbt.putDouble("destinationY", to.origin.y);
        nbt.putDouble("destinationZ", to.origin.z);

        nbt.putBoolean("teleportable", true);
        nbt.putBoolean("isVisible", true);
        nbt.putBoolean("adjustPositionAfterTeleport", true);
        nbt.putBoolean("hasCrossPortalCollision", true);
        nbt.putBoolean("doRenderPlayer", true);

        portal.readNbt(nbt);
        fromWorld.spawnEntity(portal);
        return portal.getUuid();
    }

    public static void tickPortals(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 2 != 0) return;

        for (PlayerPortalPair pair : PLAYER_PORTALS.values()) {
            if (pair.bluePortal != null) {
                drawPortalBorder(server, pair.bluePortal, BLUE_PARTICLE);
            }
            if (pair.orangePortal != null) {
                drawPortalBorder(server, pair.orangePortal, ORANGE_PARTICLE);
            }
        }
    }

    private static void drawPortalBorder(MinecraftServer server, PortalRecord portal, DustParticleEffect effect) {
        ServerWorld world = server.getWorld(portal.dimension);
        if (world == null) return;

        double halfW = 0.58;
        double halfH = 1.03;
        int points = 20;
        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI * i) / points;
            double cos = Math.cos(angle) * halfW;
            double sin = Math.sin(angle) * halfH;
            Vec3d pos = portal.origin
                    .add(portal.axisW.multiply(cos))
                    .add(portal.axisH.multiply(sin))
                    .add(portal.normal.multiply(0.02));
            world.spawnParticles(effect, pos.x, pos.y, pos.z, 1, 0.01, 0.01, 0.01, 0.0);
        }
    }
}
