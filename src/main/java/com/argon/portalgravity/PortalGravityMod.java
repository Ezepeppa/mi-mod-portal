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
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortalGravityMod implements ModInitializer {
    public static final String MOD_ID = "portalgravity";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier LEFT_CLICK_ACTION_PACKET = new Identifier(MOD_ID, "left_click_action");

    public static final Item PORTAL_GUN = new PortalGunItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());
    public static final Item GRAVITY_GUN = new GravityGunItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof());

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "portal_gun"), PORTAL_GUN);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "gravity_gun"), GRAVITY_GUN);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(PORTAL_GUN);
            entries.add(GRAVITY_GUN);
        });

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            entries.add(PORTAL_GUN);
            entries.add(GRAVITY_GUN);
        });

        // Evitar que el clic izquierdo rompa bloques cuando se sostiene la Portal Gun o la Gravity Gun
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(PORTAL_GUN) || stack.isOf(GRAVITY_GUN)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Evitar golpear directamente con clic izquierdo si la Gravity Gun o Portal Gun están en uso
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.isOf(PORTAL_GUN) || stack.isOf(GRAVITY_GUN)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Recibir el clic izquierdo desde el cliente para disparar el Portal Azul o lanzar con la Gravity Gun
        ServerPlayNetworking.registerGlobalReceiver(LEFT_CLICK_ACTION_PACKET, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                ItemStack mainStack = player.getMainHandStack();
                if (mainStack.isOf(PORTAL_GUN)) {
                    PortalManager.shootPortal(player, false); // false = Portal Azul (Clic Izquierdo)
                } else if (mainStack.isOf(GRAVITY_GUN)) {
                    GravityGunManager.launchHeldObject(player);
                }
            });
        });

        // Actualizar físicas suaves de la Gravity Gun y los anillos de partículas de los portales en cada tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GravityGunManager.tick(server);
            PortalManager.tickPortals(server);
        });

        // Limpiar estado si el jugador se desconecta
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            GravityGunManager.releaseObject(handler.player, false);
        });

        LOGGER.info("Mod Portal Gun & Gravity Gun cargado correctamente.");
    }
}
