package fr.cabane.lames;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cabane.Cabane;
import fr.cabane.lames.Lames.Sorte;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Les coups larges des combos : le tourbillon des Kiba touche tout autour, les balayages tout ce qui est devant en large,
 * les coups au sol tout ce qui est là où la lame tombe, la Flèche de Nuibari tout ce qui est sur sa ligne. Le client dit
 * quel coup du combo il joue (le même que son animation, {@link Lames.Coup}) ; le serveur vérifie la lame en main et
 * frappe au moment de l'impact (combos.json : « zone » et « frappe », écrits par jeu.mjs depuis combos.mjs).
 * <p>
 * Les dégâts : ceux d'un coup normal du joueur (l'attribut d'attaque : la lame, Force, les pouvoirs qui l'augmentent),
 * en coup de joueur (les pouvoirs qui ajoutent des dégâts aux coups s'appliquent aussi). Celui qu'on a frappé
 * directement ne prend pas le coup deux fois.
 */
final class Zones {
    record Coup(String zone, int frappe, int duree) {
    }

    private static Map<Sorte, Coup[]> coups;
    /** La longueur de chaque lame (de la prise à la pointe), en blocs. */
    private static final Map<Sorte, Float> LONGUEURS = new EnumMap<>(Sorte.class);

    static float longueur(Sorte s) {
        de(s);
        return LONGUEURS.getOrDefault(s, 2F);
    }

    static Coup[] de(Sorte s) {
        if (coups == null) {
            coups = lire();
        }
        return coups.get(s);
    }

    private static Map<Sorte, Coup[]> lire() {
        Map<Sorte, Coup[]> m = new EnumMap<>(Sorte.class);
        var flux = Zones.class.getResourceAsStream("/assets/cabane/lames/combos.json");
        if (flux == null) {
            Cabane.LOGGER.error("lames/combos.json introuvable");
            return m;
        }
        try (var r = new InputStreamReader(flux, StandardCharsets.UTF_8)) {
            JsonObject tout = JsonParser.parseReader(r).getAsJsonObject();
            for (Sorte s : Sorte.values()) {
                JsonObject o = tout.getAsJsonObject(s.id);
                if (o == null) {
                    continue;
                }
                LONGUEURS.put(s, o.get("pointe").getAsFloat() / 16);
                JsonArray cs = o.getAsJsonArray("coups");
                Coup[] l = new Coup[cs.size()];
                for (int i = 0; i < l.length; i++) {
                    JsonObject c = cs.get(i).getAsJsonObject();
                    JsonElement z = c.get("zone");
                    JsonArray cles = c.getAsJsonArray("cles"), tr = c.getAsJsonArray("trace");
                    String zone = z == null || z.isJsonNull() ? null : z.getAsString();
                    int frappe = c.get("frappe").getAsInt();
                    // le tourbillon : au milieu du tour
                    if ("cercle".equals(zone)) {
                        frappe = (tr.get(0).getAsInt() + tr.get(1).getAsInt()) / 2;
                    }
                    l[i] = new Coup(zone, frappe, cles.get(cles.size() - 1).getAsJsonObject().get("t").getAsInt());
                }
                m.put(s, l);
            }
        } catch (Exception e) {
            Cabane.LOGGER.error("lames/combos.json illisible", e);
        }
        return m;
    }

    /** Le dernier coup de chaque joueur : la lame, le coup, quand. */
    private static final Map<UUID, int[]> DERNIER = new HashMap<>();
    /** Ce que chaque joueur vient de frapper directement (l'entité, quand) : elle ne reprend pas le coup de zone. */
    private static final Map<UUID, int[]> FRAPPE = new HashMap<>();

    /** Le coup du combo que ce joueur joue en ce moment (pour le coup final de hurtEnemy) ; -1 si on ne sait pas. */
    static int coupEnCours(ServerPlayer j, Sorte s) {
        int[] d = DERNIER.get(j.getUUID());
        Coup[] l = de(s);
        if (d == null || l == null || d[0] != s.ordinal() || d[1] >= l.length || j.tickCount - d[2] > l[d[1]].duree() + 4) {
            return -1;
        }
        return d[1];
    }

    static void frappee(ServerPlayer j, LivingEntity cible) {
        FRAPPE.put(j.getUUID(), new int[]{cible.getId(), j.tickCount});
    }

    /** Le client : ce joueur commence ce coup du combo. */
    static void joue(ServerPlayer j, int index) {
        Sorte s = Lames.sorte(j.getMainHandItem());
        Coup[] l = s == null ? null : de(s);
        if (l == null || index < 0 || index >= l.length) {
            return;
        }
        int[] d = DERNIER.get(j.getUUID());
        // pas plus d'un coup toutes les 8 ticks (la vitesse d'attaque des lames : un coup toutes les 12,5)
        if (d != null && j.tickCount - d[2] < 8) {
            return;
        }
        DERNIER.put(j.getUUID(), new int[]{s.ordinal(), index, j.tickCount});
        Coup c = l[index];
        if (c.zone() != null) {
            Lames.plusTard(j, s, Math.max(1, c.frappe()), () -> frapper(j, s, c.zone()));
        }
    }

    /** Les dégâts d'un coup normal de ce joueur : l'attribut d'attaque (la lame, Force, les pouvoirs). */
    static float coupNormal(ServerPlayer j) {
        return (float) j.getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    /** Ce que la force du joueur ajoute aux coups spéciaux : 1 avec la lame seule, plus avec Force ou un pouvoir. */
    static float force(ServerPlayer j) {
        return Math.max(1, Math.min(3, coupNormal(j) / Lames.DEGATS));
    }

    private static void frapper(ServerPlayer j, Sorte s, String zone) {
        ServerLevel monde = j.serverLevel();
        Vec3 pied = j.position(), vue = j.getLookAngle();
        Vec3 devant = new Vec3(vue.x, 0, vue.z);
        devant = devant.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, j.getYRot()) : devant.normalize();
        float portee = longueur(s) + 1.2F;
        Vec3 centre = switch (zone) {
            case "impact" -> pied.add(devant.scale(2.2));
            case "ligne" -> pied.add(devant.scale(2.6));
            default -> pied;
        };
        double rayon = switch (zone) {
            case "impact" -> 2.0;
            case "ligne" -> 3.2;
            default -> portee;
        };
        int[] f = FRAPPE.get(j.getUUID());
        float degats = coupNormal(j);
        boolean touche = false;
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(rayon + 1, 2.5, rayon + 1))) {
            if (e == j || !e.isAlive() || e.isSpectator() || e instanceof ArmorStand || Lames.avale(e)
                    || e instanceof OwnableEntity o && j.getUUID().equals(o.getOwnerUUID())
                    || f != null && f[0] == e.getId() && j.tickCount - f[1] < 12) {
                continue;
            }
            Vec3 vers = e.position().subtract(pied);
            Vec3 plat = new Vec3(vers.x, 0, vers.z);
            double d = plat.length();
            boolean dedans = switch (zone) {
                case "cercle" -> d <= portee;
                case "arc" -> d <= portee && (d < 0.8 || plat.normalize().dot(devant) > 0.26);
                case "impact" -> e.position().subtract(centre).multiply(1, 0, 1).length() <= rayon;
                case "ligne" -> {
                    double le = plat.dot(devant), cote = Math.abs(plat.dot(new Vec3(-devant.z, 0, devant.x)));
                    yield le > 0 && le <= 5.5 && cote <= 1.0;
                }
                default -> false;
            };
            if (!dedans || Math.abs(e.getY() - pied.y) > 2.5) {
                continue;
            }
            if (e.hurt(monde.damageSources().playerAttack(j), degats)) {
                touche = true;
                monde.sendParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY(0.55), e.getZ(), 1, 0, 0, 0, 0);
                monde.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY(0.55), e.getZ(), 8, 0.3, 0.4, 0.3, 0.4);
                Vec3 loin = plat.lengthSqr() < 1e-4 ? devant : plat.normalize();
                e.setDeltaMovement(e.getDeltaMovement().add(loin.x * 0.35, 0.12, loin.z * 0.35));
                e.hurtMarked = true;
            }
        }
        if (touche) {
            monde.playSound(null, j.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1F, 0.8F);
        }
    }

    private Zones() {
    }
}
