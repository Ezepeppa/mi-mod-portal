package com.argon.portalgravity;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

public class PortalGravityClient implements ClientModInitializer {
    private int leftClickCooldown = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (leftClickCooldown > 0) {
                leftClickCooldown--;
            }
            if (client.player == null || client.world == null || client.currentScreen != null) {
                return;
            }
            ItemStack stack = client.player.getMainHandStack();
            boolean holdingModGun = stack.isOf(PortalGravityMod.PORTAL_GUN) || stack.isOf(PortalGravityMod.GRAVITY_GUN);
            if (!holdingModGun) {
                return;
            }
            while (client.options.attackKey.wasPressed()) {
                if (leftClickCooldown <= 0) {
                    ClientPlayNetworking.send(PortalGravityMod.LEFT_CLICK_ACTION_PACKET, PacketByteBufs.empty());
                    client.player.swingHand(Hand.MAIN_HAND);
                    leftClickCooldown = 5;
                }
            }
        });
    }
}
