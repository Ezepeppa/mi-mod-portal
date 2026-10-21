package com.argon.octopus;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Entidad del Pulpo Terrestre Gigante Montable v2.0.0.
 *
 * Características clave v2.0.0:
 * - Locomoción 100% conducida por tentáculos (CERO movimiento directo de `WASD` sobre el cuerpo):
 *   cuando apretás `WASD`, primero se deslizan los tentáculos por el suelo en sucesión asíncrona,
 *   clavan sus ventosas y recién al tensarse arrastran al cuerpo pesado!
 * - Solver rápido `O(N)` de colisión entre tentáculos (`solveInterTentacleCollisions`) para que nunca se atraviesen.
 * - Inclinación 3D completa de la base del cuerpo (`bodyPitch` y `bodyRoll`) adaptándose a desniveles y paredes verticales.
 * - Compañero leal: una vez que te montás, registra tu UUID (`ownerUuid`), no se escapa nunca y te sigue como un perro
 *   cuando te alejás (o se queda quieto con `Shift + Clic Derecho` con mano vacía).
 * - Natación real en el agua (`canBreatheInWater() -> true`, propulsión a chorro + ondulación de tentáculos).
 * - Agarre procedural progresivo (el tentáculo viaja hasta el bloque/mob antes de arrancarlo) y lanzamiento
 *   con wind-up e inercia real (`FallingBlockEntity` volador).
 */
public class GiantOctopusEntity extends PathAwareEntity {
    private static final TrackedData<Integer> HELD_BLOCK_RAW_ID = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> HELD_ENTITY_ID = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ACTIVE_TENTACLE_COUNT = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> SYNCED_TENTACLE_LENGTH = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Optional<UUID>> OWNER_UUID = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Boolean> SITTING_STAY = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private final List<TentacleLimb> tentacles = new ArrayList<>();
    public Vec3d showcaseTargetPos = null;
    public int showcaseStage = 0;

    // Inclinación 3D dinámica del cuerpo (Pitch adelante/atrás y Roll izquierda/derecha)
    public float bodyPitch = 0.0f;
    public float prevBodyPitch = 0.0f;
    public float bodyRoll = 0.0f;
    public float prevBodyRoll = 0.0f;

    // Objetivo pendiente durante la fase `REACHING_TO_GRAB` antes de que la punta del tentáculo toque el objeto
    @Nullable
    private BlockPos pendingGrabBlockPos = null;
    private int pendingGrabEntityId = -1;

    public GiantOctopusEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        this.setStepHeight(1.35f);
        this.ignoreCameraFrustum = true;
        rebuildTentacleArray(OctopusPhysicsConfig.tentacleCount);
    }

    public static DefaultAttributeContainer.Builder createOctopusAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 80.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.85)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 10.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(HELD_BLOCK_RAW_ID, 0);
        this.dataTracker.startTracking(HELD_ENTITY_ID, -1);
        this.dataTracker.startTracking(ACTIVE_TENTACLE_COUNT, OctopusPhysicsConfig.tentacleCount);
        this.dataTracker.startTracking(SYNCED_TENTACLE_LENGTH, OctopusPhysicsConfig.tentacleLength);
        this.dataTracker.startTracking(OWNER_UUID, Optional.empty());
        this.dataTracker.startTracking(SITTING_STAY, false);
    }

    @Override
    public boolean canBreatheInWater() {
        return true; // Es un pulpo: respira perfectamente bajo el agua y jamás se ahoga!
    }

    @Override
    public boolean isPushedByFluids() {
        return false;
    }

    @Nullable
    public UUID getOwnerUuid() {
        return this.dataTracker.get(OWNER_UUID).orElse(null);
    }

    public void setOwnerUuid(@Nullable UUID uuid) {
        this.dataTracker.set(OWNER_UUID, Optional.ofNullable(uuid));
    }

    public boolean isSittingStay() {
        return this.dataTracker.get(SITTING_STAY);
    }

    public void setSittingStay(boolean stay) {
        this.dataTracker.set(SITTING_STAY, stay);
    }

    public List<TentacleLimb> getTentacles() {
        int desiredCount = MathHelper.clamp(this.dataTracker.get(ACTIVE_TENTACLE_COUNT), 4, 12);
        if (tentacles.size() != desiredCount) {
            rebuildTentacleArray(desiredCount);
        }
        return tentacles;
    }

    public int countCurrentlyCreepingLimbs() {
        int count = 0;
        for (TentacleLimb limb : tentacles) {
            if (limb.state == TentacleLimb.LimbState.SLIDING_CREEP) {
                count++;
            }
        }
        return count;
    }

    /**
     * Inhibición lateral entre brazos vecinos (Nishii & Ikeda, AMAM 2019 / EPFL - Octopus sinensis):
     * Evita que dos tentáculos contiguos (k-1 o k+1) se despeguen del sustrato al mismo tiempo,
     * manteniendo un factor de soporte >= 70% y generando una onda metacrónica real.
     */
    public boolean isAdjacentNeighborCreeping(int limbIndex) {
        int n = tentacles.size();
        if (n < 3) return false;
        int prevIdx = (limbIndex - 1 + n) % n;
        int nextIdx = (limbIndex + 1) % n;
        return tentacles.get(prevIdx).state == TentacleLimb.LimbState.SLIDING_CREEP
                || tentacles.get(nextIdx).state == TentacleLimb.LimbState.SLIDING_CREEP;
    }

    public void rebuildTentacleArray(int count) {
        int validCount = MathHelper.clamp(count, 4, 12);
        while (tentacles.size() < validCount) {
            tentacles.add(new TentacleLimb(tentacles.size(), validCount, this.getPos()));
        }
        while (tentacles.size() > validCount) {
            tentacles.remove(tentacles.size() - 1);
        }
        for (int i = 0; i < tentacles.size(); i++) {
            tentacles.get(i).updateBaseAngle(i, validCount);
        }
    }

    public void applyUpdatedConfig(int count, float length, float pull, float grav, float stiff, boolean climb) {
        this.dataTracker.set(ACTIVE_TENTACLE_COUNT, MathHelper.clamp(count, 4, 12));
        this.dataTracker.set(SYNCED_TENTACLE_LENGTH, MathHelper.clamp(length, 3.5f, 12.0f));
        rebuildTentacleArray(count);
    }

    public BlockState getHeldBlockState() {
        int raw = this.dataTracker.get(HELD_BLOCK_RAW_ID);
        return raw == 0 ? Blocks.AIR.getDefaultState() : Block.getStateFromRawId(raw);
    }

    public void setHeldBlockState(BlockState state) {
        this.dataTracker.set(HELD_BLOCK_RAW_ID, state.isAir() ? 0 : Block.getRawIdFromState(state));
    }

    @Nullable
    public Entity getHeldEntity() {
        int id = this.dataTracker.get(HELD_ENTITY_ID);
        return id < 0 ? null : this.getWorld().getEntityById(id);
    }

    public void setHeldEntity(@Nullable Entity entity) {
        this.dataTracker.set(HELD_ENTITY_ID, entity == null ? -1 : entity.getId());
    }

    @Nullable
    public Entity getPendingGrabEntity() {
        return pendingGrabEntityId < 0 ? null : this.getWorld().getEntityById(pendingGrabEntityId);
    }

    public boolean isHoldingAnything() {
        return !getHeldBlockState().isAir() || getHeldEntity() != null;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isOf(OctopusMod.OCTOPUS_CONTROLLER)) {
            return ActionResult.PASS;
        }
        if (!this.getWorld().isClient) {
            // Registrar automáticamente al jugador como dueño para que lo siga fielmente al bajarse
            if (getOwnerUuid() == null) {
                setOwnerUuid(player.getUuid());
            }
            if (player.isSneaking()) {
                boolean nextStay = !isSittingStay();
                setSittingStay(nextStay);
                player.sendMessage(Text.literal("Pulpo Compañero: ").formatted(Formatting.GOLD)
                        .append(Text.literal(nextStay ? "QUEDARSE QUIETO" : "SEGUIRTE COMO COMPAÑERO")
                                .formatted(nextStay ? Formatting.YELLOW : Formatting.GREEN)), true);
                return ActionResult.SUCCESS;
            }
            setSittingStay(false);
            player.startRiding(this);
            player.sendMessage(Text.literal("Montaste al Pulpo Gigante: ")
                    .formatted(Formatting.GOLD)
                    .append(Text.literal("WASD = Arrastrarse con Tentáculos / Nadar | R = Agarrar/Lanzar | G = Latigazo | Espacio = Impulso | O = Menú")
                            .formatted(Formatting.AQUA)), true);
            return ActionResult.SUCCESS;
        }
        return ActionResult.SUCCESS;
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        Entity first = this.getFirstPassenger();
        return (first instanceof LivingEntity le) ? le : null;
    }

    @Override
    public double getMountedHeightOffset() {
        return 1.02;
    }

    @Override
    public void tick() {
        super.tick();

        this.prevBodyPitch = this.bodyPitch;
        this.prevBodyRoll = this.bodyRoll;

        if (this.getWorld().isClient) {
            OctopusPhysicsConfig.tentacleLength = this.dataTracker.get(SYNCED_TENTACLE_LENGTH);
        }

        // 1. Calcular el vector de intención de avance (del jinete, de seguir al dueño como un perro, o del Showcase)
        Vec3d moveIntentDir = computeDesiredMovementIntent();

        // 2. Actualizar la máquina de estados de cada tentáculo y sumar únicamente la fuerza física real de los tentáculos anclados
        float bodyYawRad = (float) Math.toRadians(this.getYaw());
        Vec3d totalTentacleForce = Vec3d.ZERO;
        int anchoredCount = 0;

        List<TentacleLimb> activeLimbs = getTentacles();
        for (TentacleLimb limb : activeLimbs) {
            Vec3d limbForce = limb.tickPhysics(
                    this.getWorld(), this, this.getPos(),
                    bodyYawRad, this.bodyPitch, this.bodyRoll, moveIntentDir
            );
            totalTentacleForce = totalTentacleForce.add(limbForce);
            if (limb.state == TentacleLimb.LimbState.ANCHORED_PULL || limb.state == TentacleLimb.LimbState.ANCHORED_PUSH) {
                anchoredCount++;
            }
        }

        // 3. Ejecutar 5 sub-pasos de física acoplada (Verlet + Hidrostato + Colisión Entre Tentáculos + Colisión Voxel 6 Caras)
        int subSteps = 5;
        for (int step = 0; step < subSteps; step++) {
            for (TentacleLimb limb : activeLimbs) {
                limb.runPhysicsSubStep(
                        this.getWorld(), this, this.getPos(),
                        bodyYawRad, this.bodyPitch, this.bodyRoll,
                        moveIntentDir, step, subSteps
                );
            }
            // Resolver colisión suave esfera-contra-esfera entre tentáculos distintos (evita que se atraviesen sin matar FPS!)
            solveInterTentacleCollisions(activeLimbs);
        }

        for (TentacleLimb limb : activeLimbs) {
            limb.updateRadiiAndSuctionIndicators();
        }

        // 4. Actualizar la inclinación 3D dinámica del cuerpo (Pitch y Roll) según la distribución de alturas de los tentáculos
        updateDynamic3DBodyTilt(activeLimbs, bodyYawRad);

        // 5. Mover el cuerpo (en tierra EXCLUSIVAMENTE por la fuerza de los tentáculos; en agua por propulsión a chorro)
        if (this.isTouchingWater()) {
            applyAquaticJetSwimmingLocomotion(moveIntentDir);
        } else {
            applyPureTentacleDrivenTerrestrialLocomotion(totalTentacleForce, anchoredCount);
        }

        // 6. Actualizar posición de entidad capturada en la espiral del tentáculo #0
        Entity heldEnt = getHeldEntity();
        if (heldEnt != null && !activeLimbs.isEmpty()) {
            if (!heldEnt.isAlive()) {
                setHeldEntity(null);
            } else {
                Vec3d coilCenter = activeLimbs.get(0).anchorWorldPos;
                heldEnt.setPosition(coilCenter.x, coilCenter.y - heldEnt.getHeight() * 0.4, coilCenter.z);
                heldEnt.setVelocity(Vec3d.ZERO);
                heldEnt.fallDistance = 0.0f;
            }
        }

        if (!this.getWorld().isClient && this.showcaseStage > 0) {
            OctopusShowcaseManager.tickShowcaseOctopus(this);
        }
    }

    /**
     * Solver rápido `O(N)` de colisión y repulsión entre tentáculos distintos:
     * Evita que dos tentáculos se atraviesen entre sí empujando sus nodos radialmente y deslizando uno sobre otro.
     */
    private void solveInterTentacleCollisions(List<TentacleLimb> limbs) {
        int nLimbs = limbs.size();
        if (nLimbs < 2) return;

        for (int a = 0; a < nLimbs; a++) {
            TentacleLimb limbA = limbs.get(a);
            for (int b = a + 1; b < nLimbs; b++) {
                TentacleLimb limbB = limbs.get(b);
                for (int i = 2; i < TentacleLimb.NUM_NODES; i++) {
                    Vec3d pA = limbA.nodes[i];
                    float rA = limbA.currentRadii[i];
                    int jStart = Math.max(2, i - 2);
                    int jEnd = Math.min(TentacleLimb.NUM_NODES - 1, i + 2);
                    for (int j = jStart; j <= jEnd; j++) {
                        Vec3d pB = limbB.nodes[j];
                        float minDist = (rA + limbB.currentRadii[j]) * 0.92f;
                        double dx = pB.x - pA.x;
                        double dy = pB.y - pA.y;
                        double dz = pB.z - pA.z;
                        double distSq = dx * dx + dy * dy + dz * dz;
                        if (distSq < minDist * minDist && distSq > 1e-6) {
                            double dist = Math.sqrt(distSq);
                            double overlap = (minDist - dist) * 0.48;
                            double nx = dx / dist;
                            double ny = dy / dist;
                            double nz = dz / dist;
                            // Pequeño sesgo vertical para que un tentáculo pase limpiamente por encima del otro sin trabarse
                            if (Math.abs(ny) < 0.25) {
                                ny = (a < b) ? 0.35 : -0.35;
                            }
                            Vec3d push = new Vec3d(nx * overlap, ny * overlap, nz * overlap);
                            if (i < TentacleLimb.NUM_NODES - 1 || limbA.state == TentacleLimb.LimbState.DANGLING_FREE) {
                                limbA.nodes[i] = pA.subtract(push);
                            }
                            if (j < TentacleLimb.NUM_NODES - 1 || limbB.state == TentacleLimb.LimbState.DANGLING_FREE) {
                                limbB.nodes[j] = pB.add(push);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Calcula la inclinación 3D orgánica del cuerpo (`bodyPitch` y `bodyRoll`) a partir del plano
     * que forman los nodos medios de los tentáculos alrededor del pulpo.
     */
    private void updateDynamic3DBodyTilt(List<TentacleLimb> limbs, float bodyYawRad) {
        if (limbs.isEmpty()) return;
        Vec3d fwd = new Vec3d(-Math.sin(bodyYawRad), 0, Math.cos(bodyYawRad));
        Vec3d right = new Vec3d(Math.cos(bodyYawRad), 0, Math.sin(bodyYawRad));
        Vec3d center = this.getPos().add(0, 0.35, 0);

        double pitchSum = 0.0;
        double rollSum = 0.0;
        int samples = 0;

        for (TentacleLimb limb : limbs) {
            Vec3d samplePt = limb.nodes[6];
            Vec3d rel = samplePt.subtract(center);
            double fwdProj = rel.x * fwd.x + rel.z * fwd.z;
            double rightProj = rel.x * right.x + rel.z * right.z;

            if (Math.abs(fwdProj) > 0.25) {
                pitchSum += Math.atan2(rel.y, Math.abs(fwdProj)) * Math.signum(fwdProj);
            }
            if (Math.abs(rightProj) > 0.25) {
                rollSum += Math.atan2(rel.y, Math.abs(rightProj)) * Math.signum(rightProj);
            }
            samples++;
        }

        float targetPitch = 0.0f;
        float targetRoll = 0.0f;
        if (samples > 0) {
            targetPitch = (float) Math.toDegrees(-pitchSum / samples) * 1.45f;
            targetRoll = (float) Math.toDegrees(rollSum / samples) * 1.45f;
        }

        // Si está trepando una pared vertical, inclinar el cuerpo pronunciadamente hacia la pared
        if (this.horizontalCollision && OctopusPhysicsConfig.wallClimbingEnabled) {
            targetPitch = -58.0f;
        }

        targetPitch = MathHelper.clamp(targetPitch, -68.0f, 55.0f);
        targetRoll = MathHelper.clamp(targetRoll, -48.0f, 48.0f);

        this.bodyPitch = MathHelper.lerp(0.18f, this.bodyPitch, targetPitch);
        this.bodyRoll = MathHelper.lerp(0.18f, this.bodyRoll, targetRoll);
    }

    private Vec3d computeDesiredMovementIntent() {
        LivingEntity rider = getControllingPassenger();
        if (rider instanceof PlayerEntity player) {
            // Giro suave del cuerpo hacia la cámara del jugador
            float yawDiff = MathHelper.wrapDegrees(player.getYaw() - this.getYaw());
            this.setYaw(this.getYaw() + yawDiff * 0.25f);
            this.bodyYaw = this.getYaw();
            this.headYaw = this.getYaw();

            float forward = player.forwardSpeed;
            float strafe = player.sidewaysSpeed;
            if (Math.abs(forward) < 0.01f && Math.abs(strafe) < 0.01f) {
                return Vec3d.ZERO;
            }
            float yawRad = (float) Math.toRadians(player.getYaw());
            double pitchY = this.isTouchingWater() ? (-Math.sin(Math.toRadians(player.getPitch())) * forward) : 0.0;
            Vec3d fwdVec = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
            Vec3d rightVec = new Vec3d(Math.cos(yawRad), 0, Math.sin(yawRad));
            Vec3d horiz = fwdVec.multiply(forward).add(rightVec.multiply(strafe));
            if (horiz.lengthSquared() > 1e-4) {
                horiz = horiz.normalize();
            }
            return new Vec3d(horiz.x, pitchY, horiz.z);
        }

        if (this.showcaseTargetPos != null) {
            Vec3d diff = this.showcaseTargetPos.subtract(this.getPos());
            if (diff.horizontalLengthSquared() > 0.45) {
                float targetYaw = (float) (MathHelper.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
                this.setYaw(MathHelper.wrapDegrees(targetYaw));
                this.bodyYaw = this.getYaw();
                this.headYaw = this.getYaw();
                return new Vec3d(diff.x, 0, diff.z).normalize();
            }
            return Vec3d.ZERO;
        }

        // Comportamiento de Compañero Leal (como un perro): NUNCA se va solo; si tiene dueño y no está en "Quedarse quieto",
        // sigue a su dueño cuando este se aleja a más de 6.0 bloques, y se detiene a su lado a menos de 4.2 bloques!
        if (!isSittingStay()) {
            UUID ownerId = getOwnerUuid();
            PlayerEntity owner = (ownerId != null)
                    ? this.getWorld().getPlayerByUuid(ownerId)
                    : this.getWorld().getClosestPlayer(this, 24.0);
            if (owner != null && !owner.isSpectator()) {
                double distSq = this.squaredDistanceTo(owner);
                if (distSq > 36.0 && distSq < 1600.0) {
                    Vec3d toOwner = owner.getPos().subtract(this.getPos());
                    float targetYaw = (float) (MathHelper.atan2(toOwner.z, toOwner.x) * (180.0 / Math.PI)) - 90.0f;
                    float yawDiff = MathHelper.wrapDegrees(targetYaw - this.getYaw());
                    this.setYaw(this.getYaw() + yawDiff * 0.18f);
                    this.bodyYaw = this.getYaw();
                    this.headYaw = this.getYaw();
                    return toOwner.normalize();
                } else if (distSq <= 36.0) {
                    // Mirar amistosamente al dueño mientras descansa quieto a su lado
                    Vec3d toOwner = owner.getPos().subtract(this.getPos());
                    float targetYaw = (float) (MathHelper.atan2(toOwner.z, toOwner.x) * (180.0 / Math.PI)) - 90.0f;
                    float yawDiff = MathHelper.wrapDegrees(targetYaw - this.getYaw());
                    this.setYaw(this.getYaw() + yawDiff * 0.08f);
                    this.bodyYaw = this.getYaw();
                    this.headYaw = this.getYaw();
                }
            }
        }
        return Vec3d.ZERO;
    }

    /**
     * Locomoción Terrestre 100% conducida por los tentáculos:
     * - Fijate que aquí NO se suma `moveIntentDir` a la velocidad del cuerpo!
     * - El cuerpo SOLO se mueve por `totalTentacleForce` (la suma de fuerzas de los tentáculos que ya se deslizaron,
     *   clavaron sus ventosas y acumularon `pullTension`), produciendo el movimiento pesado, pausado y orgánico real.
     */
    private void applyPureTentacleDrivenTerrestrialLocomotion(Vec3d totalTentacleForce, int anchoredCount) {
        Vec3d currentVel = this.getVelocity();

        // Si menos de 2 tentáculos están anclados, el cuerpo no tiene apoyo para traccionar y cae por gravedad real
        double supportFactor = (anchoredCount >= 2) ? MathHelper.clamp(anchoredCount / 4.5, 0.25, 1.15) : 0.05;

        // Velocidad máxima controlada y natural (no rápida ni brusca)
        double vx = currentVel.x * 0.62 + totalTentacleForce.x * supportFactor;
        double vz = currentVel.z * 0.62 + totalTentacleForce.z * supportFactor;
        double maxCrawlSpeed = 0.21 * OctopusPhysicsConfig.suctionPullStrength;
        double hSpeed = Math.sqrt(vx * vx + vz * vz);
        if (hSpeed > maxCrawlSpeed) {
            vx = (vx / hSpeed) * maxCrawlSpeed;
            vz = (vz / hSpeed) * maxCrawlSpeed;
        }

        double vy = currentVel.y - (0.078 * OctopusPhysicsConfig.gravitySlumpFactor);

        // Escalada vertical cuando los tentáculos están anclados arriba en una pared
        boolean climbingWall = OctopusPhysicsConfig.wallClimbingEnabled
                && (this.horizontalCollision || totalTentacleForce.y > 0.02)
                && totalTentacleForce.y > 0.012
                && anchoredCount >= 2;

        if (climbingWall) {
            vy = MathHelper.clamp(totalTentacleForce.y * 1.85, 0.08, 0.22);
            this.fallDistance = 0.0f;
        }

        this.setVelocity(vx, vy, vz);
        this.move(MovementType.SELF, this.getVelocity());
    }

    /**
     * Locomoción Acuática Real (Propulsión a Chorro por Sifón + Flotabilidad Neutra):
     * En el agua no se hunde ni se ahoga; avanza mediante pulsos rítmicos de chorro en 3D.
     */
    private void applyAquaticJetSwimmingLocomotion(Vec3d moveIntentDir) {
        Vec3d currentVel = this.getVelocity();
        double jetPulse = 0.78 + 0.35 * Math.sin(this.age * 0.25);

        if (moveIntentDir.lengthSquared() > 1e-4) {
            double targetVx = moveIntentDir.x * 0.26 * jetPulse;
            double targetVy = moveIntentDir.y * 0.22 + 0.015 * Math.sin(this.age * 0.25);
            double targetVz = moveIntentDir.z * 0.26 * jetPulse;
            this.setVelocity(
                    MathHelper.lerp(0.22, currentVel.x, targetVx),
                    MathHelper.lerp(0.22, currentVel.y, targetVy),
                    MathHelper.lerp(0.22, currentVel.z, targetVz)
            );
        } else {
            // Flotabilidad neutra suave en el agua
            double hoverVy = Math.sin(this.age * 0.12) * 0.012;
            this.setVelocity(currentVel.x * 0.85, MathHelper.lerp(0.2, currentVel.y, hoverVy), currentVel.z * 0.85);
        }
        this.fallDistance = 0.0f;
        this.move(MovementType.SELF, this.getVelocity());
    }

    /**
     * Ejecuta las interacciones procedurales de los tentáculos con el mundo:
     * - `actionType == 1` (`Tecla R`):
     *   * Si no sostiene nada: inicia `REACHING_TO_GRAB` para que el tentáculo #0 viaje físicamente hasta el bloque o mob!
     *   * Si ya sostiene algo: inicia `THROW_WINDUP` -> `THROW_RELEASE` (carga hacia atrás y lanza con inercia real!).
     * - `actionType == 2` (`Tecla G`): Latigazo frontal de 20 ticks en 3 fases.
     * - `actionType == 3` (`Espacio`): Impulso muscular elástico.
     */
    public void performRiderTentacleAction(PlayerEntity player, int actionType) {
        World world = this.getWorld();
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f);
        List<TentacleLimb> limbs = getTentacles();
        if (limbs.isEmpty()) return;

        if (actionType == 1) {
            TentacleLimb grabLimb = limbs.get(0);

            // Si ya sostiene un bloque o criatura, iniciar la animación procedural de LANZAMIENTO CON INERCIA (Wind-Up -> Release)!
            if (isHoldingAnything()) {
                grabLimb.state = TentacleLimb.LimbState.THROW_WINDUP;
                grabLimb.actionProgress = 0.0f;
                grabLimb.throwDirection = look.normalize();
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.NEUTRAL, 0.9f, 0.6f);
                return;
            }

            // Si no sostiene nada, buscar objetivo y lanzar el tentáculo #0 en estado `REACHING_TO_GRAB` (viaje procedural hacia el objeto!)
            Vec3d reachEnd = eye.add(look.multiply(OctopusPhysicsConfig.tentacleLength + 3.0));
            List<Entity> candidates = world.getOtherEntities(this, this.getBoundingBox().expand(OctopusPhysicsConfig.tentacleLength + 2.5),
                    e -> e != player && e instanceof LivingEntity);
            Entity closestMob = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity c : candidates) {
                if (c.getBoundingBox().expand(0.65).raycast(eye, reachEnd).isPresent()) {
                    double d = c.squaredDistanceTo(eye);
                    if (d < bestDist) {
                        bestDist = d;
                        closestMob = c;
                    }
                }
            }

            if (closestMob != null) {
                this.pendingGrabEntityId = closestMob.getId();
                this.pendingGrabBlockPos = null;
                grabLimb.state = TentacleLimb.LimbState.REACHING_TO_GRAB;
                grabLimb.actionProgress = 0.0f;
                grabLimb.actionStartPos = grabLimb.nodes[TentacleLimb.NUM_NODES - 1];
                grabLimb.actionTargetPos = closestMob.getPos().add(0, closestMob.getHeight() * 0.5, 0);
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_JUMP_SMALL, SoundCategory.NEUTRAL, 0.8f, 0.9f);
                return;
            }

            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    eye, reachEnd,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this
            ));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = blockHit.getBlockPos();
                BlockState st = world.getBlockState(bp);
                if (!st.isAir() && st.getHardness(world, bp) >= 0.0f && st.getHardness(world, bp) < 40.0f && world.getBlockEntity(bp) == null) {
                    this.pendingGrabBlockPos = bp.toImmutable();
                    this.pendingGrabEntityId = -1;
                    grabLimb.state = TentacleLimb.LimbState.REACHING_TO_GRAB;
                    grabLimb.actionProgress = 0.0f;
                    grabLimb.actionStartPos = grabLimb.nodes[TentacleLimb.NUM_NODES - 1];
                    grabLimb.actionTargetPos = Vec3d.ofCenter(bp);
                    world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_JUMP_SMALL, SoundCategory.NEUTRAL, 0.8f, 0.9f);
                }
            }
        } else if (actionType == 2) {
            // Latigazo frontal visible de 20 ticks en los 2 tentáculos delanteros
            Vec3d whipTarget = eye.add(look.multiply(OctopusPhysicsConfig.tentacleLength + 1.5));
            for (int i = 0; i < Math.min(2, limbs.size()); i++) {
                TentacleLimb limb = limbs.get(i);
                limb.state = TentacleLimb.LimbState.WHIP_STRIKE;
                limb.actionProgress = 0.0f;
                limb.actionTargetPos = whipTarget;
            }

            Box strikeBox = this.getBoundingBox().stretch(look.multiply(OctopusPhysicsConfig.tentacleLength + 1.0)).expand(2.2);
            List<LivingEntity> targets = world.getEntitiesByClass(LivingEntity.class, strikeBox, e -> e != this && e != player);
            for (LivingEntity t : targets) {
                t.damage(world.getDamageSources().mobAttack(this), 11.0f * OctopusPhysicsConfig.suctionPullStrength);
                t.setVelocity(look.multiply(1.65 * OctopusPhysicsConfig.suctionPullStrength).add(0, 0.45, 0));
            }
            if (world instanceof ServerWorld sw) {
                Vec3d sweepPos = this.getPos().add(look.multiply(3.2)).add(0, 0.8, 0);
                sw.spawnParticles(ParticleTypes.SWEEP_ATTACK, sweepPos.x, sweepPos.y, sweepPos.z, 4, 0.8, 0.3, 0.8, 0.0);
            }
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.3f, 0.55f);
        } else if (actionType == 3) {
            // Impulso de compresión muscular con `Espacio` (funciona tanto en tierra como en el agua)
            Vec3d jumpBoost = new Vec3d(look.x * 0.72, 0.76, look.z * 0.72).multiply(OctopusPhysicsConfig.suctionPullStrength);
            this.setVelocity(this.getVelocity().add(jumpBoost));
            this.velocityModified = true;
            for (TentacleLimb limb : limbs) {
                if (limb.state == TentacleLimb.LimbState.ANCHORED_PULL || limb.state == TentacleLimb.LimbState.ANCHORED_PUSH) {
                    limb.state = TentacleLimb.LimbState.DANGLING_FREE;
                    limb.pullTension = 0.0f;
                }
            }
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_JUMP, SoundCategory.NEUTRAL, 1.2f, 0.65f);
        }
    }

    /**
     * Se invoca exactamente en el tick en que la punta del tentáculo #0 llega hasta el bloque o mob objetivo (`REACHING_TO_GRAB` completado).
     */
    public void completeProceduralGrabAtTarget() {
        World world = this.getWorld();
        if (world.isClient) return;

        if (this.pendingGrabEntityId >= 0) {
            Entity target = world.getEntityById(this.pendingGrabEntityId);
            this.pendingGrabEntityId = -1;
            if (target != null && target.isAlive()) {
                setHeldEntity(target);
                world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.NEUTRAL, 1.0f, 0.75f);
                return;
            }
        }

        if (this.pendingGrabBlockPos != null) {
            BlockPos bp = this.pendingGrabBlockPos;
            this.pendingGrabBlockPos = null;
            BlockState st = world.getBlockState(bp);
            if (!st.isAir() && st.getHardness(world, bp) >= 0.0f && st.getHardness(world, bp) < 40.0f && world.getBlockEntity(bp) == null) {
                setHeldBlockState(st);
                world.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.playSound(null, bp, st.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1.1f, 0.8f);
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st),
                            bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 18, 0.35, 0.35, 0.35, 0.12);
                }
            }
        }
    }

    /**
     * Se invoca en el pico de velocidad del latigazo de lanzamiento (`THROW_RELEASE`):
     * Lanza el Mob o spawnea un `FallingBlockEntity` real con velocidad balística e inercia desde la punta del tentáculo!
     */
    public void executeBallisticReleaseFromTip(Vec3d releaseTipPos, Vec3d throwDir) {
        World world = this.getWorld();
        if (world.isClient) return;

        Vec3d launchVel = throwDir.normalize().multiply(1.85 * OctopusPhysicsConfig.suctionPullStrength).add(0, 0.32, 0);

        Entity heldEnt = getHeldEntity();
        if (heldEnt != null) {
            setHeldEntity(null);
            heldEnt.setPosition(releaseTipPos.x, releaseTipPos.y, releaseTipPos.z);
            heldEnt.setVelocity(launchVel);
            heldEnt.velocityModified = true;
            world.playSound(null, BlockPos.ofFloored(releaseTipPos), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.65f);
            return;
        }

        BlockState heldBlock = getHeldBlockState();
        if (!heldBlock.isAir()) {
            setHeldBlockState(Blocks.AIR.getDefaultState());
            BlockPos spawnPos = BlockPos.ofFloored(releaseTipPos.x, releaseTipPos.y + 0.3, releaseTipPos.z);
            BlockState existing = world.getBlockState(spawnPos);
            boolean wasAir = existing.isAir();
            if (wasAir) {
                world.setBlockState(spawnPos, heldBlock, 2);
            }
            FallingBlockEntity flyingBlock = FallingBlockEntity.spawnFromBlock(world, spawnPos, heldBlock);
            if (!wasAir) {
                world.setBlockState(spawnPos, existing, 2);
            }
            flyingBlock.setPosition(releaseTipPos.x, releaseTipPos.y + 0.2, releaseTipPos.z);
            flyingBlock.setVelocity(launchVel);
            flyingBlock.velocityModified = true;
            flyingBlock.setHurtEntities(8.0f, 24);
            world.playSound(null, spawnPos, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.65f);
        }
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("HeldBlockRaw", this.dataTracker.get(HELD_BLOCK_RAW_ID));
        nbt.putInt("TentacleCount", this.dataTracker.get(ACTIVE_TENTACLE_COUNT));
        nbt.putFloat("TentacleLength", this.dataTracker.get(SYNCED_TENTACLE_LENGTH));
        nbt.putBoolean("SittingStay", isSittingStay());
        if (getOwnerUuid() != null) {
            nbt.putUuid("OwnerUuid", getOwnerUuid());
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("HeldBlockRaw")) {
            this.dataTracker.set(HELD_BLOCK_RAW_ID, nbt.getInt("HeldBlockRaw"));
        }
        if (nbt.contains("TentacleCount")) {
            this.dataTracker.set(ACTIVE_TENTACLE_COUNT, nbt.getInt("TentacleCount"));
        }
        if (nbt.contains("TentacleLength")) {
            this.dataTracker.set(SYNCED_TENTACLE_LENGTH, nbt.getFloat("TentacleLength"));
        }
        if (nbt.contains("SittingStay")) {
            setSittingStay(nbt.getBoolean("SittingStay"));
        }
        if (nbt.containsUuid("OwnerUuid")) {
            setOwnerUuid(nbt.getUuid("OwnerUuid"));
        }
    }
}
