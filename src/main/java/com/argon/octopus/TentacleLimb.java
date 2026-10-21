package com.argon.octopus;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Motor físico y biomecánico v2.0.0 de un tentáculo individual (14 nodos articulados).
 *
 * Correcciones fundamentales v2.0.0:
 * 1. Locomoción Real (Primero el Tentáculo se Arrastra, Después Tira del Cuerpo):
 *    - En vez de levantarse en el aire como pata de araña, el tentáculo en fase `SLIDING_CREEP` se desliza
 *      a ras de la superficie de los bloques (`lift <= 0.10m`) dentro de su propio sector angular radial.
 *    - Cuando termina de extenderse y clava sus ventosas (`ANCHORED_PULL`), acumula tensión muscular (`pullTension`)
 *      y recién ahí ejerce fuerza para arrastrar la pesada masa del cuerpo hacia la ventosa!
 *    - Los tentáculos traseros NUNCA se cruzan hacia adelante: se quedan en su sector trasero empujando (`ANCHORED_PUSH`)
 *      y arrastrándose detrás del cuerpo.
 * 2. Cuelgue Real en Torres/Árboles y Cero Bloques Invisibles (`DANGLING_FREE`):
 *    - Si el raycast no encuentra un bloque sólido real al alcance (por estar en la cima de una torre o árbol),
 *      primero busca envolver las caras verticales de la torre/tronco hacia abajo (`findTowerWrapAnchor`).
 *      Si tampoco hay pared cerca, queda en `DANGLING_FREE` colgando hacia abajo por gravedad real sin anclajes falsos en el aire.
 * 3. Cero Tentáculos hacia el Techo en Cuevas:
 *    - Todos los rayos nacen desde `rootSocket` validando línea de visión libre; nunca nacen arriba del techo.
 *    - `solveVoxelTerrainCollisions` expulsa los nodos en las 6 direcciones (piso `+Y`, techo `-Y`, paredes `+-X, +-Z`).
 * 4. Agarre y Lanzamiento 100% Procedural con Peso e Inercia:
 *    - `REACHING_TO_GRAB`: la punta viaja físicamente hasta tocar el bloque o mob antes de recogerlo.
 *    - `COILED_CARRYING`: sostiene la carga con inercia de péndulo y caída por peso.
 *    - `THROW_WINDUP` -> `THROW_RELEASE`: el tentáculo se arquea hacia atrás cargando fuerza y lanza un latigazo hacia adelante.
 */
public class TentacleLimb {
    public static final int NUM_NODES = 14;

    public enum LimbState {
        ANCHORED_PULL,
        ANCHORED_PUSH,
        SLIDING_CREEP,
        DANGLING_FREE,
        SWIMMING_JET,
        REACHING_TO_GRAB,
        COILED_CARRYING,
        THROW_WINDUP,
        THROW_RELEASE,
        WHIP_STRIKE
    }

    public static class AnchorHit {
        public final Vec3d pos;
        public final Vec3d normal;
        public final boolean valid;

        public AnchorHit(Vec3d pos, Vec3d normal, boolean valid) {
            this.pos = pos;
            this.normal = normal;
            this.valid = valid;
        }
    }

    public final int index;
    public float baseAngleRad;

    public final Vec3d[] nodes = new Vec3d[NUM_NODES];
    public final Vec3d[] prevTickNodes = new Vec3d[NUM_NODES];
    public final Vec3d[] verletOldNodes = new Vec3d[NUM_NODES];
    public final float[] baseRadii = new float[NUM_NODES];
    public final float[] currentRadii = new float[NUM_NODES];
    public final boolean[] nodeSuctionLocked = new boolean[NUM_NODES];

    public LimbState state = LimbState.DANGLING_FREE;
    public Vec3d anchorWorldPos = Vec3d.ZERO;
    public Vec3d anchorNormal = new Vec3d(0, 1, 0);
    public Vec3d creepStartPos = Vec3d.ZERO;
    public Vec3d creepTargetPos = Vec3d.ZERO;
    public float creepProgress = 0.0f;
    public float pullTension = 0.0f; // Crece gradualmente cuando las ventosas se aferran para tirar del cuerpo con inercia
    public int stateTicks = 0;

    // Variables de agarre procedural, lanzamiento con wind-up y latigazo
    public Vec3d actionStartPos = Vec3d.ZERO;
    public Vec3d actionTargetPos = Vec3d.ZERO;
    public Vec3d throwDirection = new Vec3d(0, 0, 1);
    public float actionProgress = 0.0f;
    public Vec3d carrySwayOffset = Vec3d.ZERO;
    public Vec3d carrySwayVel = Vec3d.ZERO;

    public Vec3d lastAppliedBodyForce = Vec3d.ZERO;

    public TentacleLimb(int index, int totalTentacles, Vec3d initialRootPos) {
        this.index = index;
        this.baseAngleRad = (float) (index * (Math.PI * 2.0 / Math.max(1, totalTentacles)));

        Vec3d outward = new Vec3d(Math.cos(baseAngleRad), -0.15, Math.sin(baseAngleRad));
        float segLen = OctopusPhysicsConfig.tentacleLength / (NUM_NODES - 1);

        for (int i = 0; i < NUM_NODES; i++) {
            float t = (float) i / (NUM_NODES - 1);
            this.baseRadii[i] = MathHelper.lerp(t, 0.31f, 0.048f);
            this.currentRadii[i] = this.baseRadii[i];

            Vec3d p = initialRootPos.add(outward.multiply(i * segLen));
            this.nodes[i] = p;
            this.prevTickNodes[i] = p;
            this.verletOldNodes[i] = p;
        }
        this.anchorWorldPos = this.nodes[NUM_NODES - 1];
        this.creepStartPos = this.anchorWorldPos;
        this.creepTargetPos = this.anchorWorldPos;
    }

    public void updateBaseAngle(int newIndex, int totalTentacles) {
        this.baseAngleRad = (float) (newIndex * (Math.PI * 2.0 / Math.max(1, totalTentacles)));
    }

    /**
     * Calcula la posición 3D de la cavidad basal del tentáculo aplicando la rotación 3D del cuerpo
     * (Yaw, Pitch e inclinación lateral Roll).
     */
    public Vec3d getRootSocketPos(Vec3d bodyPos, float bodyYawRad, float bodyPitchDeg, float bodyRollDeg) {
        double socketRadius = 0.68;
        double lx = Math.cos(baseAngleRad) * socketRadius;
        double lz = Math.sin(baseAngleRad) * socketRadius;

        double pitchRad = Math.toRadians(bodyPitchDeg);
        double rollRad = Math.toRadians(bodyRollDeg);

        // Inclinación vertical según Pitch (adelante/atrás) y Roll (izquierda/derecha)
        double tiltedY = 0.34 - lz * Math.sin(pitchRad) * 0.65 + lx * Math.sin(rollRad) * 0.65;

        float worldAngle = baseAngleRad + bodyYawRad;
        return new Vec3d(
                bodyPos.x + Math.cos(worldAngle) * socketRadius,
                bodyPos.y + tiltedY,
                bodyPos.z + Math.sin(worldAngle) * socketRadius
        );
    }

    public Vec3d getSectorWorldDir(float bodyYawRad) {
        float worldAngle = baseAngleRad + bodyYawRad;
        return new Vec3d(Math.cos(worldAngle), 0, Math.sin(worldAngle)).normalize();
    }

    public Vec3d tickPhysics(World world, GiantOctopusEntity octopus, Vec3d bodyPos,
                             float bodyYawRad, float bodyPitchDeg, float bodyRollDeg,
                             Vec3d moveIntentDir) {
        for (int i = 0; i < NUM_NODES; i++) {
            this.prevTickNodes[i] = this.nodes[i];
        }

        stateTicks++;
        float totalLen = Math.max(3.5f, OctopusPhysicsConfig.tentacleLength);
        float segLen = totalLen / (NUM_NODES - 1);
        Vec3d rootSocket = getRootSocketPos(bodyPos, bodyYawRad, bodyPitchDeg, bodyRollDeg);
        Vec3d sectorDir = getSectorWorldDir(bodyYawRad);
        boolean inWater = octopus.isTouchingWater();

        // 1. Actualizar estado del tentáculo (Natación, Acciones especiales o Locomoción Terrestre por Arrastrado)
        if (this.state == LimbState.REACHING_TO_GRAB || this.state == LimbState.COILED_CARRYING
                || this.state == LimbState.THROW_WINDUP || this.state == LimbState.THROW_RELEASE
                || this.state == LimbState.WHIP_STRIKE) {
            updateSpecialActionState(world, octopus, rootSocket, sectorDir);
        } else if (inWater) {
            this.state = LimbState.SWIMMING_JET;
            this.pullTension = 0.0f;
            for (int i = 0; i < NUM_NODES; i++) {
                this.nodeSuctionLocked[i] = false;
            }
        } else {
            if (this.state == LimbState.SWIMMING_JET) {
                this.state = LimbState.DANGLING_FREE;
            }
            updateTerrestrialCrawlingGait(world, octopus, bodyPos, rootSocket, sectorDir, moveIntentDir, totalLen);
        }

        // 2. Calcular la fuerza real que este tentáculo ejerce sobre el cuerpo (SOLO después de arrastrarse y adherir ventosas!)
        this.lastAppliedBodyForce = computeDelayedTentacleTractionForce(rootSocket, sectorDir, moveIntentDir, totalLen);

        return this.lastAppliedBodyForce;
    }

    /**
     * Ejecuta un sub-paso de integración Verlet + restricciones de hidrostato + colisión con bloques en 6 caras.
     * Se llama desde `GiantOctopusEntity` intercalado con el solver de colisión tentáculo-con-tentáculo.
     */
    public void runPhysicsSubStep(World world, GiantOctopusEntity octopus, Vec3d bodyPos,
                                  float bodyYawRad, float bodyPitchDeg, float bodyRollDeg,
                                  Vec3d moveIntentDir, int step, int totalSubSteps) {
        float totalLen = Math.max(3.5f, OctopusPhysicsConfig.tentacleLength);
        float segLen = totalLen / (NUM_NODES - 1);
        float stiffness = OctopusPhysicsConfig.hydrostatStiffness;
        boolean inWater = octopus.isTouchingWater();
        float gravity = inWater ? 0.004f : (0.062f * OctopusPhysicsConfig.gravitySlumpFactor);

        Vec3d rootSocket = getRootSocketPos(bodyPos, bodyYawRad, bodyPitchDeg, bodyRollDeg);
        Vec3d sectorDir = getSectorWorldDir(bodyYawRad);

        this.nodes[0] = rootSocket;

        // Paso A: Integración Verlet + Gravedad real + Onda muscular
        for (int i = 1; i < NUM_NODES; i++) {
            float t = (float) i / (NUM_NODES - 1);
            Vec3d cur = this.nodes[i];
            Vec3d old = this.verletOldNodes[i];
            double damping = inWater ? 0.88 : 0.80;
            Vec3d vel = cur.subtract(old).multiply(damping);

            double gy = -gravity / totalSubSteps;
            Vec3d lateral = new Vec3d(-sectorDir.z, 0, sectorDir.x);
            double wavePhase = (octopus.age * 0.14) + (index * 0.95) - (t * 3.4);
            Vec3d wave = lateral.multiply(Math.sin(wavePhase) * (inWater ? 0.012 : 0.004) * t);

            Vec3d next = cur.add(vel).add(wave.x, gy, wave.z);
            this.verletOldNodes[i] = cur;
            this.nodes[i] = next;
        }

        // Paso B: Aplicar postura o trayectoria según el estado actual
        applyStateConstraintsForSubStep(world, octopus, rootSocket, sectorDir, segLen);

        // Paso C: Restricción de longitud de segmento (XPBD) + Restricción de sector angular (evita que los tentáculos se crucen por encima del cuerpo!)
        solveDistanceBendingAndSectorConstraints(bodyPos, rootSocket, sectorDir, segLen, stiffness);

        // Paso D: Colisión en las 6 caras (Piso +Y, Techo -Y y 4 Paredes) contra los bloques reales del mundo
        solveSixWayVoxelCollisions(world);
    }

    public void updateRadiiAndSuctionIndicators() {
        float totalLen = Math.max(3.5f, OctopusPhysicsConfig.tentacleLength);
        float segLen = totalLen / (NUM_NODES - 1);
        for (int i = 0; i < NUM_NODES; i++) {
            if (i < NUM_NODES - 1) {
                double actualLen = Math.max(0.15, this.nodes[i + 1].distanceTo(this.nodes[i]));
                float stretchRatio = (float) (segLen / actualLen);
                // Abultamiento extra cuando el músculo se contrae con `pullTension`
                float tensionBulge = 1.0f + (this.pullTension * 0.18f * (1.0f - (float) i / NUM_NODES));
                float bulge = MathHelper.clamp((float) Math.sqrt(stretchRatio) * tensionBulge, 0.76f, 1.38f);
                this.currentRadii[i] = this.baseRadii[i] * bulge;
            } else {
                this.currentRadii[i] = this.baseRadii[i];
            }

            if (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) {
                this.nodeSuctionLocked[i] = (i >= 8 && i <= 11);
            } else if (this.state == LimbState.SLIDING_CREEP) {
                // Mientras se desliza a ras del suelo, solo las ventosas proximales/medias rozan el suelo
                this.nodeSuctionLocked[i] = (i >= 4 && i <= 8 && creepProgress < 0.5f);
            } else if (this.state == LimbState.COILED_CARRYING) {
                this.nodeSuctionLocked[i] = (i >= NUM_NODES / 2);
            } else {
                this.nodeSuctionLocked[i] = false;
            }
        }
    }

    /**
     * Coordina la locomoción terrestre real del tentáculo:
     * 1. Reclutamiento por sector: cada tentáculo opera únicamente en su abanico angular `sectorDir`.
     *    Los tentáculos traseros NUNCA viajan hacia adelante; se quedan detrás empujando o arrastrándose.
     * 2. Sucesión asíncrona (onda metacrónica): no se despegan todos al mismo tiempo. Solo hasta 2-3 tentáculos
     *    pueden estar en fase `SLIDING_CREEP` simultáneamente gracias a su ventana de turno escalonada.
     * 3. Arrastrado a ras del suelo: en `SLIDING_CREEP`, la punta se desliza pegada a los bloques del suelo/pared
     *    antes de clavar sus ventosas e iniciar la tracción del cuerpo.
     */
    private void updateTerrestrialCrawlingGait(World world, GiantOctopusEntity octopus, Vec3d bodyPos,
                                               Vec3d rootSocket, Vec3d sectorDir, Vec3d moveIntentDir, float totalLen) {
        boolean wantsToMove = moveIntentDir.horizontalLengthSquared() > 1e-4;
        Vec3d moveDir = wantsToMove ? moveIntentDir.normalize() : Vec3d.ZERO;
        double alignmentWithMove = wantsToMove ? sectorDir.dotProduct(moveDir) : 0.0;

        // Calcular dónde debería estar anclado este tentáculo dentro de su PROPIO sector angular:
        // - Si es un tentáculo frontal/lateral (alignment >= -0.15), se extiende hacia adelante dentro de su sector para TRACCIONAR.
        // - Si es un tentáculo trasero (alignment < -0.15), permanece DETRÁS en su sector a menor distancia para EMPUJAR y arrastrarse!
        Vec3d desiredReachDir;
        double desiredReachDist;
        if (!wantsToMove) {
            desiredReachDir = sectorDir;
            desiredReachDist = totalLen * 0.62;
        } else if (alignmentWithMove >= -0.15) {
            // Tentáculo delantero o lateral: se estira hacia adelante combinando su sector con la dirección de avance
            desiredReachDir = sectorDir.multiply(0.62).add(moveDir.multiply(0.48)).normalize();
            desiredReachDist = totalLen * 0.82;
        } else {
            // Tentáculo trasero: JAMÁS va hacia adelante; se mantiene en su sector trasero acompañando el arrastre
            desiredReachDir = sectorDir.multiply(0.82).add(moveDir.multiply(0.18)).normalize();
            desiredReachDist = totalLen * 0.48;
        }

        // Turno asíncrono escalonado (sucesión natural no simultánea entre los N tentáculos)
        int totalLimbs = Math.max(1, octopus.getTentacles().size());
        int activePhaseSlot = (octopus.age / 4) % totalLimbs;
        boolean neighborCreeping = octopus.isAdjacentNeighborCreeping(this.index);
        boolean isMyAsynchronousTurn = !neighborCreeping
                && octopus.countCurrentlyCreepingLimbs() < Math.max(2, totalLimbs / 3)
                && ((activePhaseSlot == this.index) || (((activePhaseSlot + totalLimbs / 2) % totalLimbs) == this.index));

        if (this.state == LimbState.DANGLING_FREE) {
            this.pullTension = 0.0f;
            if ((octopus.age + index * 3) % 6 == 0) {
                AnchorHit hit = findRealisticSubstrateOrTowerAnchor(world, octopus, bodyPos, rootSocket, desiredReachDir, desiredReachDist, totalLen, wantsToMove && alignmentWithMove > 0.2);
                if (hit.valid) {
                    startSlidingCreepTo(hit.pos, hit.normal);
                }
            }
            return;
        }

        if (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) {
            // Verificar que el bloque donde está adherida la ventosa siga existiendo y dentro del alcance máximo
            double distToAnchor = rootSocket.distanceTo(this.anchorWorldPos);
            if (distToAnchor > totalLen * 1.08) {
                // Se estiró más allá de la longitud física del tentáculo -> desprender ventosas!
                AnchorHit hit = findRealisticSubstrateOrTowerAnchor(world, octopus, bodyPos, rootSocket, desiredReachDir, desiredReachDist, totalLen, wantsToMove && alignmentWithMove > 0.2);
                if (hit.valid) {
                    startSlidingCreepTo(hit.pos, hit.normal);
                } else {
                    this.state = LimbState.DANGLING_FREE;
                    this.pullTension = 0.0f;
                }
                return;
            }

            // Incrementar progresivamente la tensión muscular después de adherir las ventosas (da el retraso orgánico antes de mover el cuerpo!)
            this.pullTension = Math.min(1.0f, this.pullTension + 0.085f);

            Vec3d toAnchor = this.anchorWorldPos.subtract(rootSocket);
            if (wantsToMove && toAnchor.lengthSquared() > 1e-4) {
                double dot = toAnchor.normalize().dotProduct(moveDir);
                this.state = (dot >= -0.05) ? LimbState.ANCHORED_PULL : LimbState.ANCHORED_PUSH;
            } else {
                this.state = LimbState.ANCHORED_PULL;
            }

            Vec3d idealAnchor = rootSocket.add(desiredReachDir.multiply(desiredReachDist));
            double driftSq = this.anchorWorldPos.subtract(idealAnchor).horizontalLengthSquared();

            // Si el jugador apretó WASD, los tentáculos delanteros que aún no se adelantaron inician su deslizamiento en turno asíncrono!
            boolean needsToAdvance = wantsToMove && (driftSq > (totalLen * 0.34) * (totalLen * 0.34));
            boolean tooCloseUnderBody = distToAnchor < totalLen * 0.24;

            if ((needsToAdvance && isMyAsynchronousTurn) || tooCloseUnderBody) {
                AnchorHit hit = findRealisticSubstrateOrTowerAnchor(world, octopus, bodyPos, rootSocket, desiredReachDir, desiredReachDist, totalLen, wantsToMove && alignmentWithMove > 0.2);
                if (hit.valid) {
                    startSlidingCreepTo(hit.pos, hit.normal);
                } else {
                    this.state = LimbState.DANGLING_FREE;
                    this.pullTension = 0.0f;
                }
            }
        } else if (this.state == LimbState.SLIDING_CREEP) {
            this.pullTension = 0.0f;
            // Velocidad de deslizamiento pausada y orgánica sobre la superficie (nunca instantánea)
            float creepSpeed = wantsToMove ? 0.095f : 0.065f;
            this.creepProgress += creepSpeed;
            if (this.creepProgress >= 1.0f) {
                this.creepProgress = 1.0f;
                this.anchorWorldPos = this.creepTargetPos;
                this.state = (alignmentWithMove >= -0.1) ? LimbState.ANCHORED_PULL : LimbState.ANCHORED_PUSH;
                this.pullTension = 0.12f; // Empieza a tensar el músculo gradualmente tras clavar las ventosas
                this.stateTicks = 0;
                if (!world.isClient && world.random.nextInt(3) == 0) {
                    world.playSound(null, this.anchorWorldPos.x, this.anchorWorldPos.y, this.anchorWorldPos.z,
                            SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.NEUTRAL, 0.22f,
                            0.75f + world.random.nextFloat() * 0.25f);
                }
            }
        }
    }

    private void startSlidingCreepTo(Vec3d newTarget, Vec3d newNormal) {
        this.state = LimbState.SLIDING_CREEP;
        this.creepProgress = 0.0f;
        this.pullTension = 0.0f;
        this.stateTicks = 0;
        this.creepStartPos = this.nodes[NUM_NODES - 1];
        this.creepTargetPos = newTarget;
        this.anchorNormal = newNormal;
    }

    /**
     * Busca un bloque SÓLIDO REAL donde adherir las ventosas, respetando estrictamente la línea de visión desde `rootSocket`:
     * - Jamás lanza rayos desde arriba del techo de una cueva (`image_cd3eb8.png`).
     * - Si no hay piso debajo porque el pulpo está arriba de una torre o árbol (`image_cd2f74.jpg`),
     *   lanza rayos hacia adentro/abajo para envolver y abrazar las paredes verticales de la torre/tronco.
     * - Si no hay ningún bloque al alcance, devuelve `valid = false` para que el tentáculo cuelgue libremente por gravedad (`DANGLING_FREE`).
     */
    public AnchorHit findRealisticSubstrateOrTowerAnchor(World world, Entity octopus, Vec3d bodyPos, Vec3d rootSocket,
                                                         Vec3d reachDir, double reachDist, float totalLen, boolean tryClimbWall) {
        // 1. Primero trazar un rayo desde `rootSocket` hacia afuera/adelante para no atravesar paredes ni techos de cuevas!
        Vec3d horizontalOut = rootSocket.add(reachDir.x * reachDist, 0.25, reachDir.z * reachDist);
        BlockHitResult outHit = world.raycast(new RaycastContext(
                rootSocket, horizontalOut,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
        ));

        if (outHit.getType() == HitResult.Type.BLOCK) {
            // Si chocó contra una pared lateral o escalón delante del tentáculo:
            if (tryClimbWall && OctopusPhysicsConfig.wallClimbingEnabled && outHit.getSide().getAxis().isHorizontal()) {
                // Escalar la pared frontal: buscar un punto un poco más alto sobre esa misma pared sin atravesar el techo
                Vec3d upProbeStart = rootSocket.add(0, 0.4, 0);
                BlockHitResult ceilingCheck = world.raycast(new RaycastContext(
                        rootSocket, upProbeStart.add(0, 1.4, 0),
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
                ));
                double safeClimbY = (ceilingCheck.getType() == HitResult.Type.BLOCK)
                        ? Math.min(rootSocket.y + 0.6, ceilingCheck.getPos().y - 0.4)
                        : rootSocket.y + 1.35;
                Vec3d wallTargetProbe = new Vec3d(horizontalOut.x, safeClimbY, horizontalOut.z);
                BlockHitResult highWallHit = world.raycast(new RaycastContext(
                        new Vec3d(rootSocket.x, safeClimbY, rootSocket.z), wallTargetProbe,
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
                ));
                if (highWallHit.getType() == HitResult.Type.BLOCK) {
                    Vec3d n = Vec3d.of(highWallHit.getSide().getVector());
                    return new AnchorHit(highWallHit.getPos().add(n.multiply(0.06)), n, true);
                }
            }
            Vec3d n = Vec3d.of(outHit.getSide().getVector());
            return new AnchorHit(outHit.getPos().add(n.multiply(0.06)), n, true);
        }

        // 2. Desde el punto exterior libre `horizontalOut`, trazar hacia abajo hasta el alcance real del tentáculo
        double maxDrop = Math.sqrt(Math.max(0.5, totalLen * totalLen - reachDist * reachDist));
        Vec3d downEnd = new Vec3d(horizontalOut.x, rootSocket.y - maxDrop, horizontalOut.z);
        BlockHitResult groundHit = world.raycast(new RaycastContext(
                horizontalOut, downEnd,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
        ));

        if (groundHit.getType() == HitResult.Type.BLOCK) {
            Vec3d n = Vec3d.of(groundHit.getSide().getVector());
            return new AnchorHit(groundHit.getPos().add(n.multiply(0.06)), n, true);
        }

        // 3. Si NO hay piso debajo en `horizontalOut` (ej. el pulpo está arriba de una torre, pilar o copa de un árbol!),
        // lanzar rayos desde afuera hacia adentro/abajo hacia la columna vertical debajo del cuerpo para ABRAZAR la torre/árbol!
        for (double drop = 0.8; drop <= totalLen * 0.82; drop += 1.1) {
            Vec3d probeStart = new Vec3d(
                    bodyPos.x + reachDir.x * Math.min(reachDist, 2.6),
                    rootSocket.y - drop,
                    bodyPos.z + reachDir.z * Math.min(reachDist, 2.6)
            );
            Vec3d probeInwardEnd = new Vec3d(bodyPos.x, rootSocket.y - drop, bodyPos.z);
            BlockHitResult towerSideHit = world.raycast(new RaycastContext(
                    probeStart, probeInwardEnd,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, octopus
            ));
            if (towerSideHit.getType() == HitResult.Type.BLOCK) {
                Vec3d n = Vec3d.of(towerSideHit.getSide().getVector());
                Vec3d anchor = towerSideHit.getPos().add(n.multiply(0.07));
                if (anchor.distanceTo(rootSocket) <= totalLen * 0.98) {
                    return new AnchorHit(anchor, n, true);
                }
            }
        }

        // 4. Si realmente está en el vacío sin paredes ni piso al alcance, NO inventar bloques invisibles: colgar por gravedad!
        return new AnchorHit(downEnd, new Vec3d(0, 1, 0), false);
    }

    /**
     * Calcula la fuerza de tracción/empuje que este tentáculo transmite al cuerpo del pulpo:
     * - Si el tentáculo aún se está deslizando (`SLIDING_CREEP`) o colgando (`DANGLING_FREE`), la fuerza es CERO
     *   (así al apretar W primero ves a los tentáculos deslizarse hacia adelante antes de que el cuerpo se mueva!).
     * - Una vez anclado (`ANCHORED_PULL` / `ANCHORED_PUSH`), la fuerza crece proporcionalmente a `pullTension`
     *   (que sube de 0 a 1 tras adherir las ventosas), tirando pesadamente del cuerpo hacia los tentáculos adelantados!
     */
    private Vec3d computeDelayedTentacleTractionForce(Vec3d rootSocket, Vec3d sectorDir, Vec3d moveIntentDir, float totalLen) {
        if (this.state != LimbState.ANCHORED_PULL && this.state != LimbState.ANCHORED_PUSH) {
            return Vec3d.ZERO;
        }

        Vec3d rootToAnchor = this.anchorWorldPos.subtract(rootSocket);
        double dist = rootToAnchor.length();
        if (dist < 1e-4) return Vec3d.ZERO;
        Vec3d dirToAnchor = rootToAnchor.multiply(1.0 / dist);

        // La fuerza depende de `pullTension` (que recién se activa tras haber adelantado y anclado el tentáculo)
        float basePull = 0.026f * OctopusPhysicsConfig.suctionPullStrength * this.pullTension;

        if (moveIntentDir.horizontalLengthSquared() > 1e-4) {
            Vec3d moveDir = moveIntentDir.normalize();
            double align = dirToAnchor.dotProduct(moveDir);

            Vec3d force;
            if (align >= 0.0) {
                // Tentáculo delantero anclado: contrae sus fibras longitudinales y TIRA del cuerpo hacia su ventosa!
                double stretchFactor = MathHelper.clamp(dist / (totalLen * 0.55), 0.35, 1.45);
                force = dirToAnchor.multiply(basePull * (0.55 + align * 0.85) * stretchFactor);
            } else {
                // Tentáculo trasero anclado: empuja suavemente por elongación desde atrás
                force = moveDir.multiply(basePull * 0.45 * (-align));
            }

            if (this.anchorWorldPos.y > rootSocket.y + 0.20 && OctopusPhysicsConfig.wallClimbingEnabled) {
                force = force.add(0, basePull * 1.45, 0);
            }
            return force;
        } else {
            // En reposo: sólo estabilizar si el cuerpo quedó muy descentrado
            double restDist = totalLen * 0.52;
            double delta = dist - restDist;
            if (Math.abs(delta) > 0.45) {
                return dirToAnchor.multiply(delta * 0.008 * OctopusPhysicsConfig.suctionPullStrength);
            }
            return Vec3d.ZERO;
        }
    }

    private void updateSpecialActionState(World world, GiantOctopusEntity octopus, Vec3d rootSocket, Vec3d sectorDir) {
        if (this.state == LimbState.REACHING_TO_GRAB) {
            // Fase 1 del Agarre Procedural: la punta del tentáculo viaja físicamente por el espacio hacia el objetivo
            this.actionProgress += 0.11f;
            Entity targetEnt = octopus.getPendingGrabEntity();
            if (targetEnt != null && targetEnt.isAlive()) {
                this.actionTargetPos = targetEnt.getPos().add(0, targetEnt.getHeight() * 0.5, 0);
            }

            double distToTarget = this.nodes[NUM_NODES - 1].distanceTo(this.actionTargetPos);
            if (this.actionProgress >= 1.0f || distToTarget < 0.95) {
                // ¡Recién cuando la punta del tentáculo TOCA físicamente el bloque o mob, lo envuelve y lo arranca!
                octopus.completeProceduralGrabAtTarget();
                this.state = LimbState.COILED_CARRYING;
                this.actionProgress = 0.0f;
                this.stateTicks = 0;
            }
        } else if (this.state == LimbState.COILED_CARRYING) {
            if (!octopus.isHoldingAnything()) {
                this.state = LimbState.DANGLING_FREE;
                return;
            }
            // Inercia de péndulo pesado mientras transporta el bloque o mob
            Vec3d bodyVel = octopus.getVelocity();
            Vec3d targetLag = new Vec3d(-bodyVel.x * 2.2, -Math.abs(bodyVel.horizontalLength()) * 0.6, -bodyVel.z * 2.2);
            this.carrySwayVel = this.carrySwayVel.multiply(0.82).add(targetLag.subtract(this.carrySwayOffset).multiply(0.14));
            this.carrySwayOffset = this.carrySwayOffset.add(this.carrySwayVel);
        } else if (this.state == LimbState.THROW_WINDUP) {
            // Fase 1 del Lanzamiento con Inercia: el tentáculo se retrae y arquea hacia atrás del hombro cargando potencia
            this.actionProgress += 0.14f;
            if (this.actionProgress >= 1.0f) {
                this.state = LimbState.THROW_RELEASE;
                this.actionProgress = 0.0f;
            }
        } else if (this.state == LimbState.THROW_RELEASE) {
            // Fase 2 del Lanzamiento: latigazo explosivo hacia adelante; a mitad del latigazo suelta el proyectil con inercia real!
            float prev = this.actionProgress;
            this.actionProgress += 0.18f;
            if (prev < 0.45f && this.actionProgress >= 0.45f) {
                Vec3d releaseTipPos = this.nodes[NUM_NODES - 2];
                octopus.executeBallisticReleaseFromTip(releaseTipPos, this.throwDirection);
            }
            if (this.actionProgress >= 1.0f) {
                this.state = LimbState.DANGLING_FREE;
                this.actionProgress = 0.0f;
            }
        } else if (this.state == LimbState.WHIP_STRIKE) {
            // Latigazo frontal de 20 ticks bien visible (0..0.35 carga atrás, 0.35..0.80 onda de látigo, 0.80..1.0 caída pesada)
            this.actionProgress += 0.052f;
            if (this.actionProgress >= 1.0f) {
                this.state = LimbState.DANGLING_FREE;
                this.actionProgress = 0.0f;
            }
        }
    }

    private void applyStateConstraintsForSubStep(World world, GiantOctopusEntity octopus,
                                                 Vec3d rootSocket, Vec3d sectorDir, float segLen) {
        if (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH) {
            // 1. Parche medial de ventosas (nodos 9, 10, 11) apoyado firmemente sobre el sustrato (Nature 2025 Light-Field Imaging & Current Biology)
            this.nodes[10] = this.anchorWorldPos;
            this.nodes[9] = this.nodes[9].lerp(
                    this.anchorWorldPos.subtract(sectorDir.multiply(segLen * 0.90)).add(this.anchorNormal.multiply(0.03)),
                    0.65
            );
            this.nodes[11] = this.nodes[11].lerp(
                    this.anchorWorldPos.add(sectorDir.multiply(segLen * 0.88)).add(this.anchorNormal.multiply(0.03)),
                    0.65
            );

            // 2. Bisagra localizada de curvatura proximal-medial (nodos 4..6 - "localized curvature and strain", Nature 2025):
            // Forma el característico arco muscular proximal cuando el pulpo empuja por elongación o tracciona por acortamiento!
            double archHeight = (this.state == LimbState.ANCHORED_PUSH)
                    ? (0.26 + 0.22 * this.pullTension)
                    : (0.16 + 0.14 * (1.0 - this.pullTension));
            Vec3d hingeTarget = rootSocket.lerp(this.anchorWorldPos, 0.42).add(0, archHeight, 0);
            this.nodes[5] = this.nodes[5].lerp(hingeTarget, 0.32);

            // 3. Punta distal táctil (nodos 12..13): se curva sutilmente hacia afuera/arriba tanteando el terreno adelante
            double tipSweep = Math.sin(octopus.age * 0.18 + index) * 0.12;
            Vec3d sideDir = new Vec3d(-sectorDir.z, 0, sectorDir.x);
            Vec3d tip12 = this.anchorWorldPos.add(sectorDir.multiply(segLen * 1.75)).add(sideDir.multiply(tipSweep)).add(this.anchorNormal.multiply(0.08));
            Vec3d tip13 = this.anchorWorldPos.add(sectorDir.multiply(segLen * 2.55)).add(sideDir.multiply(tipSweep * 1.6)).add(this.anchorNormal.multiply(0.18));
            this.nodes[12] = this.nodes[12].lerp(tip12, 0.45);
            this.nodes[13] = this.nodes[13].lerp(tip13, 0.45);
        } else if (this.state == LimbState.SLIDING_CREEP) {
            // DESLIZAMIENTO A RAS DEL SUELO: en vez de levantarse como pata de araña, la punta se arrastra por la superficie
            // con una elevación mínima (0.09 bloques en la punta) mientras una onda muscular baja recorre el brazo!
            float p = MathHelper.clamp(this.creepProgress, 0.0f, 1.0f);
            float smoothP = p * p * (3.0f - 2.0f * p);
            Vec3d slidePt = this.creepStartPos.lerp(this.creepTargetPos, smoothP);

            // Ajustar la altura de `slidePt` a la superficie real del bloque por el que se está arrastrando
            BlockPos surfaceBp = BlockPos.ofFloored(slidePt.x, slidePt.y + 0.3, slidePt.z);
            BlockState surfaceSt = world.getBlockState(surfaceBp.down());
            double surfaceY = slidePt.y;
            if (!surfaceSt.isAir()) {
                VoxelShape sh = surfaceSt.getCollisionShape(world, surfaceBp.down());
                if (!sh.isEmpty()) {
                    surfaceY = (surfaceBp.getY() - 1) + sh.getBoundingBox().maxY + 0.06;
                }
            }
            double microLift = Math.sin(p * Math.PI) * 0.09; // Apenas 9 cm para despegar las ventosas sin levantar la pata!
            Vec3d tipTarget = new Vec3d(slidePt.x, MathHelper.lerp(0.6, slidePt.y, surfaceY) + microLift, slidePt.z);
            this.nodes[NUM_NODES - 1] = this.nodes[NUM_NODES - 1].lerp(tipTarget, 0.55);

            // Onda muscular baja que viaja desde la base (i=2) hacia la punta (i=12) empujando el tentáculo por el piso
            int wavePeakIdx = 2 + (int) (p * (NUM_NODES - 4));
            if (wavePeakIdx >= 1 && wavePeakIdx < NUM_NODES - 1) {
                this.nodes[wavePeakIdx] = this.nodes[wavePeakIdx].add(0, 0.025, 0);
            }
        } else if (this.state == LimbState.SWIMMING_JET) {
            // Natación hidrodinámica: los 8 tentáculos se extienden hacia atrás del movimiento y ondulan como campana de medusa
            Vec3d swimVel = octopus.getVelocity();
            Vec3d trailDir = (swimVel.lengthSquared() > 0.002)
                    ? swimVel.normalize().multiply(-1.0)
                    : new Vec3d(sectorDir.x, -0.35, sectorDir.z).normalize();
            double bellPulse = Math.sin(octopus.age * 0.25) * 0.42;
            for (int i = 1; i < NUM_NODES; i++) {
                double frac = (double) i / (NUM_NODES - 1);
                double spread = 0.35 + Math.max(0.0, bellPulse) * frac * 0.85;
                Vec3d swimPt = rootSocket
                        .add(trailDir.multiply(i * segLen * 0.88))
                        .add(sectorDir.multiply(i * segLen * spread));
                this.nodes[i] = this.nodes[i].lerp(swimPt, 0.22);
            }
        } else if (this.state == LimbState.REACHING_TO_GRAB) {
            // El tentáculo viaja progresivamente desde su posición actual hasta `actionTargetPos` desenrollándose
            float p = MathHelper.clamp(this.actionProgress, 0.0f, 1.0f);
            float ease = 1.0f - (1.0f - p) * (1.0f - p);
            Vec3d currentGoal = this.actionStartPos.lerp(this.actionTargetPos, ease);
            double reachArc = Math.sin(p * Math.PI) * 0.45;
            this.nodes[NUM_NODES - 1] = this.nodes[NUM_NODES - 1].lerp(currentGoal.add(0, reachArc, 0), 0.55);
        } else if (this.state == LimbState.COILED_CARRYING) {
            // Sostiene el objeto con caída gravitatoria por peso e inercia de balanceo (`carrySwayOffset`)
            Vec3d holdCenter = rootSocket
                    .add(sectorDir.multiply(segLen * 4.2))
                    .add(0, segLen * 3.2 - 0.25 * OctopusPhysicsConfig.gravitySlumpFactor, 0)
                    .add(this.carrySwayOffset);
            this.anchorWorldPos = holdCenter;
            for (int i = 1; i < NUM_NODES; i++) {
                if (i < NUM_NODES / 2) {
                    double frac = (double) i / (NUM_NODES / 2);
                    Vec3d archPt = rootSocket.lerp(holdCenter.add(0, 0.25, 0), frac).add(0, Math.sin(frac * Math.PI) * 0.45, 0);
                    this.nodes[i] = this.nodes[i].lerp(archPt, 0.38);
                } else {
                    int coilIdx = i - (NUM_NODES / 2);
                    double angle = coilIdx * 0.98 + (octopus.age * 0.04);
                    double coilRadius = 0.50;
                    Vec3d coilPt = new Vec3d(
                            holdCenter.x + Math.cos(angle) * coilRadius,
                            holdCenter.y - coilIdx * 0.07,
                            holdCenter.z + Math.sin(angle) * coilRadius
                    );
                    this.nodes[i] = this.nodes[i].lerp(coilPt, 0.52);
                }
            }
        } else if (this.state == LimbState.THROW_WINDUP) {
            // Carga hacia atrás del hombro antes de lanzar (wind-up con peso)
            float p = MathHelper.clamp(this.actionProgress, 0.0f, 1.0f);
            Vec3d windupPos = rootSocket
                    .subtract(this.throwDirection.multiply(segLen * 4.5 * p))
                    .add(sectorDir.multiply(segLen * 2.5))
                    .add(0, segLen * (3.5 + 1.8 * p), 0);
            this.anchorWorldPos = windupPos;
            for (int i = NUM_NODES / 2; i < NUM_NODES; i++) {
                int coilIdx = i - (NUM_NODES / 2);
                double angle = coilIdx * 1.05;
                Vec3d coilPt = windupPos.add(Math.cos(angle) * 0.45, -coilIdx * 0.06, Math.sin(angle) * 0.45);
                this.nodes[i] = this.nodes[i].lerp(coilPt, 0.48);
            }
        } else if (this.state == LimbState.THROW_RELEASE) {
            // Latigazo explosivo hacia adelante con inercia de lanzamiento
            float p = MathHelper.clamp(this.actionProgress, 0.0f, 1.0f);
            Vec3d releaseArc = rootSocket
                    .add(this.throwDirection.multiply(segLen * (NUM_NODES - 2) * Math.sin(p * Math.PI * 0.75)))
                    .add(0, Math.cos(p * Math.PI * 0.8) * 1.6, 0);
            this.anchorWorldPos = releaseArc;
            this.nodes[NUM_NODES - 1] = this.nodes[NUM_NODES - 1].lerp(releaseArc, 0.65);
            this.nodes[NUM_NODES - 3] = this.nodes[NUM_NODES - 3].lerp(rootSocket.lerp(releaseArc, 0.75).add(0, 0.5, 0), 0.50);
        } else if (this.state == LimbState.WHIP_STRIKE) {
            // Latigazo frontal en 3 fases claramente visibles (0..0.32 carga atrás, 0.32..0.78 propagación de onda, 0.78..1.0 caída)
            float p = MathHelper.clamp(this.actionProgress, 0.0f, 1.0f);
            Vec3d whipDir = this.actionTargetPos.subtract(rootSocket);
            if (whipDir.lengthSquared() < 1e-4) whipDir = sectorDir;
            else whipDir = whipDir.normalize();
            Vec3d sideVec = new Vec3d(-whipDir.z, 0, whipDir.x);

            if (p < 0.32f) {
                float w = p / 0.32f;
                for (int i = 1; i < NUM_NODES; i++) {
                    double frac = (double) i / (NUM_NODES - 1);
                    Vec3d coilBack = rootSocket
                            .subtract(whipDir.multiply(frac * segLen * 5.0 * w))
                            .add(sideVec.multiply(Math.sin(frac * Math.PI) * 1.4 * (index % 2 == 0 ? 1 : -1)))
                            .add(0, Math.sin(frac * Math.PI) * 1.8 * w, 0);
                    this.nodes[i] = this.nodes[i].lerp(coilBack, 0.45);
                }
            } else {
                float strikeP = (p - 0.32f) / 0.68f;
                for (int i = 1; i < NUM_NODES; i++) {
                    double frac = (double) i / (NUM_NODES - 1);
                    double wave = Math.sin((strikeP * 2.2 - frac) * Math.PI);
                    Vec3d whipPt = rootSocket
                            .add(whipDir.multiply(i * segLen * Math.min(1.0, strikeP * 1.45)))
                            .add(sideVec.multiply(wave * 1.35 * (index % 2 == 0 ? 1 : -1)))
                            .add(0, Math.max(0.0, wave * 0.9), 0);
                    this.nodes[i] = this.nodes[i].lerp(whipPt, 0.55);
                }
            }
        }
    }

    private void solveDistanceBendingAndSectorConstraints(Vec3d bodyPos, Vec3d rootSocket,
                                                           Vec3d sectorDir, float segLen, float stiffness) {
        boolean pinMedialPatch = (this.state == LimbState.ANCHORED_PULL || this.state == LimbState.ANCHORED_PUSH);

        // Modulación de elongación/acortamiento proximal (Levy et al., Current Biology 2015: Pushing-by-Elongation & Pulling-by-Shortening)
        float proximalStrainScale = 1.0f;
        if (this.state == LimbState.ANCHORED_PUSH) {
            proximalStrainScale = 1.0f + 0.18f * this.pullTension; // Elongación activa del tramo proximal para empujar el cuerpo
        } else if (this.state == LimbState.ANCHORED_PULL) {
            proximalStrainScale = 1.0f - 0.14f * this.pullTension; // Acortamiento activo del tramo proximal para traccionar el cuerpo
        }

        for (int iter = 0; iter < 2; iter++) {
            this.nodes[0] = rootSocket;
            if (pinMedialPatch) {
                this.nodes[10] = this.anchorWorldPos;
            }
            for (int i = 0; i < NUM_NODES - 1; i++) {
                Vec3d p0 = this.nodes[i];
                Vec3d p1 = this.nodes[i + 1];
                Vec3d delta = p1.subtract(p0);
                double d = delta.length();
                if (d > 1e-5) {
                    double targetSegLen = (i < 10) ? (segLen * proximalStrainScale) : segLen;
                    double diff = (d - targetSegLen) / d;
                    if (i == 0) {
                        this.nodes[i + 1] = p1.subtract(delta.multiply(diff * 0.88));
                    } else if (pinMedialPatch && i + 1 == 10) {
                        this.nodes[i] = p0.add(delta.multiply(diff * 0.88));
                    } else if (pinMedialPatch && i == 10) {
                        this.nodes[i + 1] = p1.subtract(delta.multiply(diff * 0.88));
                    } else {
                        this.nodes[i] = p0.add(delta.multiply(diff * 0.46));
                        this.nodes[i + 1] = p1.subtract(delta.multiply(diff * 0.46));
                    }
                }
            }
        }

        // Restricción de sector radial: ningún nodo puede meterse adentro del cuerpo ni cruzarse al lado opuesto del pulpo
        for (int i = 1; i < NUM_NODES; i++) {
            Vec3d relFromBody = this.nodes[i].subtract(bodyPos);
            Vec3d horiz = new Vec3d(relFromBody.x, 0, relFromBody.z);
            double hLen = horiz.length();
            if (hLen > 1e-4) {
                Vec3d hDir = horiz.multiply(1.0 / hLen);
                double dotSector = hDir.dotProduct(sectorDir);
                // Si el nodo intenta doblarse hacia atrás atravesando el cuerpo (dotSector < 0.15), proyectarlo hacia su sector!
                if (dotSector < 0.15 && this.state != LimbState.THROW_WINDUP && this.state != LimbState.SWIMMING_JET) {
                    Vec3d correctedDir = hDir.lerp(sectorDir, 0.35).normalize();
                    this.nodes[i] = new Vec3d(
                            bodyPos.x + correctedDir.x * Math.max(hLen, 0.65),
                            this.nodes[i].y,
                            bodyPos.z + correctedDir.z * Math.max(hLen, 0.65)
                    );
                }
            }
        }

        float bendFactor = MathHelper.clamp(0.20f * stiffness, 0.06f, 0.38f);
        for (int i = 1; i < NUM_NODES - 1; i++) {
            if (this.state == LimbState.COILED_CARRYING && i >= NUM_NODES / 2) continue;
            Vec3d midpoint = this.nodes[i - 1].add(this.nodes[i + 1]).multiply(0.5);
            this.nodes[i] = this.nodes[i].lerp(midpoint, bendFactor * 0.55);
        }
    }

    /**
     * Resuelve colisiones en las 6 direcciones (+Y piso, -Y techo de cueva, +-X y +-Z paredes)
     * para que ningún nodo atraviese techos en cuevas (`image_cd3eb8.png`) ni paredes ni suelos.
     */
    private void solveSixWayVoxelCollisions(World world) {
        for (int i = 1; i < NUM_NODES; i++) {
            Vec3d pt = this.nodes[i];
            float r = this.currentRadii[i] * 0.58f;
            BlockPos bp = BlockPos.ofFloored(pt.x, pt.y, pt.z);
            BlockState st = world.getBlockState(bp);
            if (!st.isAir()) {
                VoxelShape shape = st.getCollisionShape(world, bp);
                if (!shape.isEmpty()) {
                    Box b = shape.getBoundingBox().offset(bp);
                    if (pt.x > b.minX - r && pt.x < b.maxX + r
                            && pt.y > b.minY - r && pt.y < b.maxY + r
                            && pt.z > b.minZ - r && pt.z < b.maxZ + r) {
                        double penTop = (b.maxY + r) - pt.y;
                        double penBottom = pt.y - (b.minY - r);
                        double penWest = pt.x - (b.minX - r);
                        double penEast = (b.maxX + r) - pt.x;
                        double penNorth = pt.z - (b.minZ - r);
                        double penSouth = (b.maxZ + r) - pt.z;

                        // Si el bloque de arriba de esta celda es sólido, no empujar hacia +Y (evita atravesar techos de cuevas!)
                        if (!world.getBlockState(bp.up()).getCollisionShape(world, bp.up()).isEmpty()) {
                            penTop = 999.0;
                        }
                        if (!world.getBlockState(bp.down()).getCollisionShape(world, bp.down()).isEmpty()) {
                            penBottom = 999.0;
                        }

                        double minPen = penTop;
                        int bestDir = 0; // 0=+Y, 1=-Y, 2=-X, 3=+X, 4=-Z, 5=+Z
                        if (penBottom < minPen) { minPen = penBottom; bestDir = 1; }
                        if (penWest < minPen) { minPen = penWest; bestDir = 2; }
                        if (penEast < minPen) { minPen = penEast; bestDir = 3; }
                        if (penNorth < minPen) { minPen = penNorth; bestDir = 4; }
                        if (penSouth < minPen) { minPen = penSouth; bestDir = 5; }

                        if (minPen < 1.2) {
                            this.nodes[i] = switch (bestDir) {
                                case 0 -> new Vec3d(pt.x, b.maxY + r, pt.z);
                                case 1 -> new Vec3d(pt.x, b.minY - r, pt.z);
                                case 2 -> new Vec3d(b.minX - r, pt.y, pt.z);
                                case 3 -> new Vec3d(b.maxX + r, pt.y, pt.z);
                                case 4 -> new Vec3d(pt.x, pt.y, b.minZ - r);
                                default -> new Vec3d(pt.x, pt.y, b.maxZ + r);
                            };
                            this.verletOldNodes[i] = this.nodes[i].lerp(this.verletOldNodes[i], 0.35);
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
