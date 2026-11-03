package fr.cabane.cartel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ce que les armes envoient en l'air : la roquette du bazooka (droite, rapide, une traînée de fumée), l'obus du
 * lance-grenades (en cloche), la grenade lancée à la main (elle rebondit, puis explose au bout de 3 secondes) et le
 * cocktail Molotov (il se brise et tout brûle autour quelques secondes). Aucune explosion ne casse de bloc, et le feu
 * du cocktail ne pose aucun bloc de feu : rien ne brûle dans le village.
 */
public class ProjectileArme extends ThrowableItemProjectile {
    private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(ProjectileArme.class, EntityDataSerializers.BYTE);
    private float puissance = 3F;
    /** Tiré par un gars du stand de tir : de l'effet, mais aucun dégât. */
    private boolean factice;

    public ProjectileArme(EntityType<? extends ProjectileArme> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MODE, (byte) ArmeItem.Tir.ROQUETTE.ordinal());
    }

    public ArmeItem.Tir mode() {
        return ArmeItem.Tir.values()[entityData.get(MODE)];
    }

    private void setMode(ArmeItem.Tir tir) {
        entityData.set(MODE, (byte) tir.ordinal());
        setItem(new ItemStack(switch (tir) {
            case ROQUETTE -> Cartel.ROQUETTE.get();
            case OBUS -> Cartel.OBUS.get();
            case GRENADE -> Cartel.GRENADE.get();
            default -> Cartel.MOLOTOV.get();
        }));
    }

    @Override
    protected Item getDefaultItem() {
        return Cartel.ROQUETTE.get();
    }

    @Override
    protected double getDefaultGravity() {
        return switch (mode()) {
            case ROQUETTE -> 0.0;
            case OBUS -> 0.035;
            default -> 0.05;
        };
    }

    // ------------------------------------------------------------------ partir

    /** La roquette ou l'obus, depuis le bout du canon. */
    static ProjectileArme tirer(ServerLevel level, LivingEntity tireur, Vec3 canon, Vec3 dir, ArmeItem.Spec spec) {
        ProjectileArme p = new ProjectileArme(Cartel.PROJECTILE.get(), level);
        p.setOwner(tireur);
        p.setMode(spec.tir());
        p.puissance = spec.puissance();
        p.setPos(canon.x, canon.y, canon.z);
        double vitesse = spec.tir() == ArmeItem.Tir.ROQUETTE ? 2.4 : 1.7;
        p.shoot(dir.x, dir.y, dir.z, (float) vitesse, 0F);
        level.addFreshEntity(p);
        Vec3 devant = canon.add(dir.scale(0.8));
        level.sendParticles(ParticleTypes.LARGE_SMOKE, devant.x, devant.y, devant.z, 2, 0.1, 0.1, 0.1, 0.01);
        if (spec.tir() == ArmeItem.Tir.ROQUETTE) {
            // le souffle qui sort par l'arrière du tube
            Vec3 arriere = canon.subtract(dir.scale(1.6));
            level.sendParticles(ParticleTypes.CLOUD, arriere.x, arriere.y, arriere.z, 10, 0.2, 0.2, 0.2, 0.05);
        }
        return p;
    }

    /** Une roquette pour rire (le gars du stand de tir) : elle part, elle explose sur sa cible, elle ne blesse personne. */
    static void factice(ServerLevel level, LivingEntity tireur, Vec3 canon, Vec3 dir) {
        ProjectileArme p = new ProjectileArme(Cartel.PROJECTILE.get(), level);
        p.setOwner(tireur);
        p.setMode(ArmeItem.Tir.ROQUETTE);
        p.factice = true;
        p.setPos(canon.x, canon.y, canon.z);
        p.shoot(dir.x, dir.y, dir.z, 2.2F, 0F);
        level.addFreshEntity(p);
    }

    /** La grenade ou le cocktail, lancés à la main. */
    static void lancer(ServerLevel level, Player joueur, ArmeItem.Tir tir) {
        ProjectileArme p = new ProjectileArme(Cartel.PROJECTILE.get(), level);
        p.setOwner(joueur);
        p.setMode(tir);
        p.puissance = 3F;
        p.setPos(joueur.getX(), joueur.getEyeY() - 0.1, joueur.getZ());
        p.shootFromRotation(joueur, joueur.getXRot(), joueur.getYRot(), -6F, 1.15F, 1F);
        level.addFreshEntity(p);
        level.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 0.6F);
        if (tir == ArmeItem.Tir.GRENADE) {
            level.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.TRIPWIRE_CLICK_OFF, SoundSource.PLAYERS, 1F, 1.6F);
        }
    }

    // ------------------------------------------------------------------ voler

    @Override
    public void tick() {
        super.tick();
        Level level = level();
        if (level.isClientSide) {
            // la traînée : fumée épaisse pour la roquette, une mince pour l'obus, la mèche du cocktail qui brûle
            switch (mode()) {
                case ROQUETTE -> {
                    level.addParticle(ParticleTypes.LARGE_SMOKE, getX(), getY(), getZ(), 0, 0.02, 0);
                    level.addParticle(ParticleTypes.FLAME, getX(), getY(), getZ(), 0, 0, 0);
                }
                case OBUS -> level.addParticle(ParticleTypes.SMOKE, getX(), getY(), getZ(), 0, 0, 0);
                case MOLOTOV -> level.addParticle(ParticleTypes.SMALL_FLAME, getX(), getY() + 0.15, getZ(), 0, 0.01, 0);
                default -> {
                }
            }
            return;
        }
        if (mode() == ArmeItem.Tir.GRENADE) {
            if (tickCount % 20 == 0) {
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.6F, 1.8F);
            }
            if (tickCount >= 60) {
                exploser();
            }
        } else if (tickCount > 200) {
            exploser();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult resultat) {
        if (mode() == ArmeItem.Tir.GRENADE) {
            setDeltaMovement(getDeltaMovement().scale(-0.25));
            return;
        }
        super.onHitEntity(resultat);
    }

    @Override
    protected void onHitBlock(BlockHitResult resultat) {
        if (mode() == ArmeItem.Tir.GRENADE) {
            // elle rebondit en perdant de la vitesse, puis roule et s'arrête
            Vec3 v = getDeltaMovement();
            Vec3 n = Vec3.atLowerCornerOf(resultat.getDirection().getNormal());
            Vec3 r = v.subtract(n.scale(2 * v.dot(n))).scale(0.38);
            setDeltaMovement(r.lengthSqr() < 0.004 ? Vec3.ZERO : r);
            if (r.lengthSqr() > 0.01) {
                level().playSound(null, getX(), getY(), getZ(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 0.7F, 1.4F);
            }
            return;
        }
        super.onHitBlock(resultat);
    }

    @Override
    protected void onHit(HitResult resultat) {
        super.onHit(resultat);
        if (level().isClientSide || mode() == ArmeItem.Tir.GRENADE) {
            return;
        }
        if (mode() == ArmeItem.Tir.MOLOTOV) {
            embraser();
        } else {
            exploser();
        }
    }

    /** Boum : ça blesse et ça projette, mais aucun bloc ne casse. */
    private void exploser() {
        if (!(level() instanceof ServerLevel level) || isRemoved()) {
            return;
        }
        Vec3 p = position();
        if (!factice) {
            Entity auteur = getOwner();
            level.explode(this, level.damageSources().explosion(this, auteur), null, p.x, p.y, p.z, puissance, false, Level.ExplosionInteraction.NONE);
        }
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.5, p.z, 30, 1.2, 0.8, 1.2, 0.04);
        level.sendParticles(ParticleTypes.FLAME, p.x, p.y + 0.3, p.z, 25, 0.8, 0.5, 0.8, 0.08);
        level.playSound(null, p.x, p.y, p.z, Cartel.SON_EXPLOSION.get(), SoundSource.PLAYERS, factice ? 2.5F : 5F, 0.9F + random.nextFloat() * 0.2F);
        discard();
    }

    // ------------------------------------------------------------------ le feu du cocktail

    private record Feu(ServerLevel level, Vec3 centre, long fin, Entity auteur) {
    }

    private static final List<Feu> FEUX = new ArrayList<>();
    private static final double RAYON_FEU = 3.5;

    private void embraser() {
        if (!(level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 p = position();
        level.playSound(null, p.x, p.y, p.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.2F, 0.9F);
        level.playSound(null, p.x, p.y, p.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.2F, 0.7F);
        FEUX.add(new Feu(level, p, level.getGameTime() + 120, getOwner()));
        discard();
    }

    /** Chaque feu brûle 6 secondes : les flammes dansent, et tout ce qui est dedans prend feu. */
    static void tickFeux(MinecraftServer server) {
        if (FEUX.isEmpty()) {
            return;
        }
        FEUX.removeIf(f -> f.level().getGameTime() > f.fin() || f.level().getServer() != server);
        for (Feu f : FEUX) {
            ServerLevel level = f.level();
            Vec3 c = f.centre();
            if (level.getGameTime() % 3 == 0) {
                level.sendParticles(ParticleTypes.FLAME, c.x, c.y + 0.2, c.z, 14, RAYON_FEU * 0.5, 0.25, RAYON_FEU * 0.5, 0.02);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.8, c.z, 2, RAYON_FEU * 0.4, 0.3, RAYON_FEU * 0.4, 0.01);
            }
            if (level.getGameTime() % 20 == 0) {
                level.playSound(null, c.x, c.y, c.z, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 1.5F, 0.8F);
            }
            if (level.getGameTime() % 5 == 0) {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RAYON_FEU, 2, RAYON_FEU),
                        e -> e.distanceToSqr(c) < RAYON_FEU * RAYON_FEU && !e.fireImmune())) {
                    e.igniteForSeconds(4);
                    e.hurt(level.damageSources().inFire(), 1F);
                }
            }
        }
    }

    // ------------------------------------------------------------------ sauvegarde

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putByte("Mode", entityData.get(MODE));
        tag.putFloat("Puissance", puissance);
        tag.putBoolean("Factice", factice);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(MODE, tag.getByte("Mode"));
        puissance = tag.getFloat("Puissance");
        factice = tag.getBoolean("Factice");
    }
}
