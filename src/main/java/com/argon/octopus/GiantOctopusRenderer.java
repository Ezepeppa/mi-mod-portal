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
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Renderizador 3D 100% Procedural de Malla Continua para los Tentáculos del Pulpo Terrestre Gigante.
 *
 * - En lugar de cubos rígidos desconectados, genera en tiempo real un tubo muscular continuo de 8 caras
 *   alrededor de la curva de los 14 nodos de cada tentáculo, respetando el radio de abultamiento
 *   (`currentRadii[i]`) del hidrostato muscular.
 * - En la cara ventral (inferior/interna) de cada segmento del tentáculo, renderiza la **doble hilera
 *   anatómica de ventosas 3D en zig-zag**, que brillan sutilmente o se comprimen cuando están adheridas al sustrato.
 * - Incluye también el modo **Rayos-X de Debug Biomecánico** (`showDebugPhysicsVectors`) para ver los nodos
 *   físicos, los puntos de succión y los vectores de fuerza de tracción/empuje en vivo.
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

        // 1. Renderizar el cuerpo central compacto (manto + cabeza con ojos laterales para dar anclaje a los tentáculos)
        matrices.push();
        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevYaw, entity.getYaw());
        double breath = Math.sin((entity.age + tickDelta) * 0.12) * 0.035;
        renderCompactCentralHub(matrices, vc, light, bodyYaw, breath);
        matrices.pop();

        // 2. Renderizar cada uno de los Tentáculos Procedurales con su malla tubular continua y su doble hilera de ventosas
        List<TentacleLimb> limbs = entity.getTentacles();
        matrices.push();
        MatrixStack.Entry worldRelativeEntry = matrices.peek();

        for (TentacleLimb limb : limbs) {
            renderProceduralTentacleTubeAndSuckers(entity, limb, entityRenderPos, tickDelta, worldRelativeEntry, vc, light);
        }
        matrices.pop();

        // 3. Si el tentáculo #0 sostiene un Bloque arrancado del mundo, renderizar el bloque dentro de la espiral del tentáculo!
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

    private void renderCompactCentralHub(MatrixStack matrices, VertexConsumer vc, int light, float bodyYaw, double breath) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        Matrix3f norm = matrices.peek().getNormalMatrix();

        // Corona muscular donde nacen los 8 tentáculos + manto superior simple
        emitTexturedBox(vc, mat, norm, -0.68f, 0.12f, -0.68f, 0.68f, (float) (0.72f + breath), 0.68f,
                155, 56, 45, light);
        emitTexturedBox(vc, mat, norm, -0.52f, 0.65f, -0.58f, 0.52f, (float) (1.18f + breath), 0.62f,
                168, 62, 50, light);

        // Ojos laterales de cefalópodo (pupila horizontal rectangular característica de los pulpos reales)
        emitTexturedBox(vc, mat, norm, -0.58f, 0.68f, -0.35f, -0.48f, 0.86f, -0.12f, 245, 220, 140, light);
        emitTexturedBox(vc, mat, norm, 0.48f, 0.68f, -0.35f, 0.58f, 0.86f, -0.12f, 245, 220, 140, light);
        emitTexturedBox(vc, mat, norm, -0.60f, 0.74f, -0.32f, -0.49f, 0.80f, -0.15f, 20, 18, 22, light);
        emitTexturedBox(vc, mat, norm, 0.49f, 0.74f, -0.32f, 0.60f, 0.80f, -0.15f, 20, 18, 22, light);
    }

    /**
     * Construye en tiempo real la malla tubular continua de 8 lados entre los 14 nodos del tentáculo
     * y modela físicamente las dos hileras de ventosas en la cara inferior/ventral de cada segmento.
     */
    private void renderProceduralTentacleTubeAndSuckers(GiantOctopusEntity entity, TentacleLimb limb,
                                                        Vec3d entityRenderPos, float tickDelta,
                                                        MatrixStack.Entry entry, VertexConsumer vc, int baseLight) {
        int sides = 8;
        Vec3d[][] rings = new Vec3d[TentacleLimb.NUM_NODES][sides];
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

        // Calcular marcos ortonormales (Tangente, Derecha, Abajo) a lo largo de la curva del tentáculo
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
                // Ligeramente achatado en la base ventral donde van las ventosas, como los brazos reales de pulpo
                double cos = Math.cos(ang) * r;
                double sin = Math.sin(ang) * r * (Math.sin(ang) < 0 ? 0.85 : 1.05);
                rings[i][s] = centers[i].add(right.multiply(cos)).add(up.multiply(sin));
            }
        }

        Matrix4f mat = entry.getPositionMatrix();
        Matrix3f norm = entry.getNormalMatrix();

        // Emitir los quads continuos de piel muscular entre cada anillo i e i+1
        for (int i = 0; i < TentacleLimb.NUM_NODES - 1; i++) {
            int segLight = Math.max(baseLight, Math.max(nodeLights[i], nodeLights[i + 1]));
            for (int s = 0; s < sides; s++) {
                int sNext = (s + 1) % sides;
                Vec3d v00 = rings[i][s];
                Vec3d v01 = rings[i][sNext];
                Vec3d v11 = rings[i + 1][sNext];
                Vec3d v10 = rings[i + 1][s];

                // Color dorsal rojizo-coral arriba, degradando a crema muscular en la cara ventral inferior
                boolean ventralFace = (s >= 4 && s <= 6);
                int cr = ventralFace ? 218 : (152 - (i * 2));
                int cg = ventralFace ? 182 : (55 - (i));
                int cb = ventralFace ? 155 : (46 - (i));

                emitQuad(vc, mat, norm, v00, v01, v11, v10, cr, cg, cb, segLight);
            }

            // Renderizar el par de ventosas 3D en zig-zag sobre la cara ventral del segmento
            if (i >= 1 && i < TentacleLimb.NUM_NODES - 1) {
                Vec3d segMid = centers[i].add(centers[i + 1]).multiply(0.5);
                float r = limb.currentRadii[i];
                float suckerSize = Math.max(0.025f, r * 0.36f);
                boolean locked = limb.nodeSuctionLocked[i];

                // Desplazamiento alternado izquierda/derecha (doble fila de ventosas de Octopus vulgaris)
                double sideSign = (i % 2 == 0) ? 0.45 : -0.45;
                Vec3d suckerCenter = segMid
                        .add(downDirs[i].multiply(r * 0.88))
                        .add(rightDirs[i].multiply(r * sideSign));

                // Si la ventosa está adherida ejerciendo succión activa, toma un tono cálido nacarado brillante
                int sr = locked ? 250 : 232;
                int sg = locked ? 235 : 204;
                int sb = locked ? 205 : 178;

                emitTexturedBox(vc, mat, norm,
                        (float) (suckerCenter.x - suckerSize), (float) (suckerCenter.y - suckerSize * 0.6f), (float) (suckerCenter.z - suckerSize),
                        (float) (suckerCenter.x + suckerSize), (float) (suckerCenter.y + suckerSize * 0.6f), (float) (suckerCenter.z + suckerSize),
                        sr, sg, sb, segLight);
            }
        }
    }

    private void renderBiomechanicalDebugOverlay(List<TentacleLimb> limbs, Vec3d entityRenderPos, float tickDelta,
                                                 MatrixStack matrices, VertexConsumer lineVc) {
        for (TentacleLimb limb : limbs) {
            // Dibujar esqueleto de nodos Verlet y estado de bloqueo de ventosas
            for (int i = 0; i < TentacleLimb.NUM_NODES; i++) {
                Vec3d pt = limb.getInterpolatedNode(i, tickDelta).subtract(entityRenderPos);
                boolean locked = limb.nodeSuctionLocked[i];
                float r = locked ? 0.1f : 1.0f;
                float g = locked ? 1.0f : 0.85f;
                float b = locked ? 0.3f : 0.1f;
                Box nodeBox = new Box(pt.x - 0.06, pt.y - 0.06, pt.z - 0.06, pt.x + 0.06, pt.y + 0.06, pt.z + 0.06);
                WorldRenderer.drawBox(matrices, lineVc, nodeBox, r, g, b, 0.9f);
            }

            // Dibujar punto de anclaje objetivo en el sustrato y vector de tracción sobre el cuerpo
            Vec3d anchorRel = limb.anchorWorldPos.subtract(entityRenderPos);
            Box anchorBox = new Box(anchorRel.x - 0.14, anchorRel.y - 0.14, anchorRel.z - 0.14,
                    anchorRel.x + 0.14, anchorRel.y + 0.14, anchorRel.z + 0.14);
            boolean pulling = (limb.state == TentacleLimb.LimbState.ANCHORED_PULL);
            WorldRenderer.drawBox(matrices, lineVc, anchorBox,
                    pulling ? 0.0f : 1.0f, pulling ? 0.9f : 0.4f, 1.0f, 1.0f);
        }
    }

    private void emitQuad(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                          Vec3d v1, Vec3d v2, Vec3d v3, Vec3d v4,
                          int r, int g, int b, int light) {
        // Usamos la región UV [0.5..1.0, 0.5..1.0] que es blanco puro para que el color RGB por vértice sea exacto
        vc.vertex(mat, (float) v1.x, (float) v1.y, (float) v1.z).color(r, g, b, 255).texture(0.6f, 0.6f)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(norm, 0.0f, 1.0f, 0.0f).next();
        vc.vertex(mat, (float) v2.x, (float) v2.y, (float) v2.z).color(r, g, b, 255).texture(0.9f, 0.6f)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(norm, 0.0f, 1.0f, 0.0f).next();
        vc.vertex(mat, (float) v3.x, (float) v3.y, (float) v3.z).color(r, g, b, 255).texture(0.9f, 0.9f)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(norm, 0.0f, 1.0f, 0.0f).next();
        vc.vertex(mat, (float) v4.x, (float) v4.y, (float) v4.z).color(r, g, b, 255).texture(0.6f, 0.9f)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(norm, 0.0f, 1.0f, 0.0f).next();
    }

    private void emitTexturedBox(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
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

        emitQuad(vc, mat, norm, p010, p110, p111, p011, r, g, b, light);
        emitQuad(vc, mat, norm, p001, p101, p100, p000, (int) (r * 0.8), (int) (g * 0.8), (int) (b * 0.8), light);
        emitQuad(vc, mat, norm, p000, p100, p110, p010, (int) (r * 0.9), (int) (g * 0.9), (int) (b * 0.9), light);
        emitQuad(vc, mat, norm, p101, p001, p011, p111, (int) (r * 0.9), (int) (g * 0.9), (int) (b * 0.9), light);
        emitQuad(vc, mat, norm, p001, p000, p010, p011, (int) (r * 0.85), (int) (g * 0.85), (int) (b * 0.85), light);
        emitQuad(vc, mat, norm, p100, p101, p111, p110, (int) (r * 0.85), (int) (g * 0.85), (int) (b * 0.85), light);
    }
}
