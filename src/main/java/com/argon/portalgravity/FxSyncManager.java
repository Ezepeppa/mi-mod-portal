package com.argon.portalgravity;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FxSyncManager {
    public static void syncToAllPlayers(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            RegistryKey<World> dim = player.getServerWorld().getRegistryKey();
            ServerWorld world = player.getServerWorld();

            PacketByteBuf buf = PacketByteBufs.create();

            // 0. Estado de bloqueo de ruedita del mouse
            boolean holdingGravityObj = GravityGunManager.hasHeldObjects(player);
            boolean interceptScroll = holdingGravityObj || ArchitectManager.isInterceptingScroll(player);
            buf.writeBoolean(interceptScroll);
            buf.writeDouble(GravityGunManager.getHoldDistance(player.getUuid()));

            // 1. Portales activos
            List<PortalManager.PortalRecord> portals = new ArrayList<>();
            List<Boolean> portalLinkedFlags = new ArrayList<>();
            for (PortalManager.PlayerPortalPair pair : PortalManager.getPlayerPortals().values()) {
                boolean linked = (pair.bluePortal != null && pair.orangePortal != null);
                if (pair.bluePortal != null && pair.bluePortal.dimension.equals(dim)) {
                    portals.add(pair.bluePortal);
                    portalLinkedFlags.add(linked);
                }
                if (pair.orangePortal != null && pair.orangePortal.dimension.equals(dim)) {
                    portals.add(pair.orangePortal);
                    portalLinkedFlags.add(linked);
                }
            }

            buf.writeInt(portals.size());
            for (int i = 0; i < portals.size(); i++) {
                PortalManager.PortalRecord p = portals.get(i);
                buf.writeBoolean(p.isOrange);
                buf.writeBoolean(portalLinkedFlags.get(i));
                buf.writeDouble(p.origin.x);
                buf.writeDouble(p.origin.y);
                buf.writeDouble(p.origin.z);
                buf.writeDouble(p.axisW.x);
                buf.writeDouble(p.axisW.y);
                buf.writeDouble(p.axisW.z);
                buf.writeDouble(p.axisH.x);
                buf.writeDouble(p.axisH.y);
                buf.writeDouble(p.axisH.z);
                buf.writeDouble(p.normal.x);
                buf.writeDouble(p.normal.y);
                buf.writeDouble(p.normal.z);
                buf.writeDouble(p.width);
                buf.writeDouble(p.height);
            }

            // 2. Objetos sostenidos por Gravity Gun
            List<Integer> holderIds = new ArrayList<>();
            List<Integer> heldEntityIds = new ArrayList<>();
            List<Boolean> isBlockFlags = new ArrayList<>();
            List<Integer> orbitSlots = new ArrayList<>();
            List<Vec3d> beamStarts = new ArrayList<>();
            List<Vec3d> beamEnds = new ArrayList<>();
            List<Float> beamRotations = new ArrayList<>();

            for (Map.Entry<UUID, List<GravityGunManager.HeldObjectState>> entry : GravityGunManager.getPlayerHeld().entrySet()) {
                ServerPlayerEntity holder = server.getPlayerManager().getPlayer(entry.getKey());
                if (holder == null || !holder.getServerWorld().getRegistryKey().equals(dim)) continue;

                Vec3d gunMuzzle = PortalManager.computeServerMuzzlePos(holder);
                for (GravityGunManager.HeldObjectState st : entry.getValue()) {
                    Entity e = world.getEntity(st.entityUuid);
                    if (e != null && e.isAlive()) {
                        Vec3d center = st.isBlockDisplay ? e.getPos() : e.getPos().add(0, e.getHeight() * 0.5, 0);
                        holderIds.add(holder.getId());
                        heldEntityIds.add(e.getId());
                        isBlockFlags.add(st.isBlockDisplay);
                        orbitSlots.add(st.orbitSlot);
                        beamStarts.add(gunMuzzle);
                        beamEnds.add(center);
                        beamRotations.add(st.rotY);
                    }
                }
            }

            buf.writeInt(beamStarts.size());
            for (int i = 0; i < beamStarts.size(); i++) {
                buf.writeInt(holderIds.get(i));
                buf.writeInt(heldEntityIds.get(i));
                buf.writeBoolean(isBlockFlags.get(i));
                buf.writeInt(orbitSlots.get(i));
                Vec3d s = beamStarts.get(i);
                Vec3d e = beamEnds.get(i);
                buf.writeDouble(s.x);
                buf.writeDouble(s.y);
                buf.writeDouble(s.z);
                buf.writeDouble(e.x);
                buf.writeDouble(e.y);
                buf.writeDouble(e.z);
                buf.writeFloat(beamRotations.get(i));
            }

            // 3. Haces de disparo
            List<PortalManager.ShotBeamFx> shots = new ArrayList<>();
            for (PortalManager.ShotBeamFx b : PortalManager.getActiveShotBeams()) {
                if (b.dimension.equals(dim)) shots.add(b);
            }
            buf.writeInt(shots.size());
            for (PortalManager.ShotBeamFx b : shots) {
                buf.writeInt(b.shooterEntityId);
                buf.writeInt(b.colorType);
                buf.writeDouble(b.start.x);
                buf.writeDouble(b.start.y);
                buf.writeDouble(b.start.z);
                buf.writeDouble(b.end.x);
                buf.writeDouble(b.end.y);
                buf.writeDouble(b.end.z);
            }

            // 4. Vórtices de Singularidad
            List<SingularityManager.BlackHoleVortex> vortices = new ArrayList<>();
            for (SingularityManager.BlackHoleVortex v : SingularityManager.getActiveVortices()) {
                if (v.dimension.equals(dim)) vortices.add(v);
            }
            buf.writeInt(vortices.size());
            for (SingularityManager.BlackHoleVortex v : vortices) {
                buf.writeDouble(v.position.x);
                buf.writeDouble(v.position.y);
                buf.writeDouble(v.position.z);
                buf.writeInt(v.ticksRemaining);
            }

            // 5. Holograma 3D del Cañón Arquitecto
            ArchitectManager.ArchitectSession arch = ArchitectManager.getSessions().get(player.getUuid());
            if (arch != null && arch.anchorPos != null && arch.currentEndPos != null && !arch.carryingStructure) {
                buf.writeBoolean(true);
                buf.writeInt(arch.mode);
                buf.writeBlockPos(arch.anchorPos);
                buf.writeBlockPos(arch.currentEndPos);
            } else if (arch != null && arch.carryingStructure) {
                buf.writeBoolean(true);
                buf.writeInt(4);
                BlockPos cPos = BlockPos.ofFloored(arch.structureCenter.x, arch.structureCenter.y, arch.structureCenter.z);
                buf.writeBlockPos(cPos.add(-1, -1, -1));
                buf.writeBlockPos(cPos.add(1, 1, 1));
            } else {
                buf.writeBoolean(false);
            }

            // 6. Cuerdas de Plasma del Rifle de Gancho y Lazo
            List<Integer> grappleHolders = new ArrayList<>();
            List<Vec3d> grappleAnchors = new ArrayList<>();
            for (Map.Entry<UUID, GrappleManager.PlayerGrappleHook> gEntry : GrappleManager.getPlayerGrapples().entrySet()) {
                ServerPlayerEntity gPlayer = server.getPlayerManager().getPlayer(gEntry.getKey());
                if (gPlayer != null && gEntry.getValue().dimension.equals(dim)) {
                    grappleHolders.add(gPlayer.getId());
                    grappleAnchors.add(gEntry.getValue().anchorPoint);
                }
            }
            buf.writeInt(grappleHolders.size());
            for (int i = 0; i < grappleHolders.size(); i++) {
                buf.writeInt(grappleHolders.get(i));
                Vec3d a = grappleAnchors.get(i);
                buf.writeDouble(a.x);
                buf.writeDouble(a.y);
                buf.writeDouble(a.z);
            }

            List<Vec3d> tetherA = new ArrayList<>();
            List<Vec3d> tetherB = new ArrayList<>();
            for (GrappleManager.ActiveTether t : GrappleManager.getActiveTethers()) {
                if (t.dimension.equals(dim)) {
                    Vec3d pA = t.pointA.resolvePos(world);
                    Vec3d pB = t.pointB.resolvePos(world);
                    if (pA != null && pB != null) {
                        tetherA.add(pA);
                        tetherB.add(pB);
                    }
                }
            }
            buf.writeInt(tetherA.size());
            for (int i = 0; i < tetherA.size(); i++) {
                Vec3d pA = tetherA.get(i);
                Vec3d pB = tetherB.get(i);
                buf.writeDouble(pA.x);
                buf.writeDouble(pA.y);
                buf.writeDouble(pA.z);
                buf.writeDouble(pB.x);
                buf.writeDouble(pB.y);
                buf.writeDouble(pB.z);
            }

            ServerPlayNetworking.send(player, PortalGravityMod.SYNC_FX_PACKET, buf);
        }
    }
}
