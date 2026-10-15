package com.argon.portalgravity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
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
        public final int colorType; // 0 = Azul, 1 = Naranja, 2 = Esmeralda Arquitecto
        public int ticksLeft;

        public ShotBeamFx(RegistryKey<World> dimension, Vec3d start, Vec3d end, int colorType, int ticksLeft) {
            this.dimension = dimension;
            this.start = start;
            this.end = end;
            this.colorType = colorType;
            this.ticksLeft = ticksLeft;
        }
    }

    public static class PlayerPortalPair {
        public PortalRecord bluePortal;
        public PortalRecord orangePortal;
        public int mode = 0; // 0 = Normal, 1 = Colosal, 2 = Impulso
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
            default -> "Portal Estandar (1.55m x 2.55m)";
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
        ACTIVE_SHOT_BEAMS.add(new ShotBeamFx(world.getRegistryKey(), handStart, hit.getPos(), isOrange ? 1 : 0, 7));

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
            if (world.getBlockState(frontPos.down()).isSolidBlock(world, frontPos.down())) {
                baseY = frontPos.getY();
            } else if (!world.getBlockState(frontPos.up()).isAir() && world.getBlockState(frontPos.down()).isAir()) {
                baseY -= 1.0;
            }
            origin = new Vec3d(
                    hitPos.getX() + 0.5 + normal.x * 0.54,
                    baseY + (height * 0.5) - 0.02,
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
            return;
        }

        pair.bluePortal.forwardUuid = spawnLinkedPortal(server, pair.bluePortal, pair.orangePortal);
        pair.orangePortal.forwardUuid = spawnLinkedPortal(server, pair.orangePortal, pair.bluePortal);

        if (pair.bluePortal.forwardUuid != null && pair.orangePortal.forwardUuid != null) {
            ServerCommandSource silentSource = server.getCommandSource().withLevel(4).withSilent();
            String blueId = pair.bluePortal.forwardUuid.toString();
            String orangeId = pair.orangePortal.forwardUuid.toString();

            // 1. Dar forma elíptica exacta de 64 lados que encaja con nuestro óvalo 3D
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + blueId + " run portal make_portal_round");
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + orangeId + " run portal make_portal_round");

            // 2. Aplicar rotación 3D exacta R = E2 * E1^T y sincronizar con los clientes
            applyExactPortalRotation(server, silentSource, blueId, pair.bluePortal, pair.orangePortal);
            applyExactPortalRotation(server, silentSource, orangeId, pair.orangePortal, pair.bluePortal);

            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + blueId + " run portal set_portal_nbt {teleportable:true,isVisible:true,doRenderPlayer:true,hasCrossPortalCollision:true,adjustPositionAfterTeleport:true}");
            server.getCommandManager().executeWithPrefix(silentSource,
                    "execute as " + orangeId + " run portal set_portal_nbt {teleportable:true,isVisible:true,doRenderPlayer:true,hasCrossPortalCollision:true,adjustPositionAfterTeleport:true}");
        }
    }

    private static void applyExactPortalRotation(MinecraftServer server, ServerCommandSource source,
                                                 String portalUuid, PortalRecord from, PortalRecord to) {
        Vec3d u1 = from.axisW.multiply(-1.0);
        Vec3d v1 = from.axisH;
        Vec3d w1 = from.normal.multiply(-1.0);

        Vec3d u2 = to.axisW;
        Vec3d v2 = to.axisH;
        Vec3d w2 = to.normal;

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

    /**
     * Inicializa y spawnea la entidad `immersive_portals:portal` usando `EntityType.loadEntityWithPassengers`
     * con todos los campos requeridos en NBT (sin llamar a `writeNbt` sobre una instancia no inicializada).
     */
    private static UUID spawnLinkedPortal(MinecraftServer server, PortalRecord from, PortalRecord to) {
        ServerWorld fromWorld = server.getWorld(from.dimension);
        if (fromWorld == null) return null;

        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", IMM_PTL_ID.toString());

        NbtList posList = new NbtList();
        posList.add(NbtDouble.of(from.origin.x));
        posList.add(NbtDouble.of(from.origin.y));
        posList.add(NbtDouble.of(from.origin.z));
        nbt.put("Pos", posList);

        NbtList motionList = new NbtList();
        motionList.add(NbtDouble.of(0.0));
        motionList.add(NbtDouble.of(0.0));
        motionList.add(NbtDouble.of(0.0));
        nbt.put("Motion", motionList);

        NbtList rotList = new NbtList();
        rotList.add(NbtFloat.of(0.0f));
        rotList.add(NbtFloat.of(0.0f));
        nbt.put("Rotation", rotList);

        nbt.putDouble("axisWX", from.axisW.x);
        nbt.putDouble("axisWY", from.axisW.y);
        nbt.putDouble("axisWZ", from.axisW.z);
        nbt.putDouble("axisHX", from.axisH.x);
        nbt.putDouble("axisHY", from.axisH.y);
        nbt.putDouble("axisHZ", from.axisH.z);

        nbt.putDouble("width", from.width);
        nbt.putDouble("height", from.height);

        nbt.putString("dimensionTo", to.dimension.getValue().toString());
        Vec3d dest = to.origin.add(to.normal.multiply(0.05));
        nbt.putDouble("destinationX", dest.x);
        nbt.putDouble("destinationY", dest.y);
        nbt.putDouble("destinationZ", dest.z);

        nbt.putBoolean("teleportable", true);
        nbt.putBoolean("isVisible", true);
        nbt.putBoolean("adjustPositionAfterTeleport", true);
        nbt.putBoolean("hasCrossPortalCollision", true);
        nbt.putBoolean("doRenderPlayer", true);
        if (from.momentumBoost || to.momentumBoost) {
            nbt.putDouble("motionAffinity", 0.38);
        }

        Entity portal = EntityType.loadEntityWithPassengers(nbt, fromWorld, entity -> {
            entity.refreshPositionAndAngles(from.origin.x, from.origin.y, from.origin.z, 0.0f, 0.0f);
            return entity;
        });

        if (portal == null) {
            PortalGravityMod.LOGGER.error("No se pudo instanciar immersive_portals:portal");
            return null;
        }

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
