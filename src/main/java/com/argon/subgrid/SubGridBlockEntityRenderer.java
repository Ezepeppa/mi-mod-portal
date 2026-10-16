package com.argon.subgrid;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;

public class SubGridBlockEntityRenderer implements BlockEntityRenderer<SubGridBlockEntity> {
    public SubGridBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
    }

    @Override
    public void render(SubGridBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light, int overlay) {
        if (entity.getWorld() == null) return;
        BlockRenderManager brm = MinecraftClient.getInstance().getBlockRenderManager();

        int worldLight = WorldRenderer.getLightmapCoordinates(entity.getWorld(), entity.getPos());
        int upLight = WorldRenderer.getLightmapCoordinates(entity.getWorld(), entity.getPos().up());
        int effectiveLight = Math.max(light, Math.max(worldLight, upLight));

        for (SubGridBlockEntity.CellSlice slice : entity.getSlices()) {
            if (!slice.renderHere) continue;
            matrices.push();
            matrices.translate(
                    slice.offsetX / 16.0,
                    slice.offsetY / 16.0,
                    slice.offsetZ / 16.0
            );
            brm.renderBlockAsEntity(slice.storedState, matrices, vertexConsumers, effectiveLight, OverlayTexture.DEFAULT_UV);
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
