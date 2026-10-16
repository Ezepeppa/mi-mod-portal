package com.argon.subgrid;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class SubGridClient implements ClientModInitializer {
    private static KeyBinding shrinkGridKey;
    private static KeyBinding enlargeGridKey;

    @Override
    public void onInitializeClient() {
        BlockEntityRendererFactories.register(SubGridMod.SUBGRID_BLOCK_ENTITY, SubGridBlockEntityRenderer::new);

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

            // Ejecutar el tick de animación en cliente de los BlockEntities nativos dentro de SubGrid (ej. libro flotante de Mesa de Encantamientos!)
            BlockPos center = client.player.getBlockPos();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        BlockPos bp = center.add(dx, dy, dz);
                        BlockEntity be = client.world.getBlockEntity(bp);
                        if (be instanceof SubGridBlockEntity sgbe) {
                            for (SubGridBlockEntity.CellSlice s : sgbe.getSlices()) {
                                if (s.renderHere) {
                                    s.tickInnerBlockEntity(client.world);
                                }
                            }
                        }
                    }
                }
            }

            boolean shrinkPressed = false;
            while (shrinkGridKey.wasPressed()) {
                shrinkPressed = true;
            }
            boolean enlargePressed = false;
            while (enlargeGridKey.wasPressed()) {
                enlargePressed = true;
            }

            if (shrinkPressed && enlargePressed) {
                sendGridStepUpdate(1);
            } else if (shrinkPressed) {
                if (client.player.isSneaking() && SubGridClientState.currentGridStep <= 2) {
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

        // Contorno negro nativo de selección (`RenderLayer.getLines()` + `WorldRenderer.drawBox`)
        // con la MISMA transformación de MatrixStack y rotación `yawDegrees` que el renderizador del bloque!
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hitResult) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || !(hitResult instanceof BlockHitResult bhr)) {
                return true;
            }
            BlockPos pos = bhr.getBlockPos();
            BlockState state = client.world.getBlockState(pos);
            if (!state.isOf(SubGridMod.SUBGRID_CONTAINER)) {
                return true;
            }
            BlockEntity be = client.world.getBlockEntity(pos);
            if (!(be instanceof SubGridBlockEntity sgbe)) {
                return true;
            }

            Vec3d eye = client.player.getEyePos();
            Vec3d end = eye.add(client.player.getRotationVec(1.0F).multiply(6.5));
            SubGridBlockEntity.CellSlice target = sgbe.raycastSlice(eye, end);
            if (target == null) {
                return true;
            }

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();
            if (matrices == null || consumers == null) {
                return true;
            }

            Vec3d cam = context.camera().getPos();
            VertexConsumer vc = consumers.getBuffer(RenderLayer.getLines());

            double baseGx = target.primaryPos.getX() + target.offsetX / 16.0;
            double baseGy = target.primaryPos.getY() + target.offsetY / 16.0;
            double baseGz = target.primaryPos.getZ() + target.offsetZ / 16.0;

            matrices.push();
            matrices.translate(baseGx - cam.x, baseGy - cam.y, baseGz - cam.z);
            if (Math.abs(target.yawDegrees) > 0.01f) {
                matrices.translate(0.5, 0.0, 0.5);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(target.yawDegrees));
                matrices.translate(-0.5, 0.0, -0.5);
            }

            VoxelShape origOutline = SubGridPlacementHandler.getSafeOriginalOutlineShape(target.storedState, client.world, pos);
            for (Box localBox : origOutline.getBoundingBoxes()) {
                Box drawB = localBox;
                if (target.storedState.getBlock() instanceof DoorBlock && target.storedState.contains(DoorBlock.HALF)) {
                    if (target.storedState.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        drawB = new Box(localBox.minX, localBox.minY, localBox.minZ, localBox.maxX, localBox.maxY + 1.0, localBox.maxZ);
                    } else {
                        drawB = new Box(localBox.minX, localBox.minY - 1.0, localBox.minZ, localBox.maxX, localBox.maxY, localBox.maxZ);
                    }
                }
                WorldRenderer.drawBox(matrices, vc, drawB.expand(0.002), 0.0f, 0.0f, 0.0f, 0.4f);
            }
            matrices.pop();
            return false; // Cancelar el outline vanilla sin rotar
        });

        WorldRenderEvents.AFTER_ENTITIES.register(this::renderSubtlePlacementPreview);
    }

    private void sendGridStepUpdate(int newStep) {
        SubGridClientState.currentGridStep = newStep;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(newStep);
        ClientPlayNetworking.send(SubGridMod.SET_GRID_STEP_PACKET, buf);
    }

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
                client.player, client.world, activeHand, stack, blockItem, blockHit, effectiveStep
        );
        if (desiredState == null) return;

        int[] globalOrigin16 = SubGridPlacementHandler.computeGlobalOrigin16(blockHit, desiredState, effectiveStep, client.world, client.player);
        float yawDeg = (effectiveStep == 1) ? MathHelper.wrapDegrees(180.0f - client.player.getYaw()) : 0.0f;

        boolean canSurvive = SubGridPlacementHandler.canBlockSurviveNative(
                client.world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], desiredState, null
        );
        boolean valid = canSurvive && SubGridPlacementHandler.canPlaceAtGlobal16(
                client.world, globalOrigin16[0], globalOrigin16[1], globalOrigin16[2], desiredState, yawDeg, null, true
        );
        if (valid && (desiredState.getBlock() instanceof DoorBlock || desiredState.getBlock() instanceof TallPlantBlock)) {
            BlockState upperState = desiredState.contains(Properties.DOUBLE_BLOCK_HALF)
                    ? desiredState.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                    : desiredState;
            valid = SubGridPlacementHandler.canPlaceAtGlobal16(
                    client.world, globalOrigin16[0], globalOrigin16[1] + 16, globalOrigin16[2], upperState, yawDeg, null, true
            );
        }

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        Vec3d cam = context.camera().getPos();
        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLightning());

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        int r = valid ? (effectiveStep == 1 ? 215 : 210) : 255;
        int g = valid ? (effectiveStep == 1 ? 190 : 235) : 95;
        int b = valid ? 255 : 80;
        int alpha = valid ? 72 : 85;
        double lineThickness = 0.0055;

        double pivotX = (globalOrigin16[0] + 8.0) / 16.0;
        double pivotZ = (globalOrigin16[2] + 8.0) / 16.0;

        List<int[]> localBoxes = SubGridPlacementHandler.getLocalBoxes16(desiredState, client.world);
        for (int[] lb : localBoxes) {
            int maxY = (desiredState.getBlock() instanceof DoorBlock || desiredState.getBlock() instanceof TallPlantBlock) ? 32 : lb[4];
            Box worldBox = new Box(
                    (globalOrigin16[0] + lb[0]) / 16.0,
                    (globalOrigin16[1] + lb[1]) / 16.0,
                    (globalOrigin16[2] + lb[2]) / 16.0,
                    (globalOrigin16[0] + lb[3]) / 16.0,
                    (globalOrigin16[1] + maxY) / 16.0,
                    (globalOrigin16[2] + lb[5]) / 16.0
            ).expand(0.0015);
            drawSubtleWireframeBoxRotated(vc, mat, worldBox, pivotX, pivotZ, yawDeg, lineThickness, r, g, b, alpha);
        }

        matrices.pop();
    }

    private Vec3d rotateAroundY(Vec3d pt, double pivotX, double pivotZ, float yawDeg) {
        if (Math.abs(yawDeg) < 0.01f) return pt;
        double rad = Math.toRadians(yawDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double dx = pt.x - pivotX;
        double dz = pt.z - pivotZ;
        double rx = pivotX + dx * cos + dz * sin;
        double rz = pivotZ - dx * sin + dz * cos;
        return new Vec3d(rx, pt.y, rz);
    }

    private void drawSubtleWireframeBoxRotated(VertexConsumer vc, Matrix4f mat, Box box,
                                                double pivotX, double pivotZ, float yawDeg,
                                                double thickness, int r, int g, int b, int a) {
        Vec3d p000 = rotateAroundY(new Vec3d(box.minX, box.minY, box.minZ), pivotX, pivotZ, yawDeg);
        Vec3d p100 = rotateAroundY(new Vec3d(box.maxX, box.minY, box.minZ), pivotX, pivotZ, yawDeg);
        Vec3d p101 = rotateAroundY(new Vec3d(box.maxX, box.minY, box.maxZ), pivotX, pivotZ, yawDeg);
        Vec3d p001 = rotateAroundY(new Vec3d(box.minX, box.minY, box.maxZ), pivotX, pivotZ, yawDeg);

        Vec3d p010 = rotateAroundY(new Vec3d(box.minX, box.maxY, box.minZ), pivotX, pivotZ, yawDeg);
        Vec3d p110 = rotateAroundY(new Vec3d(box.maxX, box.maxY, box.minZ), pivotX, pivotZ, yawDeg);
        Vec3d p111 = rotateAroundY(new Vec3d(box.maxX, box.maxY, box.maxZ), pivotX, pivotZ, yawDeg);
        Vec3d p011 = rotateAroundY(new Vec3d(box.minX, box.maxY, box.maxZ), pivotX, pivotZ, yawDeg);

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
