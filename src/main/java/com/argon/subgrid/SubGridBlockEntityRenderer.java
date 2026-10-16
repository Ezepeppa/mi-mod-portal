package com.argon.subgrid;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

public class SubGridBlockEntityRenderer implements BlockEntityRenderer<SubGridBlockEntity> {
    public SubGridBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    @Override
    public void render(SubGridBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (entity.getWorld() == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        BlockRenderManager brm = client.getBlockRenderManager();

        int worldLight = WorldRenderer.getLightmapCoordinates(entity.getWorld(), entity.getPos());
        int upLight = WorldRenderer.getLightmapCoordinates(entity.getWorld(), entity.getPos().up());
        int effectiveLight = Math.max(light, Math.max(worldLight, upLight));

        for (SubGridBlockEntity.CellSlice slice : entity.getSlices()) {
            if (!slice.renderHere) continue;
            BlockState state = slice.storedState;

            matrices.push();
            matrices.translate(
                    slice.offsetX / 16.0,
                    slice.offsetY / 16.0,
                    slice.offsetZ / 16.0
            );

            if (Math.abs(slice.yawDegrees) > 0.01f) {
                matrices.translate(0.5, 0.0, 0.5);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(slice.yawDegrees));
                matrices.translate(-0.5, 0.0, -0.5);
            }

            // 1. Renderizar el modelo estático del bloque con tinte real del bioma
            if (state.getRenderType() == BlockRenderType.MODEL) {
                BakedModel model = brm.getModel(state);
                int color = client.getBlockColors().getColor(state, entity.getWorld(), entity.getPos(), 0);
                float r = (color != -1) ? ((color >> 16) & 0xFF) / 255.0f : 1.0f;
                float g = (color != -1) ? ((color >> 8) & 0xFF) / 255.0f : 1.0f;
                float b = (color != -1) ? (color & 0xFF) / 255.0f : 1.0f;

                VertexConsumer vc = vertexConsumers.getBuffer(RenderLayers.getEntityBlockLayer(state, false));
                brm.getModelRenderer().render(
                        matrices.peek(),
                        vc,
                        state,
                        model,
                        r, g, b,
                        effectiveLight,
                        OverlayTexture.DEFAULT_UV
                );
            }

            // 2. FUNDAMENTAL: Si el bloque tiene un BlockEntity nativo (Mesa de Encantamientos, Cofre, Campana, Cama, Cartel, etc.),
            // obtener su BlockEntityRenderer real y renderizarlo (para que la Mesa de Encantamientos tenga su libro flotante animado!)
            BlockEntity innerBe = slice.getOrCreateInnerBlockEntity(entity.getWorld());
            if (innerBe != null) {
                try {
                    BlockEntityRenderer<BlockEntity> ber = client.getBlockEntityRenderDispatcher().get(innerBe);
                    if (ber != null) {
                        ber.render(innerBe, tickDelta, matrices, vertexConsumers, effectiveLight, OverlayTexture.DEFAULT_UV);
                    } else if (state.getRenderType() != BlockRenderType.MODEL) {
                        brm.renderBlockAsEntity(state, matrices, vertexConsumers, effectiveLight, OverlayTexture.DEFAULT_UV);
                    }
                } catch (Exception ignored) {
                }
            } else if (state.getRenderType() != BlockRenderType.MODEL) {
                brm.renderBlockAsEntity(state, matrices, vertexConsumers, effectiveLight, OverlayTexture.DEFAULT_UV);
            }

            matrices.pop();
        }
    }

    @Override
    public boolean rendersOutsideBoundingBox(SubGridBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 128;
    }
}
