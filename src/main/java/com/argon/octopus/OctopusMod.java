package com.argon.octopus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OctopusMod implements ModInitializer {
    public static final String MOD_ID = "octopusmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Paquetes C2S para sincronizar el menú de configuración in-game, las acciones de los tentáculos y el Showcase
    public static final Identifier SYNC_CONFIG_PACKET = new Identifier(MOD_ID, "sync_config");
    public static final Identifier TENTACLE_ACTION_PACKET = new Identifier(MOD_ID, "tentacle_action");
    public static final Identifier START_SHOWCASE_PACKET = new Identifier(MOD_ID, "start_showcase");

    public static final EntityType<GiantOctopusEntity> GIANT_OCTOPUS = Registry.register(
            Registries.ENTITY_TYPE,
            new Identifier(MOD_ID, "giant_octopus"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, GiantOctopusEntity::new)
                    .dimensions(EntityDimensions.changing(1.9f, 1.35f))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(1)
                    .build()
    );

    public static final Item GIANT_OCTOPUS_SPAWN_EGG = new SpawnEggItem(
            GIANT_OCTOPUS, 0x98362B, 0xE8D2B9, new Item.Settings()
    );

    public static final Item OCTOPUS_CONTROLLER = new OctopusControllerItem(
            new Item.Settings().maxCount(1).rarity(Rarity.EPIC)
    );

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(GIANT_OCTOPUS, GiantOctopusEntity.createOctopusAttributes());

        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "giant_octopus_spawn_egg"), GIANT_OCTOPUS_SPAWN_EGG);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "octopus_controller"), OCTOPUS_CONTROLLER);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> {
            entries.add(GIANT_OCTOPUS_SPAWN_EGG);
            entries.add(OCTOPUS_CONTROLLER);
        });

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(OCTOPUS_CONTROLLER);
            entries.add(GIANT_OCTOPUS_SPAWN_EGG);
        });

        // Recibir cambios de sliders en tiempo real desde el menú interactivo del jugador
        ServerPlayNetworking.registerGlobalReceiver(SYNC_CONFIG_PACKET, (server, player, handler, buf, responseSender) -> {
            int count = buf.readInt();
            float length = buf.readFloat();
            float pull = buf.readFloat();
            float grav = buf.readFloat();
            float stiff = buf.readFloat();
            boolean climb = buf.readBoolean();
            boolean debug = buf.readBoolean();
            server.execute(() -> {
                OctopusPhysicsConfig.tentacleCount = count;
                OctopusPhysicsConfig.tentacleLength = length;
                OctopusPhysicsConfig.suctionPullStrength = pull;
                OctopusPhysicsConfig.gravitySlumpFactor = grav;
                OctopusPhysicsConfig.hydrostatStiffness = stiff;
                OctopusPhysicsConfig.wallClimbingEnabled = climb;
                OctopusPhysicsConfig.showDebugPhysicsVectors = debug;

                // Actualizar todos los pulpos cercanos al jugador en el servidor
                player.getServerWorld().getEntitiesByType(
                        GIANT_OCTOPUS,
                        player.getBoundingBox().expand(64.0),
                        e -> true
                ).forEach(oct -> oct.applyUpdatedConfig(count, length, pull, grav, stiff, climb));
            });
        });

        // Recibir acciones activas de tentáculos (1 = Agarrar/Lanzar bloque o mob, 2 = Latigazo frontal, 3 = Impulso elástico)
        ServerPlayNetworking.registerGlobalReceiver(TENTACLE_ACTION_PACKET, (server, player, handler, buf, responseSender) -> {
            int actionType = buf.readInt();
            server.execute(() -> {
                if (player.getVehicle() instanceof GiantOctopusEntity octopus) {
                    octopus.performRiderTentacleAction(player, actionType);
                } else {
                    // También permite comandar al pulpo más cercano si sostiene el controlador
                    player.getServerWorld().getEntitiesByType(
                            GIANT_OCTOPUS,
                            player.getBoundingBox().expand(18.0),
                            e -> true
                    ).stream().findFirst().ifPresent(oct -> oct.performRiderTentacleAction(player, actionType));
                }
            });
        });

        // Construir y lanzar el Circuito Showcase / Demo Guiado
        ServerPlayNetworking.registerGlobalReceiver(START_SHOWCASE_PACKET, (server, player, handler, buf, responseSender) -> {
            int stage = buf.readInt();
            server.execute(() -> OctopusShowcaseManager.startOrAdvanceShowcase(player, stage));
        });

        LOGGER.info("Mod Giant Terrestrial Octopus (Procedural Tentacle Physics) cargado correctamente.");
    }
}
