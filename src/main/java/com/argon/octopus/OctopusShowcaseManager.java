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

/**
 * Construye automáticamente un Circuito de Demostración Biomecánica frente al jugador y guía paso a paso
 * un Showcase en vivo donde el Pulpo Terrestre demuestra todas las capacidades de sus tentáculos:
 * - Etapa 1: Arrastrado sobre terreno irregular y escalones (los nodos medios se amoldan a los bordes sin atravesar bloques).
 * - Etapa 2: Escalada vertical de una pared de 90° de 5 bloques de alto usando adhesión secuencial de ventosas.
 * - Etapa 3: Agarre con Tentáculo (el tentáculo #0 arranca un bloque de oro del pedestal en espiral y lo sostiene mientras camina).
 * - Etapa 4: Lanzamiento balístico del bloque contra el objetivo de prueba y Latigazo Muscular.
 */
public class OctopusShowcaseManager {
    public static void startOrAdvanceShowcase(ServerPlayerEntity player, int requestedMode) {
        ServerWorld world = player.getServerWorld();
        BlockPos origin = player.getBlockPos().add(player.getHorizontalFacing().getOffsetX() * 6, 0, player.getHorizontalFacing().getOffsetZ() * 6);

        // 1. Construir la Arena de Pruebas Biomecánica (Escalones irregulares + Muro de escalada + Pedestal de agarre)
        buildBiomechanicalTestCourse(world, origin);

        // 2. Encontrar o invocar al Pulpo Gigante en el inicio del circuito
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

        octopus.refreshPositionAndAngles(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5, 0.0f, 0.0f);
        octopus.showcaseStage = 1;
        octopus.showcaseTargetPos = new Vec3d(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 6.5);

        world.playSound(null, origin, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.MASTER, 0.9f, 1.2f);
        player.sendMessage(Text.literal("=== SHOWCASE BIOMECÁNICO INICIADO ===")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);
        player.sendMessage(Text.literal("[Etapa 1/4] ")
                .formatted(Formatting.AQUA)
                .append(Text.literal("Locomoción por Tracción/Empuje sobre terreno irregular: observá cómo cada tentáculo apoya sus nodos sobre los desniveles y desprende sus ventosas de la base a la punta.")
                        .formatted(Formatting.WHITE)), false);
    }

    private static void buildBiomechanicalTestCourse(ServerWorld world, BlockPos origin) {
        // Plataforma base de piedra pulida (5x16)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -1; dz <= 14; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.SMOOTH_STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        // Obstáculos irregulares en Z = 2..5 para demostrar cómo los 14 nodos del tentáculo envuelven los bordes sin clippear
        world.setBlockState(origin.add(-1, 0, 2), Blocks.STONE_BRICK_SLAB.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(0, 0, 3), Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(1, 0, 4), Blocks.STONE_BRICK_SLAB.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(-1, 0, 5), Blocks.MOSSY_COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);

        // Muro vertical de escalada en Z = 8 (4 bloques de alto con terraza superior en Z = 8..11)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = 8; dz <= 11; dz++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.CHISELED_STONE_BRICKS.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        // Bloque de Oro en pedestal arriba del muro (Z = 10, Y = 4) para que el tentáculo lo arranque y lo sostenga
        world.setBlockState(origin.add(0, 4, 10), Blocks.GOLD_BLOCK.getDefaultState(), Block.NOTIFY_ALL);

        // Objetivo de prueba al final (Z = 13)
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
                    "Escalada Vertical por Ventosas: los tentáculos frontales buscan la pared vertical con Raycasting 3D, adhieren sus ventosas y traccionan la masa del cuerpo hacia arriba.");
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
                    "Manipulación e Hidrostato en Espiral: el Tentáculo #1 envuelve y sostiene el Bloque de Oro en el aire mientras los otros 7 tentáculos se encargan de sostener y mover al pulpo.");
        } else if (octopus.showcaseStage == 3 && octopus.age % 80 == 0) {
            octopus.showcaseStage = 4;
            octopus.setHeldBlockState(Blocks.AIR.getDefaultState());
            for (TentacleLimb limb : octopus.getTentacles()) {
                limb.state = TentacleLimb.LimbState.WHIP_STRIKE;
                limb.whipTimer = 0.0f;
                limb.whipTarget = octopus.getPos().add(0, 0.5, 4.5);
            }
            world.playSound(null, octopus.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.6f);
            broadcastShowcaseMessage(world, octopus, "[Etapa 4/4] ",
                    "Latigazo Muscular y Lanzamiento: onda supersónica de desenrollado en los tentáculos frontales. ¡Hacé clic derecho sobre el pulpo para montarlo o presioná 'O' para abrir los sliders de física en vivo!");
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
