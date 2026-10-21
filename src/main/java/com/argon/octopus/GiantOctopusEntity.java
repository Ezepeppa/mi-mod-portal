package com.argon.octopus;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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

/**
 * Entidad principal del Pulpo Terrestre Gigante Montable.
 *
 * Su locomoción terrestre es 100% conducida por las fuerzas físicas de sus tentáculos (`TentacleLimb`):
 * - No usa animaciones pregrabadas ni caminata tradicional.
 * - El cuerpo recibe la suma vectorial de tracción/empuje de todos los tentáculos cuyas ventosas están
 *   adheridas al terreno o a las paredes verticales.
 * - Uno de sus tentáculos puede arrancar bloques del mundo o atrapar criaturas en una espiral muscular
 *   mientras los demás tentáculos continúan arrastrando el cuerpo, y lanzarlos como proyectiles pesados.
 */
public class GiantOctopusEntity extends PathAwareEntity {
    private static final TrackedData<Integer> HELD_BLOCK_RAW_ID = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> HELD_ENTITY_ID = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> ACTIVE_TENTACLE_COUNT = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> SYNCED_TENTACLE_LENGTH = DataTracker.registerData(GiantOctopusEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private final List<TentacleLimb> tentacles = new ArrayList<>();
    public Vec3d showcaseTargetPos = null;
    public int showcaseStage = 0;

    public GiantOctopusEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        this.setStepHeight(1.25f);
        this.ignoreCameraFrustum = true;
        rebuildTentacleArray(OctopusPhysicsConfig.tentacleCount);
    }

    public static DefaultAttributeContainer.Builder createOctopusAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 80.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.32)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.85)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 10.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(HELD_BLOCK_RAW_ID, 0);
        this.dataTracker.startTracking(HELD_ENTITY_ID, -1);
        this.dataTracker.startTracking(ACTIVE_TENTACLE_COUNT, OctopusPhysicsConfig.tentacleCount);
        this.dataTracker.startTracking(SYNCED_TENTACLE_LENGTH, OctopusPhysicsConfig.tentacleLength);
    }

    public List<TentacleLimb> getTentacles() {
        int desiredCount = MathHelper.clamp(this.dataTracker.get(ACTIVE_TENTACLE_COUNT), 4, 12);
        if (tentacles.size() != desiredCount) {
            rebuildTentacleArray(desiredCount);
        }
        return tentacles;
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
        this.dataTracker.set(SYNCED_TENTACLE_LENGTH, MathHelper.clamp(length, 3.0f, 12.0f));
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

    public boolean isHoldingAnything() {
        return !getHeldBlockState().isAir() || getHeldEntity() != null;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isOf(OctopusMod.OCTOPUS_CONTROLLER)) {
            return ActionResult.PASS;
        }
        if (!this.getWorld().isClient && !player.isSneaking()) {
            player.startRiding(this);
            player.sendMessage(Text.literal("Montaste al Pulpo Gigante: ")
                    .formatted(Formatting.GOLD)
                    .append(Text.literal("WASD = Arrastrarse / Trepar muros | R = Agarrar/Lanzar con Tentáculo | G = Latigazo | O = Menú de Físicas")
                            .formatted(Formatting.AQUA)), true);
            return ActionResult.SUCCESS;
        }
        return super.interactMob(player, hand);
    }

    @Nullable
    @Override
    public LivingEntity getControllingPassenger() {
        Entity first = this.getFirstPassenger();
        return (first instanceof LivingEntity le) ? le : null;
    }

    @Override
    public double getMountedHeightOffset() {
        return 1.08;
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient) {
            OctopusPhysicsConfig.tentacleLength = this.dataTracker.get(SYNCED_TENTACLE_LENGTH);
        }

        // 1. Determinar el vector de intención de movimiento (del jugador montado, del modo Showcase, o de IA autónoma)
        Vec3d desiredMove = computeDesiredMovementVector();

        // 2. Avanzar la física de los N tentáculos y sumar las fuerzas reales de tracción/empuje que ejercen sus ventosas
        float bodyYawRad = (float) Math.toRadians(this.getYaw());
        Vec3d totalTentacleForce = Vec3d.ZERO;
        int anchoredCount = 0;
        boolean holding = isHoldingAnything();

        List<TentacleLimb> activeLimbs = getTentacles();
        for (int i = 0; i < activeLimbs.size(); i++) {
            TentacleLimb limb = activeLimbs.get(i);
            boolean useForHolding = holding && (i == 0);
            Vec3d limbForce = limb.tickPhysics(this.getWorld(), this, this.getPos(), bodyYawRad, desiredMove, useForHolding);
            totalTentacleForce = totalTentacleForce.add(limbForce);
            if (limb.state == TentacleLimb.LimbState.ANCHORED_PULL || limb.state == TentacleLimb.LimbState.ANCHORED_PUSH) {
                anchoredCount++;
            }
        }

        // 3. Mover el cuerpo del pulpo en función de la fuerza neta de sus tentáculos y el peso fuera del agua
        applyTentacleDrivenBodyLocomotion(desiredMove, totalTentacleForce, anchoredCount);

        // 4. Si el tentáculo #0 sostiene un Mob en su espiral muscular, actualizar la posición del Mob en la punta enroscada
        Entity heldEnt = getHeldEntity();
        if (heldEnt != null && !activeLimbs.isEmpty()) {
            if (!heldEnt.isAlive()) {
                setHeldEntity(null);
            } else {
                Vec3d coilCenter = activeLimbs.get(0).anchorWorldPos;
                heldEnt.setPosition(coilCenter.x, coilCenter.y - heldEnt.getHeight() * 0.4, coilEntZ(coilCenter));
                heldEnt.setVelocity(Vec3d.ZERO);
                heldEnt.fallDistance = 0.0f;
            }
        }

        // 5. Ejecutar lógica guiada si el Modo Showcase está activo
        if (!this.getWorld().isClient && this.showcaseStage > 0) {
            OctopusShowcaseManager.tickShowcaseOctopus(this);
        }
    }

    private double coilEntZ(Vec3d c) {
        return c.z;
    }

    private Vec3d computeDesiredMovementVector() {
        LivingEntity rider = getControllingPassenger();
        if (rider instanceof PlayerEntity player) {
            this.setYaw(player.getYaw());
            this.prevYaw = this.getYaw();
            this.bodyYaw = this.getYaw();
            this.headYaw = this.getYaw();

            float forward = player.forwardSpeed;
            float strafe = player.sidewaysSpeed;
            if (Math.abs(forward) < 0.01f && Math.abs(strafe) < 0.01f) {
                return Vec3d.ZERO;
            }
            float yawRad = (float) Math.toRadians(player.getYaw());
            Vec3d fwdVec = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
            Vec3d rightVec = new Vec3d(Math.cos(yawRad), 0, Math.sin(yawRad));
            return fwdVec.multiply(forward).add(rightVec.multiply(strafe)).normalize().multiply(0.28);
        }

        if (this.showcaseTargetPos != null) {
            Vec3d diff = this.showcaseTargetPos.subtract(this.getPos());
            if (diff.horizontalLengthSquared() > 0.45) {
                float targetYaw = (float) (MathHelper.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
                this.setYaw(MathHelper.wrapDegrees(targetYaw));
                this.bodyYaw = this.getYaw();
                this.headYaw = this.getYaw();
                return new Vec3d(diff.x, 0, diff.z).normalize().multiply(0.24);
            }
            return Vec3d.ZERO;
        }

        // Deambular orgánico suave cuando no tiene jinete
        if (this.age % 90 < 45) {
            float yawRad = (float) Math.toRadians(this.getYaw());
            return new Vec3d(-Math.sin(yawRad) * 0.09, 0, Math.cos(yawRad) * 0.09);
        }
        return Vec3d.ZERO;
    }

    private void applyTentacleDrivenBodyLocomotion(Vec3d desiredMove, Vec3d totalTentacleForce, int anchoredCount) {
        Vec3d currentVel = this.getVelocity();

        // El pulpo necesita al menos 2 tentáculos anclados para poder traccionar su masa corporal
        double tractionEfficiency = MathHelper.clamp(anchoredCount / 4.0, 0.15, 1.25);

        double vx = currentVel.x * 0.68 + (totalTentacleForce.x + desiredMove.x * 0.38) * tractionEfficiency;
        double vz = currentVel.z * 0.68 + (totalTentacleForce.z + desiredMove.z * 0.38) * tractionEfficiency;

        // Oscilación vertical orgánica del manto al arrastrarse ("heave" de cada tirón muscular)
        double vy = currentVel.y - (0.075 * OctopusPhysicsConfig.gravitySlumpFactor);

        // Escalada vertical con ventosas cuando choca horizontalmente contra una pared y tiene tentáculos adheridos al muro
        boolean climbingWall = OctopusPhysicsConfig.wallClimbingEnabled
                && (this.horizontalCollision || totalTentacleForce.y > 0.025)
                && desiredMove.horizontalLengthSquared() > 1e-4
                && anchoredCount >= 2;

        if (climbingWall) {
            vy = Math.max(0.24 * OctopusPhysicsConfig.suctionPullStrength, totalTentacleForce.y * 2.2);
            this.fallDistance = 0.0f;
        } else if (this.isOnGround() && desiredMove.horizontalLengthSquared() > 1e-4) {
            // Pulso muscular al traccionar sobre el suelo
            double heave = Math.max(0.0, Math.sin(this.age * 0.35) * 0.045);
            vy = Math.max(vy, heave);
        }

        this.setVelocity(vx, vy, vz);
        this.move(MovementType.SELF, this.getVelocity());
    }

    /**
     * Ejecuta las interacciones activas de los tentáculos con el mundo comandadas por el jugador:
     * - `actionType == 1`: Agarrar un bloque o mob con el tentáculo #0 (o lanzarlo a toda velocidad si ya sostiene uno).
     * - `actionType == 2`: Latigazo muscular supersónico con los 2 tentáculos frontales (empuja mobs y rompe cristal/plantas).
     * - `actionType == 3`: Impulso de compresión muscular (los 8 tentáculos empujan el suelo al unísono para saltar).
     */
    public void performRiderTentacleAction(PlayerEntity player, int actionType) {
        World world = this.getWorld();
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f);

        if (actionType == 1) {
            // Si ya sostiene una entidad o un bloque en su tentáculo enroscado, LANZARLO con fuerza brutal!
            Entity heldEnt = getHeldEntity();
            if (heldEnt != null) {
                setHeldEntity(null);
                Vec3d throwVel = look.multiply(2.4 * OctopusPhysicsConfig.suctionPullStrength).add(0, 0.35, 0);
                heldEnt.setVelocity(throwVel);
                heldEnt.velocityModified = true;
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.7f);
                player.sendMessage(Text.literal("¡Tentáculo lanzó la criatura con fuerza muscular!").formatted(Formatting.GREEN), true);
                return;
            }

            BlockState heldBlock = getHeldBlockState();
            if (!heldBlock.isAir()) {
                setHeldBlockState(Blocks.AIR.getDefaultState());
                // Lanzar y colocar/impactar el bloque en la dirección de la mira
                BlockHitResult hit = world.raycast(new RaycastContext(
                        eye, eye.add(look.multiply(14.0)),
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this
                ));
                BlockPos impactPos = (hit.getType() == HitResult.Type.BLOCK)
                        ? hit.getBlockPos().offset(hit.getSide())
                        : BlockPos.ofFloored(eye.add(look.multiply(6.0)));

                if (world.getBlockState(impactPos).isAir() || world.getBlockState(impactPos).isReplaceable()) {
                    world.setBlockState(impactPos, heldBlock, Block.NOTIFY_ALL);
                }
                // Dañar criaturas en la zona de impacto del bloque lanzado
                List<LivingEntity> victims = world.getEntitiesByClass(
                        LivingEntity.class,
                        new Box(impactPos).expand(2.5),
                        e -> e != this && e != player
                );
                for (LivingEntity v : victims) {
                    v.damage(world.getDamageSources().mobAttack(this), 12.0f * OctopusPhysicsConfig.suctionPullStrength);
                    v.setVelocity(look.multiply(1.4).add(0, 0.4, 0));
                }
                world.playSound(null, impactPos, heldBlock.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1.4f, 0.8f);
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, heldBlock),
                            impactPos.getX() + 0.5, impactPos.getY() + 0.5, impactPos.getZ() + 0.5,
                            24, 0.4, 0.4, 0.4, 0.15);
                }
                player.sendMessage(Text.literal("¡Tentáculo arrojó el bloque a distancia!").formatted(Formatting.GREEN), true);
                return;
            }

            // Si no sostiene nada, buscar primero una criatura en la mira (hasta 9 bloques de alcance de tentáculo)
            Vec3d reachEnd = eye.add(look.multiply(OctopusPhysicsConfig.tentacleLength + 3.0));
            List<Entity> candidates = world.getOtherEntities(this, this.getBoundingBox().expand(OctopusPhysicsConfig.tentacleLength + 2.0),
                    e -> e != player && e instanceof LivingEntity);
            Entity closestMob = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity c : candidates) {
                if (c.getBoundingBox().expand(0.6).raycast(eye, reachEnd).isPresent()) {
                    double d = c.squaredDistanceTo(eye);
                    if (d < bestDist) {
                        bestDist = d;
                        closestMob = c;
                    }
                }
            }

            if (closestMob != null) {
                setHeldEntity(closestMob);
                world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.NEUTRAL, 1.0f, 0.7f);
                player.sendMessage(Text.literal("Tentáculo envolvió a: ").formatted(Formatting.GRAY)
                        .append(closestMob.getName().copy().formatted(Formatting.AQUA))
                        .append(Text.literal(" (Presioná R de nuevo para lanzarlo)").formatted(Formatting.YELLOW)), true);
                return;
            }

            // Si no apuntó a un mob, arrancar el bloque apuntado con las ventosas!
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    eye, reachEnd,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this
            ));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                BlockPos bp = blockHit.getBlockPos();
                BlockState st = world.getBlockState(bp);
                if (!st.isAir() && st.getHardness(world, bp) >= 0.0f && st.getHardness(world, bp) < 40.0f && world.getBlockEntity(bp) == null) {
                    setHeldBlockState(st);
                    world.setBlockState(bp, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    world.playSound(null, bp, st.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1.0f, 0.85f);
                    player.sendMessage(Text.literal("Ventosas arrancaron: ").formatted(Formatting.GRAY)
                            .append(st.getBlock().getName().copy().formatted(Formatting.AQUA))
                            .append(Text.literal(" (Presioná R de nuevo para arrojarlo)").formatted(Formatting.YELLOW)), true);
                }
            }
        } else if (actionType == 2) {
            // Latigazo muscular supersónico de los tentáculos frontales
            Vec3d whipTarget = eye.add(look.multiply(OctopusPhysicsConfig.tentacleLength + 1.5));
            List<TentacleLimb> limbs = getTentacles();
            for (int i = 0; i < Math.min(3, limbs.size()); i++) {
                TentacleLimb limb = limbs.get(i);
                limb.state = TentacleLimb.LimbState.WHIP_STRIKE;
                limb.whipTimer = 0.0f;
                limb.whipTarget = whipTarget;
            }

            Box strikeBox = this.getBoundingBox().stretch(look.multiply(OctopusPhysicsConfig.tentacleLength)).expand(2.0);
            List<LivingEntity> targets = world.getEntitiesByClass(LivingEntity.class, strikeBox, e -> e != this && e != player);
            for (LivingEntity t : targets) {
                t.damage(world.getDamageSources().mobAttack(this), 11.0f * OctopusPhysicsConfig.suctionPullStrength);
                t.setVelocity(look.multiply(1.65 * OctopusPhysicsConfig.suctionPullStrength).add(0, 0.45, 0));
            }
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.3f, 0.55f);
        } else if (actionType == 3) {
            // Impulso elástico síncrono de los 8 tentáculos contra el sustrato
            Vec3d jumpVel = look.multiply(0.95 * OctopusPhysicsConfig.suctionPullStrength).add(0, 0.78 * OctopusPhysicsConfig.suctionPullStrength, 0);
            this.setVelocity(this.getVelocity().add(jumpVel));
            this.velocityModified = true;
            world.playSound(null, this.getBlockPos(), SoundEvents.ENTITY_SLIME_JUMP, SoundCategory.NEUTRAL, 1.2f, 0.65f);
        }
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        // Los tentáculos hidro-musculares absorben las caídas como amortiguadores elásticos
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("HeldBlockRaw", this.dataTracker.get(HELD_BLOCK_RAW_ID));
        nbt.putInt("TentacleCount", this.dataTracker.get(ACTIVE_TENTACLE_COUNT));
        nbt.putFloat("TentacleLength", this.dataTracker.get(SYNCED_TENTACLE_LENGTH));
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
    }
}
