package com.argon.octopus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import org.lwjgl.glfw.GLFW;

public class OctopusClient implements ClientModInitializer {
    private static KeyBinding openConfigKey;
    private static KeyBinding tentacleGrabKey;
    private static KeyBinding tentacleWhipKey;
    private static boolean wasSpacePressedRaw = false;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(OctopusMod.GIANT_OCTOPUS, GiantOctopusRenderer::new);

        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.octopusmod.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.octopusmod.main"
        ));

        tentacleGrabKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.octopusmod.tentacle_grab",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.octopusmod.main"
        ));

        tentacleWhipKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.octopusmod.tentacle_whip",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.octopusmod.main"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (openConfigKey.wasPressed()) {
                openConfigScreenOnClient();
            }

            while (tentacleGrabKey.wasPressed()) {
                sendTentacleAction(1);
            }

            while (tentacleWhipKey.wasPressed()) {
                sendTentacleAction(2);
            }

            // Lectura directa por hardware GLFW de la Barra Espaciadora para que funcione siempre aunque estés montado!
            if (client.currentScreen == null && client.player.getVehicle() instanceof GiantOctopusEntity) {
                long handle = client.getWindow().getHandle();
                boolean spaceDown = InputUtil.isKeyPressed(handle, GLFW.GLFW_KEY_SPACE);
                if (spaceDown && !wasSpacePressedRaw) {
                    sendTentacleAction(3);
                }
                wasSpacePressedRaw = spaceDown;
            } else {
                wasSpacePressedRaw = false;
            }
        });
    }

    public static void openConfigScreenOnClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new OctopusConfigScreen());
    }

    private void sendTentacleAction(int actionType) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(actionType);
        ClientPlayNetworking.send(OctopusMod.TENTACLE_ACTION_PACKET, buf);
    }
}
