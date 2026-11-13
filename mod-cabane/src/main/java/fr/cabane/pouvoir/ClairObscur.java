package fr.cabane.pouvoir;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Ce que les Ténèbres ({@link Tenebres}) et la Lumière ({@link Lumiere}) font pareil : viser, trouver le sol, blesser,
 * prévenir les joueurs autour, compter les recharges. Les touches sont les mêmes pour les deux :
 * clic droit, s'accroupir (un appui court), accroupi + clic droit (dès le niveau 2), accroupi + clic gauche et la
 * touche technique (niveau 3), la touche Ulti (niveau 3, une fois par éveil). Le clic gauche reste un coup normal,
 * renforcé par le pouvoir.
 */
public final class ClairObscur {
    public static final int DROIT = 0, ACCROUPI = 1, ACC_DROIT = 2, ACC_GAUCHE = 3, TECHNIQUE = 4, ULTI = 5;

    /** Un coup du pouvoir : il compte même juste après un autre. */
    static boolean dansPouvoir;

    static void blesser(ServerPlayer joueur, LivingEntity cible, float degats) {
        blesser(joueur, cible, degats, joueur.damageSources().playerAttack(joueur));
    }

    static void blesser(ServerPlayer joueur, LivingEntity cible, float degats, DamageSource source) {
        dansPouvoir = true;
        try {
            cible.invulnerableTime = 0;
            cible.hurt(source, degats);
        } finally {
            dansPouvoir = false;
        }
    }

    static boolean touchable(ServerPlayer joueur, LivingEntity e) {
        return Pouvoirs.cible(joueur, e) && !e.isSpectator();
    }

    static Vec3 milieu(Entity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    static void envoyer(ServerLevel monde, CustomPacketPayload paquet, Vec3 autour) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 160 * 160) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    static void son(ServerLevel monde, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    /** Ce qu'il vise : le vivant le plus près de son regard, à moins de {@code portee} blocs, ou null. */
    static LivingEntity visee(ServerPlayer joueur, double portee, double cosinus) {
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        LivingEntity meilleure = null;
        double score = Double.MAX_VALUE;
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee), e -> touchable(joueur, e))) {
            Vec3 vers = milieu(e).subtract(oeil);
            double d = vers.length();
            if (d > portee || d < 0.01) {
                continue;
            }
            double c = vers.normalize().dot(regard);
            if (c < cosinus || !joueur.hasLineOfSight(e)) {
                continue;
            }
            double s = d * (2 - c);
            if (s < score) {
                score = s;
                meilleure = e;
            }
        }
        return meilleure;
    }

    /** Le sol sous ce point (en descendant jusqu'à 12 blocs ; en montant de 3 s'il est dans le sol). */
    static double sol(ServerLevel monde, Vec3 p) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.x, p.y + 3, p.z).mutable();
        for (int i = 0; i < 16; i++) {
            if (!monde.getBlockState(pos).getCollisionShape(monde, pos).isEmpty()) {
                return pos.getY() + 1;
            }
            pos.move(0, -1, 0);
        }
        return p.y;
    }

    /** Le point qu'il vise au sol (ce qu'il regarde, à {@code portee} blocs au plus), posé sur le sol. */
    static Vec3 pointVise(ServerPlayer joueur, double portee) {
        Vec3 oeil = joueur.getEyePosition(), loin = oeil.add(joueur.getLookAngle().scale(portee));
        HitResult r = joueur.serverLevel().clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 p = r.getType() == HitResult.Type.MISS ? loin : r.getLocation();
        return new Vec3(p.x, sol(joueur.serverLevel(), p), p.z);
    }

    /** Peut-on tenir debout ici (les pieds et la tête dans le vide, le sol dessous) ? */
    static boolean libre(ServerLevel monde, Vec3 pieds) {
        BlockPos p = BlockPos.containing(pieds);
        return monde.getBlockState(p).getCollisionShape(monde, p).isEmpty() && monde.getBlockState(p.above()).getCollisionShape(monde, p.above()).isEmpty();
    }

    /** Le plus loin où il peut arriver en filant vers dir (à plat), jusqu'à {@code portee} blocs, posé sur le sol. */
    static Vec3 arrivee(ServerPlayer joueur, Vec3 dir, double portee) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 d = new Vec3(dir.x, 0, dir.z);
        d = d.lengthSqr() < 1e-4 ? Pouvoirs.devant(joueur) : d.normalize();
        Vec3 dernier = joueur.position();
        for (double k = 1; k <= portee; k += 0.5) {
            Vec3 p = joueur.position().add(d.scale(k));
            double y = sol(monde, p.add(0, 1, 0));
            Vec3 pieds = new Vec3(p.x, y, p.z);
            if (Math.abs(y - dernier.y) > 3 || !libre(monde, pieds)) {
                break;
            }
            dernier = pieds;
        }
        return dernier;
    }

    // ---------------------------------------------------------------- les recharges

    static boolean pret(Map<UUID, Integer> m, ServerPlayer joueur) {
        return joueur.server.getTickCount() >= m.getOrDefault(joueur.getUUID(), 0);
    }

    static void armer(Map<UUID, Integer> m, ServerPlayer joueur, int ticks) {
        m.put(joueur.getUUID(), joueur.server.getTickCount() + ticks);
    }

    /** Une recharge par touche. */
    static final class Recharges {
        final Map<UUID, Integer> droit = new HashMap<>(), accroupi = new HashMap<>(), accDroit = new HashMap<>(), accGauche = new HashMap<>(),
                technique = new HashMap<>();

        void oublier(UUID qui) {
            droit.remove(qui);
            accroupi.remove(qui);
            accDroit.remove(qui);
            accGauche.remove(qui);
            technique.remove(qui);
        }

        void vider() {
            droit.clear();
            accroupi.clear();
            accDroit.clear();
            accGauche.clear();
            technique.clear();
        }
    }

    private ClairObscur() {
    }
}
