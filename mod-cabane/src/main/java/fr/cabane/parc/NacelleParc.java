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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Une nacelle d'un manège qui tourne ({@link Tournants}) : un cheval du carrousel, une tasse, une cabine de la grande
 * roue, le bateau pirate, la rangée de sièges de la toupie ou l'anneau de la tour. Elle ne bouge pas toute seule : sa
 * place se calcule à partir de l'heure du départ du tour (seule chose envoyée aux joueurs), pareil chez tout le monde.
 */
public class NacelleParc extends Entity {
    private static final EntityDataAccessor<Byte> MANEGE = SynchedEntityData.defineId(NacelleParc.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> INDEX = SynchedEntityData.defineId(NacelleParc.class, EntityDataSerializers.BYTE);
    /** L'heure du monde (ticks) du départ du tour, -1 en gare. */
    private static final EntityDataAccessor<Integer> DEBUT = SynchedEntityData.defineId(NacelleParc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> LIBRE = SynchedEntityData.defineId(NacelleParc.class, EntityDataSerializers.BOOLEAN);

    private final Trajet.Point point = new Trajet.Point();
    /** Chez le joueur : la position au tick d'avant (pour la vitesse, et donc le vent). */
    private Vec3 avant;
    private float vitesse;

    public NacelleParc(EntityType<? extends NacelleParc> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    static NacelleParc creer(Level level, byte manege, int index) {
        NacelleParc n = new NacelleParc(Parc.NACELLE.get(), level);
        n.entityData.set(MANEGE, manege);
        n.entityData.set(INDEX, (byte) index);
        n.placer();
        return n;
    }

    public byte manege() {
        return entityData.get(MANEGE);
    }

    public int index() {
        return entityData.get(INDEX);
    }

    public int debut() {
        return entityData.get(DEBUT);
    }

    void setDebut(int t) {
        entityData.set(DEBUT, t);
    }

    public boolean libre() {
        return entityData.get(LIBRE);
    }

    void setLibre(boolean oui) {
        if (libre() != oui) {
            entityData.set(LIBRE, oui);
        }
    }

    /** La vitesse de la nacelle chez le joueur (blocs par seconde), pour le vent dans les oreilles. */
    public float vitesse() {
        return vitesse;
    }

    /** Le temps du manège (secondes) : depuis le départ du tour, ou l'heure du monde pour la grande roue. */
    public double temps(float partiel) {
        long maintenant = level().getGameTime();
        if (manege() == Tournants.ROUE) {
            return (maintenant + partiel) / 20.0;
        }
        int d = debut();
        if (d < 0) {
            return 0;
        }
        return Math.max(0, Math.min(Tournants.fiche(manege()).duree(), (maintenant - d + partiel) / 20.0));
    }

    /** Où est la nacelle, et comment elle est tournée. */
    public Trajet.Point cadre(float partiel, Trajet.Point o) {
        return Tournants.cadre(manege(), index(), temps(partiel), o);
    }

    void placer() {
        cadre(0F, point);
        setPos(point.x, point.y, point.z);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(MANEGE, (byte) 0);
        builder.define(INDEX, (byte) 0);
        builder.define(DEBUT, -1);
        builder.define(LIBRE, true);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            return;
        }
        cadre(1F, point);
        setPos(point.x, point.y, point.z);
        Vec3 ici = position();
        vitesse = avant == null ? 0 : (float) (ici.distanceTo(avant) * 20);
        avant = ici;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int pas) {
    }

    /** La boîte où l'on clique pour monter : toute la nacelle (le bateau pend 12 blocs sous son axe). */
    @Override
    protected AABB makeBoundingBox() {
        double x = getX(), y = getY(), z = getZ();
        return switch (manege()) {
            case Tournants.TOUR -> new AABB(x - 4.6, y, z - 4.6, x + 4.6, y + 2.3, z + 4.6);
            case Tournants.ROUE -> new AABB(x - 1.4, y - 4.1, z - 1.3, x + 1.4, y - 0.4, z + 1.3);
            case Tournants.CARROUSEL -> new AABB(x - 0.7, y - 0.8, z - 0.7, x + 0.7, y + 1.4, z + 0.7);
            case Tournants.TASSES -> new AABB(x - 1.4, y, z - 1.4, x + 1.4, y + 1.5, z + 1.4);
            case Tournants.BATEAU -> new AABB(x - 10.5, y - 16.5, z - 3.5, x + 10.5, y - 10.5, z + 3.5);
            default -> new AABB(x - 6, y - 0.6, z - 1, x + 6, y + 2.4, z + 1);
        };
    }

    // ------------------------------------------------------------------ les passagers

    /** La tête du passager assis à la place k : le siège, plus un bloc vers le haut de la nacelle. */
    public Vec3 tete(int k, float partiel) {
        double[][] places = Tournants.places(manege());
        double[] pl = places[Math.max(0, Math.min(k, places.length - 1))];
        Trajet.Point p = cadre(partiel, point);
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
        if (passager instanceof LivingEntity l && Math.abs(point.fy) < 0.97) {
            double[] pl = Tournants.places(manege())[Math.min(k, Tournants.places(manege()).length - 1)];
            double c = Math.cos(pl[3]), sn = Math.sin(pl[3]);
            double gx = point.uy * point.fz - point.uz * point.fy, gz = point.ux * point.fy - point.uy * point.fx;
            l.setYBodyRot(WagonParc.lacet(point.fx * c + gx * sn, point.fz * c + gz * sn));
        }
        passager.fallDistance = 0;
    }

    @Override
    protected boolean canAddPassenger(Entity passager) {
        return getPassengers().size() < Tournants.places(manege()).length;
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
            joueur.displayClientMessage(Component.literal(manege() == Tournants.ROUE ? "Attends que la cabine arrive en bas." : "Attends la fin du tour.")
                    .withStyle(ChatFormatting.GOLD), true);
            return InteractionResult.CONSUME;
        }
        if (!canAddPassenger(joueur)) {
            joueur.displayClientMessage(Component.literal("C'est plein, essaie la suivante.").withStyle(ChatFormatting.GOLD), true);
            return InteractionResult.CONSUME;
        }
        if (joueur.startRiding(this) && joueur instanceof ServerPlayer j) {
            j.displayClientMessage(Component.literal(manege() == Tournants.ROUE ? "Bonne balade !" : "Installe-toi : ça part dans quelques secondes !")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
        return InteractionResult.CONSUME;
    }

    /** On descend à côté, sur le sol (ou le quai de la roue, ou la passerelle du bateau). */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passager) {
        int k = Math.max(0, getPassengers().indexOf(passager));
        Vec3 t = tete(k, 1F);
        double x, y, z;
        switch (manege()) {
            case Tournants.ROUE -> {
                x = t.x;
                y = Maneges.SOL + 2;
                z = Tournants.ROUE_Z - 2.6;
            }
            case Tournants.BATEAU -> {
                x = Math.max(Tournants.BATEAU_X - 4, Math.min(Tournants.BATEAU_X + 4, t.x));
                y = Maneges.SOL + 5;
                z = Tournants.BATEAU_Z + 5;
            }
            case Tournants.TOUPIE -> {
                x = t.x;
                y = Maneges.SOL;
                z = Tournants.TOUPIE_Z + 2.6;
            }
            default -> {
                // vers l'extérieur du manège
                double[] c = Tournants.centres()[manege()];
                double dx = t.x - c[0], dz = t.z - c[1], l = Math.max(1e-3, Math.hypot(dx, dz));
                double plus = manege() == Tournants.TOUR ? 2.2 : manege() == Tournants.TASSES ? 2.2 : 1.4;
                x = t.x + dx / l * plus;
                y = Maneges.SOL;
                z = t.z + dz / l * plus;
            }
        }
        BlockPos.MutableBlockPos q = BlockPos.containing(x, y + 0.1, z).mutable();
        for (int i = 0; i < 4 && !level().getBlockState(q).getCollisionShape(level(), q).isEmpty(); i++) {
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
