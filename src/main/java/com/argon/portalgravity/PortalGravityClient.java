package com.argon.portalgravity;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class PortalGravityClient implements ClientModInitializer {
    private boolean wasAttackPressed = false;
    private int leftClickCooldown = 0;

    public static class ClientPortalFx {
        public boolean isOrange;
        public boolean isLinked;
        public Vec3d origin;
        public Vec3d axisW;
        public Vec3d axisH;
        public Vec3d normal;
        public double width;
        public double height;
    }

    public static class ClientGravityBeamFx {
        public Vec3d start;
        public Vec3d end;
        public float rotY;
    }

    public static class ClientShotFx {
        public boolean isOrange;
        public Vec3d start;
        public Vec3d end;
    }

    public static class ClientVortexFx {
        public Vec3d pos;
        public int ticksRemaining;
    }

    private static volatile List<ClientPortalFx> ACTIVE_PORTALS = new ArrayList<>();
    private static volatile List<ClientGravityBeamFx> ACTIVE_BEAMS = new ArrayList<>();
    private static volatile List<ClientShotFx> ACTIVE_SHOTS = new ArrayList<>();
    private static volatile List<ClientVortexFx> ACTIVE_VORTICES = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        // 1. Detección instantánea de Clic Izquierdo en START_CLIENT_TICK (antes de que Minecraft consuma la tecla)
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (leftClickCooldown > 0) {
                leftClickCooldown--;
            }
            if (client.player == null || client.world == null || client.currentScreen != null) {
                wasAttackPressed = false;
                return;
            }

            ItemStack stack = client.player.getMainHandStack();
            boolean holdingModItem = stack.isOf(PortalGravityMod.PORTAL_GUN)
                    || stack.isOf(PortalGravityMod.GRAVITY_GUN)
                    || stack.isOf(PortalGravityMod.SINGULARITY_ORB);

            if (!holdingModItem) {
                wasAttackPressed = client.options.attackKey.isPressed();
                return;
            }

            boolean isPressed = client.options.attackKey.isPressed();
            boolean clicked = false;
            while (client.options.attackKey.wasPressed()) {
                clicked = true;
            }
            if (isPressed && !wasAttackPressed) {
                clicked = true;
            }
            wasAttackPressed = isPressed;

            if (clicked && leftClickCooldown <= 0) {
                ClientPlayNetworking.send(PortalGravityMod.LEFT_CLICK_ACTION_PACKET, PacketByteBufs.empty());
                client.player.swingHand(Hand.MAIN_HAND);
                leftClickCooldown = 4;
            }
        });

        // 2. Recibir estado sincronizado para renderizar efectos 3D puros (sin partículas)
        ClientPlayNetworking.registerGlobalReceiver(PortalGravityMod.SYNC_FX_PACKET, (client, handler, buf, responseSender) -> {
            int portalCount = buf.readInt();
            List<ClientPortalFx> portals = new ArrayList<>(portalCount);
            for (int i = 0; i < portalCount; i++) {
                ClientPortalFx p = new ClientPortalFx();
                p.isOrange = buf.readBoolean();
                p.isLinked = buf.readBoolean();
                p.origin = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                p.axisW = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                p.axisH = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                p.normal = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                p.width = buf.readDouble();
                p.height = buf.readDouble();
                portals.add(p);
            }

            int beamCount = buf.readInt();
            List<ClientGravityBeamFx> beams = new ArrayList<>(beamCount);
            for (int i = 0; i < beamCount; i++) {
                ClientGravityBeamFx b = new ClientGravityBeamFx();
                b.start = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                b.end = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                b.rotY = buf.readFloat();
                beams.add(b);
            }

            int shotCount = buf.readInt();
            List<ClientShotFx> shots = new ArrayList<>(shotCount);
            for (int i = 0; i < shotCount; i++) {
                ClientShotFx s = new ClientShotFx();
                s.isOrange = buf.readBoolean();
                s.start = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                s.end = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                shots.add(s);
            }

            int vortexCount = buf.readInt();
            List<ClientVortexFx> vortices = new ArrayList<>(vortexCount);
            for (int i = 0; i < vortexCount; i++) {
                ClientVortexFx v = new ClientVortexFx();
                v.pos = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                v.ticksRemaining = buf.readInt();
                vortices.add(v);
            }

            client.execute(() -> {
                ACTIVE_PORTALS = portals;
                ACTIVE_BEAMS = beams;
                ACTIVE_SHOTS = shots;
                ACTIVE_VORTICES = vortices;
            });
        });

        // 3. Renderizado 3D volumétrico (Óvalos mágicos de portales, rayos de plasma de Gravity Gun y Singularidades)
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderAllMagicalFx);
    }

    private void renderAllMagicalFx(WorldRenderContext context) {
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) return;

        Vec3d cam = context.camera().getPos();
        double time = (System.currentTimeMillis() % 1000000L) / 1000.0;

        VertexConsumer vc = consumers.getBuffer(RenderLayer.getLightning());
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        // A. Renderizar los Óvalos Mágicos 3D de cada Portal
        for (ClientPortalFx p : ACTIVE_PORTALS) {
            renderMagicalPortalOval(vc, mat, p, time);
        }

        // B. Renderizar los Rayos de Plasma Mágico-Tecnológico de la Gravity Gun y la jaula de energía
        for (ClientGravityBeamFx b : ACTIVE_BEAMS) {
            renderGravityPlasmaBeam(vc, mat, b, time);
        }

        // C. Renderizar haces de disparo de la Portal Gun
        for (ClientShotFx s : ACTIVE_SHOTS) {
            int r = s.isOrange ? 255 : 30;
            int g = s.isOrange ? 140 : 185;
            int bl = s.isOrange ? 20 : 255;
            draw3DRibbonSegment(vc, mat, s.start, s.end, 0.055, r, g, bl, 220);
            draw3DRibbonSegment(vc, mat, s.start, s.end, 0.022, 255, 255, 255, 255);
        }

        // D. Renderizar Vórtices 3D del Orbe de Singularidad
        for (ClientVortexFx v : ACTIVE_VORTICES) {
            renderSingularityVortex(vc, mat, v, time);
        }

        matrices.pop();
    }

    /**
     * Dibuja un contorno ovalado 3D mágico, continuo y contundente (64 segmentos)
     * que encaja exactamente con el borde elíptico de Immersive Portals.
     */
    private void renderMagicalPortalOval(VertexConsumer vc, Matrix4f mat, ClientPortalFx p, double time) {
        int segments = 64;
        double rx = p.width * 0.5;
        double ry = p.height * 0.5;

        int coreR = p.isOrange ? 255 : 180;
        int coreG = p.isOrange ? 240 : 245;
        int coreB = p.isOrange ? 190 : 255;

        int mainR = p.isOrange ? 255 : 15;
        int mainG = p.isOrange ? 115 : 145;
        int mainB = p.isOrange ? 10 : 255;

        int outerR = p.isOrange ? 220 : 10;
        int outerG = p.isOrange ? 45 : 65;
        int outerB = p.isOrange ? 5 : 220;

        Vec3d nOffset = p.normal.multiply(0.015);
        Vec3d nOuter = p.normal.multiply(0.065);

        // Si el segundo portal aún no fue disparado, rellenar el interior con una membrana de vórtice mágico 3D
        if (!p.isLinked) {
            Vec3d center = p.origin.add(nOffset);
            for (int i = 0; i < segments; i++) {
                double a1 = (2.0 * Math.PI * i) / segments;
                double a2 = (2.0 * Math.PI * (i + 1)) / segments;
                Vec3d p1 = center.add(p.axisW.multiply(Math.cos(a1) * rx)).add(p.axisH.multiply(Math.sin(a1) * ry));
                Vec3d p2 = center.add(p.axisW.multiply(Math.cos(a2) * rx)).add(p.axisH.multiply(Math.sin(a2) * ry));
                emitQuad(vc, mat, center, center, p1, p2, mainR, mainG, mainB, 115);
            }
        }

        // Borde sólido 3D multicapa (Núcleo brillante + Halo mágico ondulante + Cinta volumétrica 3D)
        for (int i = 0; i < segments; i++) {
            double a1 = (2.0 * Math.PI * i) / segments;
            double a2 = (2.0 * Math.PI * (i + 1)) / segments;

            double wave1 = 1.0 + 0.045 * Math.sin(time * 5.5 + a1 * 6.0);
            double wave2 = 1.0 + 0.045 * Math.sin(time * 5.5 + a2 * 6.0);

            double c1 = Math.cos(a1), s1 = Math.sin(a1);
            double c2 = Math.cos(a2), s2 = Math.sin(a2);

            // Borde interior exacto del portal (oculta la transición con el bloque)
            Vec3d in1 = p.origin.add(p.axisW.multiply(c1 * (rx - 0.04))).add(p.axisH.multiply(s1 * (ry - 0.04))).add(nOffset);
            Vec3d in2 = p.origin.add(p.axisW.multiply(c2 * (rx - 0.04))).add(p.axisH.multiply(s2 * (ry - 0.04))).add(nOffset);

            // Anillo central incandescente
            Vec3d mid1 = p.origin.add(p.axisW.multiply(c1 * (rx + 0.05))).add(p.axisH.multiply(s1 * (ry + 0.05))).add(nOuter);
            Vec3d mid2 = p.origin.add(p.axisW.multiply(c2 * (rx + 0.05))).add(p.axisH.multiply(s2 * (ry + 0.05))).add(nOuter);

            // Corona exterior mágica con pulso energético
            Vec3d out1 = p.origin.add(p.axisW.multiply(c1 * (rx + 0.19) * wave1)).add(p.axisH.multiply(s1 * (ry + 0.19) * wave1)).add(nOffset);
            Vec3d out2 = p.origin.add(p.axisW.multiply(c2 * (rx + 0.19) * wave2)).add(p.axisH.multiply(s2 * (ry + 0.19) * wave2)).add(nOffset);

            emitQuad(vc, mat, in1, in2, mid2, mid1, coreR, coreG, coreB, 245);
            emitQuad(vc, mat, mid1, mid2, out2, out1, mainR, mainG, mainB, 210);
        }

        // 2 Filamentos espiralados de energía arcana que orbitan el óvalo en 3D
        for (int strand = 0; strand < 2; strand++) {
            double phase = strand * Math.PI + time * 3.8;
            for (int i = 0; i < segments; i++) {
                double a1 = (2.0 * Math.PI * i) / segments;
                double a2 = (2.0 * Math.PI * (i + 1)) / segments;

                double rOff1 = 0.06 * Math.sin(a1 * 4.0 + phase);
                double rOff2 = 0.06 * Math.sin(a2 * 4.0 + phase);
                double zOff1 = 0.04 + 0.035 * Math.cos(a1 * 4.0 + phase);
                double zOff2 = 0.04 + 0.035 * Math.cos(a2 * 4.0 + phase);

                Vec3d s1 = p.origin.add(p.axisW.multiply(Math.cos(a1) * (rx + 0.06 + rOff1)))
                        .add(p.axisH.multiply(Math.sin(a1) * (ry + 0.06 + rOff1)))
                        .add(p.normal.multiply(zOff1));
                Vec3d s2 = p.origin.add(p.axisW.multiply(Math.cos(a2) * (rx + 0.06 + rOff2)))
                        .add(p.axisH.multiply(Math.sin(a2) * (ry + 0.06 + rOff2)))
                        .add(p.normal.multiply(zOff2));

                draw3DRibbonSegment(vc, mat, s1, s2, 0.028, outerR, outerG, outerB, 200);
            }
        }
    }

    /**
     * Dibuja 3 filamentos de plasma tipo rayo que salen del cañón de la Gravity Gun
     * y envuelven las esquinas en rotación del bloque o entidad sostenida.
     */
    private void renderGravityPlasmaBeam(VertexConsumer vc, Matrix4f mat, ClientGravityBeamFx b, double time) {
        double rotRad = Math.toRadians(b.rotY);
        double radius = 0.56;

        // 4 esquinas rotatorias alrededor del objeto agarrado
        Vec3d[] corners = new Vec3d[4];
        for (int c = 0; c < 4; c++) {
            double ang = rotRad + c * (Math.PI * 0.5);
            double dy = (c % 2 == 0) ? 0.32 : -0.32;
            corners[c] = b.end.add(Math.cos(ang) * radius, dy, Math.sin(ang) * radius);
        }

        // Jaula energética que envuelve al objeto en rotación
        for (int c = 0; c < 4; c++) {
            Vec3d c1 = corners[c];
            Vec3d c2 = corners[(c + 1) % 4];
            draw3DRibbonSegment(vc, mat, c1, c2, 0.032, 255, 165, 30, 210);
            draw3DRibbonSegment(vc, mat, c1, c2, 0.014, 255, 245, 190, 250);
        }

        // 3 Rayos de plasma sinuosos desde la pistola hasta las esquinas en rotación del objeto
        Vec3d dir = b.end.subtract(b.start);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d side = dir.crossProduct(up).normalize();
        if (side.lengthSquared() < 0.01) side = new Vec3d(1, 0, 0);
        Vec3d orthoUp = side.crossProduct(dir.normalize()).normalize();

        for (int branch = 0; branch < 3; branch++) {
            Vec3d targetCorner = corners[branch];
            int steps = 12;
            Vec3d prev = b.start;
            double branchPhase = branch * 2.1 + time * 11.0;

            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                Vec3d basePoint = b.start.lerp(targetCorner, t);
                double envelope = Math.sin(t * Math.PI); // 0 en los extremos, máximo en el medio

                double waveA = Math.sin(t * 9.0 - branchPhase) * 0.22 * envelope;
                double waveB = Math.cos(t * 7.5 + branchPhase * 1.3) * 0.22 * envelope;

                Vec3d curr = basePoint.add(side.multiply(waveA)).add(orthoUp.multiply(waveB));

                // Halo exterior ámbar/violeta + núcleo eléctrico blanco-dorado
                if (branch == 2) {
                    draw3DRibbonSegment(vc, mat, prev, curr, 0.042, 165, 80, 255, 195);
                } else {
                    draw3DRibbonSegment(vc, mat, prev, curr, 0.045, 255, 145, 20, 215);
                }
                draw3DRibbonSegment(vc, mat, prev, curr, 0.018, 255, 245, 200, 255);
                prev = curr;
            }
        }
    }

    private void renderSingularityVortex(VertexConsumer vc, Matrix4f mat, ClientVortexFx v, double time) {
        int segs = 32;
        for (int ring = 0; ring < 3; ring++) {
            double radius = 0.7 + ring * 0.55;
            double tilt = ring * 0.55 + time * 2.5;
            Vec3d axisA = new Vec3d(Math.cos(tilt), Math.sin(tilt) * 0.4, Math.sin(tilt)).normalize();
            Vec3d axisB = new Vec3d(-Math.sin(tilt), Math.cos(tilt), 0.3).normalize();

            for (int i = 0; i < segs; i++) {
                double a1 = (2.0 * Math.PI * i) / segs + time * 6.0;
                double a2 = (2.0 * Math.PI * (i + 1)) / segs + time * 6.0;
                Vec3d p1 = v.pos.add(axisA.multiply(Math.cos(a1) * radius)).add(axisB.multiply(Math.sin(a1) * radius));
                Vec3d p2 = v.pos.add(axisA.multiply(Math.cos(a2) * radius)).add(axisB.multiply(Math.sin(a2) * radius));
                draw3DRibbonSegment(vc, mat, p1, p2, 0.05, 150, 50, 255, 220);
                draw3DRibbonSegment(vc, mat, p1, p2, 0.02, 120, 235, 255, 255);
            }
        }
    }

    private void draw3DRibbonSegment(VertexConsumer vc, Matrix4f mat, Vec3d p1, Vec3d p2, double halfThickness,
                                     int r, int g, int b, int a) {
        Vec3d d = p2.subtract(p1);
        if (d.lengthSquared() < 1e-6) return;
        Vec3d off1 = new Vec3d(-d.z, d.y, d.x).normalize().multiply(halfThickness);
        Vec3d off2 = new Vec3d(0, halfThickness, 0);

        emitQuad(vc, mat, p1.subtract(off1), p1.add(off1), p2.add(off1), p2.subtract(off1), r, g, b, a);
        emitQuad(vc, mat, p1.subtract(off2), p1.add(off2), p2.add(off2), p2.subtract(off2), r, g, b, a);
    }

    private void emitQuad(VertexConsumer vc, Matrix4f mat, Vec3d v1, Vec3d v2, Vec3d v3, Vec3d v4,
                          int r, int g, int b, int a) {
        vc.vertex(mat, (float) v1.x, (float) v1.y, (float) v1.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v2.x, (float) v2.y, (float) v2.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v3.x, (float) v3.y, (float) v3.z).color(r, g, b, a).next();
        vc.vertex(mat, (float) v4.x, (float) v4.y, (float) v4.z).color(r, g, b, a).next();
    }
}
