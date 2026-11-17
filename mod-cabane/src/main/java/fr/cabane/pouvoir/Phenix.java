package fr.cabane.pouvoir;

import static fr.cabane.pouvoir.ClairObscur.*;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientPhenix;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le fruit du phénix (Tori Tori no Mi, modèle Phénix), d'après les planches docs/fruits/planche-phenix*.png. Trois listes :
 * « phenix » (ce que donne le fruit), « phenix2 », « phenix3 » (seul l'hôte les donne). Comme les autres fruits : l'eau
 * lui fait perdre un demi-cœur par seconde (toujours), éveil 2 minutes puis repos. Sa spécialité : les flammes bleues
 * qui soignent (lui et ses alliés) ; en échange il frappe un peu moins fort que la lave. Pas de dégâts de chute, éveillé.
 * <ul>
 * <li>niveau 1 : l'avant-bras droit en aile de flammes. Clic gauche +2 et brûle 3 s ; clic droit : le jet de flammes
 * bleues (12 blocs, 6, brûle 4 s, 6 s) ; accroupi (au sol) : les flammes de régénération (lui +6, ses alliés à 5 blocs
 * +3, 20 s). Il se soigne d'un demi-cœur toutes les 4 s ;</li>
 * <li>niveau 2 : la moitié droite en plumes, des ailes, une serre. Clic gauche +4, et 3 à 2 blocs autour ; clic droit :
 * le coup de serre (une ruée de 8 blocs, 12 et recul au premier touché, 8 s) ; accroupi (au sol) : la pluie de plumes là
 * où il vise (30 blocs ; 3 s, 5 par plume, 15 au plus, 25 s) ; sauter en l'air : 3 battements d'ailes, puis il plane.
 * Un demi-cœur toutes les 2 s ; qui le frappe brûle 2 s ;</li>
 * <li>niveau 3 (voir planche-phenix3.png) : tout en plumes, la crête, deux serres, il vole 30 s au plus (la jauge se
 * remplit au sol ; accroupi ne lance rien : il descend). Clic gauche +6 ; au 3e coup de suite, les serres en croix (8 à
 * 2 blocs, il se soigne 4). Clic droit : l'éventail (7 plumes, 20 blocs, 6 chacune, 12 au plus sur un seul, 10 s) ; clic
 * droit maintenu : le grand phénix qui suit son regard 5 s puis explose (20 au milieu, 12 au bord, alliés +6, des
 * flammes bleues au sol, 30 s). Technique : le cocon (3 s, il se soigne 10 ; 4 et recul à 4 blocs, 25 s). L : le piqué
 * (25 blocs, 12 à 4 blocs, en l'air, flammes bleues, 20 s). I : le vol bleu (15 blocs en ligne, 10 à qui il traverse,
 * sa traînée soigne ses alliés 4, 12 s). Virgule : le brasier (un cercle de flammes bleues de 4 blocs, 5 s ; lui +1 cœur
 * par seconde dedans, les autres brûlent, 20 s). Point : le tourbillon de plumes (4 s, 3 toutes les 0,5 s à 3 blocs,
 * 24 au plus, 18 s). J : l'aube du phénix (une fois par éveil).</li>
 * </ul>
 * Niveau 2 aussi : technique, le coup d'aile (un croissant de feu, 8 blocs) ; L, l'envol ardent (une colonne de feu).
 * Passifs, toujours (éveillé ou pas, à tous les niveaux) : il se soigne, pas de dégâts de chute, le feu ne lui fait rien,
 * et la renaissance (au plus une fois toutes les 10 minutes).
 * <ul>
 * </ul>
 * Rien ne casse. Ce qu'on voit est dessiné par {@link ClientPhenix} ; les flammes au sol sont de vrais blocs
 * ({@link FeuBleu}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Phenix {
    public static final String NOM = "phenix", NOM2 = "phenix2", NOM3 = "phenix3";

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir) || NOM3.equals(pouvoir);
    }

    // ================================================================ les chiffres (en ticks)

    /** Les touches (client → serveur). */
    public static final int DROIT = 0, ACCROUPI = 1, TECHNIQUE = 2, PIQUE = 3, VOL_BLEU = 4, BRASIER = 5, TOURBILLON = 6, ULTI = 7,
            GRAND = 8, GRAND_LACHE = 9, BATTEMENT = 10, COUP_AILE = 11, ENVOL = 12;

    public static final int RECHARGE_JET = 6 * 20, RECHARGE_REGEN = 20 * 20, RECHARGE_SERRE = 8 * 20, RECHARGE_PLUIE = 25 * 20,
            RECHARGE_EVENTAIL = 10 * 20, RECHARGE_GRAND = 30 * 20, RECHARGE_COCON = 25 * 20, RECHARGE_PIQUE = 20 * 20, RECHARGE_VOL = 12 * 20,
            RECHARGE_BRASIER = 20 * 20, RECHARGE_TOURBILLON = 18 * 20, RECHARGE_AILE = 12 * 20, RECHARGE_ENVOL = 15 * 20;
    /** La renaissance : au plus une fois toutes les 10 minutes (elle marche même endormi, à tous les niveaux). */
    public static final int RECHARGE_RENAISSANCE = 10 * 60 * 20;
    /** 2026-11-16 : tout ramené à environ deux tiers (« il fait trop trop mal »). */
    private static final float COUP_1 = 1.5F, COUP_2 = 3, ECLAT_2 = 2, COUP_3 = 4, CROIX = 5, JET = 4, SERRE = 8, PLUME_PLUIE = 3, PLUIE_MAX = 10,
            PLUME = 4, PLUME_MAX = 8, GRAND_MILIEU = 13, GRAND_BORD = 8, COCON = 3, PIQUE_DEGATS = 8, VOL_DEGATS = 7, TOURBILLON_COUP = 2,
            TOURBILLON_MAX = 15, VAGUE = 5, DOME = 16, ONDE = 4, AILE = 5, ENVOL_DEGATS = 4, COCON_OUVERTURE = 4;
    public static final double PORTEE_JET = 12, PORTEE_PLUIE = 30, RAYON_PLUIE = 4, PORTEE_PLUME = 20, VITESSE_PLUME = 1.4, PORTEE_PIQUE = 25,
            RAYON_BRASIER = 4, RAYON_TOURBILLON = 3, RAYON_GRAND = 5, RAYON_VAGUE = 18, RAYON_DOME = 12;
    public static final int DUREE_PLUIE = 60, DUREE_GRAND = 100, DUREE_COCON = 60, DUREE_BRASIER = 100, DUREE_TOURBILLON = 80, DUREE_RENAISSANCE = 92;
    /**
     * L'ulti (comme l'aperçu docs/phenix/apercu) : la colonne de feu et le phénix géant (0 → 3,4 s), la 1re vague (71), la
     * 2e (91), le plongeon puis le dôme (132), la pluie de plumes et la fin (190).
     */
    public static final int U_VAGUE1 = 71, U_VAGUE2 = 91, U_DOME = 132, U_FIN = 190;
    /** Au niveau 3, il vole 30 s au plus d'affilée (comme le vent 3) ; la jauge se remplit au sol, deux fois plus vite. */
    public static final int VOL_MAX = 30 * 20;
    private static final float VITESSE_VOL = 0.045F;

    // ================================================================ qui est éveillé

    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Recharges R = new Recharges();
    /** Les recharges des touches du niveau 3 qui ne sont pas dans {@link Recharges}. */
    private static final Map<UUID, Integer> R_GRAND = new HashMap<>(), R_PIQUE = new HashMap<>(), R_VOL = new HashMap<>(), R_BRASIER = new HashMap<>(),
            R_TOURBILLON = new HashMap<>();
    private static final Set<UUID> ULTI_FAITE = new HashSet<>();
    /** Quand sa renaissance sera de nouveau prête (tick du serveur). */
    private static final Map<UUID, Integer> RENAISSANCE_PRETE = new HashMap<>();
    private static final Map<UUID, Integer> VOL_RESTE = new HashMap<>();
    private static final Set<UUID> VOL_EPUISE = new HashSet<>();
    /** Qui vient de s'endormir (ou de finir une attaque) en l'air : pas de dégâts de chute jusqu'au sol. Le tick, et s'il a quitté le sol. */
    private static final Map<UUID, int[]> SANS_CHUTE = new HashMap<>();
    /** Ses coups au corps à corps d'affilée (niveau 3) : combien, et le tick du dernier. */
    private static final Map<UUID, int[]> SERIE = new HashMap<>();
    /** Un coup du pouvoir est en train de partir : le clic gauche ne doit pas le compter. */
    private static boolean dansPhenix;

    static boolean eveille(ServerPlayer joueur) {
        return EVEILLES.containsKey(joueur.getUUID()) && est(Pouvoirs.pouvoir(joueur));
    }

    public static int niveau(ServerPlayer joueur) {
        return eveille(joueur) ? EVEILLES.get(joueur.getUUID()) : 0;
    }

    /** Le niveau de son fruit (sa liste blanche), éveillé ou pas : 1, 2, 3 ; 0 s'il n'est pas phénix. */
    public static int niveauFruit(ServerPlayer joueur) {
        String p = Pouvoirs.pouvoir(joueur);
        return NOM3.equals(p) ? 3 : NOM2.equals(p) ? 2 : NOM.equals(p) ? 1 : 0;
    }

    /** L'ulti, ou la renaissance, en cours : l'éveil ne s'endort pas avant la fin. */
    public static boolean occupe(ServerPlayer joueur) {
        return ULTIS.containsKey(joueur.getUUID()) || RENAISSANCES.containsKey(joueur.getUUID());
    }

    static void eveiller(ServerPlayer joueur, int niveau) {
        EVEILLES.put(joueur.getUUID(), niveau);
        ServerLevel monde = joueur.serverLevel();
        Vec3 p = joueur.position();
        son(monde, p, SoundEvents.FIRECHARGE_USE, 1.2F, niveau >= 3 ? 0.6F : 0.9F);
        son(monde, p, SoundEvents.BLAZE_SHOOT, 1F, 0.6F + 0.15F * niveau);
        if (niveau >= 2) {
            son(monde, p, SoundEvents.ENDER_DRAGON_FLAP, 1F, 1.6F);
        }
        flammes(monde, p.add(0, 1, 0), 30 + niveau * 20, 0.6);
        if (niveau >= 3) {
            son(monde, p, SoundEvents.ENDER_DRAGON_GROWL, 0.6F, 1.8F);
            ULTI_FAITE.remove(joueur.getUUID());
            VOL_RESTE.put(joueur.getUUID(), VOL_MAX);
            VOL_EPUISE.remove(joueur.getUUID());
            voler(joueur, true);
            joueur.setDeltaMovement(0, 0.7, 0);
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
        }
    }

    static void endormir(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        Integer etait = EVEILLES.remove(id);
        R.oublier(id);
        for (Map<UUID, Integer> m : List.of(R_GRAND, R_PIQUE, R_VOL, R_BRASIER, R_TOURBILLON, VOL_RESTE)) {
            m.remove(id);
        }
        VOL_EPUISE.remove(id);
        SERIE.remove(id);
        GRANDS.remove(id);
        COCONS.remove(id);
        PIQUES.remove(id);
        VOLS.remove(id);
        SERRES.remove(id);
        TOURBILLONS.remove(id);
        AILES.remove(id);
        ENVOLS.remove(id);
        if (etait != null) {
            if (etait >= 3) {
                voler(joueur, false);
            }
            SANS_CHUTE.put(id, new int[]{joueur.server.getTickCount(), joueur.onGround() ? 0 : 1});
            if (joueur.isAlive()) {
                // les plumes tombent en cendres
                ServerLevel monde = joueur.serverLevel();
                monde.sendParticles(ParticleTypes.SMOKE, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 30, 0.4, 0.7, 0.4, 0.02);
                monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, joueur.getX(), joueur.getY() + 1, joueur.getZ(), 20, 0.4, 0.7, 0.4, 0.02);
                son(monde, joueur.position(), SoundEvents.FIRE_EXTINGUISH, 0.8F, 0.9F);
            }
        }
    }

    /** Au niveau 3, il peut voler (le double saut, comme en créatif) ; endormi, il retombe (sans dégâts de chute). */
    private static void voler(ServerPlayer joueur, boolean oui) {
        if (joueur.isCreative() || joueur.isSpectator()) {
            return;
        }
        Abilities a = joueur.getAbilities();
        a.mayfly = oui;
        a.flying = oui;
        a.setFlyingSpeed(oui ? VITESSE_VOL : 0.05F);
        joueur.onUpdateAbilities();
        joueur.fallDistance = 0;
    }

    public static boolean ultiActif(ServerPlayer joueur) {
        return ULTIS.containsKey(joueur.getUUID());
    }

    // ================================================================ réseau

    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("phenix_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Ce que montre un {@link Effet}. */
    public static final int E_JET = 0, E_REGEN = 1, E_SERRE = 2, E_PLUIE = 3, E_COUP = 4, E_CROIX = 5, E_PLUME = 6, E_PLUME_FIN = 7, E_GRAND = 8,
            E_COCON = 9, E_PIQUE = 10, E_VOL_BLEU = 11, E_BRASIER = 12, E_TOURBILLON = 13, E_ULTI = 14, E_RENAISSANCE = 15, E_REPAS = 16,
            E_BATTEMENT = 17, E_VOL = 18, E_AILE = 19, E_ENVOL = 20;

    /** Serveur → ceux qui sont autour : un coup de flammes. {@code pos}, {@code dir} : où et vers où ; {@code n} : un nombre (un numéro, une entité…). */
    public record Effet(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, int n) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("phenix_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Effet::joueur, ByteBufCodecs.VAR_INT, Effet::sorte, ByteBufCodecs.VAR_INT, Effet::phase,
                ByteBufCodecs.VECTOR3F, Effet::pos, ByteBufCodecs.VECTOR3F, Effet::dir, ByteBufCodecs.VAR_INT, Effet::n, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        touche(joueur, p.action());
                    }
                }))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientPhenix.effet(p)));
    }

    static void effet(ServerPlayer joueur, int sorte, int phase, Vec3 pos, Vec3 dir, int n) {
        envoyer(joueur.serverLevel(), new Effet(joueur.getUUID(), sorte, phase, v(pos), v(dir), n), pos);
    }

    private static final DustParticleOptions CYAN = new DustParticleOptions(new Vector3f(0.35F, 0.9F, 1F), 1.4F);
    private static final DustParticleOptions OR = new DustParticleOptions(new Vector3f(1F, 0.82F, 0.25F), 1.2F);

    /** Des flammes bleues et des étincelles cyan, autour de p. */
    static void flammes(ServerLevel monde, Vec3 p, int n, double r) {
        monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, n, r, r * 0.7, r, 0.03);
        monde.sendParticles(CYAN, p.x, p.y, p.z, n / 2, r, r * 0.7, r, 0.02);
    }

    /** Une hélice de perles dorées qui monte autour de ce vivant (le soin du phénix). */
    private static void perles(ServerLevel monde, LivingEntity e) {
        for (int k = 0; k < 16; k++) {
            double a = k * 0.8, y = k * 0.13;
            monde.sendParticles(OR, e.getX() + Math.cos(a) * 0.7, e.getY() + y, e.getZ() + Math.sin(a) * 0.7, 1, 0, 0, 0, 0);
        }
        monde.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 2, 0.3, 0.1, 0.3, 0);
    }

    /** Ses alliés (son équipe), autour de p : jamais lui-même. */
    private static List<LivingEntity> allies(ServerPlayer joueur, Vec3 p, double r) {
        return joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(r),
                e -> e != joueur && e.isAlive() && e.isAlliedTo(joueur) && e.distanceToSqr(p) <= r * r);
    }

    /** Ceux qu'il peut toucher autour de p (à r blocs du point). */
    private static List<LivingEntity> ennemis(ServerPlayer joueur, Vec3 p, double r) {
        return joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(r + 1),
                e -> touchable(joueur, e) && milieu(e).distanceToSqr(p) <= (r + e.getBbWidth() / 2) * (r + e.getBbWidth() / 2));
    }

    private static void frapper(ServerPlayer joueur, LivingEntity cible, float degats) {
        dansPhenix = true;
        try {
            blesser(joueur, cible, degats);
        } finally {
            dansPhenix = false;
        }
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        int niveau = niveau(joueur);
        if (niveau == 0 || ultiActif(joueur) || RENAISSANCES.containsKey(joueur.getUUID()) || fr.cabane.police.Police.menotte(joueur)) {
            return;
        }
        boolean occupe = COCONS.containsKey(joueur.getUUID()) || PIQUES.containsKey(joueur.getUUID()) || VOLS.containsKey(joueur.getUUID())
                || ENVOLS.containsKey(joueur.getUUID());
        switch (action) {
            case DROIT -> {
                if (occupe) {
                    return;
                }
                if (niveau >= 3) {
                    eventail(joueur);
                } else if (niveau == 2) {
                    serre(joueur);
                } else {
                    jet(joueur);
                }
            }
            case ACCROUPI -> {
                if (niveau == 1) {
                    regen(joueur);
                } else if (niveau == 2) {
                    pluie(joueur);
                }
            }
            case GRAND -> {
                if (niveau >= 3 && !occupe) {
                    grand(joueur);
                }
            }
            case GRAND_LACHE -> {
                Grand g = GRANDS.get(joueur.getUUID());
                if (g != null) {
                    g.lache = true;
                }
            }
            case TECHNIQUE -> {
                if (niveau >= 3 && !occupe) {
                    cocon(joueur);
                }
            }
            case PIQUE -> {
                if (niveau >= 3 && !occupe) {
                    pique(joueur);
                }
            }
            case VOL_BLEU -> {
                if (niveau >= 3 && !occupe) {
                    volBleu(joueur);
                }
            }
            case BRASIER -> {
                if (niveau >= 3) {
                    brasier(joueur);
                }
            }
            case TOURBILLON -> {
                if (niveau >= 3) {
                    tourbillon(joueur);
                }
            }
            case ULTI -> {
                if (niveau >= 3 && !occupe) {
                    lancerUlti(joueur);
                }
            }
            case COUP_AILE -> {
                if (niveau == 2 && !occupe) {
                    coupAile(joueur);
                }
            }
            case ENVOL -> {
                if (niveau == 2 && !occupe) {
                    envol(joueur);
                }
            }
            case BATTEMENT -> {
                if (niveau == 2 && !joueur.onGround()) {
                    joueur.fallDistance = 0;
                    son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 0.8F, 1.5F);
                    flammes(joueur.serverLevel(), joueur.position().add(0, 0.6, 0), 10, 0.5);
                    effet(joueur, E_BATTEMENT, 0, joueur.position(), Vec3.ZERO, 0);
                }
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- niveau 1 : le jet, la régénération

    /** Le premier vivant sur la ligne de a à b (à {@code large} blocs près), ou null. */
    private static LivingEntity premier(ServerPlayer joueur, Vec3 a, Vec3 b, double large) {
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        LivingEntity mieux = null;
        double tMieux = 2;
        for (LivingEntity e : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(large + 1), e -> touchable(joueur, e))) {
            Vec3 c = milieu(e);
            double t = l2 < 1e-6 ? 0 : Mth.clamp(c.subtract(a).dot(ab) / l2, 0, 1);
            double d = c.distanceTo(a.add(ab.scale(t)));
            if (d <= large + e.getBbWidth() / 2 + e.getBbHeight() * 0.3 && t < tMieux) {
                tMieux = t;
                mieux = e;
            }
        }
        return mieux;
    }

    /** Jusqu'où va une ligne droite depuis a, vers dir, sur {@code portee} blocs, avant de buter sur un bloc. */
    private static Vec3 bout(ServerPlayer joueur, Vec3 a, Vec3 dir, double portee) {
        Vec3 loin = a.add(dir.scale(portee));
        HitResult r = joueur.serverLevel().clip(new ClipContext(a, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        return r.getType() == HitResult.Type.MISS ? loin : r.getLocation();
    }

    private static Vec3 main(ServerPlayer joueur) {
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        return joueur.getEyePosition().add(0, -0.5, 0).add(droite.scale(0.35)).add(joueur.getLookAngle().scale(0.5));
    }

    private static void jet(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_JET);
        ServerLevel monde = joueur.serverLevel();
        Vec3 dep = main(joueur), dir = joueur.getLookAngle();
        Vec3 fin = bout(joueur, joueur.getEyePosition(), dir, PORTEE_JET);
        LivingEntity cible = premier(joueur, joueur.getEyePosition(), fin, 0.6);
        if (cible != null) {
            fin = milieu(cible);
            frapper(joueur, cible, JET);
            cible.igniteForSeconds(4);
            flammes(monde, fin, 20, 0.4);
        }
        effet(joueur, E_JET, 0, dep, fin, cible == null ? -1 : cible.getId());
        for (double k = 0; k < 1; k += 0.08) {
            Vec3 p = dep.lerp(fin, k);
            monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 2, 0.08, 0.08, 0.08, 0.01);
        }
        son(monde, dep, SoundEvents.BLAZE_SHOOT, 1F, 1.3F);
        son(monde, dep, SoundEvents.FIRECHARGE_USE, 0.8F, 1.4F);
    }

    private static void regen(ServerPlayer joueur) {
        if (!joueur.onGround() || !pret(R.accroupi, joueur)) {
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_REGEN);
        ServerLevel monde = joueur.serverLevel();
        joueur.heal(6);
        perles(monde, joueur);
        for (LivingEntity a : allies(joueur, joueur.position(), 5)) {
            a.heal(3);
            perles(monde, a);
        }
        flammes(monde, joueur.position().add(0, 0.3, 0), 30, 1.5);
        effet(joueur, E_REGEN, 0, joueur.position(), Vec3.ZERO, 5);
        son(monde, joueur.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2F, 1.2F);
        son(monde, joueur.position(), SoundEvents.FIRECHARGE_USE, 0.6F, 1.6F);
    }

    // ---------------------------------------------------------------- niveau 2 : la serre, la pluie de plumes

    /** Une ruée (le coup de serre) : le tick de la fin, sa direction. */
    private record Serre(int fin, Vec3 dir) {
    }

    private static final Map<UUID, Serre> SERRES = new HashMap<>();

    private static void serre(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_SERRE);
        Vec3 l = joueur.getLookAngle();
        Vec3 dir = new Vec3(l.x, Mth.clamp(l.y, -0.3, 0.3), l.z).normalize();
        SERRES.put(joueur.getUUID(), new Serre(joueur.server.getTickCount() + 6, dir));
        effet(joueur, E_SERRE, 0, joueur.position(), dir, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.2F, 1.3F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.PHANTOM_SWOOP, 1.2F, 1F);
    }

    private static void serres(MinecraftServer server, int maintenant) {
        for (Iterator<Map.Entry<UUID, Serre>> it = SERRES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Serre> en = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Serre s = en.getValue();
            if (joueur == null || maintenant > s.fin()) {
                it.remove();
                continue;
            }
            joueur.setDeltaMovement(s.dir().scale(1.35));
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
            ServerLevel monde = joueur.serverLevel();
            flammes(monde, joueur.position().add(0, 0.8, 0), 6, 0.3);
            LivingEntity cible = premier(joueur, joueur.position().add(0, 0.9, 0), joueur.position().add(0, 0.9, 0).add(s.dir().scale(1.8)), 0.8);
            if (cible != null) {
                frapper(joueur, cible, SERRE);
                cible.knockback(1.5, -s.dir().x, -s.dir().z);
                flammes(monde, milieu(cible), 25, 0.4);
                monde.sendParticles(ParticleTypes.SWEEP_ATTACK, cible.getX(), cible.getY(0.6), cible.getZ(), 1, 0, 0, 0, 0);
                son(monde, cible.position(), SoundEvents.PLAYER_ATTACK_CRIT, 1.4F, 0.8F);
                effet(joueur, E_SERRE, 1, milieu(cible), s.dir(), cible.getId());
                joueur.setDeltaMovement(s.dir().scale(0.2));
                it.remove();
            }
        }
    }

    /** La pluie de plumes : sa zone, son début, et combien chaque cible a déjà pris. */
    private record Pluie(ServerPlayer joueur, Vec3 zone, int debut, Map<Integer, Float> pris) {
    }

    private static final List<Pluie> PLUIES = new ArrayList<>();

    private static void pluie(ServerPlayer joueur) {
        if (!joueur.onGround() || !pret(R.accroupi, joueur)) {
            return;
        }
        armer(R.accroupi, joueur, RECHARGE_PLUIE);
        Vec3 zone = pointVise(joueur, PORTEE_PLUIE);
        PLUIES.add(new Pluie(joueur, zone, joueur.server.getTickCount(), new HashMap<>()));
        effet(joueur, E_PLUIE, 0, zone, joueur.position(), DUREE_PLUIE);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 0.9F);
        son(joueur.serverLevel(), zone, SoundEvents.FIRECHARGE_USE, 1.2F, 0.8F);
    }

    private static void pluies(int maintenant) {
        PLUIES.removeIf(p -> {
            int age = maintenant - p.debut();
            if (p.joueur().isRemoved() || age > DUREE_PLUIE) {
                return true;
            }
            if (age % 10 == 0) {
                ServerLevel monde = p.joueur().serverLevel();
                for (LivingEntity e : ennemis(p.joueur(), p.zone().add(0, 1, 0), RAYON_PLUIE)) {
                    float deja = p.pris().getOrDefault(e.getId(), 0F);
                    if (deja >= PLUIE_MAX) {
                        continue;
                    }
                    p.pris().put(e.getId(), deja + PLUME_PLUIE);
                    frapper(p.joueur(), e, PLUME_PLUIE);
                    e.igniteForSeconds(2);
                    flammes(monde, milieu(e), 8, 0.3);
                }
                son(monde, p.zone(), SoundEvents.FIRECHARGE_USE, 0.8F, 1.2F);
            }
            return false;
        });
    }

    /**
     * Le coup d'aile (niveau 2, touche technique) : il ramène ses ailes devant lui, un croissant de flammes bleues part
     * droit devant (6 blocs de large, 8 de long, en 0,8 s) : 8 et gros recul à qui il traverse, des flammes bleues au sol.
     */
    private record Aile(int debut, Vec3 depart, Vec3 dir, Set<Integer> touches) {
    }

    private static final Map<UUID, Aile> AILES = new HashMap<>();

    private static void coupAile(ServerPlayer joueur) {
        if (!pret(R.technique, joueur)) {
            return;
        }
        armer(R.technique, joueur, RECHARGE_AILE);
        Vec3 l = joueur.getLookAngle(), dir = new Vec3(l.x, 0, l.z);
        dir = dir.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : dir.normalize();
        AILES.put(joueur.getUUID(), new Aile(joueur.server.getTickCount(), joueur.position(), dir, new HashSet<>()));
        effet(joueur, E_AILE, 0, joueur.position(), dir, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.6F, 0.9F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BLAZE_SHOOT, 1.2F, 0.8F);
    }

    private static void ailes(MinecraftServer server, int maintenant) {
        AILES.entrySet().removeIf(en -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Aile a = en.getValue();
            int age = maintenant - a.debut() - 3;
            if (joueur == null || age > 16) {
                return true;
            }
            if (age < 0) {
                return false;
            }
            ServerLevel monde = joueur.serverLevel();
            double front = 1 + 8 * age / 16.0;
            Vec3 cote = new Vec3(-a.dir().z, 0, a.dir().x);
            for (LivingEntity e : ennemis(joueur, a.depart().add(a.dir().scale(front)), 4)) {
                Vec3 rel = e.position().subtract(a.depart());
                double le = rel.dot(a.dir()), tr = Math.abs(rel.dot(cote));
                if (le < front - 1.4 || le > front + 0.8 || tr > 3.2 || Math.abs(rel.y) > 3 || !a.touches().add(e.getId())) {
                    continue;
                }
                frapper(joueur, e, AILE);
                e.igniteForSeconds(3);
                e.setDeltaMovement(a.dir().scale(1.5).add(0, 0.45, 0));
                e.hurtMarked = true;
                flammes(monde, milieu(e), 12, 0.4);
            }
            if (age % 3 == 0) {
                for (int s = -1; s <= 1; s++) {
                    Vec3 p = a.depart().add(a.dir().scale(front)).add(cote.scale(s * 2.2));
                    BlockPos b = FeuBleu.sol(monde, BlockPos.containing(p.x, p.y + 0.5, p.z));
                    if (b != null && monde.random.nextInt(3) > 0) {
                        FeuBleu.poser(monde, b, joueur.getUUID());
                    }
                }
            }
            return false;
        });
    }

    /**
     * L'envol ardent (niveau 2, touche L) : il s'accroupit 0,35 s, puis décolle de 7 blocs dans une colonne de feu bleu ;
     * à 3 blocs : 6, projetés en l'air, un cercle de flammes bleues au sol. Puis il plane (ses battements sont finis).
     */
    private static final Map<UUID, Integer> ENVOLS = new HashMap<>();

    private static void envol(ServerPlayer joueur) {
        if (!joueur.onGround() || !pret(R_PIQUE, joueur)) {
            return;
        }
        armer(R_PIQUE, joueur, RECHARGE_ENVOL);
        ENVOLS.put(joueur.getUUID(), joueur.server.getTickCount());
        effet(joueur, E_ENVOL, 0, joueur.position(), Vec3.ZERO, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.FIRECHARGE_USE, 1.2F, 0.6F);
    }

    private static void envols(MinecraftServer server, int maintenant) {
        ENVOLS.entrySet().removeIf(en -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            int age = maintenant - en.getValue();
            if (joueur == null) {
                return true;
            }
            if (age < 7) {
                joueur.setDeltaMovement(0, Math.min(0, joueur.getDeltaMovement().y), 0);
                joueur.hurtMarked = true;
                return false;
            }
            ServerLevel monde = joueur.serverLevel();
            Vec3 c = joueur.position();
            joueur.setDeltaMovement(joueur.getDeltaMovement().x, 1.15, joueur.getDeltaMovement().z);
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
            SANS_CHUTE.put(joueur.getUUID(), new int[]{maintenant, 0});
            effet(joueur, E_ENVOL, 1, c, Vec3.ZERO, 0);
            son(monde, c, SoundEvents.BLAZE_SHOOT, 1.6F, 0.6F);
            son(monde, c, SoundEvents.ENDER_DRAGON_FLAP, 1.6F, 1.2F);
            flammes(monde, c.add(0, 1, 0), 60, 1);
            for (LivingEntity e : ennemis(joueur, c.add(0, 1, 0), 3)) {
                frapper(joueur, e, ENVOL_DEGATS);
                e.igniteForSeconds(3);
                Vec3 h = new Vec3(e.getX() - c.x, 0, e.getZ() - c.z);
                h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                e.setDeltaMovement(h.scale(0.5).add(0, 0.95, 0));
                e.hurtMarked = true;
            }
            for (int k = 0; k < 12; k++) {
                double a = k * Mth.TWO_PI / 12;
                BlockPos b = FeuBleu.sol(monde, BlockPos.containing(c.x + Math.cos(a) * 3, c.y + 0.3, c.z + Math.sin(a) * 3));
                if (b != null) {
                    FeuBleu.poser(monde, b, joueur.getUUID());
                }
            }
            return true;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : l'éventail (des plumes qui volent)

    private static final class Plume {
        final ServerPlayer tireur;
        final int id;
        Vec3 pos;
        final Vec3 vit;
        double parcouru;
        final Map<Integer, Float> pris;

        Plume(ServerPlayer tireur, int id, Vec3 pos, Vec3 vit, Map<Integer, Float> pris) {
            this.tireur = tireur;
            this.id = id;
            this.pos = pos;
            this.vit = vit;
            this.pris = pris;
        }
    }

    private static final List<Plume> PLUMES = new ArrayList<>();
    private static int prochaine;

    private static void eventail(ServerPlayer joueur) {
        if (!pret(R.droit, joueur)) {
            return;
        }
        armer(R.droit, joueur, RECHARGE_EVENTAIL);
        Vec3 regard = joueur.getLookAngle(), dep = joueur.getEyePosition().add(0, -0.3, 0).add(regard.scale(0.8));
        Map<Integer, Float> pris = new HashMap<>();
        for (int k = 0; k < 7; k++) {
            Vec3 d = regard.yRot((k - 3) * 0.16F).normalize().scale(VITESSE_PLUME);
            Plume p = new Plume(joueur, ++prochaine, dep, d, pris);
            PLUMES.add(p);
            effet(joueur, E_PLUME, (int) Math.round(PORTEE_PLUME / VITESSE_PLUME), dep, d, p.id);
        }
        son(joueur.serverLevel(), dep, SoundEvents.ENDER_DRAGON_FLAP, 1.2F, 1.4F);
        son(joueur.serverLevel(), dep, SoundEvents.BLAZE_SHOOT, 0.9F, 1.5F);
    }

    private static void plumes() {
        PLUMES.removeIf(p -> {
            if (p.tireur.isRemoved()) {
                return true;
            }
            ServerLevel monde = p.tireur.serverLevel();
            Vec3 suite = p.pos.add(p.vit);
            HitResult r = monde.clip(new ClipContext(p.pos, suite, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p.tireur));
            Vec3 arret = r.getType() == HitResult.Type.MISS ? suite : r.getLocation();
            LivingEntity cible = premier(p.tireur, p.pos, arret, 0.5);
            boolean fini = false;
            if (cible != null) {
                float deja = p.pris.getOrDefault(cible.getId(), 0F);
                if (deja < PLUME_MAX) {
                    p.pris.put(cible.getId(), deja + PLUME);
                    frapper(p.tireur, cible, PLUME);
                    cible.igniteForSeconds(2);
                }
                flammes(monde, milieu(cible), 8, 0.3);
                arret = milieu(cible);
                fini = true;
            } else if (r.getType() != HitResult.Type.MISS) {
                fini = true;
            }
            p.parcouru += p.vit.length();
            p.pos = arret;
            if (monde.random.nextInt(2) == 0) {
                monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.pos.x, p.pos.y, p.pos.z, 1, 0.05, 0.05, 0.05, 0);
            }
            if (fini || p.parcouru >= PORTEE_PLUME) {
                envoyer(monde, new Effet(p.tireur.getUUID(), E_PLUME_FIN, 0, v(p.pos), v(Vec3.ZERO), p.id), p.pos);
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : le grand phénix (clic droit maintenu)

    private static final class Grand {
        final int debut;
        Vec3 pos;
        boolean lache;

        Grand(int debut, Vec3 pos) {
            this.debut = debut;
            this.pos = pos;
        }
    }

    private static final Map<UUID, Grand> GRANDS = new HashMap<>();

    private static void grand(ServerPlayer joueur) {
        if (GRANDS.containsKey(joueur.getUUID()) || !pret(R_GRAND, joueur)) {
            return;
        }
        armer(R_GRAND, joueur, RECHARGE_GRAND);
        Vec3 p = joueur.getEyePosition().add(joueur.getLookAngle().scale(3));
        GRANDS.put(joueur.getUUID(), new Grand(joueur.server.getTickCount(), p));
        effet(joueur, E_GRAND, 0, p, joueur.getLookAngle(), 0);
        son(joueur.serverLevel(), p, SoundEvents.ENDER_DRAGON_GROWL, 1F, 1.7F);
        son(joueur.serverLevel(), p, SoundEvents.BLAZE_SHOOT, 1.4F, 0.6F);
    }

    private static void grands(MinecraftServer server, int maintenant) {
        for (Iterator<Map.Entry<UUID, Grand>> it = GRANDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Grand> en = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Grand g = en.getValue();
            if (joueur == null) {
                it.remove();
                continue;
            }
            int age = maintenant - g.debut;
            ServerLevel monde = joueur.serverLevel();
            Vec3 dir = joueur.getLookAngle();
            Vec3 suite = g.pos.add(dir.scale(0.8));
            HitResult r = monde.clip(new ClipContext(g.pos, suite, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
            boolean bute = r.getType() != HitResult.Type.MISS || premier(joueur, g.pos, suite, 1.4) != null;
            g.pos = r.getType() == HitResult.Type.MISS ? suite : r.getLocation();
            if (age % 2 == 0) {
                effet(joueur, E_GRAND, 1, g.pos, dir, age);
            }
            if (age % 3 == 0) {
                flammes(monde, g.pos, 6, 0.8);
            }
            if (age % 16 == 0) {
                son(monde, g.pos, SoundEvents.ENDER_DRAGON_FLAP, 1.2F, 1.2F);
            }
            if (g.lache || bute || age >= DUREE_GRAND) {
                it.remove();
                exploserGrand(joueur, g.pos);
            }
        }
    }

    private static void exploserGrand(ServerPlayer joueur, Vec3 c) {
        ServerLevel monde = joueur.serverLevel();
        effet(joueur, E_GRAND, 2, c, Vec3.ZERO, 0);
        son(monde, c, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 1.2F);
        son(monde, c, SoundEvents.ENDER_DRAGON_GROWL, 1F, 1.9F);
        monde.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 3, 1, 1, 1, 0);
        flammes(monde, c, 120, 2.5);
        for (LivingEntity e : ennemis(joueur, c, RAYON_GRAND)) {
            double d = milieu(e).distanceTo(c);
            frapper(joueur, e, (float) (GRAND_MILIEU - (GRAND_MILIEU - GRAND_BORD) * Mth.clamp(d / RAYON_GRAND, 0, 1)));
            e.igniteForSeconds(3);
            Vec3 h = new Vec3(e.getX() - c.x, 0, e.getZ() - c.z);
            h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
            e.setDeltaMovement(h.scale(1.1).add(0, 0.5, 0));
            e.hurtMarked = true;
        }
        for (LivingEntity a : allies(joueur, c, RAYON_GRAND + 1)) {
            a.heal(6);
            perles(monde, a);
        }
        if (joueur.distanceToSqr(c) < (RAYON_GRAND + 1) * (RAYON_GRAND + 1)) {
            joueur.heal(6);
        }
        FeuBleu.semer(monde, c, 4, 0.4, joueur.getUUID());
    }

    // ---------------------------------------------------------------- niveau 3 : le cocon (touche technique)

    private static final Map<UUID, Integer> COCONS = new HashMap<>();

    private static void cocon(ServerPlayer joueur) {
        if (!pret(R.technique, joueur)) {
            return;
        }
        armer(R.technique, joueur, RECHARGE_COCON);
        ServerLevel monde = joueur.serverLevel();
        COCONS.put(joueur.getUUID(), joueur.server.getTickCount());
        effet(joueur, E_COCON, 0, joueur.position(), Vec3.ZERO, DUREE_COCON);
        son(monde, joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 0.8F);
        son(monde, joueur.position(), SoundEvents.FIRECHARGE_USE, 1.2F, 0.7F);
        flammes(monde, joueur.position().add(0, 1, 0), 60, 1.2);
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DUREE_COCON, 3, false, false, false));
    }

    private static void cocons(MinecraftServer server, int maintenant) {
        COCONS.entrySet().removeIf(en -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            int age = maintenant - en.getValue();
            if (joueur == null || age > DUREE_COCON) {
                return true;
            }
            ServerLevel monde = joueur.serverLevel();
            // ceux qui le touchent brûlent : 4 par seconde à 2 blocs
            if (age % 20 == 10) {
                for (LivingEntity e : ennemis(joueur, joueur.position().add(0, 1, 0), 2)) {
                    frapper(joueur, e, COCON);
                    e.igniteForSeconds(2);
                }
            }
            // il s'ouvre comme une fleur : 6 et recul à 4 blocs
            if (age == DUREE_COCON) {
                son(monde, joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.6F, 0.9F);
                son(monde, joueur.position(), SoundEvents.BLAZE_SHOOT, 1.4F, 0.7F);
                flammes(monde, joueur.position().add(0, 1, 0), 60, 1.6);
                for (LivingEntity e : ennemis(joueur, joueur.position().add(0, 1, 0), 4)) {
                    frapper(joueur, e, COCON_OUVERTURE);
                    Vec3 h = new Vec3(e.getX() - joueur.getX(), 0, e.getZ() - joueur.getZ());
                    h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                    e.setDeltaMovement(h.scale(1.3).add(0, 0.5, 0));
                    e.hurtMarked = true;
                    e.igniteForSeconds(2);
                }
            }
            // 10 de soin en 3 s : 1 toutes les 6 ticks
            if (age % 6 == 0 && age > 0) {
                joueur.heal(1);
            }
            if (age % 4 == 0) {
                flammes(joueur.serverLevel(), joueur.position().add(0, 1, 0), 6, 0.7);
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : le piqué (L)

    private record Pique(int debut, Vec3 cible) {
    }

    private static final Map<UUID, Pique> PIQUES = new HashMap<>();

    private static void pique(ServerPlayer joueur) {
        if (!pret(R_PIQUE, joueur)) {
            return;
        }
        armer(R_PIQUE, joueur, RECHARGE_PIQUE);
        Vec3 cible = pointVise(joueur, PORTEE_PIQUE);
        PIQUES.put(joueur.getUUID(), new Pique(joueur.server.getTickCount(), cible));
        Abilities a = joueur.getAbilities();
        if (a.flying) {
            a.flying = false;
            joueur.onUpdateAbilities();
        }
        effet(joueur, E_PIQUE, 0, joueur.position(), cible, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 1F);
    }

    private static void piques(MinecraftServer server, int maintenant) {
        for (Iterator<Map.Entry<UUID, Pique>> it = PIQUES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pique> en = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Pique p = en.getValue();
            if (joueur == null) {
                it.remove();
                continue;
            }
            int age = maintenant - p.debut();
            ServerLevel monde = joueur.serverLevel();
            joueur.fallDistance = 0;
            flammes(monde, joueur.position().add(0, 0.8, 0), 5, 0.3);
            if (age < 8) {
                // il monte, ailes ouvertes
                joueur.setDeltaMovement(0, 1.0, 0);
                joueur.hurtMarked = true;
                continue;
            }
            if (age == 8) {
                effet(joueur, E_PIQUE, 1, joueur.position(), p.cible(), 0);
                son(monde, joueur.position(), SoundEvents.PHANTOM_SWOOP, 1.6F, 0.8F);
            }
            Vec3 vers = p.cible().subtract(joueur.position());
            boolean arrive = vers.length() < 1.6 || (age > 11 && joueur.onGround()) || age > 50;
            if (!arrive) {
                joueur.setDeltaMovement(vers.normalize().scale(Math.min(1.8, vers.length())));
                joueur.hurtMarked = true;
                continue;
            }
            it.remove();
            Vec3 c = joueur.position();
            joueur.setDeltaMovement(0, 0, 0);
            joueur.hurtMarked = true;
            effet(joueur, E_PIQUE, 2, c, Vec3.ZERO, 0);
            son(monde, c, SoundEvents.GENERIC_EXPLODE.value(), 1.4F, 1.3F);
            son(monde, c, SoundEvents.BLAZE_SHOOT, 1.4F, 0.6F);
            monde.sendParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 2, 0.8, 0.3, 0.8, 0);
            flammes(monde, c.add(0, 0.5, 0), 80, 2);
            for (LivingEntity e : ennemis(joueur, c.add(0, 0.5, 0), 4)) {
                frapper(joueur, e, PIQUE_DEGATS);
                Vec3 h = new Vec3(e.getX() - c.x, 0, e.getZ() - c.z);
                h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                e.setDeltaMovement(h.scale(0.6).add(0, 0.9, 0));
                e.hurtMarked = true;
                e.igniteForSeconds(3);
            }
            FeuBleu.semer(monde, c, 3, 0.5, joueur.getUUID());
            SANS_CHUTE.put(joueur.getUUID(), new int[]{maintenant, joueur.onGround() ? 0 : 1});
        }
    }

    // ---------------------------------------------------------------- niveau 3 : le vol bleu (I)

    private record Vol(int debut, Vec3 depart, Vec3 dir, Set<Integer> touches, Set<Integer> soignes) {
    }

    private static final Map<UUID, Vol> VOLS = new HashMap<>();

    private static void volBleu(ServerPlayer joueur) {
        if (!pret(R_VOL, joueur)) {
            return;
        }
        armer(R_VOL, joueur, RECHARGE_VOL);
        Vec3 dir = joueur.getLookAngle();
        VOLS.put(joueur.getUUID(), new Vol(joueur.server.getTickCount(), joueur.position(), dir, new HashSet<>(), new HashSet<>()));
        effet(joueur, E_VOL_BLEU, 0, joueur.position(), dir, 0);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 1.6F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.FIRECHARGE_USE, 1.2F, 1.2F);
    }

    private static void vols(MinecraftServer server, int maintenant) {
        for (Iterator<Map.Entry<UUID, Vol>> it = VOLS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Vol> en = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Vol vol = en.getValue();
            int age = maintenant - vol.debut();
            if (joueur == null) {
                it.remove();
                continue;
            }
            ServerLevel monde = joueur.serverLevel();
            if (age >= 8) {
                it.remove();
                joueur.setDeltaMovement(vol.dir().scale(0.3));
                joueur.hurtMarked = true;
                effet(joueur, E_VOL_BLEU, 1, joueur.position(), vol.depart(), 0);
                SANS_CHUTE.put(joueur.getUUID(), new int[]{maintenant, joueur.onGround() ? 0 : 1});
                continue;
            }
            joueur.setDeltaMovement(vol.dir().scale(1.9));
            joueur.hurtMarked = true;
            joueur.fallDistance = 0;
            Vec3 p = joueur.position().add(0, 0.9, 0);
            monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 12, 0.4, 0.4, 0.4, 0.02);
            monde.sendParticles(CYAN, p.x, p.y, p.z, 8, 0.5, 0.5, 0.5, 0.02);
            for (LivingEntity e : ennemis(joueur, p, 1.6)) {
                if (vol.touches().add(e.getId())) {
                    frapper(joueur, e, VOL_DEGATS);
                    e.igniteForSeconds(3);
                    e.knockback(0.8, -vol.dir().x, -vol.dir().z);
                }
            }
            for (LivingEntity a : allies(joueur, p, 2.5)) {
                if (vol.soignes().add(a.getId())) {
                    a.heal(4);
                    perles(monde, a);
                }
            }
        }
    }

    // ---------------------------------------------------------------- niveau 3 : le brasier (virgule)

    private record Brasier(ServerPlayer joueur, Vec3 centre, int debut) {
    }

    private static final List<Brasier> BRASIERS = new ArrayList<>();

    private static void brasier(ServerPlayer joueur) {
        if (!pret(R_BRASIER, joueur)) {
            return;
        }
        armer(R_BRASIER, joueur, RECHARGE_BRASIER);
        ServerLevel monde = joueur.serverLevel();
        Vec3 c = joueur.position();
        BRASIERS.add(new Brasier(joueur, c, joueur.server.getTickCount()));
        // le cercle de flammes (une case environ par bloc de tour), et quelques-unes dedans
        int n = (int) Math.round(Mth.TWO_PI * RAYON_BRASIER);
        for (int k = 0; k < n; k++) {
            double a = k * Mth.TWO_PI / n;
            BlockPos p = FeuBleu.sol(monde, BlockPos.containing(c.x + Math.cos(a) * RAYON_BRASIER, c.y + 0.2, c.z + Math.sin(a) * RAYON_BRASIER));
            if (p != null) {
                FeuBleu.poser(monde, p, joueur.getUUID(), DUREE_BRASIER);
            }
        }
        for (int k = 0; k < 8; k++) {
            double a = monde.random.nextDouble() * Mth.TWO_PI, r = 1 + monde.random.nextDouble() * (RAYON_BRASIER - 1.5);
            BlockPos p = FeuBleu.sol(monde, BlockPos.containing(c.x + Math.cos(a) * r, c.y + 0.2, c.z + Math.sin(a) * r));
            if (p != null) {
                FeuBleu.poser(monde, p, joueur.getUUID(), DUREE_BRASIER);
            }
        }
        effet(joueur, E_BRASIER, 0, c, Vec3.ZERO, DUREE_BRASIER);
        son(monde, c, SoundEvents.FIRECHARGE_USE, 1.4F, 0.6F);
        son(monde, c, SoundEvents.BLAZE_SHOOT, 1.2F, 0.5F);
        flammes(monde, c.add(0, 0.3, 0), 60, RAYON_BRASIER * 0.6);
    }

    private static void brasiers(int maintenant) {
        BRASIERS.removeIf(b -> {
            int age = maintenant - b.debut();
            if (b.joueur().isRemoved() || age > DUREE_BRASIER) {
                return true;
            }
            if (age % 20 == 10) {
                ServerLevel monde = b.joueur().serverLevel();
                if (b.joueur().position().distanceToSqr(b.centre()) <= (RAYON_BRASIER + 0.5) * (RAYON_BRASIER + 0.5)) {
                    b.joueur().heal(2);
                    monde.sendParticles(ParticleTypes.HEART, b.joueur().getX(), b.joueur().getY() + 2, b.joueur().getZ(), 1, 0.2, 0.1, 0.2, 0);
                }
                for (LivingEntity e : ennemis(b.joueur(), b.centre().add(0, 0.5, 0), RAYON_BRASIER + 0.5)) {
                    if (!e.fireImmune()) {
                        e.igniteForSeconds(4);
                    }
                    frapper(b.joueur(), e, 1);
                }
            }
            return false;
        });
    }

    // ---------------------------------------------------------------- niveau 3 : le tourbillon de plumes (point)

    private record Tourbillon(int debut, Map<Integer, Float> pris) {
    }

    private static final Map<UUID, Tourbillon> TOURBILLONS = new HashMap<>();

    private static void tourbillon(ServerPlayer joueur) {
        if (!pret(R_TOURBILLON, joueur)) {
            return;
        }
        armer(R_TOURBILLON, joueur, RECHARGE_TOURBILLON);
        TOURBILLONS.put(joueur.getUUID(), new Tourbillon(joueur.server.getTickCount(), new HashMap<>()));
        effet(joueur, E_TOURBILLON, 0, joueur.position(), Vec3.ZERO, DUREE_TOURBILLON);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 1.2F);
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BREEZE_WHIRL, 1.4F, 1.1F);
    }

    private static void tourbillons(MinecraftServer server, int maintenant) {
        TOURBILLONS.entrySet().removeIf(en -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Tourbillon t = en.getValue();
            int age = maintenant - t.debut();
            if (joueur == null || age > DUREE_TOURBILLON) {
                return true;
            }
            if (age % 10 == 5) {
                ServerLevel monde = joueur.serverLevel();
                for (LivingEntity e : ennemis(joueur, joueur.position().add(0, 1, 0), RAYON_TOURBILLON)) {
                    float deja = t.pris().getOrDefault(e.getId(), 0F);
                    if (deja >= TOURBILLON_MAX) {
                        continue;
                    }
                    t.pris().put(e.getId(), deja + TOURBILLON_COUP);
                    frapper(joueur, e, TOURBILLON_COUP);
                    flammes(monde, milieu(e), 5, 0.3);
                }
                if (age % 20 == 5) {
                    son(monde, joueur.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1F, 1.3F);
                }
            }
            return false;
        });
    }

    // ================================================================ l'ulti : l'aube du phénix

    /**
     * Une fois par éveil : un phénix géant jaillit de lui et monte au-dessus (0) ; deux vagues de plumes de feu partent
     * tout autour (18 blocs, 8 chacune, à 1,5 s et 3 s) ; puis il plonge et le dôme de flammes bleues éclate (12 blocs,
     * 25, tout est projeté ; ses alliés sont soignés à fond, des flammes bleues au sol). Il peut bouger pendant ce temps.
     */
    private record Ulti(ServerLevel monde, int debut, Vec3 centre) {
    }

    private static final Map<UUID, Ulti> ULTIS = new HashMap<>();

    private static void lancerUlti(ServerPlayer joueur) {
        if (ULTI_FAITE.contains(joueur.getUUID())) {
            joueur.displayClientMessage(Component.literal("L'aube du phénix est déjà passée pendant cet éveil."), true);
            return;
        }
        ULTI_FAITE.add(joueur.getUUID());
        Vec3 c = joueur.position();
        ULTIS.put(joueur.getUUID(), new Ulti(joueur.serverLevel(), joueur.server.getTickCount(), c));
        effet(joueur, E_ULTI, 0, c, Pouvoirs.devant(joueur), 0);
        son(joueur.serverLevel(), c, SoundEvents.ENDER_DRAGON_GROWL, 2F, 1.4F);
        son(joueur.serverLevel(), c, SoundEvents.BLAZE_SHOOT, 2F, 0.4F);
        flammes(joueur.serverLevel(), c.add(0, 1, 0), 120, 2);
    }

    private static void ultis(MinecraftServer server, int maintenant) {
        for (Iterator<Map.Entry<UUID, Ulti>> it = ULTIS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ulti> en = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Ulti u = en.getValue();
            if (joueur == null) {
                it.remove();
                continue;
            }
            int age = maintenant - u.debut();
            ServerLevel monde = u.monde();
            Vec3 c = u.centre();
            if (age < U_DOME && age % 5 == 0) {
                flammes(monde, c.add(0, 8, 0), 20, 3);
            }
            if (age % 20 == 0 && age < U_DOME) {
                son(monde, c.add(0, 10, 0), SoundEvents.ENDER_DRAGON_FLAP, 2.5F, 0.8F);
            }
            if (age == U_VAGUE1 || age == U_VAGUE2) {
                effet(joueur, E_ULTI, age == U_VAGUE1 ? 1 : 2, c, Vec3.ZERO, 0);
                son(monde, c, SoundEvents.BLAZE_SHOOT, 2F, 0.8F);
                son(monde, c, SoundEvents.ENDER_DRAGON_GROWL, 1.2F, 1.6F);
                for (LivingEntity e : ennemis(joueur, c.add(0, 1, 0), RAYON_VAGUE)) {
                    frapper(joueur, e, VAGUE);
                    e.igniteForSeconds(3);
                    flammes(monde, milieu(e), 10, 0.3);
                }
            }
            if (age == U_DOME) {
                effet(joueur, E_ULTI, 3, c, Vec3.ZERO, 0);
                son(monde, c, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.7F);
                son(monde, c, SoundEvents.ENDER_DRAGON_GROWL, 2F, 1.2F);
                son(monde, c, SoundEvents.FIRECHARGE_USE, 2F, 0.5F);
                monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 2, 1, 1, 1, 0);
                flammes(monde, c.add(0, 2, 0), 300, 6);
                for (LivingEntity e : ennemis(joueur, c.add(0, 1, 0), RAYON_DOME)) {
                    frapper(joueur, e, DOME);
                    e.igniteForSeconds(5);
                    Vec3 h = new Vec3(e.getX() - c.x, 0, e.getZ() - c.z);
                    h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                    e.setDeltaMovement(h.scale(1.8).add(0, 1, 0));
                    e.hurtMarked = true;
                }
                for (LivingEntity a : allies(joueur, c, RAYON_DOME)) {
                    a.setHealth(a.getMaxHealth());
                    perles(monde, a);
                }
                joueur.heal(joueur.getMaxHealth());
                perles(monde, joueur);
                FeuBleu.semer(monde, c, 9, 0.22, joueur.getUUID());
            }
            if (age >= U_FIN) {
                it.remove();
                effet(joueur, E_ULTI, 4, c, Vec3.ZERO, 0);
            }
        }
    }

    // ================================================================ la renaissance (passif du niveau 3)

    /**
     * Une fois par éveil, au niveau 3, le coup qui devrait le tuer ne le tue pas : il s'embrase et renaît de ses cendres,
     * sur place, en 4 s (docs/fruits/planche-phenix-renaissance.png). Pendant ce temps, rien ne peut le toucher (il est en
     * cendres, invisible de 0,5 s à 3,5 s) et il ne bouge pas ; il garde ses objets. À la fin : 10 cœurs, Régénération II
     * 5 s, 6 et recul à 4 blocs, ses alliés à 6 blocs +4. Les coups qui passent tout (le vide, /kill) le tuent quand même.
     */
    private record Renaissance(ServerLevel monde, int debut, Vec3 pos) {
    }

    private static final Map<UUID, Renaissance> RENAISSANCES = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void mort(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joueur) || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }
        if (RENAISSANCES.containsKey(joueur.getUUID())) {
            event.setCanceled(true);
            joueur.setHealth(1);
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (niveauFruit(joueur) == 0 || RENAISSANCE_PRETE.getOrDefault(joueur.getUUID(), 0) > maintenant) {
            return;
        }
        event.setCanceled(true);
        joueur.setHealth(1);
        RENAISSANCE_PRETE.put(joueur.getUUID(), maintenant + RECHARGE_RENAISSANCE);
        ServerLevel monde = joueur.serverLevel();
        Vec3 p = joueur.position();
        double y = sol(monde, p.add(0, 0.5, 0));
        if (Math.abs(y - p.y) < 6) {
            p = new Vec3(p.x, y, p.z);
        }
        RENAISSANCES.put(joueur.getUUID(), new Renaissance(monde, joueur.server.getTickCount(), p));
        GRANDS.remove(joueur.getUUID());
        COCONS.remove(joueur.getUUID());
        PIQUES.remove(joueur.getUUID());
        VOLS.remove(joueur.getUUID());
        SERRES.remove(joueur.getUUID());
        joueur.clearFire();
        joueur.stopUsingItem();
        AILES.remove(joueur.getUUID());
        ENVOLS.remove(joueur.getUUID());
        effet(joueur, E_RENAISSANCE, 0, p, Vec3.ZERO, DUREE_RENAISSANCE);
        son(monde, p, SoundEvents.FIRECHARGE_USE, 2F, 0.5F);
        son(monde, p, SoundEvents.BLAZE_SHOOT, 1.6F, 0.4F);
        flammes(monde, p.add(0, 1, 0), 80, 0.7);
    }

    private static void renaissances(MinecraftServer server, int maintenant) {
        for (Iterator<Map.Entry<UUID, Renaissance>> it = RENAISSANCES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Renaissance> en = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            Renaissance r = en.getValue();
            if (joueur == null || joueur.serverLevel() != r.monde()) {
                it.remove();
                if (joueur != null) {
                    joueur.setInvisible(false);
                }
                continue;
            }
            int age = maintenant - r.debut();
            ServerLevel monde = r.monde();
            Vec3 p = r.pos();
            // il ne bouge pas : il est en cendres
            if (joueur.position().distanceToSqr(p) > 0.04) {
                joueur.teleportTo(p.x, p.y, p.z);
            }
            joueur.setDeltaMovement(Vec3.ZERO);
            joueur.fallDistance = 0;
            joueur.clearFire();
            joueur.setInvisible(age >= 12 && age < DUREE_RENAISSANCE);
            if (age < 10) {
                flammes(monde, p.add(0, 1, 0), 10, 0.5);
            } else if (age < 30) {
                monde.sendParticles(ParticleTypes.ASH, p.x, p.y + 0.4, p.z, 10, 0.6, 0.2, 0.6, 0.01);
                monde.sendParticles(ParticleTypes.SMOKE, p.x, p.y + 0.4, p.z, 3, 0.4, 0.2, 0.4, 0.02);
            } else if (age < 60) {
                monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + 1.2, p.z, 4, 0.5, 0.6, 0.5, 0.01);
            }
            if (age == 30) {
                son(monde, p, SoundEvents.FIRECHARGE_USE, 1.4F, 0.7F);
            }
            if (age == 48) {
                son(monde, p, SoundEvents.TURTLE_EGG_CRACK, 2F, 0.6F);
                son(monde, p, SoundEvents.AMETHYST_BLOCK_RESONATE, 2F, 1.4F);
            }
            if (age == 60) {
                son(monde, p, SoundEvents.ENDER_DRAGON_GROWL, 2F, 1.7F);
                son(monde, p, SoundEvents.BLAZE_SHOOT, 2F, 0.6F);
                flammes(monde, p.add(0, 2, 0), 150, 1.5);
                Component msg = Component.literal("🔥 ").append(joueur.getDisplayName()).append(" renaît de ses cendres !")
                        .withStyle(ChatFormatting.AQUA);
                for (ServerPlayer autre : monde.players()) {
                    if (autre.distanceToSqr(p) < 96 * 96) {
                        autre.sendSystemMessage(msg);
                    }
                }
            }
            if (age < DUREE_RENAISSANCE) {
                continue;
            }
            it.remove();
            joueur.setInvisible(false);
            joueur.setHealth(Math.min(joueur.getMaxHealth(), 20));
            joueur.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            effet(joueur, E_RENAISSANCE, 1, p, Vec3.ZERO, 0);
            son(monde, p, SoundEvents.GENERIC_EXPLODE.value(), 1.6F, 1.4F);
            monde.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y + 1, p.z, 3, 1, 0.5, 1, 0);
            flammes(monde, p.add(0, 0.5, 0), 120, 3);
            for (LivingEntity e : ennemis(joueur, p.add(0, 1, 0), 4)) {
                frapper(joueur, e, ONDE);
                Vec3 h = new Vec3(e.getX() - p.x, 0, e.getZ() - p.z);
                h = h.lengthSqr() < 1e-3 ? new Vec3(1, 0, 0) : h.normalize();
                e.setDeltaMovement(h.scale(1.6).add(0, 0.6, 0));
                e.hurtMarked = true;
            }
            for (LivingEntity a : allies(joueur, p, 6)) {
                a.heal(4);
                perles(monde, a);
            }
            FeuBleu.semer(monde, p, 3, 0.35, joueur.getUUID());
        }
    }

    // ================================================================ le fruit mangé

    /** Il mange le Tori Tori no Mi : sur la liste blanche « phenix », et des flammes bleues l'enveloppent 3 s. */
    static void manger(ServerPlayer joueur) {
        Pouvoirs.donner(joueur, NOM, Pouvoirs.Couleur.CYAN);
        joueur.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 14, 0, false, false));
        joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, Fruit.DUREE_REPAS + 10, 0, false, false));
        joueur.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, Fruit.DUREE_REPAS, 4, false, false));
        effet(joueur, E_REPAS, 0, joueur.position(), Vec3.ZERO, Fruit.DUREE_REPAS);
        String nom = "Tori Tori no Mi";
        joueur.connection.send(new ClientboundSetTitlesAnimationPacket(5, 45, 15));
        joueur.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("Modèle Phénix : les flammes bleues entrent en toi").withStyle(ChatFormatting.WHITE)));
        joueur.connection.send(new ClientboundSetTitleTextPacket(Component.literal(nom).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        joueur.sendSystemMessage(Component.literal("Tu as mangé le " + nom + ", modèle Phénix : tu es sur la whitelist " + NOM + ".").withStyle(ChatFormatting.AQUA));
        joueur.sendSystemMessage(Component.literal("Touche Éveil pour réveiller ton pouvoir. Attention : l'eau te fait mal, maintenant.")
                .withStyle(ChatFormatting.GRAY));
        ServerLevel monde = joueur.serverLevel();
        son(monde, joueur.position(), SoundEvents.WARDEN_HEARTBEAT, 1.6F, 0.8F);
        son(monde, joueur.position(), SoundEvents.FIRECHARGE_USE, 1F, 0.8F);
        flammes(monde, joueur.position().add(0, 1, 0), 40, 0.6);
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (ServerPlayer joueur : server.getPlayerList().getPlayers()) {
            if (!est(Pouvoirs.pouvoir(joueur)) || joueur.isSpectator()) {
                continue;
            }
            // l'eau, pour qui a mangé un fruit : un demi-cœur par seconde (rien avec la bulle de verre sur la tête)
            if (maintenant % 20 == 0 && !joueur.isCreative() && fr.cabane.accessoire.Bulle.noye(joueur)) {
                joueur.hurt(new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(Fruit.EAU)), 1);
            }
            int niveau = niveau(joueur), passif = Math.max(niveau, niveauFruit(joueur));
            // ses passifs marchent toujours, éveillé ou pas : il se soigne tout seul (un demi-cœur toutes les 4 s au
            // niveau 1, toutes les 2 s ensuite) et le feu ne lui fait rien
            if (maintenant % (passif >= 2 ? 40 : 80) == 0 && joueur.getHealth() < joueur.getMaxHealth() && !fr.cabane.accessoire.Bulle.noye(joueur)) {
                joueur.heal(1);
            }
            if (joueur.isOnFire()) {
                joueur.clearFire();
            }
            if (niveau == 0) {
                continue;
            }
            if (niveau >= 3 && !joueur.isCreative()) {
                jaugeVol(joueur);
            }
            // des flammèches bleues sur lui, plus il monte de niveau
            if (maintenant % (niveau >= 3 ? 2 : 5) == 0 && !joueur.isInvisible()) {
                ServerLevel monde = joueur.serverLevel();
                monde.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, joueur.getX(), joueur.getY() + 0.6 + monde.random.nextDouble() * 1.2, joueur.getZ(),
                        1, 0.3, 0.2, 0.3, 0.01);
            }
        }
        SANS_CHUTE.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            int[] v = e.getValue();
            if (joueur == null || maintenant - v[0] > 30 * 20) {
                return true;
            }
            if (!joueur.onGround()) {
                v[1] = 1;
                return false;
            }
            return v[1] == 1 ? maintenant - v[0] > 3 : maintenant - v[0] > 3 * 20;
        });
        serres(server, maintenant);
        pluies(maintenant);
        plumes();
        grands(server, maintenant);
        cocons(server, maintenant);
        piques(server, maintenant);
        vols(server, maintenant);
        brasiers(maintenant);
        tourbillons(server, maintenant);
        ailes(server, maintenant);
        envols(server, maintenant);
        ultis(server, maintenant);
        renaissances(server, maintenant);
    }

    /** La jauge de vol du niveau 3 : elle se vide en l'air (30 s), se remplit au sol deux fois plus vite. */
    private static void jaugeVol(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        Abilities a = joueur.getAbilities();
        int reste = VOL_RESTE.getOrDefault(id, VOL_MAX);
        if (a.flying && !ultiActif(joueur)) {
            reste--;
            if (reste <= 0) {
                VOL_EPUISE.add(id);
                a.flying = false;
                a.mayfly = false;
                joueur.onUpdateAbilities();
                SANS_CHUTE.put(id, new int[]{joueur.server.getTickCount(), 1});
                joueur.displayClientMessage(Component.literal("Tes ailes s'épuisent : touche le sol pour reprendre des forces.").withStyle(ChatFormatting.AQUA), true);
            }
        } else if (joueur.onGround()) {
            reste = Math.min(VOL_MAX, reste + 2);
            VOL_EPUISE.remove(id);
        }
        VOL_RESTE.put(id, Math.max(0, reste));
        if (!a.mayfly && !VOL_EPUISE.contains(id) && reste > 0) {
            a.mayfly = true;
            a.setFlyingSpeed(VITESSE_VOL);
            joueur.onUpdateAbilities();
        }
    }

    // ================================================================ les coups reçus et donnés

    /** Pas de dégâts de chute : un passif, toujours (éveillé ou pas). */
    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur
                && (niveauFruit(joueur) > 0 || SANS_CHUTE.containsKey(joueur.getUUID()) || RENAISSANCES.containsKey(joueur.getUUID()))) {
            event.setCanceled(true);
        }
    }

    /** Dans l'eau, un fruit du démon ne guérit pas. */
    @SubscribeEvent
    public static void soin(LivingHealEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && est(Pouvoirs.pouvoir(joueur)) && fr.cabane.accessoire.Bulle.noye(joueur)) {
            event.setCanceled(true);
        }
    }

    /**
     * Ses coups au corps à corps (n'importe quelle arme, ou les mains) : +2 et brûle 3 s (niveau 1) ; +4 et 3 à ceux qui
     * sont à 2 blocs de la cible (niveau 2) ; +6, et au 3e coup de suite les serres en croix (niveau 3). Qui le frappe au
     * corps à corps prend feu (niveaux 2 et 3). Pendant sa renaissance, rien ne le touche.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource s = event.getSource();
        if (event.getEntity() instanceof ServerPlayer victime) {
            if (RENAISSANCES.containsKey(victime.getUUID()) && !s.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                event.setCanceled(true);
                return;
            }
            int nv = niveau(victime);
            if (nv >= 2 && s.getEntity() instanceof LivingEntity agresseur && s.getDirectEntity() == agresseur && agresseur != victime) {
                agresseur.igniteForSeconds(nv >= 3 ? 3 : 2);
            }
        }
        if (dansPhenix || dansPouvoir || !s.is(DamageTypes.PLAYER_ATTACK) || !(s.getEntity() instanceof ServerPlayer j) || s.getDirectEntity() != j) {
            return;
        }
        int niveau = niveau(j);
        if (niveau == 0 || !(event.getEntity() instanceof LivingEntity cible) || cible == j) {
            return;
        }
        ServerLevel monde = j.serverLevel();
        event.setAmount(event.getAmount() + (niveau >= 3 ? COUP_3 : niveau == 2 ? COUP_2 : COUP_1));
        cible.igniteForSeconds(3);
        flammes(monde, milieu(cible), 8, 0.3);
        effet(j, E_COUP, niveau, milieu(cible), Pouvoirs.devant(j), cible.getId());
        if (niveau == 2) {
            for (LivingEntity e : ennemis(j, milieu(cible), 2)) {
                if (e != cible) {
                    frapper(j, e, ECLAT_2);
                    e.igniteForSeconds(2);
                }
            }
        }
        if (niveau >= 3) {
            int maintenant = j.server.getTickCount();
            int[] serie = SERIE.computeIfAbsent(j.getUUID(), k -> new int[]{0, -100});
            serie[0] = maintenant - serie[1] <= 60 ? serie[0] + 1 : 1;
            serie[1] = maintenant;
            if (serie[0] >= 3) {
                serie[0] = 0;
                croix(j, cible);
            }
        }
    }

    /** Les serres en croix : 8 à tout ce qui est à 2 blocs de la cible, et il se soigne 4. */
    private static void croix(ServerPlayer joueur, LivingEntity cible) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 c = milieu(cible);
        effet(joueur, E_CROIX, 0, c, Pouvoirs.devant(joueur), cible.getId());
        son(monde, c, SoundEvents.PLAYER_ATTACK_CRIT, 1.6F, 0.7F);
        son(monde, c, SoundEvents.PLAYER_ATTACK_SWEEP, 1.6F, 0.8F);
        monde.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 2, 0.3, 0.3, 0.3, 0);
        flammes(monde, c, 30, 0.6);
        List<LivingEntity> touches = ennemis(joueur, c, 2);
        if (!touches.contains(cible) && touchable(joueur, cible)) {
            touches.add(cible);
        }
        for (LivingEntity e : touches) {
            frapper(joueur, e, CROIX);
        }
        joueur.heal(4);
        perles(monde, joueur);
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && RENAISSANCES.remove(joueur.getUUID()) != null) {
            joueur.setInvisible(false);
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        R.vider();
        for (Map<UUID, Integer> m : List.of(R_GRAND, R_PIQUE, R_VOL, R_BRASIER, R_TOURBILLON, VOL_RESTE, COCONS)) {
            m.clear();
        }
        ULTI_FAITE.clear();
        RENAISSANCE_PRETE.clear();
        AILES.clear();
        ENVOLS.clear();
        VOL_EPUISE.clear();
        SANS_CHUTE.clear();
        SERIE.clear();
        SERRES.clear();
        PLUIES.clear();
        PLUMES.clear();
        GRANDS.clear();
        PIQUES.clear();
        VOLS.clear();
        BRASIERS.clear();
        TOURBILLONS.clear();
        ULTIS.clear();
        RENAISSANCES.clear();
        FeuBleu.vider();
    }

    private Phenix() {
    }
}
