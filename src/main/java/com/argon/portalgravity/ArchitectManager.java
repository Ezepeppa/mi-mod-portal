package com.argon.portalgravity;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ArchitectManager {
    public static class MovingBlockPiece {
        public final BlockPos relOffset;
        public final BlockState state;
        public final UUID displayUuid;

        public MovingBlockPiece(BlockPos relOffset, BlockState state, UUID displayUuid) {
            this.relOffset = relOffset;
            this.state = state;
            this.displayUuid = displayUuid;
        }
    }

    public static class ArchitectSession {
        public int mode = 0; // 0 = Suelo XZ, 1 = Pared Vertical, 2 = Cubo 3D Libre, 3 = Mover Estructura con Peso
        public BlockState selectedBlock = Blocks.QUARTZ_BLOCK.getDefaultState();
        public BlockPos anchorPos = null;
        public BlockPos currentEndPos = null;
        public int extraHeight = 3;

        public boolean carryingStructure = false;
        public List<MovingBlockPiece> carriedPieces = new ArrayList<>();
        public Vec3d structureCenter = Vec3d.ZERO;
        public Vec3d structureVelocity = Vec3d.ZERO;
        public double holdDistance = 5.5;
        public float rotX = 0f, rotY = 0f, rotZ = 0f;
        public int ageTicks = 0;
    }

    private static final Map<UUID, ArchitectSession> SESSIONS = new HashMap<>();

    public static Map<UUID, ArchitectSession> getSessions() {
        return SESSIONS;
    }

    public static ArchitectSession getOrCreate(ServerPlayerEntity player) {
        return SESSIONS.computeIfAbsent(player.getUuid(), k -> new ArchitectSession());
    }

    public static boolean isInterceptingScroll(ServerPlayerEntity player) {
        ArchitectSession s = SESSIONS.get(player.getUuid());
        return s != null && (s.anchorPos != null || s.carryingStructure);
    }

    public static void cycleAxisOrMode(ServerPlayerEntity player) {
        ArchitectSession s = getOrCreate(player);
        if (s.carryingStructure) {
            placeCarriedStructure(player, s);
        }
        s.anchorPos = null;
        s.mode = (s.mode + 1) % 4;
        String modeDesc = switch (s.mode) {
            case 0 -> "Suelo Rápido (Plano Horizontal XZ)";
            case 1 -> "Pared Rápida (Plano Vertical - Ruedita ajusta altura)";
            case 2 -> "Volumen / Cubo 3D Libre (Ruedita ajusta pisos de altura)";
            default -> "Telequinesis de Estructuras (Seleccionar libremente y Mover con Peso)";
        };
        player.sendMessage(Text.literal("Modo Arquitecto: ").formatted(Formatting.GRAY)
                .append(Text.literal(modeDesc).formatted(Formatting.AQUA)), true);
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.3f);
    }

    public static void sampleBlockFromWorld(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(40.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player
        ));

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockState st = world.getBlockState(hit.getBlockPos());
            if (!st.isAir() && !st.isOf(Blocks.BEDROCK)) {
                ArchitectSession s = getOrCreate(player);
                s.selectedBlock = st;

                Vec3d handStart = PortalManager.computeServerMuzzlePos(player);
                PortalManager.getActiveShotBeams().add(
                        new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), handStart, Vec3d.ofCenter(hit.getBlockPos()), 2, 8)
                );

                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.8f, 1.6f);
                player.sendMessage(Text.literal("Material escaneado: ").formatted(Formatting.GRAY)
                        .append(st.getBlock().getName().copy().formatted(Formatting.GREEN)), true);
            }
        }
    }

    public static void adjustDimensionOrDistance(ServerPlayerEntity player, int scrollDelta) {
        ArchitectSession s = getOrCreate(player);
        if (s.carryingStructure) {
            s.holdDistance = Math.max(2.5, Math.min(18.0, s.holdDistance + scrollDelta * 0.85));
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.PLAYERS, 0.35f, scrollDelta > 0 ? 1.5f : 1.1f);
        } else if (s.anchorPos != null) {
            s.extraHeight = Math.max(-12, Math.min(18, s.extraHeight + scrollDelta));
            if (s.extraHeight == 0) s.extraHeight = scrollDelta > 0 ? 1 : -1;
            player.sendMessage(Text.literal("Altura / Capas verticales: " + Math.abs(s.extraHeight) + " bloques").formatted(Formatting.AQUA), true);
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_AMETHYST_CLUSTER_STEP, SoundCategory.PLAYERS, 0.5f, 1.2f);
        }
    }

    public static void handleLeftClickCancelOrLaunch(ServerPlayerEntity player) {
        ArchitectSession s = getOrCreate(player);
        if (s.carryingStructure) {
            ServerWorld world = player.getServerWorld();
            Vec3d look = player.getRotationVec(1.0F);
            Vec3d launchVel = look.multiply(2.4).add(0, 0.2, 0);
            for (MovingBlockPiece piece : s.carriedPieces) {
                Entity disp = world.getEntity(piece.displayUuid);
                Vec3d pPos = (disp != null) ? disp.getPos() : s.structureCenter.add(Vec3d.of(piece.relOffset));
                if (disp != null) disp.discard();
                GravityGunManager.spawnPhysicalFallingBlock(world, pPos, launchVel, piece.state, 10.0f);
            }
            s.carriedPieces.clear();
            s.carryingStructure = false;
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.7f, 1.4f);
            return;
        }
        if (s.anchorPos != null) {
            s.anchorPos = null;
            s.currentEndPos = null;
            player.sendMessage(Text.literal("Holograma de construcción cancelado").formatted(Formatting.YELLOW), true);
            player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.6f, 1.5f);
        }
    }

    public static void handleRightClickBuildOrMove(ServerPlayerEntity player) {
        ArchitectSession s = getOrCreate(player);
        ServerWorld world = player.getServerWorld();

        ItemStack offhand = player.getOffHandStack();
        if (offhand.getItem() instanceof BlockItem blockItem) {
            Block b = blockItem.getBlock();
            if (b != Blocks.AIR && b != Blocks.BEDROCK) {
                s.selectedBlock = b.getDefaultState();
            }
        }

        if (s.carryingStructure) {
            placeCarriedStructure(player, s);
            return;
        }

        BlockPos targetBlock = raycastTargetBlock(player, s, s.mode == 3);
        if (targetBlock == null) return;

        Vec3d handStart = PortalManager.computeServerMuzzlePos(player);
        PortalManager.getActiveShotBeams().add(
                new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), handStart, Vec3d.ofCenter(targetBlock), 2, 7)
        );

        if (s.anchorPos == null) {
            s.anchorPos = targetBlock;
            if (s.mode == 3) {
                s.extraHeight = 2; // Selección libre inicial
            }
            s.currentEndPos = computeEndPosForMode(player, s, targetBlock);
            world.playSound(null, targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(),
                    SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.75f, 1.75f);
            player.sendMessage(Text.literal("Esquina 1 fijada. Mové la mira (y ruedita para altura) y hacé Clic Derecho para confirmar.").formatted(Formatting.GREEN), true);
            return;
        }

        BlockPos endPos = computeEndPosForMode(player, s, targetBlock);
        List<BlockPos> boxPositions = getBoxPositions(s.anchorPos, endPos, 512);

        if (s.mode == 3) {
            liftStructure(player, s, boxPositions);
            s.anchorPos = null;
            s.currentEndPos = null;
        } else {
            int placed = 0;
            for (BlockPos bp : boxPositions) {
                BlockState existing = world.getBlockState(bp);
                if (existing.isAir() || existing.isReplaceable()) {
                    world.setBlockState(bp, s.selectedBlock, 3);
                    placed++;
                }
            }
            PortalManager.getActiveShotBeams().add(
                    new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), handStart, Vec3d.ofCenter(s.anchorPos), 2, 10)
            );
            PortalManager.getActiveShotBeams().add(
                    new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), handStart, Vec3d.ofCenter(endPos), 2, 10)
            );

            world.playSound(null, endPos.getX(), endPos.getY(), endPos.getZ(),
                    SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 0.9f, 1.25f);
            player.sendMessage(Text.literal("¡Construcción materializada (" + placed + " bloques)!").formatted(Formatting.AQUA), true);
            s.anchorPos = null;
            s.currentEndPos = null;
        }
    }

    private static void liftStructure(ServerPlayerEntity player, ArchitectSession s, List<BlockPos> positions) {
        ServerWorld world = player.getServerWorld();
        List<BlockPos> solidPositions = new ArrayList<>();
        double sumX = 0, sumY = 0, sumZ = 0;

        for (BlockPos bp : positions) {
            if (solidPositions.size() >= 256) break;
            BlockState st = world.getBlockState(bp);
            if (GravityGunManager.canLiftBlock(world, bp, st)) {
                solidPositions.add(bp);
                sumX += bp.getX() + 0.5;
                sumY += bp.getY() + 0.5;
                sumZ += bp.getZ() + 0.5;
            }
        }

        if (solidPositions.isEmpty()) {
            player.sendMessage(Text.literal("No hay bloques movibles en esa selección").formatted(Formatting.RED), true);
            return;
        }

        Vec3d center = new Vec3d(sumX / solidPositions.size(), sumY / solidPositions.size(), sumZ / solidPositions.size());
        BlockPos centerBlock = BlockPos.ofFloored(center.x, center.y, center.z);

        // Primero borrar todos los bloques de la estructura del mundo para que no colisionen consigo mismos ni con el pozo al salir
        List<BlockState> savedStates = new ArrayList<>(solidPositions.size());
        for (BlockPos bp : solidPositions) {
            savedStates.add(world.getBlockState(bp));
            world.setBlockState(bp, Blocks.AIR.getDefaultState(), 3);
        }

        s.carriedPieces.clear();
        // Elevar ligeramente el centro inicial (+0.6 en Y) para despegue limpio incluso si estaba rodeado de bloques
        Vec3d liftedCenter = center.add(0, 0.6, 0);
        for (int i = 0; i < solidPositions.size(); i++) {
            BlockPos bp = solidPositions.get(i);
            BlockState st = savedStates.get(i);
            BlockPos rel = bp.subtract(centerBlock);
            Entity disp = GravityGunManager.createRotatingBlockDisplay(world, liftedCenter.add(rel.getX(), rel.getY(), rel.getZ()), st);
            if (disp != null) {
                s.carriedPieces.add(new MovingBlockPiece(rel, st, disp.getUuid()));
            }
        }

        s.structureCenter = liftedCenter;
        s.structureVelocity = new Vec3d(0, 0.25, 0);
        s.holdDistance = Math.max(3.5, Math.min(14.0, player.getEyePos().distanceTo(liftedCenter)));
        s.carryingStructure = true;
        s.ageTicks = 0;

        world.playSound(null, center.x, center.y, center.z,
                SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.9f, 1.5f);
        player.sendMessage(Text.literal("¡Estructura levantada (" + s.carriedPieces.size() + " bloques)! Ruedita acerca/aleja, Clic Derecho emplaza.").formatted(Formatting.GREEN), true);
    }

    private static void placeCarriedStructure(ServerPlayerEntity player, ArchitectSession s) {
        ServerWorld world = player.getServerWorld();
        BlockPos targetCenter = BlockPos.ofFloored(s.structureCenter.x, s.structureCenter.y, s.structureCenter.z);
        Vec3d handStart = PortalManager.computeServerMuzzlePos(player);

        for (MovingBlockPiece piece : s.carriedPieces) {
            Entity disp = world.getEntity(piece.displayUuid);
            if (disp != null) disp.discard();
            BlockPos dest = targetCenter.add(piece.relOffset);
            if (world.getBlockState(dest).isAir() || world.getBlockState(dest).isReplaceable()) {
                world.setBlockState(dest, piece.state, 3);
            } else {
                Block.dropStacks(piece.state, world, dest);
            }
        }

        PortalManager.getActiveShotBeams().add(
                new PortalManager.ShotBeamFx(world.getRegistryKey(), player.getId(), handStart, s.structureCenter, 2, 10)
        );
        world.playSound(null, s.structureCenter.x, s.structureCenter.y, s.structureCenter.z,
                SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 0.7f, 1.4f);

        s.carriedPieces.clear();
        s.carryingStructure = false;
        player.sendMessage(Text.literal("¡Estructura fusionada en su nueva posición!").formatted(Formatting.AQUA), true);
    }

    public static void cancelOrDrop(ServerPlayerEntity player) {
        ArchitectSession s = SESSIONS.remove(player.getUuid());
        if (s != null && s.carryingStructure) {
            placeCarriedStructure(player, s);
        }
    }

    private static BlockPos raycastTargetBlock(ServerPlayerEntity player, ArchitectSession s, boolean exactBlockHit) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(36.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player
        ));

        if (hit.getType() == HitResult.Type.BLOCK) {
            if (exactBlockHit) {
                return hit.getBlockPos();
            }
            BlockState hitState = world.getBlockState(hit.getBlockPos());
            if (hitState.isReplaceable()) {
                return hit.getBlockPos();
            }
            return hit.getBlockPos().offset(hit.getSide());
        }
        // Si apunta al aire mientras estira una selección, proyectar a la distancia del ancla para que pueda estirar libremente
        double fallbackDist = (s.anchorPos != null)
                ? Math.max(5.0, Math.min(28.0, eye.distanceTo(Vec3d.ofCenter(s.anchorPos)) + 2.0))
                : 8.0;
        return BlockPos.ofFloored(eye.add(look.multiply(fallbackDist)));
    }

    private static BlockPos computeEndPosForMode(ServerPlayerEntity player, ArchitectSession s, BlockPos rawTarget) {
        if (s.anchorPos == null) return rawTarget;
        int hOffset = (s.extraHeight >= 0) ? (s.extraHeight - 1) : (s.extraHeight + 1);
        return switch (s.mode) {
            case 0 -> // Suelo Horizontal XZ
                    new BlockPos(rawTarget.getX(), s.anchorPos.getY(), rawTarget.getZ());
            case 1 -> { // Pared Vertical
                int dx = Math.abs(rawTarget.getX() - s.anchorPos.getX());
                int dz = Math.abs(rawTarget.getZ() - s.anchorPos.getZ());
                int topY = s.anchorPos.getY() + hOffset;
                if (dx >= dz) {
                    yield new BlockPos(rawTarget.getX(), topY, s.anchorPos.getZ());
                } else {
                    yield new BlockPos(s.anchorPos.getX(), topY, rawTarget.getZ());
                }
            }
            case 2 -> // Cubo / Volumen 3D Libre
                    new BlockPos(rawTarget.getX(), Math.max(rawTarget.getY(), s.anchorPos.getY() + hOffset), rawTarget.getZ());
            default -> { // Modo 3 (Selección libre 3D de estructuras: incluye tanto la altura mirada como el ajuste de ruedita)
                int minY = Math.min(s.anchorPos.getY(), rawTarget.getY());
                int maxY = Math.max(s.anchorPos.getY(), rawTarget.getY()) + Math.max(0, hOffset);
                yield new BlockPos(rawTarget.getX(), (rawTarget.getY() >= s.anchorPos.getY() ? maxY : minY - Math.max(0, hOffset)), rawTarget.getZ());
            }
        };
    }

    public static List<BlockPos> getBoxPositions(BlockPos a, BlockPos b, int maxBlocks) {
        List<BlockPos> result = new ArrayList<>();
        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    result.add(new BlockPos(x, y, z));
                    if (result.size() >= maxBlocks) return result;
                }
            }
        }
        return result;
    }

    private static boolean hasArchitectGunInHotbar(ServerPlayerEntity player) {
        if (player.getMainHandStack().isOf(PortalGravityMod.ARCHITECT_GUN)
                || player.getOffHandStack().isOf(PortalGravityMod.ARCHITECT_GUN)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(PortalGravityMod.ARCHITECT_GUN)) return true;
        }
        return false;
    }

    public static void tick(MinecraftServer server) {
        for (Map.Entry<UUID, ArchitectSession> entry : SESSIONS.entrySet()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null || !player.isAlive()) continue;

            ArchitectSession s = entry.getValue();
            if (!hasArchitectGunInHotbar(player)) {
                if (s.carryingStructure) {
                    placeCarriedStructure(player, s);
                }
                s.anchorPos = null;
                s.currentEndPos = null;
                continue;
            }

            if (s.anchorPos != null && !s.carryingStructure) {
                BlockPos lookBlock = raycastTargetBlock(player, s, s.mode == 3);
                if (lookBlock != null) {
                    s.currentEndPos = computeEndPosForMode(player, s, lookBlock);
                }
            }

            if (s.carryingStructure) {
                ServerWorld world = player.getServerWorld();
                s.ageTicks++;
                Vec3d eye = player.getEyePos();
                Vec3d look = player.getRotationVec(1.0F);
                Vec3d rawTarget = eye.add(look.multiply(s.holdDistance));

                BlockHitResult obstacleHit = world.raycast(new RaycastContext(
                        eye, rawTarget, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player
                ));
                if (obstacleHit.getType() == HitResult.Type.BLOCK) {
                    rawTarget = obstacleHit.getPos().add(Vec3d.of(obstacleHit.getSide().getVector()).multiply(1.1));
                }

                double bobY = Math.sin(s.ageTicks * 0.1) * 0.12;
                Vec3d desiredTarget = rawTarget.add(0, bobY, 0);
                Vec3d delta = desiredTarget.subtract(s.structureCenter);

                Vec3d desiredStep = s.structureVelocity.multiply(0.45).add(delta.multiply(0.38));
                boolean[] col = new boolean[3];
                // Durante los primeros 15 ticks tras levantar la estructura, desactivar colisión para que salga libremente de cualquier pozo/pared
                Vec3d actualStep = (s.ageTicks < 15)
                        ? desiredStep
                        : GravityGunManager.resolveBoxCollision(world, s.structureCenter, desiredStep, 0.65, 0.65, col);

                s.structureCenter = s.structureCenter.add(actualStep);
                s.structureVelocity = actualStep;

                s.rotX = (float) (actualStep.z * 20.0 + Math.sin(s.ageTicks * 0.08) * 3.0);
                s.rotZ = (float) (-actualStep.x * 20.0 + Math.cos(s.ageTicks * 0.08) * 3.0);

                for (MovingBlockPiece piece : s.carriedPieces) {
                    Entity disp = world.getEntity(piece.displayUuid);
                    if (disp != null && disp.isAlive()) {
                        Vec3d piecePos = s.structureCenter.add(
                                piece.relOffset.getX(),
                                piece.relOffset.getY(),
                                piece.relOffset.getZ()
                        );
                        disp.setPosition(piecePos.x, piecePos.y, piecePos.z);
                        NbtCompound nbt = new NbtCompound();
                        disp.writeNbt(nbt);
                        GravityGunManager.updateDisplayRotationNbt(nbt, s.rotX, 0f, s.rotZ);
                        disp.readNbt(nbt);
                    }
                }
            }
        }
    }
}
