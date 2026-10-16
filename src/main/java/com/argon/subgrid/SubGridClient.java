package com.argon.subgrid;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class SubGridClient implements ClientModInitializer {
    private static KeyBinding shrinkGridKey;
    private static KeyBinding enlargeGridKey;

    @Override
    public void onInitializeClient() {
        BlockEntityRendererFactories.register(SubGridMod.SUBGRID_BLOCK_ENTITY, SubGridBlockEntityRenderer::new);

        // Las 2 teclas para subir y bajar el tamaño de la Grid (por defecto G y H)
        shrinkGridKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.subgridmod.grid_smaller",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.subgridmod.main"
        ));

        enlargeGridKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.subgridmod.grid_larger",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.subgridmod.main"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Subida automática y fluida de micro-escalones cuando caminás sobre bloques en Sub-Grid
            client.player.setStepHeight(1.05f);

            boolean shrinkPressed = false;
            while (shrinkGridKey.wasPressed()) {
                shrinkPressed = true;
            }
            boolean enlargePressed = false;
            while (enlargeGridKey.wasPressed()) {
                enlargePressed = true;
            }

            if (shrinkPressed && enlargePressed) {
                // Combinación secreta 1: presionar G y H al mismo tiempo activa Off-Grid
                sendGridStepUpdate(1);
            } else if (shrinkPressed) {
                if (client.player.isSneaking() && SubGridClientState.currentGridStep <= 2) {
                    // Combinación secreta 2: Shift + G estando en 1/8 desbloquea el modo secreto Off-Grid (1/16 libre)
                    sendGridStepUpdate(1);
                } else {
                    int next = switch (SubGridClientState.currentGridStep) {
                        case 16 -> 8;
                        case 8 -> 4;
                        case 4 -> 2;
                        case 2 -> (client.player.isSneaking() ? 1 : 2);
                        default -> 1;
                    };
                    sendGridStepUpdate(next);
                }
            } else if (enlargePressed) {
                int next = switch (SubGridClientState.currentGridStep) {
                    case 1 -> 2;
                    case 2 -> 4;
                    case 4 -> 8;
                    default -> 16;
                };
                sendGridStepUpdate(next);
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(this::renderSubtlePlacementPreview);
    }

    private void sendGridStepUpdate(int newStep) {
        SubGridClientState.currentGridStep = newStep;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(newStep);
        ClientPlayNetworking.send(SubGridMod.SET_GRID_STEP_PACKET, buf);
    }

    /**
     * Dibuja una previsualización arquitectónica 3D MUY sutil y no invasiva (sin partículas),
     * mostrando las aristas finas exactas de la posición del bloque en la Sub-Grid actual o en Off-Grid.
     */
    private void renderSubtlePlacementPreview(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.currentScreen != null) return;
        if (!(client.crosshairTarget instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        ItemStack stack = client.player.getMainHandStack();
        Hand activeHand = Hand.MAIN_HAND;
        if (!(stack.getItem() instanceof BlockItem)) {
            stack = client.player.getOffHandStack();
            activeHand = Hand.OFF_HAND;
            if (!(stack.getItem() instanceof BlockItem)) return;
        }

        BlockItem blockItem = (BlockItem) stack.getItem();
        if (blockItem.getBlock() == SubGridMod.SUBGRID_CONTAINER) return;

        int gridStep = SubGridClientState.currentGridStep;
        BlockState hitState = client.world.getBlockState(blockHit.getBlockPos());
        boolean combiningSlabOrStair = (hitState.getBlock() instanceof SlabBlock
                || hitState.getBlock() instanceof StairsBlock
                || hitState.isOf(SubGridMod.SUBGRID_CONTAINER))
                && (blockItem.getBlock() instanceof SlabBlock || blockItem.getBlock() instanceof StairsBlock);

        int effectiveStep = (gridStep == 16 && combiningSlabOrStair) ? 8 : gridStep;

        BlockState desiredState = SubGridPlacementHandler.getDesiredBlockState(
                client.player, client.world, activeHand, stack, blockItem, blockHit
        );
        if (desiredState == null) return;

        int[] globalOrigin16 = SubGridPlacementHandler.computeGlobalOrigin16(blockHit, desiredState, effectiveStep);
        boolean valid = SubGridPlacementHandler.canPlaceAtGlobal16(
                client.world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], desiredState, null
        );

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        Vec3d cam = context.camera().getPos();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLightning());

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        // Color muy sutil y elegante: blanco-hielo translúcido si es válido, ámbar-coral suave si hay solapamiento
        int r = valid ? (effectiveStep == 1 ? 215 : 210) : 255;
        int g = valid ? (effectiveStep == 1 ? 190 : 235) : 95;
        int b = valid ? 255 : 80;
        int alpha = valid ? 72 : 85;
        double lineThickness = 0.0055; // Línea arquitectónica finísima y nada invasiva

        List<int[]> localBoxes = SubGridPlacementHandler.getLocalBoxes16(desiredState);
        for (int[] lb : localBoxes) {
            Box worldBox = new Box(
                    (globalOrigin16[0] + lb[0]) / 16.0,
                    (globalOrigin16[1] + lb[1]) / 16.0,
                    (globalOrigin16[2] + lb[2]) / 16.0,
                    (globalOrigin16[0] + lb[3]) / 16.0,
                    (globalOrigin16[1] + lb[4]) / 16.0,
                    (globalOrigin16[2] + lb[5]) / 16.0
            ).expand(0.0015);
            drawSubtleWireframeBox(vc, mat, worldBox, lineThickness, r, g, b, alpha);
        }

        matrices.pop();
    }

    private void drawSubtleWireframeBox(VertexConsumer vc, Matrix4f mat, Box box, double thickness,
                                        int r, int g, int b, int a) {
        Vec3d p000 = new Vec3d(box.minX, box.minY, box.minZ);
        Vec3d p100 = new Vec3d(box.maxX, box.minY, box.minZ);
        Vec3d p101 = new Vec3d(box.maxX, box.minY, box.maxZ);
        Vec3d p001 = new Vec3d(box.minX, box.minY, box.maxZ);

        Vec3d p010 = new Vec3d(box.minX, box.maxY, box.minZ);
        Vec3d p110 = new Vec3d(box.maxX, box.maxY, box.minZ);
        Vec3d p111 = new Vec3d(box.maxX, box.maxY, box.maxZ);
        Vec3d p011 = new Vec3d(box.minX, box.maxY, box.maxZ);

        drawThinLine(vc, mat, p000, p100, thickness, r, g, b, a);
        drawThinLine(vc, mat, p100, p101, thickness, r, g, b, a);
        drawThinLine(vc, mat, p101, p001, thickness, r, g, b, a);
        drawThinLine(vc, mat, p001, p000, thickness, r, g, b, a);

        drawThinLine(vc, mat, p010, p110, thickness, r, g, b, a);
        drawThinLine(vc, mat, p110, p111, thickness, r, g, b, a);
        drawThinLine(vc, mat, p111, p011, thickness, r, g, b, a);
        drawThinLine(vc, mat, p011, p010, thickness, r, g, b, a);

        drawThinLine(vc, mat, p000, p010, thickness, r, g, b, a);
        drawThinLine(vc, mat, p100, p110, thickness, r, g, b, a);
        drawThinLine(vc, mat, p101, p111, thickness, r, g, b, a);
        drawThinLine(vc, mat, p001, p011, thickness, r, g, b, a);
    }

    private void drawThinLine(VertexConsumer vc, Matrix4f mat, Vec3d p1, Vec3d p2, double halfThickness,
                              int r, int g, int b, int a) {
        Vec3d d = p2.subtract(p1);
        if (d.lengthSquared() < 1e-6) return;
        Vec3d dir = d.normalize();

        Vec3d ref = (Math.abs(dir.y) > 0.92) ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d off1 = dir.crossProduct(ref).normalize().multiply(halfThickness);
        Vec3d off2 = dir.crossProduct(off1).normalize().multiply(halfThickness);

        emitDoubleSidedQuad(vc, mat, p1.subtract(off1), p1.add(off1), p2.add(off1), p2.subtract(off1), r, g, b, a);
        emitDoubleSidedQuad(vc, mat, p1.subtract(off2), p1.add(off2), p2.add(off2), p2.subtract(off2), r, g, b, a);
    }

    private void emitDoubleSidedQuad(VertexConsumer vc, Matrix4f mat, Vec3d v1, Vec3d v2, Vec3d v3, Vec3d v4,
                                     int r, int g, int b, int a) {
        vc.vertex(mat, (float) v1.x, (float) v1.y, (float) v1.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v2.x, (float) v2.y, (float) v2.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v3.x, (float) v3.y, (float) v3.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v4.x, (float) v4.y, (float) v4.z).color(r, g, b, a).next();

        vc.vertex(mat, (float) v4.x, (float) v4.y, (float) v4.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v3.x, (float) v3.y, (float) v3.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v2.x, (float) v2.y, (float) v2.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v1.x, (float) v1.y, (float) v1.z).color(r, g, b, a).next();
    }
}
