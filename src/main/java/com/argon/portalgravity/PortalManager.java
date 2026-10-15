package com.argon.portalgravity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PortalManager {
    private static final Identifier IMM_PTL_ID = new Identifier("immersive_portals", "portal");

    public static class PortalRecord {
        public final RegistryKey<World> dimension;
        public final Vec3d origin;
        public final Vec3d axisW;
        public final Vec3d axisH;
        public final Vec3d normal;
        public final double width;
        public final double height;
        public final boolean isOrange;
        public final boolean momentumBoost;
        public UUID forwardUuid;

        public PortalRecord(RegistryKey<World> dimension, Vec3d origin, Vec3d axisW, Vec3d axisH, Vec3d normal,
                            double width, double height, boolean isOrange, boolean momentumBoost) {
            this.dimension = dimension;
            this.origin = origin;
            this.axisW = axisW;
            this.axisH = axisH;
            this.normal = normal;
            this.width = width;
            this.height = height;
            this.isOrange = isOrange;
            this.momentumBoost = momentumBoost;
        }
    }

    public static class ShotBeamFx {
        public final RegistryKey<World> dimension;
        public final Vec3d start;
        public final Vec3d end;
        public final boolean isOrange;
        public int ticksLeft;

        public ShotBeamFx(RegistryKey<World> dimension, Vec3d start, Vec3d end, boolean isOrange, int ticksLeft) {
            this.dimension = dimension;
            this.start = start;
            this.end = end;
            this.isOrange = isOrange;
            this.ticksLeft = ticksLeft;
        }
    }

    public static class PlayerPortalPair {
        public PortalRecord bluePortal;
        public PortalRecord orangePortal;
        // 0 = Normal (1.5 x 2.5), 1 = Colosal (3.2 x 4.2), 2 = Impulso Catapulta (1.6 x 2.6 + boost)
        public int mode = 0;
    }

    private static final Map<UUID, PlayerPortalPair> PLAYER_PORTALS = new HashMap<>();
    private static final List<ShotBeamFx> ACTIVE_SHOT_BEAMS = new ArrayList<>();

    public static Map<UUID, PlayerPortalPair> getPlayerPortals() {
        return PLAYER_PORTALS;
    }

    public static List<ShotBeamFx> getActiveShotBeams() {
        return ACTIVE_SHOT_BEAMS;
    }

    public static void cyclePortalMode(ServerPlayerEntity player) {
        PlayerPortalPair pair = PLAYER_PORTALS.computeIfAbsent(player.getUuid(), k -> new PlayerPortalPair());
        pair.mode = (pair.mode + 1) % 3;
        String modeName = switch (pair.mode) {
            case 1 -> "Portal Colosal Gigante (3.2m x 4.2m)";
            case 2 -> "Portal de Impulso Catapulta (Momentum Boost)";
            default -> "Portal Estandar (1.5m x 2.5m)";
        };
        player.sendMessage(Text.literal("Modo de Portal Gun: ").formatted(Formatting.GRAY)
                .append(Text.literal(modeName).formatted(Formatting.AQUA)), true);
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.4f);
    }

    public static void shootPortal(ServerPlayerEntity player, boolean isOrange) {
        ServerWorld world = player.getServerWorld();
        if (!Registries.ENTITY_TYPE.containsId(IMM_PTL_ID)) {
            player.sendMessage(Text.literal("¡Falta el mod Immersive Portals en tu carpeta mods!").formatted(Formatting.RED), true);
            return;
        }

        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d endPos = eyePos.add(lookVec.multiply(90.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                eyePos, endPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        Vec3d handStart = eyePos.add(lookVec.multiply(0.5)).subtract(0, 0.2, 0);
        ACTIVE_SHOT_BEAMS.add(new ShotBeamFx(world.getRegistryKey(), handStart, hit.getPos(), isOrange, 6));

        if (hit.getType() != HitResult.Type.BLOCK) {
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_DISPENSER_FAIL, SoundCategory.PLAYERS, 0.8f, 1.4f);
            return;
        }

        PlayerPortalPair pair = PLAYER_PORTALS.computeIfAbsent(player.getUuid(), k -> new PlayerPortalPair());
        double width = (pair.mode == 1) ? 3.2 : 1.55;
        double height = (pair.mode == 1) ? 4.2 : 2.55;
        boolean boost = (pair.mode == 2);

        Direction face = hit.getSide();
        BlockPos hitPos = hit.getBlockPos();
        Vec3d normal = new Vec3d(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ());

        Vec3d axisW;
        Vec3d axisH;
        Vec3d origin;

        if (face.getAxis().isHorizontal()) {
            axisH = new Vec3d(0, 1, 0);
            axisW = axisH.crossProduct(normal).normalize();

            BlockPos frontPos = hitPos.offset(face);
            double baseY = frontPos.getY();
            // Alinear la base del óvalo exactamente al nivel del suelo si se dispara cerca del piso
            if (world.getBlockState(frontPos.down()).isSolidBlock(world, frontPos.down())) {
                baseY = frontPos.getY();
            } else if (!world.getBlockState(frontPos.up()).isAir() && world.getBlockState(frontPos.down()).isAir()) {
                baseY -= 1.0;
            }
            origin = new Vec3d(
                    hitPos.getX() + 0.5 + normal.x * 0.52,
                    baseY + (height * 0.5) - 0.05,
                    hitPos.getZ() + 0.5 + normal.z * 0.52
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
                    hitPos.getX() + 0.5 + normal.x * 0.52,
                    hitPos.getY() + 0.5 + normal.y * 0.52,
                    hitPos.getZ() + 0.5 + normal.z * 0.52
            );
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (isOrange) {
            removePortalEntity(server, pair.orangePortal);
            pair.orangePortal = new PortalRecord(world.getRegistryKey(), origin, axisW, axisH, normal, width, height, true, boost);
        } else {
            removePortalEntity(server, pair.bluePortal);
            pair.bluePortal = new PortalRecord(world.getRegistryKey(), origin, axisW, axisH, normal, width, height, false, boost);
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
            removePortalEntity(server, pair.bluePortal);
            removePortalEntity(server, pair.orangePortal);
        }
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        player.sendMessage(Text.literal("Portales cerrados").formatted(Formatting.GRAY), true);
    }

    private static void removePortalEntity(MinecraftServer server, PortalRecord record) {
        if (record == null || record.forwardUuid == null) return;
        ServerWorld w = server.getWorld(record.dimension);
        if (w != null) {
            Entity e = w.getEntity(record.forwardUuid);
            if (e != null) e.discard();
        }
        record.forwardUuid = null;
    }

    private static void rebuildPortalEntities(MinecraftServer server, PlayerPortalPair pair) {
        removePortalEntity(server, pair.bluePortal);
        removePortalEntity(server, pair.orangePortal);

        if (pair.bluePortal == null || pair.orangePortal == null) {
            // Cuando solo hay 1 portal colocado, el renderizador 3D del cliente dibuja el óvalo mágico con vórtice cerrado
            return;
        }

        pair.bluePortal.forwardUuid = spawnLinkedPortal(server, pair.bluePortal, pair.orangePortal);
        pair.orangePortal.forwardUuid = spawnLinkedPortal(server, pair.orangePortal, pair.bluePortal);

        if (pair.bluePortal.forwardUuid != null && pair.orangePortal.forwardUuid != null) {
            ServerCommandSource silentSource = server.getCommandSource().withLevel(4).withSilent();
            String blueId = pair.bluePortal.forwardUuid.toString();
            String orangeId = pair.orangePortal.forwardUuid.toString();

            // 1. Dar forma elíptica exacta de 64 lados que encaja milimétricamente con nuestro contorno mágico 3D
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + blueId + " run portal make_portal_round");
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + orangeId + " run portal make_portal_round");

            // 2. Calcular y aplicar la rotación 3D exacta R = E2 * E1^T para cada portal
            applyExactPortalRotation(server, silentSource, blueId, pair.bluePortal, pair.orangePortal);
            applyExactPortalRotation(server, silentSource, orangeId, pair.orangePortal, pair.bluePortal);
        }
    }

    /**
     * Calcula la matriz de rotación ortogonal 3x3 que transforma la dirección de entrada en `from`
     * (-axisW1, axisH1, -normal1) en la dirección de salida de `to` (+axisW2, +axisH2, +normal2),
     * extrae eje-ángulo y lo aplica vía `portal set_portal_rotation`.
     */
    private static void applyExactPortalRotation(MinecraftServer server, ServerCommandSource source,
                                                 String portalUuid, PortalRecord from, PortalRecord to) {
        Vec3d u1 = from.axisW.multiply(-1.0);
        Vec3d v1 = from.axisH;
        Vec3d w1 = from.normal.multiply(-1.0);

        Vec3d u2 = to.axisW;
        Vec3d v2 = to.axisH;
        Vec3d w2 = to.normal;

        // R = u2 * u1^T + v2 * v1^T + w2 * w1^T
        double r00 = u2.x * u1.x + v2.x * v1.x + w2.x * w1.x;
        double r01 = u2.x * u1.y + v2.x * v1.y + w2.x * w1.y;
        double r02 = u2.x * u1.z + v2.x * v1.z + w2.x * w1.z;

        double r10 = u2.y * u1.x + v2.y * v1.x + w2.y * w1.x;
        double r11 = u2.y * u1.y + v2.y * v1.y + w2.y * w1.y;
        double r12 = u2.y * u1.z + v2.y * v1.z + w2.y * w1.z;

        double r20 = u2.z * u1.x + v2.z * v1.x + w2.z * w1.x;
        double r21 = u2.z * u1.y + v2.z * v1.y + w2.z * w1.y;
        double r22 = u2.z * u1.z + v2.z * v1.z + w2.z * w1.z;

        double trace = r00 + r11 + r22;
        double cosTheta = Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5));
        double angleRad = Math.acos(cosTheta);
        double angleDeg = Math.toDegrees(angleRad);

        double ax = 0.0, ay = 1.0, az = 0.0;
        if (angleDeg < 0.5) {
            angleDeg = 0.0;
        } else if (Math.abs(angleDeg - 180.0) < 0.5) {
            // Caso de 180 grados: extraer el vector propio con valor +1 de (R + I)/2
            double xx = Math.max(0.0, (r00 + 1.0) * 0.5);
            double yy = Math.max(0.0, (r11 + 1.0) * 0.5);
            double zz = Math.max(0.0, (r22 + 1.0) * 0.5);
            ax = Math.sqrt(xx);
            ay = Math.sqrt(yy);
            az = Math.sqrt(zz);
            if (xx >= yy && xx >= zz) {
                if (r01 + r10 < 0) ay = -ay;
                if (r02 + r20 < 0) az = -az;
            } else if (yy >= xx && yy >= zz) {
                if (r01 + r10 < 0) ax = -ax;
                if (r12 + r21 < 0) az = -az;
            } else {
                if (r02 + r20 < 0) ax = -ax;
                if (r12 + r21 < 0) ay = -ay;
            }
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (len > 1e-5) {
                ax /= len;
                ay /= len;
                az /= len;
            } else {
                ax = 0.0;
                ay = 1.0;
                az = 0.0;
            }
        } else {
            double rx = r21 - r12;
            double ry = r02 - r20;
            double rz = r10 - r01;
            double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (len > 1e-5) {
                ax = rx / len;
                ay = ry / len;
                az = rz / len;
            }
        }

        String cmd = String.format(Locale.ROOT,
                "execute as %s run portal set_portal_rotation %.4f %.4f %.4f %.2f",
                portalUuid, ax, ay, az, angleDeg);
        server.getCommandManager().executeWithPrefix(source, cmd);
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

        nbt.putDouble("width", from.width);
        nbt.putDouble("height", from.height);

        nbt.putString("dimensionTo", to.dimension.getValue().toString());
        // Destino ligeramente hacia afuera del otro portal para salir limpiamente sin tocar la pared trasera
        Vec3d dest = to.origin.add(to.normal.multiply(0.08));
        nbt.putDouble("destinationX", dest.x);
        nbt.putDouble("destinationY", dest.y);
        nbt.putDouble("destinationZ", dest.z);

        nbt.putBoolean("teleportable", true);
        nbt.putBoolean("isVisible", true);
        nbt.putBoolean("adjustPositionAfterTeleport", true);
        nbt.putBoolean("hasCrossPortalCollision", true);
        nbt.putBoolean("doRenderPlayer", true);
        if (from.momentumBoost || to.momentumBoost) {
            nbt.putDouble("motionAffinity", 0.35);
        }

        portal.readNbt(nbt);
        fromWorld.spawnEntity(portal);
        return portal.getUuid();
    }

    public static void tickPortals(MinecraftServer server) {
        ACTIVE_SHOT_BEAMS.removeIf(b -> {
            b.ticksLeft--;
            return b.ticksLeft <= 0;
        });
    }
}
