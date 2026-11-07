package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Ichibi (Shukaku, le démon du sable), refait en trois niveaux d'après docs/ichibi/planche-refonte.png : liste blanche
 * « ichibi », la touche Éveil passe au niveau 1, puis 2, puis 3 (une minute d'éveil par niveau, puis une minute de repos).
 * Dès qu'il a le pouvoir, même endormi, il porte la grosse jarre de Gaara sur le dos (dessinée chez chacun, voir
 * {@link Porteurs}) ; à l'éveil, le bouchon saute. Ce qu'on voit est dessiné par {@link fr.cabane.client.pouvoir.ClientIchibi}.
 * <ul>
 * <li>niveau 1, le sable de Gaara (pas de rire, armure diamant) : le sable tourne autour de lui ; clic gauche, un gros
 * poing de sable sort de la jarre, à droite puis à gauche (7 dégâts, 7 blocs) ; clic droit, le cercueil de sable (12
 * blocs) le remplit jusqu'au cou, le soulève de 2 blocs et serre (12 d'un coup) ; accroupi + clic gauche, une boule de
 * sable (8, aveuglé 1 s) ; accroupi, un petit nuage (20 s) ;</li>
 * <li>niveau 2, la moitié d'Ichibi (le rire, netherite) : le bras d'Ichibi (11, 6 blocs) ; la grande main (serre 14,
 * jette 10) ; accroupi + clic droit, le cercueil (15 blocs, 16) ; accroupi + clic gauche, un rocher de sable lancé (14
 * en zone de 3, 25 blocs) ; la touche technique, le mausolée : une statue de sable de 9 blocs sort derrière sa cible et
 * l'étouffe dans ses mains (4 s, 2 par seconde + 6) ; le nuage (30 s) ;</li>
 * <li>niveau 3, la bête de sable (le rire, plus de jarre, vitesse II, armure du chevalier) : les griffes (13 à tout ce
 * qui est devant, 7 blocs) ; la grande main (serre 18, jette 12) ; accroupi + clic gauche, la grande vague (4 blocs de
 * haut, 15 devant : 14, recul, lenteur 3 s) ; accroupi + clic droit, trois cercueils d'un coup (16 chacun) ; le
 * mausolée à 11 blocs (6 s, 2,5 par seconde + 8) ; la touche d'ulti : la tempête de sable ({@link IchibiTempete}).</li>
 * </ul>
 * Rien ne casse le sol : le sable s'effrite après. Les clics et s'accroupir arrivent par les paquets du Susano
 * ({@link Pouvoirs.Frappe}, {@link Pouvoirs.Attraper}, {@link Pouvoirs.Charge}), la touche technique par {@link Touche}.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Ichibi {
    /** Les listes blanches : « ichibi » va jusqu'au niveau 1, « ichibi2 » jusqu'au 2, « ichibi3 » jusqu'au 3 (et l'ulti). */
    public static final String NOM = "ichibi";
    public static final String NOM2 = "ichibi2";
    public static final String NOM3 = "ichibi3";

    /** Le sable le prend pendant ce temps après chaque éveil (le bouchon, le sable qui coule, la jarre qui s'effondre). */
    public static final int TRANSFORMATION = 20;
    public static final int TRANSFORMATION_2 = 40;

    // niveau 1
    public static final int CADENCE_POING = 9;
    private static final double PORTEE_POING = 7;
    private static final float POING = 7;
    private static final double PORTEE_CERCUEIL = 12;
    private static final float CERCUEIL = 12;
    public static final int RECHARGE_CERCUEIL = 12 * 20;
    private static final double PORTEE_BOULE = 20;
    private static final float BOULE = 8;
    public static final int RECHARGE_BOULE = 6 * 20;
    // niveau 2
    private static final int CADENCE_BRAS = 12;
    private static final double PORTEE_BRAS = 6;
    private static final float BRAS = 11;
    private static final double PORTEE_PRISE = 8;
    public static final int RECHARGE_PRISE = 12 * 20;
    private static final int DUREE_PRISE = 8 * 20;
    private static final double PORTEE_CERCUEIL_2 = 15;
    private static final float CERCUEIL_2 = 16;
    public static final int RECHARGE_CERCUEIL_2 = 12 * 20;
    private static final double PORTEE_ROCHER = 25;
    private static final float ROCHER = 14;
    private static final double ZONE_ROCHER = 3;
    public static final int RECHARGE_ROCHER = 8 * 20;
    /** Le bras d'Ichibi arrache le rocher pendant ce temps, puis le lance. */
    public static final int ARRACHE_ROCHER = 8;
    /** Le rocher retombe un peu en vol (blocs par tick, chaque tick). */
    public static final double CHUTE_ROCHER = 0.012;
    private static final double PORTEE_MAUSOLEE = 20;
    public static final int RECHARGE_MAUSOLEE = 25 * 20;
    // niveau 3
    public static final int CADENCE_GRIFFES = 13;
    private static final double PORTEE_GRIFFES = 7;
    private static final float GRIFFES = 13;
    /** Le troisième coup : les deux bras s'abattent (18 à 6 blocs), puis il lui faut ce temps avant de frapper encore. */
    private static final double PORTEE_ABATTRE = 6;
    private static final float ABATTRE_DEGATS = 18;
    public static final int ABATTRE = 22;
    private static final double LONGUEUR_VAGUE = 15;
    private static final float VAGUE = 14;
    public static final int RECHARGE_VAGUE = 14 * 20;
    private static final double PORTEE_CERCUEILS = 15;
    public static final int RECHARGE_CERCUEILS = 15 * 20;

    /** La main met ce temps à ramener sa prise jusqu'à elle (le bras s'allonge jusqu'à elle, puis se replie). */
    private static final int ARRACHER = 8;
    /** Clic droit encore : la main se referme sur lui pendant ce temps, puis le lâche. */
    private static final int SERRER = 7;
    private static final float ECRASE = 1;
    public static final int RECHARGE_NUAGE = 30 * 20;
    /** Le nuage le pose doucement : il tombe au ralenti, au plus ce temps. */
    private static final int ATTERRISSAGE = 15 * 20;
    /**
     * Sur son nuage (le vol créatif va à 0,05, environ 11 blocs/s) : au niveau 1 la vitesse de course (5,6 blocs/s), au
     * niveau 2 7 blocs/s, au niveau 3 celle du Vent (8,5 blocs/s).
     */
    private static float vitesseVol(int niveau) {
        return niveau <= 1 ? 0.026F : niveau == 2 ? 0.032F : Vent.VOL;
    }

    /** Le cercueil : le sable vole jusqu'à lui, le remplit jusqu'au cou, le soulève de 2 blocs, serre, puis s'effrite. */
    public static final int C_VOL = 6, C_PLEIN = 18, C_LEVE = 28, C_SERRE = 32, C_FIN = 42;
    private static final double LEVER = 2;

    /** Le mausolée : la statue sort du sol, ses mains se referment sur lui, elle le tient, puis s'effrite. */
    public static final int M_SORT = 16, M_FERME = 26;

    /** Ce que voient les autres (paquet {@link Pouvoirs.Geste}) : le coup de bras ; la prise (l'entité, -1 : lâchée). */
    public static final int GESTE_COUP = 40;
    public static final int GESTE_PRISE = 41;
    public static final int GESTE_SERRE = 42;
    public static final int GESTE_LANCE = 43;
    /** Le nuage : 1 il se forme, 0 il s'en va (2 : le petit nuage du niveau 1). */
    public static final int GESTE_NUAGE = 44;

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir) || NOM3.equals(pouvoir);
    }

    /** Le niveau le plus haut de cette liste blanche. */
    public static int max(String pouvoir) {
        return NOM3.equals(pouvoir) ? 3 : NOM2.equals(pouvoir) ? 2 : 1;
    }

    public static int dureeNuage(int niveau) {
        return (niveau <= 1 ? 20 : 30) * 20;
    }

    // ================================================================ réseau

    public static final int T_MAUSOLEE = 0;

    /** Client → serveur : la touche technique (le mausolée). */
    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("ichibi_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Ce que montre un {@link Effet}. */
    public static final int E_POING = 0, E_CERCUEIL = 1, E_BOULE = 2, E_ROCHER = 3, E_STATUE = 4, E_VAGUE = 5, E_GRIFFE = 6, E_TEMPETE = 7, E_IMPACT = 8;

    /**
     * Serveur → ceux qui sont autour : un coup de sable de ce joueur. {@code sorte} dit lequel, {@code phase} où il en est
     * (ou le côté), {@code pos} et {@code dir} où et vers où, {@code n} un nombre (l'entité prise, le numéro d'un tir…).
     */
    public record Effet(UUID joueur, int sorte, int phase, Vector3f pos, Vector3f dir, int n) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("ichibi_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Effet::joueur, ByteBufCodecs.VAR_INT, Effet::sorte, ByteBufCodecs.VAR_INT, Effet::phase,
                ByteBufCodecs.VECTOR3F, Effet::pos, ByteBufCodecs.VECTOR3F, Effet::dir, ByteBufCodecs.VAR_INT, Effet::n, Effet::new);

        @Override
        public Type<Effet> type() {
            return TYPE;
        }
    }

    /** Serveur → tous : ceux qui ont le pouvoir Ichibi (éveillés ou non) : ils portent la jarre. */
    public record Porteurs(List<UUID> joueurs) implements CustomPacketPayload {
        public static final Type<Porteurs> TYPE = new Type<>(Cabane.id("ichibi_porteurs"));
        public static final StreamCodec<ByteBuf, Porteurs> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Porteurs::joueurs, Porteurs::new);

        @Override
        public Type<Porteurs> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Touche.TYPE, Touche.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur && p.action() == T_MAUSOLEE) {
                        mausolee(joueur);
                    }
                }))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientIchibi.effet(p)))
                .playToClient(Porteurs.TYPE, Porteurs.CODEC, (p, ctx) -> ctx.enqueueWork(() -> fr.cabane.client.pouvoir.ClientIchibi.porteurs(p)));
    }

    static void effet(ServerPlayer joueur, int sorte, int phase, Vec3 pos, Vec3 dir, int n) {
        Vent.envoyer(joueur.serverLevel(), new Effet(joueur.getUUID(), sorte, phase, Vent.v(pos), Vent.v(dir), n), pos);
    }

    private static List<UUID> porteurs = List.of();

    private static List<UUID> porteurs(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream().filter(p -> est(Pouvoirs.pouvoir(p))).map(Entity::getUUID).toList();
    }

    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            porteurs = porteurs(joueur.server);
            PacketDistributor.sendToAllPlayers(new Porteurs(porteurs));
        }
    }

    // ================================================================ l'état de chacun

    /** Où en est un Ichibi éveillé (en ticks du serveur ; -1 : pas en cours). */
    private static final class Etat {
        int niveau;
        /** Le dernier éveil (ou changement de niveau) : le sable le prend d'abord. */
        int debut;
        int coup = -100;
        int cote;
        /** Les griffes d'affilée (le troisième coup abat les deux bras). */
        int combo;
        int prise = -1;
        int priseDebut;
        Vec3 priseDepart;
        int priseRecharge = -1;
        /** Quand la main qui serre le lâchera (-1 : elle ne serre pas). */
        int lacher = -1;
        int nuageFin = -1;
        int nuageRecharge = -1;
        int cercueilRecharge = -1;
        int bouleRecharge = -1;
        int rocherRecharge = -1;
        int mausoleeRecharge = -1;
        int vagueRecharge = -1;
        /** Le rocher arraché, pas encore lancé : quand il part (-1 : aucun). */
        int rocherPart = -1;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();
    /** Ceux que le nuage vient de lâcher : ils tombent doucement jusqu'au sol (le tick où ça s'arrête de toute façon). */
    private static final Map<UUID, Integer> POSES = new HashMap<>();

    /** Un cercueil de sable en cours : sur qui, depuis quand, d'où, combien il fait mal. */
    private record Cercueil(UUID lanceur, int cible, int debut, Vec3 depart, float degats) {
    }

    /** Un mausolée en cours : la statue (ses pieds, vers où elle regarde), qui elle tient, sa taille (1 : 9 blocs). */
    private record Statue(UUID lanceur, int cible, int debut, Vec3 pieds, Vec3 face, double taille, int duree, float parSeconde, float fin) {
        Vec3 mains() {
            return pieds.add(face.scale(26 * taille / 16)).add(0, (34 * taille - 17) / 16, 0);
        }
    }

    /** Une boule de sable ou un rocher, en vol. */
    private static final class Tir {
        final UUID lanceur;
        final int numero;
        final boolean rocher;
        Vec3 pos;
        Vec3 vitesse;
        double reste;

        Tir(UUID lanceur, int numero, boolean rocher, Vec3 pos, Vec3 vitesse, double portee) {
            this.lanceur = lanceur;
            this.numero = numero;
            this.rocher = rocher;
            this.pos = pos;
            this.vitesse = vitesse;
            this.reste = portee;
        }
    }

    /** Une grande vague : d'où, vers où, depuis quand, ce qu'elle a déjà emporté. */
    private record Vague(UUID lanceur, Vec3 depart, Vec3 dir, int debut, double longueur, Set<Integer> touches) {
    }

    private static final List<Cercueil> CERCUEILS = new ArrayList<>();
    private static final List<Statue> STATUES = new ArrayList<>();
    private static final List<Tir> TIRS = new ArrayList<>();
    private static final List<Vague> VAGUES = new ArrayList<>();
    private static int numeros;
    private static final net.minecraft.resources.ResourceLocation VITESSE = Cabane.id("ichibi_vitesse");

    static int niveau(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        return e == null ? 0 : e.niveau;
    }

    // ================================================================ s'éveiller, s'endormir

    static void eveiller(ServerPlayer joueur, int niveau) {
        Etat e = ETATS.computeIfAbsent(joueur.getUUID(), u -> new Etat());
        int avant = e.niveau;
        e.niveau = niveau;
        e.debut = joueur.server.getTickCount();
        lacher(joueur, e);
        ServerLevel monde = joueur.serverLevel();
        if (niveau == 1 || avant == 0) {
            // le bouchon saute, le sable sort du goulot et se met à tourner (pas de rire au niveau 1)
            son(joueur, SoundEvents.BOTTLE_EMPTY, 1.4F, 0.6F);
            son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.7F);
            monde.sendParticles(sable(), joueur.getX(), joueur.getY(1.6), joueur.getZ(), 30, 0.3, 0.3, 0.3, 0.12);
        }
        if (niveau >= 2) {
            monde.playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), CabaneSons.ICHIBI_EVEIL.get(), SoundSource.PLAYERS, 1.6F, 1F);
            son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.6F);
            monde.sendParticles(sable(), joueur.getX(), joueur.getY(0.5), joueur.getZ(), 80, 0.6, 0.9, 0.6, 0.15);
            monde.sendParticles(poussiere(), joueur.getX(), joueur.getY(0.6), joueur.getZ(), 40, 0.7, 0.9, 0.7, 0.05);
        }
        if (niveau >= 3) {
            // la jarre se fissure et s'effondre : tout son sable tombe sur lui
            son(joueur, SoundEvents.DECORATED_POT_SHATTER, 1.6F, 0.6F);
            son(joueur, SoundEvents.GRAVEL_BREAK, 1.6F, 0.5F);
            monde.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.TERRACOTTA.defaultBlockState()), joueur.getX(), joueur.getY(1.1),
                    joueur.getZ(), 60, 0.4, 0.5, 0.4, 0.2);
        }
        vitesse(joueur);
    }

    static void endormir(ServerPlayer joueur) {
        IchibiTempete.endormir(joueur);
        Etat e = ETATS.remove(joueur.getUUID());
        if (e == null) {
            return;
        }
        lacher(joueur, e);
        if (e.nuageFin >= 0) {
            finNuage(joueur, e, joueur.server.getTickCount());
        }
        UUID moi = joueur.getUUID();
        STATUES.removeIf(s -> s.lanceur().equals(moi));
        CERCUEILS.removeIf(c -> c.lanceur().equals(moi));
        vitesse(joueur);
        joueur.serverLevel().sendParticles(sable(), joueur.getX(), joueur.getY(0.5), joueur.getZ(), 50, 0.6, 0.9, 0.6, 0.1);
        son(joueur, SoundEvents.SAND_BREAK, 1.2F, 0.8F);
    }

    /** Au niveau 3, il court penché : vitesse II (un modificateur, pour ne pas toucher aux potions). */
    static void vitesse(ServerPlayer joueur) {
        var a = joueur.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (a == null) {
            return;
        }
        a.removeModifier(VITESSE);
        double bonus = niveau(joueur) >= 3 ? 0.4 : 0;
        if (bonus > 0) {
            a.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(VITESSE, bonus,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static boolean pret(ServerPlayer joueur, Etat e) {
        return e != null && joueur.server.getTickCount() - e.debut >= (e.niveau >= 2 ? TRANSFORMATION_2 : TRANSFORMATION);
    }

    static ParticleOptions sable() {
        return new BlockParticleOption(ParticleTypes.BLOCK, Blocks.SAND.defaultBlockState());
    }

    static ParticleOptions poussiere() {
        return new DustParticleOptions(new Vector3f(0.84F, 0.73F, 0.6F), 1.6F);
    }

    static void son(ServerPlayer joueur, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static void son(ServerLevel monde, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static void message(ServerPlayer joueur, String texte, int reste) {
        joueur.displayClientMessage(Component.literal(texte + "… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
    }

    /** Un coup de sable (il compte même juste après un autre). */
    static void blesser(ServerPlayer joueur, LivingEntity cible, float degats) {
        cible.invulnerableTime = 0;
        cible.hurt(joueur.damageSources().playerAttack(joueur), degats);
    }

    /** La portée, la recharge, la cadence d'une attaque (la tempête ne le booste plus : elle frappe à sa place). */
    private static double portee(ServerPlayer joueur, double p) {
        return p;
    }

    private static int recharge(ServerPlayer joueur, int t) {
        return t;
    }

    private static int cadence(ServerPlayer joueur, int t) {
        return t;
    }

    /** Ce que le sable tient déjà (une main, un cercueil, une statue) : on ne le reprend pas, on ne le frappe pas dedans. */
    private static boolean tenu(LivingEntity c) {
        int id = c.getId();
        return ETATS.values().stream().anyMatch(x -> x.prise == id) || CERCUEILS.stream().anyMatch(x -> x.cible() == id) || IchibiTempete.tenu(id)
                || STATUES.stream().anyMatch(x -> x.cible() == id);
    }

    private static boolean touchable(ServerPlayer joueur, LivingEntity c) {
        return Pouvoirs.cible(joueur, c) && !c.isSpectator() && !tenu(c);
    }

    static Vec3 devant(ServerPlayer joueur) {
        Vec3 regard = joueur.getLookAngle();
        Vec3 plat = new Vec3(regard.x, 0, regard.z);
        return plat.lengthSqr() < 1e-4 ? Pouvoirs.devant(joueur) : plat.normalize();
    }

    /** Les créatures devant lui (dans son regard, à plat), de la plus proche à la plus loin. */
    private static List<LivingEntity> devantLui(ServerPlayer joueur, double portee, double cone) {
        Vec3 devant = devant(joueur);
        List<LivingEntity> l = new ArrayList<>();
        for (LivingEntity c : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee, 5, portee),
                c -> touchable(joueur, c))) {
            Vec3 vers = new Vec3(c.getX() - joueur.getX(), 0, c.getZ() - joueur.getZ());
            double d = vers.length();
            if (d <= portee && (d < 0.8 || vers.normalize().dot(devant) > cone)) {
                l.add(c);
            }
        }
        l.sort(Comparator.comparingDouble(c -> c.distanceToSqr(joueur)));
        return l;
    }

    /** Celle qu'il vise (la plus près de la ligne de son regard, jusqu'à portee), ou null. */
    private static LivingEntity visee(ServerPlayer joueur, double portee) {
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        LivingEntity meilleure = null;
        double score = Double.MAX_VALUE;
        for (LivingEntity c : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee),
                c -> touchable(joueur, c))) {
            Vec3 m = c.position().add(0, c.getBbHeight() / 2, 0).subtract(oeil);
            double le = m.dot(regard);
            if (le < 0 || le > portee) {
                continue;
            }
            double ecart = m.subtract(regard.scale(le)).length() - c.getBbWidth() / 2;
            if (ecart > 1.6 + le * 0.06) {
                continue;
            }
            double s = ecart * 4 + le;
            if (s < score) {
                score = s;
                meilleure = c;
            }
        }
        return meilleure;
    }

    // ================================================================ clic gauche

    static void coup(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e) || e.lacher >= 0) {
            return;
        }
        if (e.prise >= 0) {
            lancer(joueur, e);
            return;
        }
        boolean accroupi = joueur.isShiftKeyDown();
        switch (e.niveau) {
            case 1 -> {
                if (accroupi) {
                    boule(joueur, e);
                } else {
                    poing(joueur, e);
                }
            }
            case 2 -> {
                if (accroupi) {
                    rocher(joueur, e);
                } else {
                    bras(joueur, e);
                }
            }
            default -> {
                if (accroupi) {
                    vague(joueur, e);
                } else {
                    griffes(joueur, e);
                }
            }
        }
    }

    /** Niveau 1 : un gros poing de sable sort de la jarre, à droite puis à gauche, et frappe droit devant (7 blocs). */
    private static void poing(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant - e.coup < cadence(joueur, CADENCE_POING)) {
            return;
        }
        e.coup = maintenant;
        e.cote = 1 - e.cote;
        double portee = portee(joueur, PORTEE_POING);
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        Vec3 bout = oeil.add(regard.scale(portee));
        HitResult mur = joueur.level().clip(new ClipContext(oeil, bout, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        if (mur.getType() != HitResult.Type.MISS) {
            bout = mur.getLocation();
        }
        ServerLevel monde = joueur.serverLevel();
        boolean touche = false;
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, new AABB(oeil, bout).inflate(1.5), c -> touchable(joueur, c))) {
            if (Vent.distanceAuSegment(Vent.milieu(cible), oeil, bout) > 1.1 + cible.getBbWidth() / 2) {
                continue;
            }
            blesser(joueur, cible, POING);
            cible.knockback(0.9, -regard.x, -regard.z);
            cible.hurtMarked = true;
            monde.sendParticles(sable(), cible.getX(), cible.getY(0.6), cible.getZ(), 22, 0.4, 0.5, 0.4, 0.2);
            touche = true;
        }
        effet(joueur, E_POING, e.cote, bout, regard, 0);
        son(joueur, SoundEvents.SAND_PLACE, 1.4F, 0.6F);
        if (touche) {
            son(monde, bout, SoundEvents.PLAYER_ATTACK_STRONG, 1F, 0.6F);
            son(monde, bout, SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
        }
    }

    /** Niveau 2 : un coup du bras d'Ichibi (11 dégâts à 6 blocs), qui envoie loin. */
    private static void bras(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant - e.coup < cadence(joueur, CADENCE_BRAS)) {
            return;
        }
        e.coup = maintenant;
        Pouvoirs.geste(joueur, GESTE_COUP, 0);
        son(joueur, SoundEvents.PLAYER_ATTACK_STRONG, 1F, 0.6F);
        son(joueur, SoundEvents.SAND_HIT, 1.4F, 0.6F);
        Vec3 devant = Pouvoirs.devant(joueur);
        ServerLevel monde = joueur.serverLevel();
        double portee = portee(joueur, PORTEE_BRAS);
        for (LivingEntity cible : devantLui(joueur, portee, 0.35)) {
            blesser(joueur, cible, BRAS);
            cible.knockback(1.6, -devant.x, -devant.z);
            cible.setDeltaMovement(cible.getDeltaMovement().add(0, 0.25, 0));
            cible.hurtMarked = true;
            monde.sendParticles(sable(), cible.getX(), cible.getY(0.6), cible.getZ(), 26, 0.4, 0.5, 0.4, 0.2);
            monde.sendParticles(poussiere(), cible.getX(), cible.getY(0.6), cible.getZ(), 10, 0.4, 0.4, 0.4, 0.05);
            son(monde, cible.position(), SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
        }
    }

    /**
     * Niveau 3 : les griffes géantes, à droite puis à gauche (13 à tout ce qui est devant, 7 blocs) ; le troisième coup
     * d'affilée (en moins de 1,6 s), les deux bras s'abattent ensemble : le sol se hérisse de pics de sable devant lui,
     * 18 à tout ce qui est à 6 blocs, projeté en l'air.
     */
    private static void griffes(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant - e.coup < cadence(joueur, e.combo == 0 && maintenant - e.coup < 30 ? ABATTRE : CADENCE_GRIFFES)) {
            return;
        }
        e.combo = maintenant - e.coup < 32 ? e.combo + 1 : 1;
        e.coup = maintenant;
        Vec3 devant = devant(joueur);
        ServerLevel monde = joueur.serverLevel();
        if (e.combo >= 3) {
            e.combo = 0;
            effet(joueur, E_GRIFFE, 2, joueur.position(), devant, 0);
            son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 1.4F, 0.6F);
            son(joueur, SoundEvents.SAND_BREAK, 2F, 0.4F);
            son(joueur, SoundEvents.POINTED_DRIPSTONE_LAND, 2F, 0.5F);
            double portee = portee(joueur, PORTEE_ABATTRE);
            Vec3 centre = joueur.position().add(devant.scale(2.5));
            for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee + 1, 4, portee + 1),
                    c -> touchable(joueur, c))) {
                Vec3 d = cible.position().subtract(joueur.position());
                Vec3 plat = new Vec3(d.x, 0, d.z);
                if (plat.length() > portee || plat.lengthSqr() > 1 && plat.normalize().dot(devant) < -0.2) {
                    continue;
                }
                blesser(joueur, cible, ABATTRE_DEGATS);
                Vec3 loin = cible.position().subtract(centre);
                if (loin.horizontalDistanceSqr() > 1e-4) {
                    cible.knockback(1.2, -loin.x, -loin.z);
                }
                cible.setDeltaMovement(cible.getDeltaMovement().add(0, 0.85, 0));
                cible.hurtMarked = true;
                monde.sendParticles(sable(), cible.getX(), cible.getY(0.3), cible.getZ(), 40, 0.5, 0.4, 0.5, 0.3);
            }
            monde.sendParticles(ParticleTypes.EXPLOSION, centre.x, centre.y + 0.3, centre.z, 3, 1.5, 0.2, 1.5, 0);
            monde.sendParticles(sable(), centre.x, centre.y + 0.3, centre.z, 160, 3, 0.4, 3, 0.35);
            return;
        }
        e.cote = 1 - e.cote;
        effet(joueur, E_GRIFFE, e.cote, joueur.position(), devant, 0);
        son(joueur, SoundEvents.PLAYER_ATTACK_SWEEP, 1.6F, 0.4F);
        son(joueur, SoundEvents.SAND_HIT, 1.8F, 0.5F);
        son(joueur, SoundEvents.RAVAGER_ATTACK, 0.8F, 0.7F);
        for (LivingEntity cible : devantLui(joueur, portee(joueur, PORTEE_GRIFFES), 0.1)) {
            blesser(joueur, cible, GRIFFES);
            cible.knockback(1.6, -devant.x, -devant.z);
            cible.setDeltaMovement(cible.getDeltaMovement().add(0, 0.3, 0));
            cible.hurtMarked = true;
            monde.sendParticles(sable(), cible.getX(), cible.getY(0.6), cible.getZ(), 40, 0.4, 0.5, 0.4, 0.3);
            monde.sendParticles(ParticleTypes.SWEEP_ATTACK, cible.getX(), cible.getY(0.6), cible.getZ(), 2, 0.3, 0.3, 0.3, 0);
        }
    }

    /** Niveau 1, accroupi : une boule de sable part de la jarre (20 blocs) : 8, et elle aveugle 1 s. */
    private static void boule(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant < e.bouleRecharge) {
            message(joueur, "La boule de sable se reforme", e.bouleRecharge - maintenant);
            return;
        }
        e.bouleRecharge = maintenant + recharge(joueur, RECHARGE_BOULE);
        Vec3 depart = joueur.getEyePosition().add(joueur.getLookAngle().scale(0.8)).add(0, 0.2, 0);
        Vec3 vitesse = joueur.getLookAngle().scale(1.6);
        Tir t = new Tir(joueur.getUUID(), ++numeros, false, depart, vitesse, portee(joueur, PORTEE_BOULE));
        TIRS.add(t);
        effet(joueur, E_BOULE, 1, depart, vitesse, t.numero);
        son(joueur, SoundEvents.SAND_PLACE, 1.4F, 0.8F);
    }

    /** Niveau 2, accroupi : le bras d'Ichibi arrache un rocher de sable, puis le lance (25 blocs) : 14 en zone de 3. */
    private static void rocher(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (e.rocherPart >= 0) {
            return;
        }
        if (maintenant < e.rocherRecharge) {
            message(joueur, "Le rocher se reforme", e.rocherRecharge - maintenant);
            return;
        }
        e.rocherRecharge = maintenant + recharge(joueur, RECHARGE_ROCHER);
        e.rocherPart = maintenant + ARRACHE_ROCHER;
        effet(joueur, E_ROCHER, 0, joueur.position(), devant(joueur), 0);
        son(joueur, SoundEvents.SAND_BREAK, 1.6F, 0.5F);
        joueur.serverLevel().sendParticles(sable(), joueur.getX(), joueur.getY() + 0.2, joueur.getZ(), 40, 1, 0.2, 1, 0.15);
    }

    private static void lancerRocher(ServerPlayer joueur) {
        // il part d'au-dessus de son épaule et file vers ce qu'il regarde (25 blocs plus loin), en retombant un peu
        Vec3 depart = joueur.getEyePosition().add(joueur.getLookAngle().scale(1.2)).add(0, 0.9, 0);
        Vec3 vise = joueur.getEyePosition().add(joueur.getLookAngle().scale(PORTEE_ROCHER));
        Vec3 vitesse = vise.subtract(depart).normalize().scale(1.6);
        Tir t = new Tir(joueur.getUUID(), ++numeros, true, depart, vitesse, portee(joueur, PORTEE_ROCHER));
        TIRS.add(t);
        effet(joueur, E_ROCHER, 1, depart, vitesse, t.numero);
        Pouvoirs.geste(joueur, GESTE_LANCE, -1);
        son(joueur, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.4F, 0.5F);
    }

    /** Niveau 3, accroupi : une grande vague de sable (4 blocs de haut) avance de 15 blocs et emporte tout. */
    private static void vague(ServerPlayer joueur, Etat e) {
        int maintenant = joueur.server.getTickCount();
        if (maintenant < e.vagueRecharge) {
            message(joueur, "La vague se reforme", e.vagueRecharge - maintenant);
            return;
        }
        e.vagueRecharge = maintenant + recharge(joueur, RECHARGE_VAGUE);
        double l = portee(joueur, LONGUEUR_VAGUE);
        Vec3 dir = devant(joueur);
        VAGUES.add(new Vague(joueur.getUUID(), joueur.position(), dir, maintenant, l, new HashSet<>()));
        effet(joueur, E_VAGUE, 0, joueur.position(), dir, (int) Math.round(l));
        son(joueur, SoundEvents.SAND_BREAK, 2F, 0.4F);
        son(joueur, SoundEvents.ELDER_GUARDIAN_AMBIENT_LAND, 0.8F, 0.4F);
    }

    // ================================================================ clic droit

    static void attraper(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e) || e.lacher >= 0) {
            return;
        }
        if (e.prise >= 0) {
            serrer(joueur, e);
            return;
        }
        boolean accroupi = joueur.isShiftKeyDown();
        int maintenant = joueur.server.getTickCount();
        if (e.niveau == 1 || accroupi) {
            if (maintenant < e.cercueilRecharge) {
                message(joueur, "Le cercueil de sable se reforme", e.cercueilRecharge - maintenant);
                return;
            }
            if (e.niveau >= 3) {
                // trois cercueils d'un coup : les trois plus proches devant (15 blocs)
                List<LivingEntity> l = devantLui(joueur, portee(joueur, PORTEE_CERCUEILS), 0.3);
                if (l.isEmpty()) {
                    joueur.displayClientMessage(Component.literal("Personne devant toi.").withStyle(ChatFormatting.GRAY), true);
                    return;
                }
                e.cercueilRecharge = maintenant + recharge(joueur, RECHARGE_CERCUEILS);
                for (LivingEntity c : l.subList(0, Math.min(3, l.size()))) {
                    cercueil(joueur, c, CERCUEIL_2, true);
                }
                return;
            }
            boolean fort = e.niveau >= 2;
            List<LivingEntity> l = devantLui(joueur, portee(joueur, fort ? PORTEE_CERCUEIL_2 : PORTEE_CERCUEIL), 0.5);
            if (l.isEmpty()) {
                joueur.displayClientMessage(Component.literal("Personne devant toi.").withStyle(ChatFormatting.GRAY), true);
                return;
            }
            e.cercueilRecharge = maintenant + recharge(joueur, fort ? RECHARGE_CERCUEIL_2 : RECHARGE_CERCUEIL);
            cercueil(joueur, l.get(0), fort ? CERCUEIL_2 : CERCUEIL, fort);
            return;
        }
        // niveaux 2 et 3 : la grande main
        if (maintenant < e.priseRecharge) {
            message(joueur, "La main de sable se reforme", e.priseRecharge - maintenant);
            return;
        }
        List<LivingEntity> l = devantLui(joueur, portee(joueur, PORTEE_PRISE), 0.5);
        if (l.isEmpty()) {
            joueur.displayClientMessage(Component.literal("Personne à attraper devant toi.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        LivingEntity proie = l.get(0);
        e.prise = proie.getId();
        e.priseDebut = maintenant;
        e.priseDepart = proie.position();
        e.priseRecharge = maintenant + recharge(joueur, RECHARGE_PRISE);
        Pouvoirs.geste(joueur, GESTE_PRISE, proie.getId());
        ServerLevel monde = joueur.serverLevel();
        son(monde, proie.position(), SoundEvents.SAND_PLACE, 1.6F, 0.5F);
        son(monde, proie.position(), SoundEvents.SLIME_ATTACK, 1.2F, 0.5F);
        monde.sendParticles(sable(), proie.getX(), proie.getY(0.5), proie.getZ(), 30, 0.5, 0.6, 0.5, 0.15);
    }

    /** Le sable part de la jarre (ou de la bête) jusqu'à lui et le prend dans un cercueil. */
    private static void cercueil(ServerPlayer joueur, LivingEntity cible, float degats, boolean ichibi) {
        int maintenant = joueur.server.getTickCount();
        CERCUEILS.add(new Cercueil(joueur.getUUID(), cible.getId(), maintenant, cible.position(), degats));
        effet(joueur, E_CERCUEIL, 0, cible.position(), new Vec3(ichibi ? 1 : 0, 0, 0), cible.getId());
        son(joueur, SoundEvents.SAND_PLACE, 1.6F, 0.6F);
        son(joueur.serverLevel(), cible.position(), SoundEvents.SAND_BREAK, 1.4F, 0.5F);
    }

    /** Où la main tient sa prise (le milieu de son corps) : devant lui, un peu à droite, soulevée. */
    public static Vec3 main(LivingEntity joueur) {
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(lacet), 0, Mth.cos(lacet));
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        return joueur.position().add(devant.scale(2.6)).add(droite.scale(0.45)).add(0, 1.85, 0);
    }

    private static void lacher(ServerPlayer joueur, Etat e) {
        e.lacher = -1;
        if (e.prise >= 0) {
            e.prise = -1;
            Pouvoirs.geste(joueur, GESTE_PRISE, -1);
        }
    }

    /** Avec une prise, clic droit : la main serre de toutes ses forces (14, 18 au niveau 3), puis la laisse tomber. */
    private static void serrer(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        ServerLevel monde = joueur.serverLevel();
        if (monde.getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            blesser(joueur, proie, e.niveau >= 3 ? 18 : 14);
            proie.fallDistance = 0;
            monde.sendParticles(sable(), proie.getX(), proie.getY(0.6), proie.getZ(), 50, 0.5, 0.6, 0.5, 0.25);
            monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, proie.getX(), proie.getY(0.6), proie.getZ(), 8, 0.4, 0.4, 0.4, 0.1);
            son(monde, proie.position(), SoundEvents.SAND_BREAK, 1.6F, 0.5F);
            son(monde, proie.position(), SoundEvents.SKELETON_HURT, 1F, 0.5F);
        }
        Pouvoirs.geste(joueur, GESTE_SERRE, prise);
        e.lacher = joueur.server.getTickCount() + SERRER;
    }

    /** Avec une prise, clic gauche : la main la jette loin (10, 12 au niveau 3), là où il regarde. */
    private static void lancer(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        ServerLevel monde = joueur.serverLevel();
        if (monde.getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            blesser(joueur, proie, e.niveau >= 3 ? 12 : 10);
            proie.setDeltaMovement(joueur.getLookAngle().scale(2.6).add(0, 0.55, 0));
            proie.hurtMarked = true;
            proie.fallDistance = 0;
            monde.sendParticles(sable(), proie.getX(), proie.getY(0.5), proie.getZ(), 30, 0.4, 0.5, 0.4, 0.2);
            son(monde, proie.position(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.4F, 0.6F);
            son(monde, proie.position(), SoundEvents.SAND_BREAK, 1.2F, 0.7F);
        }
        Pouvoirs.geste(joueur, GESTE_LANCE, prise);
        e.prise = -1;
    }

    /** La prise est ramenée jusqu'à la main, la suit et se fait écraser ; au bout de 8 secondes, la main la lâche. */
    private static void tenir(ServerPlayer joueur, Etat e, int maintenant) {
        int age = maintenant - e.priseDebut;
        if (!(joueur.serverLevel().getEntity(e.prise) instanceof LivingEntity proie) || !proie.isAlive() || proie.distanceTo(joueur) > 16
                || age > DUREE_PRISE) {
            lacher(joueur, e);
            return;
        }
        Vec3 ici = main(joueur);
        if (age < ARRACHER) {
            float k = age / (float) ARRACHER;
            ici = e.priseDepart.add(0, proie.getBbHeight() / 2, 0).lerp(ici, k * k * (3 - 2 * k));
        }
        proie.teleportTo(ici.x, ici.y - proie.getBbHeight() / 2, ici.z);
        proie.setDeltaMovement(Vec3.ZERO);
        proie.fallDistance = 0;
        if (age > ARRACHER && age % 20 == 0) {
            blesser(joueur, proie, ECRASE);
            joueur.serverLevel().sendParticles(sable(), proie.getX(), proie.getY(0.6), proie.getZ(), 6, 0.4, 0.4, 0.4, 0.05);
        }
    }

    // ================================================================ la touche technique : le mausolée

    static void mausolee(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e)) {
            return;
        }
        if (e.niveau < 2) {
            joueur.displayClientMessage(Component.literal("Le mausolée se réveille au niveau 2.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant < e.mausoleeRecharge) {
            message(joueur, "Le mausolée se reforme", e.mausoleeRecharge - maintenant);
            return;
        }
        LivingEntity cible = visee(joueur, portee(joueur, PORTEE_MAUSOLEE));
        if (cible == null) {
            joueur.displayClientMessage(Component.literal("Vise quelqu'un (20 blocs).").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        e.mausoleeRecharge = maintenant + recharge(joueur, RECHARGE_MAUSOLEE);
        boolean trois = e.niveau >= 3;
        double taille = trois ? 1.6 * 11 / 9 : 1.6;
        // la statue sort derrière lui et regarde vers Ichibi ; ses mains se referment là où il se tient
        Vec3 face = new Vec3(joueur.getX() - cible.getX(), 0, joueur.getZ() - cible.getZ());
        face = face.lengthSqr() < 1e-4 ? devant(joueur).scale(-1) : face.normalize();
        Vec3 pieds = cible.position().subtract(face.scale(26 * taille / 16));
        pieds = new Vec3(pieds.x, sol(joueur.serverLevel(), pieds), pieds.z);
        Statue s = new Statue(joueur.getUUID(), cible.getId(), maintenant, pieds, face, taille, (trois ? 6 : 4) * 20, trois ? 2.5F : 2, trois ? 8 : 6);
        STATUES.add(s);
        effet(joueur, E_STATUE, trois ? 1 : 0, pieds, face, cible.getId());
        ServerLevel monde = joueur.serverLevel();
        son(monde, pieds, SoundEvents.SAND_BREAK, 2F, 0.4F);
        son(monde, pieds, SoundEvents.GRAVEL_BREAK, 2F, 0.4F);
        monde.sendParticles(sable(), pieds.x, pieds.y + 0.2, pieds.z, 120, 2.5, 0.3, 2.5, 0.2);
    }

    /** Le sol sous ce point (au plus 8 blocs plus bas). */
    private static double sol(ServerLevel monde, Vec3 p) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(p.x, p.y + 1, p.z).mutable();
        for (int i = 0; i < 10; i++) {
            var etat = monde.getBlockState(pos);
            if (!etat.getCollisionShape(monde, pos).isEmpty()) {
                return pos.getY() + etat.getCollisionShape(monde, pos).max(net.minecraft.core.Direction.Axis.Y);
            }
            pos.move(0, -1, 0);
        }
        return p.y;
    }

    // ================================================================ s'accroupir : le nuage de sable

    static void nuage(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (!pret(joueur, e)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int duree = dureeNuage(e.niveau);
        if (e.nuageFin >= 0) {
            // déjà dessus (deux appuis rapides sur s'accroupir) : il en descend, sauf s'il vient juste de se former
            if (maintenant > e.nuageFin - duree + 10) {
                finNuage(joueur, e, maintenant);
            }
            return;
        }
        if (maintenant < e.nuageRecharge) {
            message(joueur, "Le nuage de sable se reforme", e.nuageRecharge - maintenant);
            return;
        }
        e.nuageFin = maintenant + duree;
        POSES.remove(joueur.getUUID());
        joueur.removeEffect(MobEffects.SLOW_FALLING);
        Abilities a = joueur.getAbilities();
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = true;
            a.setFlyingSpeed(vitesseVol(e.niveau));
        }
        a.flying = true;
        joueur.onUpdateAbilities();
        joueur.setDeltaMovement(joueur.getDeltaMovement().x, 0.55, joueur.getDeltaMovement().z);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        Pouvoirs.geste(joueur, GESTE_NUAGE, e.niveau <= 1 ? 2 : 1);
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(sable(), joueur.getX(), joueur.getY() + 0.2, joueur.getZ(), 70, 1.2, 0.3, 1, 0.12);
        son(joueur, SoundEvents.SAND_PLACE, 1.6F, 0.6F);
        son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.5F);
    }

    /** Le nuage s'effrite : il le pose doucement au sol, et se reforme en 30 s. */
    private static void finNuage(ServerPlayer joueur, Etat e, int maintenant) {
        e.nuageFin = -1;
        e.nuageRecharge = maintenant + recharge(joueur, RECHARGE_NUAGE);
        Abilities a = joueur.getAbilities();
        if (!joueur.isCreative() && !joueur.isSpectator()) {
            a.mayfly = false;
            a.flying = false;
            a.setFlyingSpeed(0.05F);
            joueur.onUpdateAbilities();
            if (!joueur.onGround()) {
                joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ATTERRISSAGE, 0, false, false));
                POSES.put(joueur.getUUID(), maintenant + ATTERRISSAGE);
            }
        }
        joueur.fallDistance = 0;
        Pouvoirs.geste(joueur, GESTE_NUAGE, 0);
        joueur.serverLevel().sendParticles(sable(), joueur.getX(), joueur.getY(), joueur.getZ(), 60, 1.2, 0.3, 1, 0.08);
        son(joueur, SoundEvents.SAND_BREAK, 1.4F, 0.7F);
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        if (maintenant % 20 == 0) {
            List<UUID> l = porteurs(server);
            if (!l.equals(porteurs)) {
                porteurs = l;
                PacketDistributor.sendToAllPlayers(new Porteurs(l));
            }
        }
        for (Map.Entry<UUID, Etat> entree : new ArrayList<>(ETATS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(entree.getKey());
            Etat e = entree.getValue();
            if (joueur == null) {
                ETATS.remove(entree.getKey());
                continue;
            }
            if (e.prise >= 0 && e.lacher >= 0 && maintenant >= e.lacher) {
                lacher(joueur, e);
            }
            if (e.prise >= 0) {
                tenir(joueur, e, maintenant);
            }
            if (e.rocherPart >= 0 && maintenant >= e.rocherPart) {
                e.rocherPart = -1;
                lancerRocher(joueur);
            }
            if (e.nuageFin >= 0) {
                if (maintenant >= e.nuageFin) {
                    finNuage(joueur, e, maintenant);
                } else {
                    if (!joueur.getAbilities().flying) {
                        joueur.getAbilities().flying = true;
                        joueur.onUpdateAbilities();
                    }
                    joueur.fallDistance = 0;
                    if (maintenant % 3 == 0) {
                        joueur.serverLevel().sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()),
                                joueur.getX(), joueur.getY() - 0.35, joueur.getZ(), e.niveau <= 1 ? 3 : 5, e.niveau <= 1 ? 0.7 : 1.1, 0.05, 0.6, 0);
                    }
                }
            }
            if (maintenant % 8 == 0) {
                joueur.serverLevel().sendParticles(poussiere(), joueur.getX(), joueur.getY(0.55), joueur.getZ(), 2, 0.5, 0.6, 0.5, 0.01);
            }
        }
        cercueils(server, maintenant);
        statues(server, maintenant);
        tirs(server);
        vagues(server, maintenant);
        POSES.entrySet().removeIf(en -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(en.getKey());
            if (joueur == null) {
                return true;
            }
            joueur.fallDistance = 0;
            if (joueur.onGround() || joueur.isInWater() || maintenant >= en.getValue()) {
                joueur.removeEffect(MobEffects.SLOW_FALLING);
                return true;
            }
            return false;
        });
    }

    /** Les cercueils : la cible ne bouge plus, le sable monte jusqu'au cou, la soulève, serre, puis s'effrite. */
    private static void cercueils(MinecraftServer server, int maintenant) {
        for (Iterator<Cercueil> it = CERCUEILS.iterator(); it.hasNext(); ) {
            Cercueil c = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(c.lanceur());
            int age = maintenant - c.debut();
            Entity e = joueur == null ? null : joueur.serverLevel().getEntity(c.cible());
            if (joueur == null || !(e instanceof LivingEntity cible) || !cible.isAlive() || age > C_FIN) {
                if (joueur != null && e != null) {
                    effet(joueur, E_CERCUEIL, 2, e.position(), Vec3.ZERO, c.cible());
                    joueur.serverLevel().sendParticles(sable(), e.getX(), e.getY(0.5), e.getZ(), 40, 0.5, 0.8, 0.5, 0.1);
                }
                it.remove();
                continue;
            }
            double leve = age < C_PLEIN ? 0 : LEVER * Mth.clamp((age - C_PLEIN) / (double) (C_LEVE - C_PLEIN), 0, 1);
            Vec3 ici = c.depart().add(0, leve, 0);
            cible.teleportTo(ici.x, ici.y, ici.z);
            cible.setDeltaMovement(Vec3.ZERO);
            cible.fallDistance = 0;
            ServerLevel monde = joueur.serverLevel();
            if (age == C_VOL) {
                son(monde, ici, SoundEvents.SAND_PLACE, 1.6F, 0.5F);
            }
            if (age == C_SERRE) {
                blesser(joueur, cible, c.degats());
                effet(joueur, E_CERCUEIL, 1, ici, Vec3.ZERO, c.cible());
                monde.sendParticles(sable(), cible.getX(), cible.getY(0.6), cible.getZ(), 60, 0.6, 0.7, 0.6, 0.3);
                monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, cible.getX(), cible.getY(0.6), cible.getZ(), 8, 0.4, 0.4, 0.4, 0.1);
                son(monde, ici, SoundEvents.SAND_BREAK, 2F, 0.4F);
                son(monde, ici, SoundEvents.SKELETON_HURT, 1F, 0.5F);
            }
        }
    }

    /** Les statues : elles sortent, leurs mains se referment sur la cible ; elle étouffe sans bouger, puis la statue s'effrite. */
    private static void statues(MinecraftServer server, int maintenant) {
        for (Iterator<Statue> it = STATUES.iterator(); it.hasNext(); ) {
            Statue s = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(s.lanceur());
            int age = maintenant - s.debut();
            Entity e = joueur == null ? null : joueur.serverLevel().getEntity(s.cible());
            boolean fini = age >= M_FERME + s.duree();
            if (joueur == null || !(e instanceof LivingEntity cible) || !cible.isAlive() || fini) {
                if (joueur != null && e instanceof LivingEntity cible && cible.isAlive() && fini) {
                    // la dernière seconde, puis le coup de la fin
                    blesser(joueur, cible, s.parSeconde() + s.fin());
                    joueur.serverLevel().sendParticles(ParticleTypes.DAMAGE_INDICATOR, cible.getX(), cible.getY(0.6), cible.getZ(), 10, 0.4, 0.4, 0.4, 0.1);
                    son(joueur.serverLevel(), cible.position(), SoundEvents.SAND_BREAK, 2F, 0.4F);
                }
                if (joueur != null) {
                    effet(joueur, E_STATUE, 2, s.pieds(), s.face(), s.cible());
                    joueur.serverLevel().sendParticles(sable(), s.pieds().x, s.pieds().y + 3, s.pieds().z, 160, 1.5, 3, 1.5, 0.15);
                }
                it.remove();
                continue;
            }
            // la cible ne bouge plus : au sol pendant que la statue sort, puis soulevée jusqu'à ses mains
            Vec3 mains = s.mains();
            Vec3 ici = age < M_SORT ? new Vec3(mains.x, cible.getY(), mains.z)
                    : new Vec3(mains.x, Mth.lerp(Mth.clamp((age - M_SORT) / (double) (M_FERME - M_SORT), 0, 1), cible.getY(), mains.y), mains.z);
            cible.teleportTo(ici.x, ici.y, ici.z);
            cible.setDeltaMovement(Vec3.ZERO);
            cible.fallDistance = 0;
            if (age > M_FERME && (age - M_FERME) % 20 == 0) {
                blesser(joueur, cible, s.parSeconde());
                joueur.serverLevel().sendParticles(sable(), cible.getX(), cible.getY(0.6), cible.getZ(), 12, 0.4, 0.4, 0.4, 0.05);
            }
            if (age == M_FERME) {
                son(joueur.serverLevel(), ici, SoundEvents.SAND_PLACE, 2F, 0.4F);
            }
        }
    }

    /** Les boules et les rochers : ils filent tout droit (le rocher retombe un peu) jusqu'à toucher quelque chose. */
    private static void tirs(MinecraftServer server) {
        for (Iterator<Tir> it = TIRS.iterator(); it.hasNext(); ) {
            Tir t = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(t.lanceur);
            if (joueur == null) {
                it.remove();
                continue;
            }
            ServerLevel monde = joueur.serverLevel();
            Vec3 avant = t.pos, apres = t.pos.add(t.vitesse);
            HitResult mur = monde.clip(new ClipContext(avant, apres, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
            Vec3 bout = mur.getType() == HitResult.Type.MISS ? apres : mur.getLocation();
            double rayon = t.rocher ? 0.9 : 0.5;
            LivingEntity touchee = null;
            for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, new AABB(avant, bout).inflate(rayon + 1), c -> touchable(joueur, c))) {
                if (c.getBoundingBox().inflate(rayon).clip(avant, bout).isPresent() || c.getBoundingBox().inflate(rayon).contains(avant)) {
                    touchee = c;
                    break;
                }
            }
            t.reste -= t.vitesse.length();
            if (touchee == null && mur.getType() == HitResult.Type.MISS && t.reste > 0) {
                t.pos = apres;
                if (t.rocher) {
                    t.vitesse = t.vitesse.add(0, -CHUTE_ROCHER, 0);
                }
                continue;
            }
            Vec3 ici = touchee != null ? Vent.milieu(touchee) : bout;
            it.remove();
            effet(joueur, t.rocher ? E_ROCHER : E_BOULE, 2, ici, Vec3.ZERO, t.numero);
            if (t.rocher) {
                // le rocher éclate : 14 à tout ce qui est à 3 blocs
                for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, new AABB(ici, ici).inflate(ZONE_ROCHER + 1), c -> touchable(joueur, c))) {
                    Vec3 d = Vent.milieu(c).subtract(ici);
                    if (d.length() > ZONE_ROCHER + c.getBbWidth() / 2) {
                        continue;
                    }
                    blesser(joueur, c, ROCHER);
                    Vec3 plat = new Vec3(d.x, 0, d.z);
                    if (plat.lengthSqr() > 1e-4) {
                        c.knockback(1.2, -plat.x, -plat.z);
                    }
                    c.setDeltaMovement(c.getDeltaMovement().add(0, 0.35, 0));
                    c.hurtMarked = true;
                }
                monde.sendParticles(sable(), ici.x, ici.y, ici.z, 120, 1.4, 0.8, 1.4, 0.3);
                monde.sendParticles(poussiere(), ici.x, ici.y, ici.z, 50, 1.6, 0.8, 1.6, 0.05);
                monde.sendParticles(ParticleTypes.EXPLOSION, ici.x, ici.y, ici.z, 2, 0.4, 0.2, 0.4, 0);
                son(monde, ici, SoundEvents.GENERIC_EXPLODE.value(), 1.2F, 0.8F);
                son(monde, ici, SoundEvents.SAND_BREAK, 2F, 0.5F);
            } else {
                if (touchee != null) {
                    blesser(joueur, touchee, BOULE);
                    touchee.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 + 10, 0, false, false), joueur);
                    touchee.knockback(0.6, -t.vitesse.x, -t.vitesse.z);
                    touchee.hurtMarked = true;
                }
                monde.sendParticles(sable(), ici.x, ici.y, ici.z, 50, 0.5, 0.5, 0.5, 0.2);
                son(monde, ici, SoundEvents.SAND_BREAK, 1.6F, 0.6F);
            }
        }
    }

    /** La grande vague avance d'un bloc par tick : ce qu'elle rattrape prend 14, recule loin et reste lent 3 s. */
    private static void vagues(MinecraftServer server, int maintenant) {
        for (Iterator<Vague> it = VAGUES.iterator(); it.hasNext(); ) {
            Vague v = it.next();
            ServerPlayer joueur = server.getPlayerList().getPlayer(v.lanceur());
            int age = maintenant - v.debut();
            if (joueur == null || age > v.longueur() + 2) {
                it.remove();
                continue;
            }
            double front = Math.min(v.longueur(), age + 1.5);
            ServerLevel monde = joueur.serverLevel();
            Vec3 cote = new Vec3(-v.dir().z, 0, v.dir().x);
            Vec3 ici = v.depart().add(v.dir().scale(front));
            for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, new AABB(ici, ici).inflate(4.5, 5, 4.5), c -> touchable(joueur, c))) {
                Vec3 d = c.position().subtract(v.depart());
                double avance = d.dot(v.dir()), lateral = Math.abs(d.dot(cote));
                if (avance < 0.5 || avance > front + 0.8 || lateral > 3.8 || c.getY() > v.depart().y + 4.5 || !v.touches().add(c.getId())) {
                    continue;
                }
                blesser(joueur, c, VAGUE);
                c.knockback(2.4, -v.dir().x, -v.dir().z);
                c.setDeltaMovement(c.getDeltaMovement().add(0, 0.55, 0));
                c.hurtMarked = true;
                c.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2, false, true), joueur);
            }
            if (age % 2 == 0 && front < v.longueur()) {
                monde.sendParticles(sable(), ici.x, ici.y + 1.5, ici.z, 30, 3, 1.2, 0.6, 0.2);
                son(monde, ici, SoundEvents.SAND_BREAK, 1.4F, 0.4F + (float) (front / v.longueur()) * 0.3F);
            }
        }
    }

    /** Le serveur s'arrête : ceux qui volent sur leur nuage ne doivent pas garder le vol à la reconnexion. */
    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (UUID uuid : new ArrayList<>(ETATS.keySet())) {
            ServerPlayer joueur = event.getServer().getPlayerList().getPlayer(uuid);
            if (joueur != null) {
                endormir(joueur);
            }
        }
        ETATS.clear();
        POSES.clear();
        CERCUEILS.clear();
        STATUES.clear();
        TIRS.clear();
        VAGUES.clear();
        porteurs = List.of();
    }

    private Ichibi() {
    }
}
