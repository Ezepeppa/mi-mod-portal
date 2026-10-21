package com.argon.octopus;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class OctopusShowcaseManager {
    public static void startOrAdvanceShowcase(ServerPlayerEntity player, int requestedMode) {
        ServerWorld world = player.getServerWorld();
        BlockPos origin = player.getBlockPos().add(player.getHorizontalFacing().getOffsetX() * 6, 0, player.getHorizontalFacing().getOffsetZ() * 6);

        buildBiomechanicalTestCourse(world, origin);

        GiantOctopusEntity octopus = world.getEntitiesByType(
                OctopusMod.GIANT_OCTOPUS,
                player.getBoundingBox().expand(32.0),
                e -> true
        ).stream().findFirst().orElseGet(() -> {
            GiantOctopusEntity created = new GiantOctopusEntity(OctopusMod.GIANT_OCTOPUS, world);
            created.refreshPositionAndAngles(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5, 0.0f, 0.0f);
            world.spawnEntity(created);
            return created;
        });

        octopus.setOwnerUuid(player.getUuid());
        octopus.setSittingStay(false);
        octopus.refreshPositionAndAngles(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5, 0.0f, 0.0f);
        octopus.showcaseStage = 1;
        octopus.showcaseTargetPos = new Vec3d(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 6.5);

        world.playSound(null, origin, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 0.9f, 1.2f);
        player.sendMessage(Text.literal("=== SHOWCASE BIOMECÁNICO v2.0.0 INICIADO ===")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);
        player.sendMessage(Text.literal("[Etapa 1/4] ")
                .formatted(Formatting.AQUA)
                .append(Text.literal("Arrastrado por Sucesión Asíncrona: observá cómo primero se deslizan los tentáculos a ras del suelo y recién cuando clavan sus ventosas tiran de la pesada masa del cuerpo.")
                        .formatted(Formatting.WHITE)), false);
    }

    private static void buildBiomechanicalTestCourse(ServerWorld world, BlockPos origin) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 14; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.SMOOTH_STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        world.setBlockState(origin.add(-1, 0, 2), Blocks.STONE_BRICK_SLAB.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(0, 0, 3), Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(1, 0, 4), Blocks.STONE_BRICK_SLAB.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(-1, 0, 5), Blocks.MOSSY_COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = 8; dz <= 11; dz++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.CHISELED_STONE_BRICKS.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        world.setBlockState(origin.add(0, 4, 10), Blocks.GOLD_BLOCK.getDefaultState(), Block.NOTIFY_ALL);

        ArmorStandEntity dummy = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
        dummy.refreshPositionAndAngles(origin.getX() + 0.5, origin.getY(), origin.getZ() + 13.5, 180.0f, 0.0f);
        dummy.setCustomName(Text.literal("Objetivo Biomecánico").formatted(Formatting.YELLOW));
        dummy.setCustomNameVisible(true);
        world.spawnEntity(dummy);
    }

    public static void tickShowcaseOctopus(GiantOctopusEntity octopus) {
        if (!(octopus.getWorld() instanceof ServerWorld world) || octopus.showcaseTargetPos == null) return;

        double distSq = octopus.getPos().squaredDistanceTo(octopus.showcaseTargetPos);

        if (octopus.showcaseStage == 1 && distSq < 2.2) {
            octopus.showcaseStage = 2;
            octopus.showcaseTargetPos = octopus.showcaseTargetPos.add(0, 4.0, 3.5);
            broadcastShowcaseMessage(world, octopus, "[Etapa 2/4] ",
                    "Escalada e Inclinación 3D: el cuerpo se inclina dinámicamente en Pitch y Roll mientras los tentáculos frontales adhieren sus ventosas al muro vertical.");
        } else if (octopus.showcaseStage == 2 && (octopus.getY() >= octopus.showcaseTargetPos.y - 0.5 || octopus.age % 120 == 0)) {
            octopus.showcaseStage = 3;
            BlockPos goldPos = BlockPos.ofFloored(octopus.getPos()).add(0, 0, 1);
            octopus.setHeldBlockState(Blocks.GOLD_BLOCK.getDefaultState());
            if (world.getBlockState(goldPos).isOf(Blocks.GOLD_BLOCK)) {
                world.setBlockState(goldPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            octopus.showcaseTargetPos = octopus.showcaseTargetPos.add(0, 0, 1.5);
            world.playSound(null, octopus.getBlockPos(), SoundEvents.BLOCK_METAL_BREAK, SoundCategory.BLOCKS, 1.0f, 0.8f);
            broadcastShowcaseMessage(world, octopus, "[Etapa 3/4] ",
                    "Agarre con Inercia de Péndulo: el Tentáculo #1 sostiene el bloque con peso real mientras los otros 7 tentáculos sostienen y envuelven la torre.");
        } else if (octopus.showcaseStage == 3 && octopus.age % 90 == 0) {
            octopus.showcaseStage = 4;
            if (!octopus.getTentacles().isEmpty()) {
                TentacleLimb limb0 = octopus.getTentacles().get(0);
                limb0.state = TentacleLimb.LimbState.THROW_WINDUP;
                limb0.actionProgress = 0.0f;
                limb0.throwDirection = new Vec3d(0, 0.1, 1).normalize();
            }
            broadcastShowcaseMessage(world, octopus, "[Etapa 4/4] ",
                    "Lanzamiento con Wind-Up y Proyectil Físico: el tentáculo carga hacia atrás y arroja el bloque por el aire. ¡Al terminar te seguirá fielmente como compañero!");
            octopus.showcaseStage = 0;
            octopus.showcaseTargetPos = null;
        }
    }

    private static void broadcastShowcaseMessage(ServerWorld world, GiantOctopusEntity octopus, String prefix, String body) {
        world.getPlayers(p -> p.squaredDistanceTo(octopus) < 1600.0).forEach(p ->
                p.sendMessage(Text.literal(prefix).formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(Text.literal(body).formatted(Formatting.WHITE)), false)
        );
    }
}
