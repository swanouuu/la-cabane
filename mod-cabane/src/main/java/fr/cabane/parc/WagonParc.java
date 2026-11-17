package fr.cabane.parc;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Un wagon de manège sur rails. Il ne bouge pas tout seul : le train ({@link ParcVie}) lui donne sa distance sur le
 * parcours à chaque tick, et c'est tout ce qui passe par le réseau. Chez chaque joueur, le wagon se place lui-même
 * sur le rail à partir de cette distance (le mouvement reste fluide, même dans le looping), et ses passagers sont assis
 * dans son repère : la tête en bas quand il est la tête en bas.
 */
public class WagonParc extends Entity {
    private static final EntityDataAccessor<Byte> MANEGE = SynchedEntityData.defineId(WagonParc.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> INDEX = SynchedEntityData.defineId(WagonParc.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(WagonParc.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> VITESSE = SynchedEntityData.defineId(WagonParc.class, EntityDataSerializers.FLOAT);
    /** Le train est en gare : on peut monter et descendre (synchronisé, pour que la descente soit refusée des deux côtés). */
    private static final EntityDataAccessor<Boolean> LIBRE = SynchedEntityData.defineId(WagonParc.class, EntityDataSerializers.BOOLEAN);

    /** Chez le joueur : la distance au tick d'avant et à ce tick (le dessin interpole entre les deux). */
    private double sAvant, sIci;
    private boolean pret;
    private final Trajet.Point point = new Trajet.Point();

    public WagonParc(EntityType<? extends WagonParc> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    static WagonParc creer(Level level, byte manege, int index, double s) {
        WagonParc w = new WagonParc(Parc.WAGON.get(), level);
        w.entityData.set(MANEGE, manege);
        w.entityData.set(INDEX, (byte) index);
        w.placer(s, 0);
        return w;
    }

    public byte manege() {
        return entityData.get(MANEGE);
    }

    public int index() {
        return entityData.get(INDEX);
    }

    public Trajet trajet() {
        return Maneges.trajet(manege());
    }

    /** Le train est en gare : on peut monter et descendre. */
    public boolean libre() {
        return entityData.get(LIBRE);
    }

    void setLibre(boolean oui) {
        entityData.set(LIBRE, oui);
    }

    /** La vitesse du train (blocs par seconde), pour le vent dans les oreilles. */
    public float vitesse() {
        return entityData.get(VITESSE);
    }

    /** Le serveur : le wagon est maintenant à {@code s} blocs du début du parcours. */
    void placer(double s, double v) {
        Trajet t = trajet();
        s = t.normaliser(s);
        entityData.set(DISTANCE, (float) s);
        entityData.set(VITESSE, (float) v);
        sAvant = sIci = s;
        t.a(s, point);
        setPos(point.x, point.y, point.z);
    }

    /** La distance à dessiner entre deux ticks (côté client), ou la distance tout court (côté serveur). */
    public double distance(float partiel) {
        if (!level().isClientSide) {
            return entityData.get(DISTANCE);
        }
        return sAvant + (sIci - sAvant) * partiel;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MANEGE, (byte) 0);
        builder.define(INDEX, (byte) 0);
        builder.define(DISTANCE, 0F);
        builder.define(VITESSE, 0F);
        builder.define(LIBRE, true);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            return;
        }
        // chez le joueur : on suit la distance envoyée par le serveur, sans jamais repartir en arrière au passage du tour
        Trajet t = trajet();
        double cible = entityData.get(DISTANCE);
        if (!pret) {
            sAvant = sIci = cible;
            pret = true;
        }
        sAvant = sIci;
        double d = cible - t.normaliser(sIci);
        if (d > t.total / 2) {
            d -= t.total;
        } else if (d < -t.total / 2) {
            d += t.total;
        }
        sIci += d;
        t.a(sIci, point);
        setPos(point.x, point.y, point.z);
    }

    /** Les déplacements envoyés par le serveur sont ignorés : le wagon se place lui-même d'après sa distance. */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int pas) {
    }

    /** Le lacet (en degrés, comme Minecraft) d'une direction. */
    public static float lacet(double fx, double fz) {
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }

    /** Où est la tête du passager assis à la place {@code k} : le siège, plus un bloc vers le « haut » du wagon. */
    public Vec3 tete(int k, float partiel) {
        double[][] places = Maneges.places(manege());
        double[] pl = places[Math.min(k, places.length - 1)];
        trajet().a(distance(partiel), point).caisse();
        Trajet.Point p = point;
        double gx = p.uy * p.fz - p.uz * p.fy, gy = p.uz * p.fx - p.ux * p.fz, gz = p.ux * p.fy - p.uy * p.fx;
        double hy = pl[1] + 1.0;
        return new Vec3(p.x + gx * pl[0] + p.ux * hy + p.fx * pl[2], p.y + gy * pl[0] + p.uy * hy + p.fy * pl[2], p.z + gz * pl[0] + p.uz * hy + p.fz * pl[2]);
    }

    @Override
    protected void positionRider(Entity passager, MoveFunction bouger) {
        if (!hasPassenger(passager)) {
            return;
        }
        int k = getPassengers().indexOf(passager);
        Vec3 h = tete(k, 1F);
        bouger.accept(passager, h.x, h.y - passager.getEyeHeight(), h.z);
        // le corps regarde vers l'avant du wagon (la caméra, elle, suit tout le wagon : voir ClientParc)
        if (passager instanceof LivingEntity l && Math.abs(point.fy) < 0.97) {
            double[] pl = Maneges.places(manege())[Math.min(k, Maneges.places(manege()).length - 1)];
            double c = Math.cos(pl[3]), sn = Math.sin(pl[3]);
            double gx = point.uy * point.fz - point.uz * point.fy, gz = point.ux * point.fy - point.uy * point.fx;
            l.setYBodyRot(lacet(point.fx * c + gx * sn, point.fz * c + gz * sn));
        }
        passager.fallDistance = 0;
    }

    @Override
    protected boolean canAddPassenger(Entity passager) {
        return getPassengers().size() < Maneges.fiche(manege()).places();
    }

    @Override
    public InteractionResult interact(Player joueur, InteractionHand main) {
        if (joueur.isSecondaryUseActive() || joueur.isPassenger()) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!libre()) {
            joueur.displayClientMessage(Component.literal("Attends que le train revienne en gare.").withStyle(ChatFormatting.GOLD), true);
            return InteractionResult.CONSUME;
        }
        if (!canAddPassenger(joueur)) {
            joueur.displayClientMessage(Component.literal("Ce wagon est plein, essaie le suivant.").withStyle(ChatFormatting.GOLD), true);
            return InteractionResult.CONSUME;
        }
        if (joueur.startRiding(this) && joueur instanceof ServerPlayer j) {
            j.displayClientMessage(Component.literal("Accroche-toi : départ dans quelques secondes !").withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResult.CONSUME;
    }

    /** On descend sur le quai, à gauche du wagon. */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passager) {
        trajet().a(distance(1F), point);
        double gx = point.uy * point.fz - point.uz * point.fy, gz = point.ux * point.fy - point.uy * point.fx;
        double l = Math.max(1e-3, Math.sqrt(gx * gx + gz * gz));
        double x = point.x + gx / l * 2.4, z = point.z + gz / l * 2.4;
        // sur le quai : on remonte tant qu'on est dans un bloc (le quai du Splash est au ras de l'eau, plus haut que le tronc)
        BlockPos.MutableBlockPos q = BlockPos.containing(x, Math.floor(point.y) + 0.1, z).mutable();
        for (int k = 0; k < 4 && !level().getBlockState(q).getCollisionShape(level(), q).isEmpty(); k++) {
            q.move(0, 1, 0);
        }
        return new Vec3(x, q.getY() + 0.05, z);
    }

    @Override
    public boolean hurt(DamageSource source, float degats) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 256 * 256;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
