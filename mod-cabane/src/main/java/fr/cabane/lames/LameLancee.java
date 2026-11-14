package fr.cabane.lames;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * La Kubikiribôchô lancée : elle part tout droit en tournoyant (14 blocs, ou jusqu'au premier mur), coupe tout ce
 * qu'elle traverse (une fois à l'aller, une fois au retour), puis revient dans la main de celui qui l'a lancée.
 */
public class LameLancee extends Entity {
    private static final EntityDataAccessor<Boolean> RETOUR = SynchedEntityData.defineId(LameLancee.class, EntityDataSerializers.BOOLEAN);
    /** À chaque passage (l'aller, puis le retour) : 12 en tout sur qui se fait toucher deux fois. */
    public static final float DEGATS = 6;
    private UUID proprio;
    private Vec3 dir = Vec3.ZERO;
    private final Set<Integer> touches = new HashSet<>();

    public LameLancee(EntityType<? extends LameLancee> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    public LameLancee(Level level, ServerPlayer joueur) {
        this(Lames.LANCER.get(), level);
        proprio = joueur.getUUID();
        Vec3 oeil = joueur.getEyePosition();
        dir = joueur.getLookAngle();
        setPos(oeil.x, oeil.y - 0.45, oeil.z);
        setYRot(joueur.getYRot());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(RETOUR, false);
    }

    public boolean retour() {
        return entityData.get(RETOUR);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        ServerLevel monde = (ServerLevel) level();
        ServerPlayer p = proprio == null ? null : monde.getServer().getPlayerList().getPlayer(proprio);
        if (p == null || p.level() != monde || tickCount > 200) {
            finir(p);
            discard();
            return;
        }
        Vec3 v;
        if (!retour()) {
            v = dir.scale(1.15);
            HitResult mur = monde.clip(new ClipContext(position(), position().add(v), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (mur.getType() != HitResult.Type.MISS || tickCount >= 12) {
                entityData.set(RETOUR, true);
                touches.clear();
            }
        } else {
            Vec3 cible = p.getEyePosition().add(0, -0.5, 0), d = cible.subtract(position());
            if (d.length() < 1.5) {
                finir(p);
                discard();
                return;
            }
            v = d.normalize().scale(Math.min(d.length(), 1.4));
        }
        setPos(position().add(v));
        setDeltaMovement(v);
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.8, 0.9, 0.8))) {
            if (e != p && e.isAlive() && !e.isSpectator() && touches.add(e.getId())) {
                e.hurt(monde.damageSources().playerAttack(p), DEGATS * (p instanceof net.minecraft.server.level.ServerPlayer sp ? Zones.force(sp) : 1));
                e.knockback(0.6, -v.x, -v.z);
                monde.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY(0.5), e.getZ(), 1, 0, 0, 0, 0);
            }
        }
        if (tickCount % 4 == 0) {
            monde.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.9F, 0.6F);
        }
    }

    /** Elle est revenue : la Kubikiribôchô du joueur n'est plus « en vol ». */
    private static void finir(ServerPlayer p) {
        if (p == null) {
            return;
        }
        for (ItemStack s : p.getInventory().items) {
            s.remove(Lames.LANCEE.get());
        }
        p.getOffhandItem().remove(Lames.LANCEE.get());
        p.serverLevel().playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1F, 0.8F);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
