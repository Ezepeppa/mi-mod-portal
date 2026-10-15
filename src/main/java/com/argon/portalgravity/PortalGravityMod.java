package com.argon.portalgravity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PortalGravityMod implements ModInitializer {
    public static final String MOD_ID = "portalgravity";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier LEFT_CLICK_ACTION_PACKET = new Identifier(MOD_ID, "left_click_action");
    public static final Identifier SYNC_FX_PACKET = new Identifier(MOD_ID, "sync_fx");

    public static final Item PORTAL_GUN = new PortalGunItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
    public static final Item GRAVITY_GUN = new GravityGunItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
    public static final Item SINGULARITY_ORB = new SingularityOrbItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());

    private static final Map<UUID, Long> LAST_LEFT_CLICK_TICK = new HashMap<>();

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "portal_gun"), PORTAL_GUN);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "gravity_gun"), GRAVITY_GUN);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "singularity_orb"), SINGULARITY_ORB);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(PORTAL_GUN);
            entries.add(GRAVITY_GUN);
            entries.add(SINGULARITY_ORB);
        });

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            entries.add(PORTAL_GUN);
            entries.add(GRAVITY_GUN);
            entries.add(SINGULARITY_ORB);
        });

        // Evitar rotura de bloques con clic izquierdo y disparar también si golpea un bloque cercano
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(PORTAL_GUN) || stack.isOf(GRAVITY_GUN) || stack.isOf(SINGULARITY_ORB)) {
                if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                    handleLeftClickServer(serverPlayer);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(PORTAL_GUN) || stack.isOf(GRAVITY_GUN) || stack.isOf(SINGULARITY_ORB)) {
                if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                    handleLeftClickServer(serverPlayer);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Paquete de Clic Izquierdo enviado desde START_CLIENT_TICK
        ServerPlayNetworking.registerGlobalReceiver(LEFT_CLICK_ACTION_PACKET, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> handleLeftClickServer(player));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GravityGunManager.tick(server);
            PortalManager.tickPortals(server);
            SingularityManager.tick(server);
            FxSyncManager.syncToAllPlayers(server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            GravityGunManager.releaseAll(handler.player, false);
        });

        LOGGER.info("Mod Portal Gun, Gravity Gun & Singularity v2.0.0 cargado.");
    }

    public static void handleLeftClickServer(ServerPlayerEntity player) {
        long now = player.getServerWorld().getTime();
        Long last = LAST_LEFT_CLICK_TICK.get(player.getUuid());
        if (last != null && (now - last) < 4) {
            return;
        }
        LAST_LEFT_CLICK_TICK.put(player.getUuid(), now);

        ItemStack mainStack = player.getMainHandStack();
        if (mainStack.isOf(PORTAL_GUN)) {
            if (player.isSneaking()) {
                PortalManager.cyclePortalMode(player);
            } else {
                PortalManager.shootPortal(player, false); // Portal Azul
            }
        } else if (mainStack.isOf(GRAVITY_GUN)) {
            GravityGunManager.launchHeldObjects(player);
        } else if (mainStack.isOf(SINGULARITY_ORB)) {
            SingularityManager.quantumSwap(player);
        }
    }
}
