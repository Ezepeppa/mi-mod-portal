package com.argon.subgrid;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SubGridMod implements ModInitializer {
    public static final String MOD_ID = "subgridmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier SET_GRID_STEP_PACKET = new Identifier(MOD_ID, "set_grid_step");

    public static final Block SUBGRID_CONTAINER = new SubGridBlock(
            AbstractBlock.Settings.create()
                    .solid()
                    .strength(1.5f, 6.0f)
                    .nonOpaque()
                    .dynamicBounds()
                    .pistonBehavior(PistonBehavior.BLOCK)
                    .sounds(BlockSoundGroup.STONE)
                    .luminance(state -> state.get(SubGridBlock.LIGHT_LEVEL))
    );

    public static final Block SUBGRID_ACTUATOR = new SubGridActuatorBlock(
            AbstractBlock.Settings.create()
                    .solid()
                    .strength(2.0f, 6.0f)
                    .sounds(BlockSoundGroup.METAL)
    );

    public static final Item SUBGRID_ACTUATOR_ITEM = new BlockItem(SUBGRID_ACTUATOR, new Item.Settings().rarity(Rarity.RARE));
    public static final Item PRECISION_CHISEL = new PrecisionChiselItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC));

    public static BlockEntityType<SubGridBlockEntity> SUBGRID_BLOCK_ENTITY;

    private static final Map<UUID, Integer> PLAYER_GRID_STEP = new HashMap<>();

    public static int getPlayerGridStep(UUID playerUuid) {
        return PLAYER_GRID_STEP.getOrDefault(playerUuid, 16);
    }

    public static void setPlayerGridStep(UUID playerUuid, int step) {
        PLAYER_GRID_STEP.put(playerUuid, step);
    }

    @Override
    public void onInitialize() {
        Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "subgrid_container"), SUBGRID_CONTAINER);
        Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "subgrid_actuator"), SUBGRID_ACTUATOR);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "subgrid_actuator"), SUBGRID_ACTUATOR_ITEM);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "precision_chisel"), PRECISION_CHISEL);

        SUBGRID_BLOCK_ENTITY = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                new Identifier(MOD_ID, "subgrid_container_be"),
                FabricBlockEntityTypeBuilder.create(SubGridBlockEntity::new, SUBGRID_CONTAINER).build()
        );

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(PRECISION_CHISEL);
            entries.add(SUBGRID_ACTUATOR_ITEM);
        });

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> {
            entries.add(SUBGRID_ACTUATOR_ITEM);
            entries.add(PRECISION_CHISEL);
        });

        ServerPlayNetworking.registerGlobalReceiver(SET_GRID_STEP_PACKET, (server, player, handler, buf, responseSender) -> {
            int newStep = buf.readInt();
            server.execute(() -> SubGridPlacementHandler.updatePlayerGridOnServer(player, newStep));
        });

        UseBlockCallback.EVENT.register(SubGridPlacementHandler::onUseBlock);

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (state.isOf(SUBGRID_CONTAINER) && blockEntity instanceof SubGridBlockEntity sgbe) {
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    return SubGridPlacementHandler.tryBreakTargetedPart(world, serverPlayer, pos, sgbe);
                }
            }
            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(SubGridPlacementHandler::tickServer);

        LOGGER.info("Mod Sub-Grid & Off-Grid Micro-Architecture v4.0.0 cargado correctamente.");
    }
}
