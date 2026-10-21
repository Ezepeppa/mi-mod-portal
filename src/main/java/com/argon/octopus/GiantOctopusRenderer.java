package com.argon.octopus;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Renderizador 3D v2.0.0 con Iluminación Volumétrica Cilíndrica, Normales Verdaderas por Vértice,
 * Oclusión Ambiental Ventral, Anillos Musculares Texturizados e Inclinación 3D del Cuerpo (Pitch + Roll + Yaw).
 */
public class GiantOctopusRenderer extends EntityRenderer<GiantOctopusEntity> {
    private static final Identifier TEXTURE = new Identifier(OctopusMod.MOD_ID, "textures/entity/giant_octopus.png");

    public GiantOctopusRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.15f;
    }

    @Override
    public Identifier getTexture(GiantOctopusEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(GiantOctopusEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

        Vec3d entityRenderPos = new Vec3d(
                MathHelper.lerp(tickDelta, entity.prevX, entity.getX()),
                MathHelper.lerp(tickDelta, entity.prevY, entity.getY()),
                MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ())
        );

        VertexConsumer vc = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));

        // 1. Renderizar la base del cuerpo con su Inclinación 3D Dinámica (Yaw, Pitch y Roll interpolados)
        matrices.push();
        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevYaw, entity.getYaw());
        float pitch = MathHelper.lerp(tickDelta, entity.prevBodyPitch, entity.bodyPitch);
        float roll = MathHelper.lerp(tickDelta, entity.prevBodyRoll, entity.bodyRoll);
        double breath = Math.sin((entity.age + tickDelta) * 0.12) * 0.035;

        matrices.translate(0.0, 0.42, 0.0);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
        matrices.translate(0.0, -0.42, 0.0);

        renderTiltedCentralHub(matrices, vc, light, breath);
        matrices.pop();

        // 2. Renderizar los Tentáculos Procedurales con Normales 3D Verdaderas, Sombreado Cilíndrico y Ventosas Cóncavas
        List<TentacleLimb> limbs = entity.getTentacles();
        matrices.push();
        MatrixStack.Entry worldRelativeEntry = matrices.peek();

        for (TentacleLimb limb : limbs) {
            renderProceduralTentacleTubeAndSuckers(entity, limb, entityRenderPos, tickDelta, worldRelativeEntry, vc, light);
        }
        matrices.pop();

        // 3. Si el tentáculo #0 sostiene o está lanzando un Bloque, renderizarlo en el centro de la espiral del tentáculo
        BlockState heldBlock = entity.getHeldBlockState();
        if (!heldBlock.isAir() && !limbs.isEmpty()) {
            TentacleLimb holder = limbs.get(0);
            Vec3d coilWorld = holder.getInterpolatedNode(TentacleLimb.NUM_NODES - 3, tickDelta);
            Vec3d rel = coilWorld.subtract(entityRenderPos);
            matrices.push();
            matrices.translate(rel.x - 0.5, rel.y - 0.5, rel.z - 0.5);
            MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(
                    heldBlock, matrices, vertexConsumers, light, OverlayTexture.DEFAULT_UV
            );
            matrices.pop();
        }

        // 4. Modo Rayos-X de Debug Biomecánico (configurable desde el menú 'O')
        if (OctopusPhysicsConfig.showDebugPhysicsVectors) {
            VertexConsumer lineVc = vertexConsumers.getBuffer(RenderLayer.getLines());
            renderBiomechanicalDebugOverlay(limbs, entityRenderPos, tickDelta, matrices, lineVc);
        }
    }

    private void renderTiltedCentralHub(MatrixStack matrices, VertexConsumer vc, int light, double breath) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        Matrix3f norm = matrices.peek().getNormalMatrix();

        // Base muscular con sombreado 3D marcado por cara para distinguir claramente su inclinación
        emitShadedBox(vc, mat, norm, -0.66f, 0.10f, -0.66f, 0.66f, (float) (0.70f + breath), 0.66f,
                162, 58, 46, light);
        emitShadedBox(vc, mat, norm, -0.50f, 0.62f, -0.56f, 0.50f, (float) (1.14f + breath), 0.60f,
                176, 65, 52, light);

        // Ojos laterales con pupila horizontal de cefalópodo
        emitShadedBox(vc, mat, norm, -0.56f, 0.66f, 0.12f, -0.46f, 0.84f, 0.35f, 245, 222, 145, light);
        emitShadedBox(vc, mat, norm, 0.46f, 0.66f, 0.12f, 0.56f, 0.84f, 0.35f, 245, 222, 145, light);
        emitShadedBox(vc, mat, norm, -0.58f, 0.72f, 0.15f, -0.47f, 0.78f, 0.32f, 22, 18, 24, light);
        emitShadedBox(vc, mat, norm, 0.47f, 0.72f, 0.15f, 0.58f, 0.78f, 0.32f, 22, 18, 24, light);
    }

    /**
     * Renderiza cada tentáculo con:
     * - Normales 3D verdaderas por cada vértice del cilindro (`ringNormals[i][s]`) para que la luz del sol y antorchas
     *   impacte con volumen real.
     * - Sombreado cilíndrico direccional (brillo húmedo dorsal arriba, flancos sombreados al 72% a los costados,
     *   oclusión ambiental al 56% abajo y oscurecimiento sutil entre segmentos musculares).
     * - Textura con estrías musculares (`u, v` en `[0.0 .. 0.5]`) para dar relieve táctil y separar visualmente un tentáculo de otro.
     */
    private void renderProceduralTentacleTubeAndSuckers(GiantOctopusEntity entity, TentacleLimb limb,
                                                        Vec3d entityRenderPos, float tickDelta,
                                                        MatrixStack.Entry entry, VertexConsumer vc, int baseLight) {
        int sides = 8;
        Vec3d[][] rings = new Vec3d[TentacleLimb.NUM_NODES][sides];
        Vec3d[][] ringNormals = new Vec3d[TentacleLimb.NUM_NODES][sides];
        Vec3d[] centers = new Vec3d[TentacleLimb.NUM_NODES];
        Vec3d[] downDirs = new Vec3d[TentacleLimb.NUM_NODES];
        Vec3d[] rightDirs = new Vec3d[TentacleLimb.NUM_NODES];
        int[] nodeLights = new int[TentacleLimb.NUM_NODES];

        for (int i = 0; i < TentacleLimb.NUM_NODES; i++) {
            Vec3d worldPt = limb.getInterpolatedNode(i, tickDelta);
            centers[i] = worldPt.subtract(entityRenderPos);
            nodeLights[i] = WorldRenderer.getLightmapCoordinates(entity.getWorld(), BlockPos.ofFloored(worldPt));
            if (nodeLights[i] == 0) nodeLights[i] = baseLight;
        }

        for (int i = 0; i < TentacleLimb.NUM_NODES; i++) {
            Vec3d tangent;
            if (i == 0) {
                tangent = centers[1].subtract(centers[0]);
            } else if (i == TentacleLimb.NUM_NODES - 1) {
                tangent = centers[i].subtract(centers[i - 1]);
            } else {
                tangent = centers[i + 1].subtract(centers[i - 1]);
            }
            if (tangent.lengthSquared() < 1e-6) {
                tangent = new Vec3d(1, 0, 0);
            } else {
                tangent = tangent.normalize();
            }

            Vec3d upRef = (Math.abs(tangent.y) > 0.92) ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            Vec3d right = tangent.crossProduct(upRef).normalize();
            Vec3d up = right.crossProduct(tangent).normalize();
            Vec3d down = up.multiply(-1.0);

            rightDirs[i] = right;
            downDirs[i] = down;

            float r = limb.currentRadii[i];
            for (int s = 0; s < sides; s++) {
                double ang = s * (Math.PI * 2.0 / sides);
                double nxLocal = Math.cos(ang);
                double nyLocal = Math.sin(ang);
                Vec3d radialNormal = right.multiply(nxLocal).add(up.multiply(nyLocal)).normalize();
                ringNormals[i][s] = radialNormal;

                double cos = nxLocal * r;
                double sin = nyLocal * r * (nyLocal < 0 ? 0.84 : 1.06);
                rings[i][s] = centers[i].add(right.multiply(cos)).add(up.multiply(sin));
            }
        }

        Matrix4f mat = entry.getPositionMatrix();
        Matrix3f normMat = entry.getNormalMatrix();

        // Pequeña variación de tono por índice de tentáculo para que cuando dos tentáculos se toquen se distingan al instante
        int limbToneOffset = ((limb.index % 3) - 1) * 9;

        for (int i = 0; i < TentacleLimb.NUM_NODES - 1; i++) {
            int segLight = Math.max(baseLight, Math.max(nodeLights[i], nodeLights[i + 1]));
            // Variación entre anillos impares/pares para marcar la segmentación muscular
            float segRingShade = (i % 2 == 0) ? 1.0f : 0.91f;

            for (int s = 0; s < sides; s++) {
                int sNext = (s + 1) % sides;
                Vec3d v00 = rings[i][s];
                Vec3d v01 = rings[i][sNext];
                Vec3d v11 = rings[i + 1][sNext];
                Vec3d v10 = rings[i + 1][s];

                Vec3d n00 = ringNormals[i][s];
                Vec3d n01 = ringNormals[i][sNext];
                Vec3d n11 = ringNormals[i + 1][sNext];
                Vec3d n10 = ringNormals[i + 1][s];

                boolean ventralFace = (s >= 4 && s <= 6);
                int baseR = ventralFace ? 226 : MathHelper.clamp(172 + limbToneOffset - i * 2, 110, 210);
                int baseG = ventralFace ? 190 : MathHelper.clamp(62 + limbToneOffset / 2 - i, 35, 95);
                int baseB = ventralFace ? 162 : MathHelper.clamp(50 + limbToneOffset / 2 - i, 28, 80);

                float u0 = (float) s / sides * 0.5f;
                float u1 = (float) (s + 1) / sides * 0.5f;
                float v0 = (i % 4) * 0.125f;
                float v1 = v0 + 0.125f;

                emitLitVertex(vc, mat, normMat, v00, n00, baseR, baseG, baseB, segRingShade, u0, v0, segLight);
                emitLitVertex(vc, mat, normMat, v01, n01, baseR, baseG, baseB, segRingShade, u1, v0, segLight);
                emitLitVertex(vc, mat, normMat, v11, n11, baseR, baseG, baseB, segRingShade * 0.96f, u1, v1, segLight);
                emitLitVertex(vc, mat, normMat, v10, n10, baseR, baseG, baseB, segRingShade * 0.96f, u0, v1, segLight);
            }

            // Renderizar par de ventosas 3D con borde muscular iluminado y centro cóncavo sombreado
            if (i >= 1 && i < TentacleLimb.NUM_NODES - 1) {
                Vec3d segMid = centers[i].add(centers[i + 1]).multiply(0.5);
                float r = limb.currentRadii[i];
                float suckerSize = Math.max(0.028f, r * 0.38f);
                boolean locked = limb.nodeSuctionLocked[i];

                double sideSign = (i % 2 == 0) ? 0.46 : -0.46;
                Vec3d suckerCenter = segMid
                        .add(downDirs[i].multiply(r * 0.88))
                        .add(rightDirs[i].multiply(r * sideSign));

                int sr = locked ? 252 : 228;
                int sg = locked ? 236 : 196;
                int sb = locked ? 204 : 168;

                emitSuckerBox(vc, mat, normMat,
                        (float) (suckerCenter.x - suckerSize), (float) (suckerCenter.y - suckerSize * 0.65f), (float) (suckerCenter.z - suckerSize),
                        (float) (suckerCenter.x + suckerSize), (float) (suckerCenter.y + suckerSize * 0.65f), (float) (suckerCenter.z + suckerSize),
                        sr, sg, sb, segLight);
            }
        }
    }

    /**
     * Calcula la iluminación direccional 3D + brillo húmedo dorsal + oclusión ventral de cada vértice
     * y envía su normal verdadera `(n.x, n.y, n.z)` al pipeline de OpenGL.
     */
    private void emitLitVertex(VertexConsumer vc, Matrix4f mat, Matrix3f normMat,
                               Vec3d pos, Vec3d normal, int baseR, int baseG, int baseB,
                               float ringFactor, float u, float v, int light) {
        // Luz solar direccional cenital-lateral + oclusión ambiental en la parte baja
        double sunDot = normal.x * 0.32 + normal.y * 0.78 + normal.z * 0.25;
        float diffuse = (float) MathHelper.clamp(0.52 + (sunDot + 1.0) * 0.28, 0.44, 1.12);

        // Brillo húmedo especular en el lomo superior del tentáculo
        float wetSpecular = (normal.y > 0.68) ? (float) ((normal.y - 0.68) * 38.0) : 0.0f;

        int r = MathHelper.clamp((int) (baseR * diffuse * ringFactor + wetSpecular), 0, 255);
        int g = MathHelper.clamp((int) (baseG * diffuse * ringFactor + wetSpecular * 0.75f), 0, 255);
        int b = MathHelper.clamp((int) (baseB * diffuse * ringFactor + wetSpecular * 0.65f), 0, 255);

        vc.vertex(mat, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(normMat, (float) normal.x, (float) normal.y, (float) normal.z)
                .next();
    }

    private void renderBiomechanicalDebugOverlay(List<TentacleLimb> limbs, Vec3d entityRenderPos, float tickDelta,
                                                 MatrixStack matrices, VertexConsumer lineVc) {
        for (TentacleLimb limb : limbs) {
            for (int i = 0; i < TentacleLimb.NUM_NODES; i++) {
                Vec3d pt = limb.getInterpolatedNode(i, tickDelta).subtract(entityRenderPos);
                boolean locked = limb.nodeSuctionLocked[i];
                float r = locked ? 0.1f : 1.0f;
                float g = locked ? 1.0f : 0.85f;
                float b = locked ? 0.3f : 0.1f;
                Box nodeBox = new Box(pt.x - 0.05, pt.y - 0.05, pt.z - 0.05, pt.x + 0.05, pt.y + 0.05, pt.z + 0.05);
                WorldRenderer.drawBox(matrices, lineVc, nodeBox, r, g, b, 0.9f);
            }

            if (limb.state == TentacleLimb.LimbState.ANCHORED_PULL || limb.state == TentacleLimb.LimbState.ANCHORED_PUSH) {
                Vec3d anchorRel = limb.anchorWorldPos.subtract(entityRenderPos);
                Box anchorBox = new Box(anchorRel.x - 0.12, anchorRel.y - 0.12, anchorRel.z - 0.12,
                        anchorRel.x + 0.12, anchorRel.y + 0.12, anchorRel.z + 0.12);
                boolean pulling = (limb.state == TentacleLimb.LimbState.ANCHORED_PULL);
                WorldRenderer.drawBox(matrices, lineVc, anchorBox,
                        pulling ? 0.0f : 1.0f, pulling ? 0.9f : 0.4f, 1.0f, 1.0f);
            }
        }
    }

    private void emitFaceQuadWithNormal(VertexConsumer vc, Matrix4f mat, Matrix3f normMat,
                                        Vec3d v1, Vec3d v2, Vec3d v3, Vec3d v4,
                                        float nx, float ny, float nz,
                                        float u0, float v0, float u1, float v1,
                                        int r, int g, int b, int light) {
        vc.vertex(mat, (float) v1.x, (float) v1.y, (float) v1.z).color(r, g, b, 255).texture(u0, v0)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normMat, nx, ny, nz).next();
        vc.vertex(mat, (float) v2.x, (float) v2.y, (float) v2.z).color(r, g, b, 255).texture(u1, v0)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normMat, nx, ny, nz).next();
        vc.vertex(mat, (float) v3.x, (float) v3.y, (float) v3.z).color(r, g, b, 255).texture(u1, v1)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normMat, nx, ny, nz).next();
        vc.vertex(mat, (float) v4.x, (float) v4.y, (float) v4.z).color(r, g, b, 255).texture(u0, v1)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(normMat, nx, ny, nz).next();
    }

    private void emitShadedBox(VertexConsumer vc, Matrix4f mat, Matrix3f normMat,
                               float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                               int r, int g, int b, int light) {
        Vec3d p000 = new Vec3d(minX, minY, minZ);
        Vec3d p100 = new Vec3d(maxX, minY, minZ);
        Vec3d p101 = new Vec3d(maxX, minY, maxZ);
        Vec3d p001 = new Vec3d(minX, minY, maxZ);
        Vec3d p010 = new Vec3d(minX, maxY, minZ);
        Vec3d p110 = new Vec3d(maxX, maxY, minZ);
        Vec3d p111 = new Vec3d(maxX, maxY, maxZ);
        Vec3d p011 = new Vec3d(minX, maxY, maxZ);

        // Cara superior (+Y): iluminada con brillo húmedo
        emitFaceQuadWithNormal(vc, mat, normMat, p010, p110, p111, p011, 0, 1, 0, 0.0f, 0.0f, 0.5f, 0.5f,
                Math.min(255, (int) (r * 1.12)), Math.min(255, (int) (g * 1.12)), Math.min(255, (int) (b * 1.12)), light);
        // Cara inferior (-Y): sombreada en oclusión (55%)
        emitFaceQuadWithNormal(vc, mat, normMat, p001, p101, p100, p000, 0, -1, 0, 0.0f, 0.0f, 0.5f, 0.5f,
                (int) (r * 0.55), (int) (g * 0.55), (int) (b * 0.55), light);
        // Frente y dorso (+Z / -Z): 88%
        emitFaceQuadWithNormal(vc, mat, normMat, p000, p100, p110, p010, 0, 0, -1, 0.0f, 0.0f, 0.5f, 0.5f,
                (int) (r * 0.88), (int) (g * 0.88), (int) (b * 0.88), light);
        emitFaceQuadWithNormal(vc, mat, normMat, p101, p001, p011, p111, 0, 0, 1, 0.0f, 0.0f, 0.5f, 0.5f,
                (int) (r * 0.92), (int) (g * 0.92), (int) (b * 0.92), light);
        // Laterales (-X / +X): 72% para dar contraste tridimensional fuerte
        emitFaceQuadWithNormal(vc, mat, normMat, p001, p000, p010, p011, -1, 0, 0, 0.0f, 0.0f, 0.5f, 0.5f,
                (int) (r * 0.72), (int) (g * 0.72), (int) (b * 0.72), light);
        emitFaceQuadWithNormal(vc, mat, normMat, p100, p101, p111, p110, 1, 0, 0, 0.0f, 0.0f, 0.5f, 0.5f,
                (int) (r * 0.75), (int) (g * 0.75), (int) (b * 0.75), light);
    }

    private void emitSuckerBox(VertexConsumer vc, Matrix4f mat, Matrix3f normMat,
                               float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                               int r, int g, int b, int light) {
        Vec3d p000 = new Vec3d(minX, minY, minZ);
        Vec3d p100 = new Vec3d(maxX, minY, minZ);
        Vec3d p101 = new Vec3d(maxX, minY, maxZ);
        Vec3d p001 = new Vec3d(minX, minY, maxZ);
        Vec3d p010 = new Vec3d(minX, maxY, minZ);
        Vec3d p110 = new Vec3d(maxX, maxY, minZ);
        Vec3d p111 = new Vec3d(maxX, maxY, maxZ);
        Vec3d p011 = new Vec3d(minX, maxY, maxZ);

        // Usamos el cuadrante UV [0.5..1.0, 0.0..0.5] que tiene la textura cóncava de la ventosa
        emitFaceQuadWithNormal(vc, mat, normMat, p010, p110, p111, p011, 0, 1, 0, 0.5f, 0.0f, 1.0f, 0.5f, r, g, b, light);
        emitFaceQuadWithNormal(vc, mat, normMat, p001, p101, p100, p000, 0, -1, 0, 0.5f, 0.0f, 1.0f, 0.5f,
                (int) (r * 0.75), (int) (g * 0.75), (int) (b * 0.75), light);
        emitFaceQuadWithNormal(vc, mat, normMat, p000, p100, p110, p010, 0, 0, -1, 0.5f, 0.0f, 1.0f, 0.5f,
                (int) (r * 0.85), (int) (g * 0.85), (int) (b * 0.85), light);
        emitFaceQuadWithNormal(vc, mat, normMat, p101, p001, p011, p111, 0, 0, 1, 0.5f, 0.0f, 1.0f, 0.5f,
                (int) (r * 0.85), (int) (g * 0.85), (int) (b * 0.85), light);
        emitFaceQuadWithNormal(vc, mat, normMat, p001, p000, p010, p011, -1, 0, 0, 0.5f, 0.0f, 1.0f, 0.5f,
                (int) (r * 0.72), (int) (g * 0.72), (int) (b * 0.72), light);
        emitFaceQuadWithNormal(vc, mat, normMat, p100, p101, p111, p110, 1, 0, 0, 0.5f, 0.0f, 1.0f, 0.5f,
                (int) (r * 0.72), (int) (g * 0.72), (int) (b * 0.72), light);
    }
}
