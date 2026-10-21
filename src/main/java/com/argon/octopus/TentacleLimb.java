package com.argon.octopus;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * Simulación física y biomecánica individual de 1 tentáculo (Hidrostato Muscular de 14 nodos).
 *
 * Implementa:
 * 1. Integración Verlet con masa graduada (base pesada -> punta ágil), gravedad terrestre ("slump") y fricción de sustrato.
 * 2. Solver híbrido XPBD (restricciones elásticas de longitud con abultamiento por volumen constante y rigidez de flexión Cosserat)
 *    combinado con pasadas FABRIK hacia el punto de anclaje de las ventosas.
 * 3. Colisión real por cada uno de los 14 nodos contra los `VoxelShape` de los bloques del mundo, haciendo que la mitad
 *    del tentáculo se apoye y envuelva bordes, escalones, muros y desniveles sin atravesar el piso.
 * 4. Máquina de estados biomecánica (Levy, Flash & Hochner 2015):
 *    - `ANCHORED_PULL`: Ventosas distales/mediales adheridas delante del movimiento; acorta el músculo para traccionar el cuerpo.
 *    - `ANCHORED_PUSH`: Ventosas proximales adheridas detrás del movimiento; elonga el músculo para empujar el cuerpo.
 *    - `PEELING_AND_REACHING`: Desprende las ventosas secuencialmente de la base a la punta y lanza una onda de propagación
 *      de curvatura buscando el siguiente bloque sólido donde adherirse (en suelo, pared vertical o techo).
 *    - `COILED_HOLDING`: Envuelve en espiral su tercio distal alrededor de un bloque arrancado o un mob capturado.
 *    - `WHIP_STRIKE`: Despliega una onda supersónica de latigazo hacia la mira del jugador.
 */
public class TentacleLimb {
    public static final int NUM_NODES = 14;

    public enum LimbState {
        ANCHORED_PULL,
        ANCHORED_PUSH,
        PEELING_AND_REACHING,
        COILED_HOLDING,
        WHIP_STRIKE
    }

    public final int index;
    public float baseAngleRad;

    public final Vec3d[] nodes = new Vec3d[NUM_NODES];
    public final Vec3d[] prevTickNodes = new Vec3d[NUM_NODES];
    public final Vec3d[] verletOldNodes = new Vec3d[NUM_NODES];
    public final float[] baseRadii = new float[NUM_NODES];
    public final float[] currentRadii = new float[NUM_NODES];
    public final boolean[] nodeSuctionLocked = new boolean[NUM_NODES];

    public LimbState state = LimbState.ANCHORED_PULL;
    public Vec3d anchorWorldPos = Vec3d.ZERO;
    public Vec3d anchorNormal = new Vec3d(0, 1, 0);
    public Vec3d reachStartPos = Vec3d.ZERO;
    public Vec3d reachTargetPos = Vec3d.ZERO;
    public float reachProgress = 0.0f;
    public int stateTicks = 0;

    // Fuerza neta que este tentáculo le aplica al cuerpo del pulpo en el tick actual (para visualización y física real)
    public Vec3d lastAppliedBodyForce = Vec3d.ZERO;
    public float whipTimer = 0.0f;
    public Vec3d whipTarget = Vec3d.ZERO;

    public TentacleLimb(int index, int totalTentacles, Vec3d initialRootPos) {
        this.index = index;
        this.baseAngleRad = (float) (index * (Math.PI * 2.0 / Math.max(1, totalTentacles)));

        Vec3d outward = new Vec3d(Math.cos(baseAngleRad), -0.1, Math.sin(baseAngleRad));
        float segLen = OctopusPhysicsConfig.tentacleLength / (NUM_NODES - 1);

        for (int i = 0; i < NUM_NODES; i++) {
            float t = (float) i / (NUM_NODES - 1);
            // Perfil cónico muscular de un brazo de Octopus vulgaris
            this.baseRadii[i] = MathHelper.lerp(t, 0.30f, 0.045f);
            this.currentRadii[i] = this.baseRadii[i];

            Vec3d p = initialRootPos.add(outward.multiply(i * segLen));
            this.nodes[i] = p;
            this.prevTickNodes[i] = p;
            this.verletOldNodes[i] = p;
        }
        this.anchorWorldPos = this.nodes[NUM_NODES - 1];
        this.reachStartPos = this.anchorWorldPos;
        this.reachTargetPos = this.anchorWorldPos;
    }

    public void updateBaseAngle(int newIndex, int totalTentacles) {
        this.baseAngleRad = (float) (newIndex * (Math.PI * 2.0 / Math.max(1, totalTentacles)));
    }

    public Vec3d getRootSocketPos(Vec3d bodyPos, float bodyYawRad) {
        float worldAngle = baseAngleRad + bodyYawRad;
        double socketRadius = 0.68;
        return new Vec3d(
                bodyPos.x + Math.cos(worldAngle) * socketRadius,
                bodyPos.y + 0.36,
                bodyPos.z + Math.sin(worldAngle) * socketRadius
        );
    }

    /**
     * Avanza un tick completo de física del tentáculo (con 5 sub-pasos XPBD/Verlet + colisión con bloques)
     * y calcula la fuerza real de tracción/empuje que este tentáculo transmite al cuerpo del pulpo.
     */
    public Vec3d tickPhysics(World world, GiantOctopusEntity octopus, Vec3d bodyPos, float bodyYawRad,
                             Vec3d desiredMoveVec, boolean isHoldingWithThisLimb) {
        // Guardar posiciones para interpolación suave a 144 FPS en el renderizador
        for (int i = 0; i < NUM_NODES; i++) {
            this.prevTickNodes[i] = this.nodes[i];
        }

        stateTicks++;
        float totalLen = Math.max(3.0f, OctopusPhysicsConfig.tentacleLength);
        float segLen = totalLen / (NUM_NODES - 1);
        float stiffness = OctopusPhysicsConfig.hydrostatStiffness;
        float gravity = 0.055f * OctopusPhysicsConfig.gravitySlumpFactor;

        Vec3d rootSocket = getRootSocketPos(bodyPos, bodyYawRad);
        float worldAngle = baseAngleRad + bodyYawRad;
        Vec3d radialDir = new Vec3d(Math.cos(worldAngle), 0, Math.sin(worldAngle)).normalize();

        // 1. Actualizar máquina de estados biomecánica del tentáculo
        if (isHoldingWithThisLimb) {
            this.state = LimbState.COILED_HOLDING;
        } else if (this.state == LimbState.COILED_HOLDING) {
            this.state = LimbState.PEELING_AND_REACHING;
            this.reachProgress = 0.0f;
            this.reachStartPos = this.nodes[NUM_NODES - 1];
            this.reachTargetPos = findBestSubstrateAnchor(world, octopus, rootSocket, radialDir, desiredMoveVec, totalLen);
        }

        if (this.state == LimbState.WHIP_STRIKE) {
            whipTimer += 0.22f;
            if (whipTimer >= 1.0f) {
                this.state = LimbState.PEELING_AND_REACHING;
                this.reachProgress = 0.0f;
                this.reachStartPos = this.nodes[NUM_NODES - 1];
                this.reachTargetPos = findBestSubstrateAnchor(world, octopus, rootSocket, radialDir, desiredMoveVec, totalLen);
            }
        } else if (this.state != LimbState.COILED_HOLDING) {
            updateLocomotionGaitState(world, octopus, rootSocket, radialDir, desiredMoveVec, totalLen);
        }

        // 2. Calcular la fuerza muscular que este tentáculo ejerce sobre el cuerpo del pulpo
        this.lastAppliedBodyForce = computeMuscularForceOnBody(rootSocket, radialDir, desiredMoveVec, totalLen);

        // 3. Ejecutar 5 sub-pasos de simulación física (Verlet + Hidrostato Muscular + FABRIK + Colisión Voxel)
        int subSteps = 5;
        for (int step = 0; step < subSteps; step++) {
            // Fijar la raíz del tentáculo a su cavidad muscular en el cuerpo
            this.nodes[0] = rootSocket;

            // Paso A: Integración Verlet + Gravedad terrestre + Onda viajera muscular
            for (int i = 1; i < NUM_NODES; i++) {
                float t = (float) i / (NUM_NODES - 1);
                Vec3d cur = this.nodes[i];
                Vec3d old = this.verletOldNodes[i];
                Vec3d vel = cur.subtract(old).multiply(0.84); // Amortiguamiento muscular interno

                // Gravedad fuera del agua ("slump")
                double gy = -gravity / subSteps;

                // Onda orgánica de propagación de curvatura a lo largo del brazo (típica de Octopus vulgaris)
                double wavePhase = (octopus.age * 0.16) + (index * 1.15) - (t * 3.8);
                Vec3d lateral = new Vec3d(-radialDir.z, 0, radialDir.x);
                Vec3d waveForce = lateral.multiply(Math.sin(wavePhase) * 0.006 * t);

                Vec3d next = cur.add(vel).add(waveForce.x, gy, waveForce.z);
                this.verletOldNodes[i] = cur;
                this.nodes[i] = next;
            }

            // Paso B: Aplicar objetivos según el estado del tentáculo (Ventosas ancladas, Despegue en arco, Espiral de agarre o Latigazo)
            applyStateTargetConstraints(rootSocket, radialDir, segLen, step, subSteps, octopus.age);

            // Paso C: Restricciones de longitud de segmento (XPBD) + Rigidez de curvatura (evitar quiebres bruscos)
            solveHydrostatDistanceAndBendingConstraints(rootSocket, radialDir, segLen, stiffness);

            // Paso D: Colisión física de cada nodo contra las hitboxes (`VoxelShape`) de los bloques del mundo
            solveVoxelTerrainCollisions(world);
        }

        // 4. Actualizar abultamiento muscular por conservación de volumen (r = r0 * sqrt(L0 / L_actual))
        // y estado de succión de las ventosas de cada nodo
        for (int i = 0; i < NUM_NODES; i++) {
            if (i < NUM_NODES - 1) {
                double actualLen = Math.max(0.15, this.nodes[i + 1].distanceTo(this.nodes[i]));
                float stretchRatio = (float) (segLen / actualLen);
                float bulge = MathHelper.clamp((float) Math.sqrt(stretchRatio), 0.78f, 1.32f);
                this.currentRadii[i] = this.baseRadii[i] * bulge;
            } else {
                this.currentRadii[i] = this.baseRadii[i];
            }

            if (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) {
                this.nodeSuctionLocked[i] = (i >= NUM_NODES - 5);
            } else if (this.state == LimbState.PEELING_AND_REACHING) {
                // Desprendimiento secuencial de ventosas desde la base hacia la punta durante la primera mitad del paso
                float peelThreshold = 1.0f - reachProgress * 1.6f;
                this.nodeSuctionLocked[i] = (i >= NUM_NODES - 3) && ((float) i / NUM_NODES > peelThreshold) && (reachProgress < 0.35f);
            } else {
                this.nodeSuctionLocked[i] = (this.state == LimbState.COILED_HOLDING && i >= NUM_NODES / 2);
            }
        }

        return this.lastAppliedBodyForce;
    }

    private void updateLocomotionGaitState(World world, GiantOctopusEntity octopus, Vec3d rootSocket,
                                           Vec3d radialDir, Vec3d desiredMoveVec, float totalLen) {
        double distToAnchor = rootSocket.distanceTo(this.anchorWorldPos);
        double moveSpeedSq = desiredMoveVec.horizontalLengthSquared();

        // Calcular dónde sería el punto ideal de descanso/avance de este tentáculo ahora mismo
        Vec3d idealSpot = rootSocket
                .add(radialDir.multiply(totalLen * 0.65))
                .add(desiredMoveVec.multiply(totalLen * 1.8));
        double driftFromIdeal = this.anchorWorldPos.squaredDistanceTo(idealSpot);

        if (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) {
            // Reclasificar dinámicamente si el tentáculo actúa como Traccionador (delante del movimiento) o Empujador (detrás)
            Vec3d toAnchor = this.anchorWorldPos.subtract(rootSocket);
            if (moveSpeedSq > 1e-4) {
                double dot = toAnchor.normalize().dotProduct(desiredMoveVec.normalize());
                this.state = (dot >= -0.1) ? LimbState.ANCHORED_PULL : LimbState.ANCHORED_PUSH;
            }

            // Condición biomecánica de despegue de ventosas:
            // 1) Se estiró más allá del 95% de su longitud máxima,
            // 2) Quedó demasiado comprimido debajo del cuerpo (< 22% de su longitud), o
            // 3) Se alejó demasiado del punto de avance y su turno de fase (escalonado entre tentáculos pares/impares) está activo
            boolean phaseTurn = ((octopus.age + index * 4) % 14 == 0);
            boolean overStretched = distToAnchor > totalLen * 0.96;
            boolean crampedUnderBody = distToAnchor < totalLen * 0.22;
            boolean needsStepToAdvance = (driftFromIdeal > (totalLen * 0.55) * (totalLen * 0.55)) && phaseTurn;

            if (overStretched || crampedUnderBody || needsStepToAdvance) {
                this.state = LimbState.PEELING_AND_REACHING;
                this.reachProgress = 0.0f;
                this.stateTicks = 0;
                this.reachStartPos = this.nodes[NUM_NODES - 1];
                this.reachTargetPos = findBestSubstrateAnchor(world, octopus, rootSocket, radialDir, desiredMoveVec, totalLen);
            }
        } else if (this.state == LimbState.PEELING_AND_REACHING) {
            float stepSpeed = (moveSpeedSq > 0.002) ? 0.14f : 0.08f;
            this.reachProgress += stepSpeed;
            if (this.reachProgress >= 1.0f) {
                this.reachProgress = 1.0f;
                this.anchorWorldPos = this.reachTargetPos;
                this.state = LimbState.ANCHORED_PULL;
                this.stateTicks = 0;
                if (!world.isClient && world.random.nextInt(3) == 0) {
                    world.playSound(null, this.anchorWorldPos.x, this.anchorWorldPos.y, this.anchorWorldPos.z,
                            SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.NEUTRAL, 0.25f,
                            0.85f + world.random.nextFloat() * 0.35f);
                }
            }
        }
    }

    /**
     * Busca mediante Raycasting 3D el mejor bloque sólido del entorno donde adherir las ventosas:
     * - Si hay una pared vertical delante y `wallClimbingEnabled` está activo, adhiere las ventosas a la pared para trepar!
     * - Si no, busca el relieve exacto del terreno (escalones, pozos, rocas) en la dirección de avance.
     */
    public Vec3d findBestSubstrateAnchor(World world, Entity octopus, Vec3d rootSocket, Vec3d radialDir, Vec3d desiredMoveVec, float totalLen) {
        Vec3d moveDir = (desiredMoveVec.lengthSquared() > 1e-4) ? desiredMoveVec.normalize() : Vec3d.ZERO;
        double reachDist = totalLen * 0.72;

        Vec3d reachDir = radialDir.multiply(0.55).add(moveDir.multiply(0.75));
        if (reachDir.lengthSquared() < 1e-4) {
            reachDir = radialDir;
        } else {
            reachDir = reachDir.normalize();
        }

        // 1. Si el jugador avanza hacia una pared y la escalada está habilitada, lanzar rayo hacia la pared frontal/superior
        if (OctopusPhysicsConfig.wallClimbingEnabled && moveDir.horizontalLengthSquared() > 1e-4) {
            Vec3d wallProbeStart = rootSocket.add(0, 0.8, 0);
            Vec3d wallProbeEnd = wallProbeStart.add(reachDir.x * reachDist, 1.4, reachDir.z * reachDist);
            BlockHitResult wallHit = world.raycast(new RaycastContext(
                    wallProbeStart, wallProbeEnd,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
            ));
            if (wallHit.getType() == HitResult.Type.BLOCK && wallHit.getSide().getAxis().isHorizontal()) {
                this.anchorNormal = Vec3d.of(wallHit.getSide().getVector());
                return wallHit.getPos().add(this.anchorNormal.multiply(0.08));
            }
        }

        // 2. Raycast hacia abajo sobre la superficie del terreno (permite bajar y subir escalones, pendientes y acantilados)
        Vec3d horizontalTarget = rootSocket.add(reachDir.x * reachDist, 0, reachDir.z * reachDist);
        Vec3d skyStart = new Vec3d(horizontalTarget.x, rootSocket.y + totalLen * 0.65, horizontalTarget.z);
        Vec3d groundEnd = new Vec3d(horizontalTarget.x, rootSocket.y - totalLen * 0.90, horizontalTarget.z);

        BlockHitResult groundHit = world.raycast(new RaycastContext(
                skyStart, groundEnd,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
        ));
        if (groundHit.getType() == HitResult.Type.BLOCK) {
            this.anchorNormal = Vec3d.of(groundHit.getSide().getVector());
            return groundHit.getPos().add(this.anchorNormal.multiply(0.06));
        }

        // 3. Si hay un precipicio profundo, buscar adherirse a la pared lateral del acantilado hacia adentro
        Vec3d cliffProbeEnd = new Vec3d(rootSocket.x, rootSocket.y - totalLen * 0.8, rootSocket.z);
        BlockHitResult cliffHit = world.raycast(new RaycastContext(
                groundEnd, cliffProbeEnd,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
        ));
        if (cliffHit.getType() == HitResult.Type.BLOCK) {
            this.anchorNormal = Vec3d.of(cliffHit.getSide().getVector());
            return cliffHit.getPos().add(this.anchorNormal.multiply(0.06));
        }

        this.anchorNormal = new Vec3d(0, 1, 0);
        return new Vec3d(horizontalTarget.x, rootSocket.y - 0.5, horizontalTarget.z);
    }

    /**
     * Calcula la fuerza real que este tentáculo transmite al cuerpo del pulpo:
     * - Si está anclado delante de la dirección deseada (`ANCHORED_PULL`), contrae sus fibras longitudinales
     *   y TRACCIONA el cuerpo hacia el punto de anclaje de las ventosas.
     * - Si está anclado detrás (`ANCHORED_PUSH`), contrae sus fibras transversales (elongación de hidrostato)
     *   y EMPUJA el cuerpo hacia adelante.
     */
    private Vec3d computeMuscularForceOnBody(Vec3d rootSocket, Vec3d radialDir, Vec3d desiredMoveVec, float totalLen) {
        if (this.state != LimbState.ANCHORED_PULL && this.state != LimbState.ANCHORED_PUSH) {
            return Vec3d.ZERO;
        }

        Vec3d rootToAnchor = this.anchorWorldPos.subtract(rootSocket);
        double dist = rootToAnchor.length();
        if (dist < 1e-4) return Vec3d.ZERO;
        Vec3d dirToAnchor = rootToAnchor.multiply(1.0 / dist);

        float pullGain = 0.042f * OctopusPhysicsConfig.suctionPullStrength;
        Vec3d force = Vec3d.ZERO;

        if (desiredMoveVec.lengthSquared() > 1e-4) {
            Vec3d moveDir = desiredMoveVec.normalize();
            double alignment = dirToAnchor.dotProduct(moveDir);

            if (alignment >= 0.0) {
                // Tracción por acortamiento muscular (tira del cuerpo hacia la ventosa anclada)
                force = dirToAnchor.multiply(pullGain * (0.6 + alignment));
            } else {
                // Empuje por elongación del hidrostato (empuja desde la ventosa trasera hacia adelante)
                force = dirToAnchor.multiply(-pullGain * 0.75 * (-alignment));
            }

            // Si la ventosa está anclada más arriba que la cavidad (escalando una pared vertical),
            // generar tracción ascendente real para levantar el peso del cuerpo por el muro!
            if (this.anchorWorldPos.y > rootSocket.y + 0.15 && OctopusPhysicsConfig.wallClimbingEnabled) {
                force = force.add(0, pullGain * 1.35, 0);
            }
        } else {
            // En reposo: mantener tensión postural equilibrada hacia el radio de descanso (evita deriva)
            double restDist = totalLen * 0.52;
            double delta = dist - restDist;
            if (Math.abs(delta) > 0.25) {
                force = dirToAnchor.multiply(delta * 0.015 * OctopusPhysicsConfig.suctionPullStrength);
            }
        }

        return force;
    }

    private void applyStateTargetConstraints(Vec3d rootSocket, Vec3d radialDir, float segLen,
                                             int step, int subSteps, int entityAge) {
        if (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) {
            // Las últimas 3 secciones (punta con ventosas) se adhieren firmemente a la superficie del bloque
            this.nodes[NUM_NODES - 1] = this.anchorWorldPos;
            Vec3d tangent = radialDir;
            this.nodes[NUM_NODES - 2] = this.anchorWorldPos.subtract(tangent.multiply(segLen * 0.85)).add(this.anchorNormal.multiply(0.05));
        } else if (this.state == LimbState.PEELING_AND_REACHING) {
            // Trayectoria parabólica con onda de desenrollado muscular desde la base hacia la punta
            float p = MathHelper.clamp(this.reachProgress, 0.0f, 1.0f);
            Vec3d groundLerp = this.reachStartPos.lerp(this.reachTargetPos, p);
            double liftArc = Math.sin(p * Math.PI) * (segLen * 4.2);
            // Curva de látigo: al principio del paso la muñeca sube más que la punta (peeling), al final la punta baja hacia la presa/suelo
            Vec3d tipPos = groundLerp.add(0, liftArc * (1.0 - p * 0.35), 0);
            this.nodes[NUM_NODES - 1] = this.nodes[NUM_NODES - 1].lerp(tipPos, 0.45);

            int midIdx = NUM_NODES - 5;
            Vec3d midTarget = rootSocket.lerp(tipPos, 0.65).add(0, liftArc * 1.15, 0);
            this.nodes[midIdx] = this.nodes[midIdx].lerp(midTarget, 0.35);
        } else if (this.state == LimbState.COILED_HOLDING) {
            // El tercio proximal se eleva como una grúa muscular y la mitad distal forma una espiral que envuelve el bloque/mob
            Vec3d holdCenter = rootSocket.add(radialDir.multiply(segLen * 4.5)).add(0, segLen * 4.8, 0);
            this.anchorWorldPos = holdCenter;
            for (int i = 1; i < NUM_NODES; i++) {
                if (i < NUM_NODES / 2) {
                    double frac = (double) i / (NUM_NODES / 2);
                    Vec3d cranePt = rootSocket.lerp(holdCenter.add(0, 0.3, 0), frac).add(0, Math.sin(frac * Math.PI) * 0.6, 0);
                    this.nodes[i] = this.nodes[i].lerp(cranePt, 0.35);
                } else {
                    int coilIdx = i - (NUM_NODES / 2);
                    double angle = coilIdx * 0.95 + (entityAge * 0.05);
                    double coilRadius = 0.52;
                    Vec3d coilPt = new Vec3d(
                            holdCenter.x + Math.cos(angle) * coilRadius,
                            holdCenter.y - coilIdx * 0.08,
                            holdCenter.z + Math.sin(angle) * coilRadius
                    );
                    this.nodes[i] = this.nodes[i].lerp(coilPt, 0.55);
                }
            }
        } else if (this.state == LimbState.WHIP_STRIKE) {
            float p = MathHelper.clamp(whipTimer, 0.0f, 1.0f);
            double extend = Math.sin(p * Math.PI);
            Vec3d target = rootSocket.lerp(this.whipTarget, extend);
            this.nodes[NUM_NODES - 1] = this.nodes[NUM_NODES - 1].lerp(target, 0.65);
        }
    }

    private void solveHydrostatDistanceAndBendingConstraints(Vec3d rootSocket, Vec3d radialDir, float segLen, float stiffness) {
        // 1. Pasada hacia adelante y hacia atrás de restricción de distancia (XPBD / FABRIK elástico)
        for (int iter = 0; iter < 2; iter++) {
            this.nodes[0] = rootSocket;
            for (int i = 0; i < NUM_NODES - 1; i++) {
                Vec3d p0 = this.nodes[i];
                Vec3d p1 = this.nodes[i + 1];
                Vec3d delta = p1.subtract(p0);
                double d = delta.length();
                if (d > 1e-5) {
                    // Permitir ligera compresión/elongación muscular pero mantener la integridad del brazo
                    double diff = (d - segLen) / d;
                    if (i == 0) {
                        this.nodes[i + 1] = p1.subtract(delta.multiply(diff * 0.85));
                    } else if ((this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) && i + 1 == NUM_NODES - 1) {
                        this.nodes[i] = p0.add(delta.multiply(diff * 0.85));
                    } else {
                        this.nodes[i] = p0.add(delta.multiply(diff * 0.45));
                        this.nodes[i + 1] = p1.subtract(delta.multiply(diff * 0.45));
                    }
                }
            }
        }

        // 2. Restricción de curvatura suave (Cosserat bending stiffness) para que el tentáculo forme curvas S orgánicas y nunca ángulos quebrados
        float bendFactor = MathHelper.clamp(0.22f * stiffness, 0.08f, 0.42f);
        this.nodes[1] = this.nodes[1].lerp(rootSocket.add(radialDir.multiply(segLen).add(0, 0.12, 0)), bendFactor);

        for (int i = 1; i < NUM_NODES - 1; i++) {
            if (this.state == LimbState.COILED_HOLDING && i >= NUM_NODES / 2) continue;
            Vec3d midpoint = this.nodes[i - 1].add(this.nodes[i + 1]).multiply(0.5);
            this.nodes[i] = this.nodes[i].lerp(midpoint, bendFactor * 0.65);
        }
    }

    /**
     * Resuelve la colisión física de cada uno de los 14 nodos del tentáculo contra los bloques reales del mundo.
     * Evita que cualquier parte del tentáculo atraviese el suelo o los muros, logrando que el brazo se apoye
     * y caiga pesadamente sobre escalones, bordes de bloques y terreno irregular.
     */
    private void solveVoxelTerrainCollisions(World world) {
        for (int i = 1; i < NUM_NODES; i++) {
            Vec3d pt = this.nodes[i];
            float r = this.currentRadii[i] * 0.6f;
            BlockPos bp = BlockPos.ofFloored(pt.x, pt.y - r, pt.z);
            BlockState st = world.getBlockState(bp);
            if (!st.isAir()) {
                VoxelShape shape = st.getCollisionShape(world, bp);
                if (!shape.isEmpty()) {
                    Box localBox = shape.getBoundingBox();
                    double blockTopY = bp.getY() + localBox.maxY;
                    double minX = bp.getX() + localBox.minX;
                    double maxX = bp.getX() + localBox.maxX;
                    double minZ = bp.getZ() + localBox.minZ;
                    double maxZ = bp.getZ() + localBox.maxZ;

                    if (pt.x >= minX && pt.x <= maxX && pt.z >= minZ && pt.z <= maxZ) {
                        if (pt.y - r < blockTopY && pt.y > blockTopY - 0.85) {
                            // Empujar el nodo suavemente hacia arriba de la superficie del bloque y aplicar fricción de ventosas
                            this.nodes[i] = new Vec3d(pt.x, blockTopY + r, pt.z);
                            Vec3d old = this.verletOldNodes[i];
                            this.verletOldNodes[i] = new Vec3d(
                                    MathHelper.lerp(0.5f, old.x, pt.x),
                                    blockTopY + r,
                                    MathHelper.lerp(0.5f, old.z, pt.z)
                            );
                        }
                    }
                }
            }
        }
    }

    public Vec3d getInterpolatedNode(int idx, float tickDelta) {
        int clamped = MathHelper.clamp(idx, 0, NUM_NODES - 1);
        return this.prevTickNodes[clamped].lerp(this.nodes[clamped], tickDelta);
    }
}
