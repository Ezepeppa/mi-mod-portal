package com.argon.portalgravity;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PortalGravityClient implements ClientModInitializer {
    private boolean wasAttackPressed = false;
    private int leftClickCooldown = 0;
    private int lockedHotbarSlot = -1;
    private static volatile boolean INTERCEPT_SCROLL = false;
    private static volatile double CLIENT_HOLD_DISTANCE = 3.5;

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
        public int holderId;
        public int heldEntityId;
        public boolean isBlockDisplay;
        public int orbitSlot;
        public Vec3d start;
        public Vec3d end;
        public float rotY;
    }

    public static class ClientShotFx {
        public int colorType;
        public Vec3d start;
        public Vec3d end;
    }

    public static class ClientVortexFx {
        public Vec3d pos;
        public int ticksRemaining;
    }

    public static class ClientArchitectPreview {
        public boolean active;
        public int mode;
        public BlockPos minPos;
        public BlockPos maxPos;
    }

    private static volatile List<ClientPortalFx> ACTIVE_PORTALS = new ArrayList<>();
    private static volatile List<ClientGravityBeamFx> ACTIVE_BEAMS = new ArrayList<>();
    private static volatile List<ClientShotFx> ACTIVE_SHOTS = new ArrayList<>();
    private static volatile List<ClientVortexFx> ACTIVE_VORTICES = new ArrayList<>();
    private static volatile ClientArchitectPreview ARCHITECT_PREVIEW = new ClientArchitectPreview();

    // Estado de suavizado a 144+ FPS en el cliente para los objetos agarrados
    private static final Map<Integer, Vec3d> SMOOTH_ENTITY_POS = new HashMap<>();
    private long lastFrameNano = System.nanoTime();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (leftClickCooldown > 0) {
                leftClickCooldown--;
            }
            if (client.player == null || client.world == null || client.currentScreen != null) {
                wasAttackPressed = false;
                lockedHotbarSlot = -1;
                return;
            }

            // Interceptar el giro de la ruedita del mouse cuando se sostiene un objeto o un holograma de construcción
            if (INTERCEPT_SCROLL && PortalGravityMod.isModWeapon(client.player.getMainHandStack())) {
                int currentSlot = client.player.getInventory().selectedSlot;
                boolean numberKeyPressed = false;
                for (KeyBinding kb : client.options.hotbarKeys) {
                    if (kb.isPressed()) {
                        numberKeyPressed = true;
                        break;
                    }
                }
                if (lockedHotbarSlot == -1 || numberKeyPressed) {
                    lockedHotbarSlot = currentSlot;
                } else if (currentSlot != lockedHotbarSlot) {
                    int diff = lockedHotbarSlot - currentSlot;
                    if (diff > 4) diff -= 9;
                    if (diff < -4) diff += 9;
                    int scrollDir = diff > 0 ? 1 : -1;

                    // Restaurar el slot instantáneamente para que el arma no cambie y enviar ajuste de zoom/altura
                    client.player.getInventory().selectedSlot = lockedHotbarSlot;
                    CLIENT_HOLD_DISTANCE = Math.max(1.8, Math.min(13.0, CLIENT_HOLD_DISTANCE + scrollDir * 0.65));

                    PacketByteBuf scrollBuf = PacketByteBufs.create();
                    scrollBuf.writeInt(scrollDir);
                    ClientPlayNetworking.send(PortalGravityMod.SCROLL_ADJUST_PACKET, scrollBuf);
                }
            } else {
                lockedHotbarSlot = client.player.getInventory().selectedSlot;
            }

            ItemStack stack = client.player.getMainHandStack();
            if (!PortalGravityMod.isModWeapon(stack)) {
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

        ClientPlayNetworking.registerGlobalReceiver(PortalGravityMod.SYNC_FX_PACKET, (client, handler, buf, responseSender) -> {
            boolean intercept = buf.readBoolean();
            double holdDist = buf.readDouble();

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
                b.holderId = buf.readInt();
                b.heldEntityId = buf.readInt();
                b.isBlockDisplay = buf.readBoolean();
                b.orbitSlot = buf.readInt();
                b.start = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                b.end = new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                b.rotY = buf.readFloat();
                beams.add(b);
            }

            int shotCount = buf.readInt();
            List<ClientShotFx> shots = new ArrayList<>(shotCount);
            for (int i = 0; i < shotCount; i++) {
                ClientShotFx s = new ClientShotFx();
                s.colorType = buf.readInt();
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

            ClientArchitectPreview preview = new ClientArchitectPreview();
            preview.active = buf.readBoolean();
            if (preview.active) {
                preview.mode = buf.readInt();
                preview.minPos = buf.readBlockPos();
                preview.maxPos = buf.readBlockPos();
            }

            client.execute(() -> {
                INTERCEPT_SCROLL = intercept;
                CLIENT_HOLD_DISTANCE = holdDist;
                ACTIVE_PORTALS = portals;
                ACTIVE_BEAMS = beams;
                ACTIVE_SHOTS = shots;
                ACTIVE_VORTICES = vortices;
                ARCHITECT_PREVIEW = preview;
            });
        });

        // Actualizar la posición del objeto agarrado en CADA CUADRO DE RENDERIZADO (144+ FPS) antes de dibujar entidades
        WorldRenderEvents.START.register(this::updateSmoothHeldEntitiesPerFrame);
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderAllMagicalFx);
    }

    /**
     * Se ejecuta antes de renderizar entidades en cada cuadro (60 a 240 FPS) para que el bloque o mob
     * agarrado con la Gravity Gun se mueva con fluidez absoluta siguiendo la cámara y respetando el suelo.
     */
    private void updateSmoothHeldEntitiesPerFrame(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        long now = System.nanoTime();
        double dt = Math.min(0.05, Math.max(0.001, (now - lastFrameNano) / 1_000_000_000.0));
        lastFrameNano = now;

        float tickDelta = context.tickDelta();
        double timeSec = (System.currentTimeMillis() % 1000000L) / 1000.0;

        for (ClientGravityBeamFx b : ACTIVE_BEAMS) {
            if (b.holderId == client.player.getId() && b.orbitSlot < 0) {
                Entity held = client.world.getEntityById(b.heldEntityId);
                if (held == null) continue;

                Vec3d eye = client.player.getCameraPosVec(tickDelta);
                Vec3d look = client.player.getRotationVec(tickDelta);
                Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();

                // Posición de la boca del cañón actualizada a 144 FPS
                b.start = eye.add(look.multiply(0.65)).add(right.multiply(0.32)).subtract(0, 0.22, 0);

                Vec3d rawEnd = eye.add(look.multiply(CLIENT_HOLD_DISTANCE));
                BlockHitResult obstacleHit = client.world.raycast(new RaycastContext(
                        eye, rawEnd, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player
                ));
                Vec3d clampedTarget = rawEnd;
                if (obstacleHit.getType() == HitResult.Type.BLOCK) {
                    Vec3d hitNormal = Vec3d.of(obstacleHit.getSide().getVector());
                    clampedTarget = obstacleHit.getPos().add(hitNormal.multiply(0.55));
                }

                double bobY = Math.sin(timeSec * 2.2) * 0.10 - 0.05;
                double swayX = Math.cos(timeSec * 1.6) * 0.07;
                Vec3d desired = clampedTarget.add(right.multiply(swayX))
                        .add(0, bobY - (b.isBlockDisplay ? 0.0 : held.getHeight() * 0.4), 0);

                Vec3d prevSmooth = SMOOTH_ENTITY_POS.getOrDefault(b.heldEntityId, held.getPos());
                double blend = 1.0 - Math.exp(-16.0 * dt); // Suavizado exponencial independiente de los FPS con sensación de peso
                Vec3d step = desired.subtract(prevSmooth).multiply(blend);

                boolean[] col = new boolean[3];
                Vec3d safeStep = GravityGunManager.resolveBoxCollision(client.world, prevSmooth, step, 0.48, 0.48, col);
                Vec3d newPos = prevSmooth.add(safeStep);
                SMOOTH_ENTITY_POS.put(b.heldEntityId, newPos);

                held.setPosition(newPos.x, newPos.y, newPos.z);
                held.lastRenderX = newPos.x;
                held.lastRenderY = newPos.y;
                held.lastRenderZ = newPos.z;
                held.prevX = newPos.x;
                held.prevY = newPos.y;
                held.prevZ = newPos.z;

                b.end = b.isBlockDisplay ? newPos : newPos.add(0, held.getHeight() * 0.5, 0);
            }
        }
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

        // A. Óvalos Mágicos 3D contundentes de los Portales
        for (ClientPortalFx p : ACTIVE_PORTALS) {
            renderMagicalPortalOval(vc, mat, p, time);
        }

        // B. Rayos de plasma 3D de la Gravity Gun
        for (ClientGravityBeamFx b : ACTIVE_BEAMS) {
            renderGravityPlasmaBeam(vc, mat, b, time);
        }

        // C. Haces de disparo (Azul, Naranja o Esmeralda)
        for (ClientShotFx s : ACTIVE_SHOTS) {
            int r = (s.colorType == 1) ? 255 : (s.colorType == 2 ? 20 : 30);
            int g = (s.colorType == 1) ? 140 : (s.colorType == 2 ? 245 : 185);
            int bl = (s.colorType == 1) ? 20 : (s.colorType == 2 ? 175 : 255);
            draw3DRibbonSegment(vc, mat, s.start, s.end, 0.055, r, g, bl, 225);
            draw3DRibbonSegment(vc, mat, s.start, s.end, 0.022, 255, 255, 255, 255);
        }

        // D. Vórtices del Orbe de Singularidad
        for (ClientVortexFx v : ACTIVE_VORTICES) {
            renderSingularityVortex(vc, mat, v, time);
        }

        // E. Holograma 3D y Rayos Constructores del Cañón Arquitecto
        if (ARCHITECT_PREVIEW.active && ARCHITECT_PREVIEW.minPos != null && ARCHITECT_PREVIEW.maxPos != null) {
            renderArchitectHologramAndBeams(vc, mat, context.tickDelta(), ARCHITECT_PREVIEW, time);
        }

        matrices.pop();
    }

    /**
     * Dibuja un marco toroidal 3D grueso, mágico y luminoso que sobresale de la pared sin cortarse jamás
     * y bordea con precisión el corte elíptico de Immersive Portals.
     */
    private void renderMagicalPortalOval(VertexConsumer vc, Matrix4f mat, ClientPortalFx p, double time) {
        int segments = 64;
        double rx = p.width * 0.5;
        double ry = p.height * 0.5;

        int coreR = p.isOrange ? 255 : 195;
        int coreG = p.isOrange ? 245 : 250;
        int coreB = p.isOrange ? 200 : 255;

        int mainR = p.isOrange ? 255 : 20;
        int mainG = p.isOrange ? 125 : 155;
        int mainB = p.isOrange ? 15 : 255;

        int outerR = p.isOrange ? 235 : 35;
        int outerG = p.isOrange ? 55 : 75;
        int outerB = p.isOrange ? 10 : 235;

        Vec3d nIn = p.normal.multiply(0.03);
        Vec3d nCrest = p.normal.multiply(0.11);
        Vec3d nOut = p.normal.multiply(0.04);

        // Si el segundo portal aún no está colocado, dibujar un vórtice de anillos arcanos en espiral
        if (!p.isLinked) {
            Vec3d center = p.origin.add(nIn);
            for (int ring = 1; ring <= 5; ring++) {
                double f1 = (ring - 1) / 5.0;
                double f2 = ring / 5.0;
                double spin = time * (ring % 2 == 0 ? 3.2 : -3.2);
                int alpha = 70 + ring * 25;
                for (int i = 0; i < segments; i += 2) {
                    double a1 = (2.0 * Math.PI * i) / segments + spin;
                    double a2 = (2.0 * Math.PI * (i + 1)) / segments + spin;
                    Vec3d r1a = center.add(p.axisW.multiply(Math.cos(a1) * rx * f1)).add(p.axisH.multiply(Math.sin(a1) * ry * f1));
                    Vec3d r1b = center.add(p.axisW.multiply(Math.cos(a2) * rx * f1)).add(p.axisH.multiply(Math.sin(a2) * ry * f1));
                    Vec3d r2b = center.add(p.axisW.multiply(Math.cos(a2) * rx * f2)).add(p.axisH.multiply(Math.sin(a2) * ry * f2));
                    Vec3d r2a = center.add(p.axisW.multiply(Math.cos(a1) * rx * f2)).add(p.axisH.multiply(Math.sin(a1) * ry * f2));
                    emitQuad(vc, mat, r1a, r1b, r2b, r2a, mainR, mainG, mainB, alpha);
                }
            }
        }

        // Aro 3D volumétrico grueso (Bisel interior + Cresta 3D sobresaliente + Corona exterior ondulante)
        for (int i = 0; i < segments; i++) {
            double a1 = (2.0 * Math.PI * i) / segments;
            double a2 = (2.0 * Math.PI * (i + 1)) / segments;

            double wave1 = 1.0 + 0.055 * Math.sin(time * 6.0 + a1 * 8.0);
            double wave2 = 1.0 + 0.055 * Math.sin(time * 6.0 + a2 * 8.0);

            double c1 = Math.cos(a1), s1 = Math.sin(a1);
            double c2 = Math.cos(a2), s2 = Math.sin(a2);

            Vec3d in1 = p.origin.add(p.axisW.multiply(c1 * (rx - 0.05))).add(p.axisH.multiply(s1 * (ry - 0.05))).add(nIn);
            Vec3d in2 = p.origin.add(p.axisW.multiply(c2 * (rx - 0.05))).add(p.axisH.multiply(s2 * (ry - 0.05))).add(nIn);

            Vec3d crest1 = p.origin.add(p.axisW.multiply(c1 * (rx + 0.06))).add(p.axisH.multiply(s1 * (ry + 0.06))).add(nCrest);
            Vec3d crest2 = p.origin.add(p.axisW.multiply(c2 * (rx + 0.06))).add(p.axisH.multiply(s2 * (ry + 0.06))).add(nCrest);

            Vec3d mid1 = p.origin.add(p.axisW.multiply(c1 * (rx + 0.16))).add(p.axisH.multiply(s1 * (ry + 0.16))).add(nCrest);
            Vec3d mid2 = p.origin.add(p.axisW.multiply(c2 * (rx + 0.16))).add(p.axisH.multiply(s2 * (ry + 0.16))).add(nCrest);

            Vec3d out1 = p.origin.add(p.axisW.multiply(c1 * (rx + 0.28) * wave1)).add(p.axisH.multiply(s1 * (ry + 0.28) * wave1)).add(nOut);
            Vec3d out2 = p.origin.add(p.axisW.multiply(c2 * (rx + 0.28) * wave2)).add(p.axisH.multiply(s2 * (ry + 0.28) * wave2)).add(nOut);

            // Cara biselada interna brillante (une el corte del portal con la cresta 3D)
            emitQuad(vc, mat, in1, in2, crest2, crest1, coreR, coreG, coreB, 255);
            // Cara frontal gruesa del anillo mágico
            emitQuad(vc, mat, crest1, crest2, mid2, mid1, mainR, mainG, mainB, 240);
            // Corona exterior de energía arcana
            emitQuad(vc, mat, mid1, mid2, out2, out1, outerR, outerG, outerB, 200);
        }

        // 12 Nodos / Runas de contención energética que orbitan lentamente el marco del portal
        int runes = 12;
        for (int r = 0; r < runes; r++) {
            double ang = (2.0 * Math.PI * r) / runes + time * 0.85;
            double angNext = ang + 0.12;
            Vec3d rIn1 = p.origin.add(p.axisW.multiply(Math.cos(ang) * (rx + 0.03))).add(p.axisH.multiply(Math.sin(ang) * (ry + 0.03))).add(p.normal.multiply(0.14));
            Vec3d rIn2 = p.origin.add(p.axisW.multiply(Math.cos(angNext) * (rx + 0.03))).add(p.axisH.multiply(Math.sin(angNext) * (ry + 0.03))).add(p.normal.multiply(0.14));
            Vec3d rOut2 = p.origin.add(p.axisW.multiply(Math.cos(angNext) * (rx + 0.24))).add(p.axisH.multiply(Math.sin(angNext) * (ry + 0.24))).add(p.normal.multiply(0.14));
            Vec3d rOut1 = p.origin.add(p.axisW.multiply(Math.cos(ang) * (rx + 0.24))).add(p.axisH.multiply(Math.sin(ang) * (ry + 0.24))).add(p.normal.multiply(0.14));
            emitQuad(vc, mat, rIn1, rIn2, rOut2, rOut1, coreR, coreG, coreB, 245);
        }
    }

    private void renderGravityPlasmaBeam(VertexConsumer vc, Matrix4f mat, ClientGravityBeamFx b, double time) {
        double rotRad = Math.toRadians(b.rotY);
        double radius = 0.56;

        Vec3d[] corners = new Vec3d[4];
        for (int c = 0; c < 4; c++) {
            double ang = rotRad + c * (Math.PI * 0.5);
            double dy = (c % 2 == 0) ? 0.32 : -0.32;
            corners[c] = b.end.add(Math.cos(ang) * radius, dy, Math.sin(ang) * radius);
        }

        for (int c = 0; c < 4; c++) {
            Vec3d c1 = corners[c];
            Vec3d c2 = corners[(c + 1) % 4];
            draw3DRibbonSegment(vc, mat, c1, c2, 0.032, 255, 165, 30, 210);
            draw3DRibbonSegment(vc, mat, c1, c2, 0.014, 255, 245, 190, 250);
        }

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
                double envelope = Math.sin(t * Math.PI);

                double waveA = Math.sin(t * 9.0 - branchPhase) * 0.22 * envelope;
                double waveB = Math.cos(t * 7.5 + branchPhase * 1.3) * 0.22 * envelope;

                Vec3d curr = basePoint.add(side.multiply(waveA)).add(orthoUp.multiply(waveB));

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

    /**
     * Dibuja en 3D la grilla holográfica de todos los bloques que se van a construir o mover
     * con el Cañón Arquitecto y los rayos de plasma que salen desde la boca de la pistola hacia el holograma.
     */
    private void renderArchitectHologramAndBeams(VertexConsumer vc, Matrix4f mat, float tickDelta,
                                                 ClientArchitectPreview preview, double time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Vec3d eye = client.player.getCameraPosVec(tickDelta);
        Vec3d look = client.player.getRotationVec(tickDelta);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d gunMuzzle = eye.add(look.multiply(0.65)).add(right.multiply(0.32)).subtract(0, 0.22, 0);

        int minX = Math.min(preview.minPos.getX(), preview.maxPos.getX());
        int maxX = Math.max(preview.minPos.getX(), preview.maxPos.getX()) + 1;
        int minY = Math.min(preview.minPos.getY(), preview.maxPos.getY());
        int maxY = Math.max(preview.minPos.getY(), preview.maxPos.getY()) + 1;
        int minZ = Math.min(preview.minPos.getZ(), preview.maxPos.getZ());
        int maxZ = Math.max(preview.minPos.getZ(), preview.maxPos.getZ()) + 1;

        Box outerBox = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        drawGlowingBoxWireframe(vc, mat, outerBox, 0.035, 20, 245, 185, 235);

        // Dibujar cada cubo individual dentro de la selección (hasta 120 cubos para mantener máxima fluidez)
        List<BlockPos> blocks = ArchitectManager.getBoxPositions(preview.minPos, preview.maxPos, 120);
        for (BlockPos bp : blocks) {
            Box b = new Box(bp.getX() + 0.04, bp.getY() + 0.04, bp.getZ() + 0.04,
                            bp.getX() + 0.96, bp.getY() + 0.96, bp.getZ() + 0.96);
            drawGlowingBoxWireframe(vc, mat, b, 0.015, 90, 255, 220, 160);
        }

        // 4 Rayos de plasma esmeralda-cian desde la boca del Cañón Arquitecto hasta las esquinas del holograma
        Vec3d[] targetCorners = new Vec3d[]{
                new Vec3d(minX, minY, minZ),
                new Vec3d(maxX, minY, maxZ),
                new Vec3d(minX, maxY, maxZ),
                new Vec3d(maxX, maxY, minZ)
        };
        for (int i = 0; i < targetCorners.length; i++) {
            Vec3d c = targetCorners[i];
            Vec3d mid = gunMuzzle.lerp(c, 0.5).add(0, Math.sin(time * 8.0 + i) * 0.18, 0);
            draw3DRibbonSegment(vc, mat, gunMuzzle, mid, 0.03, 20, 245, 185, 210);
            draw3DRibbonSegment(vc, mat, mid, c, 0.03, 20, 245, 185, 210);
            draw3DRibbonSegment(vc, mat, gunMuzzle, mid, 0.012, 220, 255, 245, 255);
            draw3DRibbonSegment(vc, mat, mid, c, 0.012, 220, 255, 245, 255);
        }
    }

    private void drawGlowingBoxWireframe(VertexConsumer vc, Matrix4f mat, Box box, double thickness,
                                         int r, int g, int b, int a) {
        Vec3d p000 = new Vec3d(box.minX, box.minY, box.minZ);
        Vec3d p100 = new Vec3d(box.maxX, box.minY, box.minZ);
        Vec3d p101 = new Vec3d(box.maxX, box.minY, box.maxZ);
        Vec3d p001 = new Vec3d(box.minX, box.minY, box.maxZ);

        Vec3d p010 = new Vec3d(box.minX, box.maxY, box.minZ);
        Vec3d p110 = new Vec3d(box.maxX, box.maxY, box.minZ);
        Vec3d p111 = new Vec3d(box.maxX, box.maxY, box.maxZ);
        Vec3d p011 = new Vec3d(box.minX, box.maxY, box.maxZ);

        draw3DRibbonSegment(vc, mat, p000, p100, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p100, p101, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p101, p001, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p001, p000, thickness, r, g, b, a);

        draw3DRibbonSegment(vc, mat, p010, p110, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p110, p111, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p111, p011, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p011, p010, thickness, r, g, b, a);

        draw3DRibbonSegment(vc, mat, p000, p010, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p100, p110, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p101, p111, thickness, r, g, b, a);
        draw3DRibbonSegment(vc, mat, p001, p011, thickness, r, g, b, a);
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
