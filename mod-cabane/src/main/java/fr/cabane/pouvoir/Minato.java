package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientMinato;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.joml.Vector3f;

/**
 * Minato (liste blanche « minato », toujours jaune), d'après le rendu validé. Éveillé, le clic droit lance un kunai.
 * S'il se plante dans le décor ou ne touche rien : 10 secondes de recharge. S'il touche quelqu'un, c'est le Dieu du
 * Tonnerre Volant : un combo de 15,5 secondes (téléportation et coup de pied, clone et Rasengan, six kunais en zigzag,
 * mode Kyubi, Rasengan en pleine poitrine, dôme de lumière), puis une minute de recharge. Le lanceur et sa cible le
 * voient en cinématique, les autres dans le monde ({@link ClientMinato}). Pendant le combo, les deux restent figés là où
 * ils étaient (on ne voit que leurs doubles) et personne d'autre ne peut les toucher ; à la fin, la cible est posée là
 * où le dôme l'a projetée et le lanceur là où il a levé son kunai.
 * <p>
 * Éveillé, il tient aussi un kunai en main : ses coups valent ceux d'une épée en diamant Tranchant II, quoi qu'il tienne
 * vraiment. Accroupi + clic gauche, il lance un kunai (toutes les 10 secondes) qui retombe comme une boule de neige et se
 * plante dans le premier bloc ou la première créature ; recommencer l'y téléporte, même s'il est encore en vol. Ce
 * kunai-là est indépendant de la marque posée (accroupi + clic droit), qu'on rejoint en s'accroupissant deux fois vite.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Minato {
    public static final String NOM = "minato";
    /** Le combo, en ticks, à partir du moment où le kunai touche. */
    public static final int DUREE = 311;
    public static final int RECHARGE = 1200;
    public static final int RECHARGE_RATE = 200;
    private static final double VITESSE = 2.2;
    private static final int VOL = 12;
    /** Le kunai lancé à la main : sa vitesse au départ, et il retombe comme une boule de neige (0,03 par tick, freiné de 1 %). */
    public static final double JET_VITESSE = 1.8, JET_GRAVITE = 0.03, JET_FREIN = 0.99;
    /** Au-delà (en ticks), il est perdu. */
    public static final int JET_VIE = 120;
    private static final float JET_DEGATS = 4;
    /** Entre deux kunais lancés à la main (y aller ne recharge rien). */
    public static final int RECHARGE_JET = 200;

    /** Les points du combo, dans l'ordre du paquet (voir {@link #demarrer}). */
    public static final int O = 0, DEPART = 1, P1 = 2, AIR = 3, P2 = 4, E2 = 5, P4 = 6, E3 = 7, P3 = 8;

    /**
     * Les coups : quand (en ticks après le début), la part qui passe par l'armure (une frappe normale) et celle qui la
     * traverse, Protection comprise (l'Éclair Jaune). Sans armure : 16 (8 cœurs) ; en diamant Protection IV : environ
     * 7,5 (presque 4 cœurs), parce que les 7 de l'Éclair Jaune passent toujours.
     */
    private static final int[] QUAND = {5, 59, 62, 65, 68, 72, 75, 78, 81, 84, 153, 193};
    private static final float[] NORMAL = {2, 1 / 3F, 1 / 3F, 1 / 3F, 1 / 3F, 1 / 3F, 1 / 3F, 1 / 3F, 1 / 3F, 1 / 3F, 2.5F, 1.5F};
    private static final float[] PERCANT = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 3, 3};
    /** Les dégâts qui traversent l'armure et Protection (data/cabane/damage_type, et les tags bypasses_armor / bypasses_enchantments). */
    public static final ResourceKey<DamageType> ECLAIR_JAUNE = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("eclair_jaune"));

    /** Un kunai en vol. */
    private static final class Vol {
        Vec3 pos;
        Vec3 vitesse;
        int age;
    }

    /** Un kunai lancé à la main, en vol (dans le monde d'où il est parti). */
    private static final class Envol {
        ServerLevel monde;
        Vec3 pos;
        Vec3 vitesse;
        int age;
    }

    /** Un coup au kunai en train de partir : sur qui, chargé à quel point, et ce qu'auraient ajouté les enchantements de l'objet tenu. */
    private record Coup(int cible, float force, float bonus) {
    }

    /** Un combo en cours : la cible, le tick de départ, ses points, le prochain coup. */
    private static final class Enchainement {
        int cible;
        int debut;
        Vec3[] points;
        int coup;
    }

    private static final Set<UUID> ELUS = new HashSet<>();
    private static final Map<UUID, Vol> VOLS = new HashMap<>();
    private static final Map<UUID, Envol> ENVOLS = new HashMap<>();
    /** Le tick à partir duquel ce joueur peut relancer un kunai à la main. */
    private static final Map<UUID, Integer> PRET_JET = new HashMap<>();
    private static final Map<UUID, Coup> COUPS = new HashMap<>();
    private static final Map<UUID, Enchainement> COMBOS = new HashMap<>();
    /** Le tick à partir duquel ce joueur peut relancer. */
    private static final Map<UUID, Integer> PRET = new HashMap<>();
    /** Vrai pendant nos propres coups (les seuls qui passent pendant le combo). */
    private static boolean frappe;

    // ================================================================ réseau

    /** Client → serveur : clic droit, Minato éveillé. */
    public record Lancer() implements CustomPacketPayload {
        public static final Type<Lancer> TYPE = new Type<>(Cabane.id("minato_lancer"));
        public static final StreamCodec<ByteBuf, Lancer> CODEC = StreamCodec.unit(new Lancer());

        @Override
        public Type<Lancer> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : le kunai (0 : il part de pos à la vitesse dir ; 1 : il se plante en pos ; 2 : il disparaît). */
    public record Kunai(UUID joueur, int etape, Vector3f pos, Vector3f dir) implements CustomPacketPayload {
        public static final Type<Kunai> TYPE = new Type<>(Cabane.id("minato_kunai"));
        public static final StreamCodec<ByteBuf, Kunai> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Kunai::joueur, ByteBufCodecs.VAR_INT, Kunai::etape, ByteBufCodecs.VECTOR3F, Kunai::pos,
                ByteBufCodecs.VECTOR3F, Kunai::dir, Kunai::new);

        @Override
        public Type<Kunai> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : le combo commence (avec ses points), ou s'arrête net (points vide). */
    public record Combo(UUID joueur, int cible, List<Vector3f> points) implements CustomPacketPayload {
        public static final Type<Combo> TYPE = new Type<>(Cabane.id("minato_combo"));
        public static final StreamCodec<ByteBuf, Combo> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Combo::joueur, ByteBufCodecs.VAR_INT, Combo::cible,
                ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), Combo::points, Combo::new);

        @Override
        public Type<Combo> type() {
            return TYPE;
        }
    }

    /** Client → serveur : accroupi + clic droit, Minato éveillé (poser la marque). */
    public record Poser() implements CustomPacketPayload {
        public static final Type<Poser> TYPE = new Type<>(Cabane.id("minato_poser"));
        public static final StreamCodec<ByteBuf, Poser> CODEC = StreamCodec.unit(new Poser());

        @Override
        public Type<Poser> type() {
            return TYPE;
        }
    }

    /** Client → serveur : on vient de s'accroupir deux fois de suite, Minato éveillé, avec une marque posée (y aller). */
    public record Rejoindre() implements CustomPacketPayload {
        public static final Type<Rejoindre> TYPE = new Type<>(Cabane.id("minato_rejoindre"));
        public static final StreamCodec<ByteBuf, Rejoindre> CODEC = StreamCodec.unit(new Rejoindre());

        @Override
        public Type<Rejoindre> type() {
            return TYPE;
        }
    }

    /** Client → serveur : accroupi + clic gauche, Minato éveillé (lancer un kunai, ou aller à celui déjà lancé). */
    public record Jeter() implements CustomPacketPayload {
        public static final Type<Jeter> TYPE = new Type<>(Cabane.id("minato_jeter"));
        public static final StreamCodec<ByteBuf, Jeter> CODEC = StreamCodec.unit(new Jeter());

        @Override
        public Type<Jeter> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un kunai lancé à la main part de pos à cette vitesse (vole), ou n'est plus en vol. */
    public record Jet(UUID joueur, boolean vole, Vector3f pos, Vector3f vitesse) implements CustomPacketPayload {
        public static final Type<Jet> TYPE = new Type<>(Cabane.id("minato_jet"));
        public static final StreamCodec<ByteBuf, Jet> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Jet::joueur, ByteBufCodecs.BOOL, Jet::vole, ByteBufCodecs.VECTOR3F, Jet::pos,
                ByteBufCodecs.VECTOR3F, Jet::vitesse, Jet::new);

        @Override
        public Type<Jet> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → tous : la marque de ce joueur, celle posée (accroupi + clic droit) ou le kunai lancé une fois planté
     * (lance). Type 0 : aucune ; 1 : un kunai planté en pos, dans la dimension dim, la pointe vers dir, ou nulle s'il
     * est posé à la verticale ; 2 : sur quelqu'un, nom, dont l'entité porte le numéro entite dans dim, -1 si elle n'est
     * pas chargée.
     */
    public record MarqueEtat(UUID joueur, boolean lance, int marque, String dim, Vector3f pos, Vector3f dir, int entite, String nom)
            implements CustomPacketPayload {
        public static final Type<MarqueEtat> TYPE = new Type<>(Cabane.id("minato_marque"));
        public static final StreamCodec<ByteBuf, MarqueEtat> CODEC = StreamCodec.of((b, p) -> {
            UUIDUtil.STREAM_CODEC.encode(b, p.joueur());
            ByteBufCodecs.BOOL.encode(b, p.lance());
            ByteBufCodecs.VAR_INT.encode(b, p.marque());
            ByteBufCodecs.STRING_UTF8.encode(b, p.dim());
            ByteBufCodecs.VECTOR3F.encode(b, p.pos());
            ByteBufCodecs.VECTOR3F.encode(b, p.dir());
            ByteBufCodecs.VAR_INT.encode(b, p.entite());
            ByteBufCodecs.STRING_UTF8.encode(b, p.nom());
        }, b -> new MarqueEtat(UUIDUtil.STREAM_CODEC.decode(b), ByteBufCodecs.BOOL.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.STRING_UTF8.decode(b),
                ByteBufCodecs.VECTOR3F.decode(b), ByteBufCodecs.VECTOR3F.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.STRING_UTF8.decode(b)));

        @Override
        public Type<MarqueEtat> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Lancer.TYPE, Lancer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        lancer(joueur);
                    }
                }))
                .playToServer(Poser.TYPE, Poser.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        poser(joueur);
                    }
                }))
                .playToServer(Rejoindre.TYPE, Rejoindre.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        rejoindre(joueur, false);
                    }
                }))
                .playToServer(Jeter.TYPE, Jeter.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        jeter(joueur);
                    }
                }))
                .playToClient(Jet.TYPE, Jet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMinato.jet(p)))
                .playToClient(MarqueEtat.TYPE, MarqueEtat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMinato.marque(p)))
                .playToClient(Kunai.TYPE, Kunai.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMinato.kunai(p)))
                .playToClient(Combo.TYPE, Combo.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMinato.combo(p)));
    }

    /** À tous ceux qui sont à moins de 128 blocs de ce point. */
    private static void envoyer(ServerLevel monde, Vec3 autour, CustomPacketPayload paquet) {
        for (ServerPlayer p : monde.players()) {
            if (p.distanceToSqr(autour) < 128 * 128) {
                PacketDistributor.sendToPlayer(p, paquet);
            }
        }
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    // ================================================================ s'éveiller, s'endormir

    private static final ResourceLocation COURSE = Cabane.id("minato_vitesse");
    /** Éveillé, il court bien plus vite : +55 %, juste en dessous de la bête de Hachibi (+65 %). */
    private static final double BONUS_VITESSE = 0.55;

    private static void vitesse(ServerPlayer joueur, boolean eveille) {
        AttributeInstance a = joueur.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) {
            return;
        }
        a.removeModifier(COURSE);
        if (eveille) {
            a.addTransientModifier(new AttributeModifier(COURSE, BONUS_VITESSE, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** Le kunai qu'il a en main, éveillé : un objet sans recette ni onglet, juste pour le dessiner (assets/cabane/models/item). */
    public static final ResourceLocation KUNAI = Cabane.id("kunai_minato");

    @SubscribeEvent
    public static void objets(RegisterEvent event) {
        event.register(Registries.ITEM, KUNAI, () -> new Item(new Item.Properties().stacksTo(1)));
    }

    public static Item kunai() {
        return BuiltInRegistries.ITEM.get(KUNAI);
    }

    private static final ResourceLocation FRAPPE = Cabane.id("minato_kunai_degats");
    private static final ResourceLocation CADENCE = Cabane.id("minato_kunai_cadence");
    /** Le kunai vaut une épée en diamant (+6 de dégâts, -2,4 de vitesse d'attaque) Tranchant II (+1,5). */
    private static final double DEGATS_KUNAI = 6, CADENCE_KUNAI = -2.4;
    private static final float TRANCHANT = 1.5F;

    /** Éveillé, ce qu'apporte l'objet tenu (ses modificateurs de main) est remplacé par ce qu'apporterait le kunai. */
    public static void arme(Player joueur, boolean eveille) {
        caler(joueur.getAttribute(Attributes.ATTACK_DAMAGE), FRAPPE, Item.BASE_ATTACK_DAMAGE_ID, DEGATS_KUNAI, eveille);
        caler(joueur.getAttribute(Attributes.ATTACK_SPEED), CADENCE, Item.BASE_ATTACK_SPEED_ID, CADENCE_KUNAI, eveille);
    }

    private static void caler(AttributeInstance a, ResourceLocation id, ResourceLocation objet, double voulu, boolean eveille) {
        if (a == null) {
            return;
        }
        if (!eveille) {
            a.removeModifier(id);
            return;
        }
        AttributeModifier tenu = a.getModifier(objet);
        double montant = voulu - (tenu == null ? 0 : tenu.amount());
        AttributeModifier deja = a.getModifier(id);
        if (deja == null || Math.abs(deja.amount() - montant) > 1.0E-6) {
            a.addOrUpdateTransientModifier(new AttributeModifier(id, montant, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    static void eveiller(ServerPlayer joueur) {
        ELUS.add(joueur.getUUID());
        vitesse(joueur, true);
        arme(joueur, true);
        joueur.serverLevel().sendParticles(jaune(), joueur.getX(), joueur.getY(1), joueur.getZ(), 24, 0.5, 0.9, 0.5, 0.1);
        joueur.serverLevel().playSound(null, joueur.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.8F, 1.6F);
    }

    /** Le combo en cours continue (c'est une cinématique) ; seuls les kunais en vol disparaissent. */
    static void endormir(ServerPlayer joueur) {
        ELUS.remove(joueur.getUUID());
        vitesse(joueur, false);
        arme(joueur, false);
        if (VOLS.remove(joueur.getUUID()) != null) {
            envoyer(joueur.serverLevel(), joueur.position(), new Kunai(joueur.getUUID(), 2, v(joueur.position()), new Vector3f()));
        }
        Envol e = ENVOLS.remove(joueur.getUUID());
        if (e != null) {
            envoyer(e.monde, e.pos, new Jet(joueur.getUUID(), false, v(e.pos), new Vector3f()));
        }
    }

    private static DustParticleOptions jaune() {
        return new DustParticleOptions(new Vector3f(1F, 0.84F, 0.2F), 1.6F);
    }

    // ================================================================ le kunai

    private static void lancer(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        int maintenant = joueur.server.getTickCount();
        if (!ELUS.contains(id) || VOLS.containsKey(id) || fige(joueur)) {
            return;
        }
        int pret = PRET.getOrDefault(id, 0);
        if (maintenant < pret) {
            joueur.displayClientMessage(Component.literal(String.format("Ton kunai n'est pas revenu… (%d s)", (pret - maintenant + 19) / 20)), true);
            return;
        }
        Vol vol = new Vol();
        Vec3 regard = joueur.getLookAngle();
        vol.pos = joueur.getEyePosition().add(regard.scale(0.4)).subtract(0, 0.15, 0);
        vol.vitesse = regard.scale(VITESSE);
        VOLS.put(id, vol);
        joueur.swing(InteractionHand.MAIN_HAND, true);
        joueur.serverLevel().playSound(null, joueur.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1F, 1.6F);
        envoyer(joueur.serverLevel(), joueur.position(), new Kunai(id, 0, v(vol.pos), v(vol.vitesse)));
    }

    /** Un pas de kunai : il touche quelqu'un (le combo part), se plante, ou continue (et retombe un peu). */
    private static void voler(ServerPlayer joueur, Vol vol, int maintenant) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 a = vol.pos;
        Vec3 b = a.add(vol.vitesse);
        HitResult bloc = monde.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 fin = bloc.getType() == HitResult.Type.MISS ? b : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, a, fin, new AABB(a, fin).inflate(1),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !fige(l), 0.4F);
        UUID id = joueur.getUUID();
        if (touche != null && touche.getEntity() instanceof LivingEntity cible) {
            VOLS.remove(id);
            envoyer(monde, a, new Kunai(id, 2, v(touche.getLocation()), v(vol.vitesse)));
            demarrer(joueur, cible);
            return;
        }
        if (bloc.getType() != HitResult.Type.MISS) {
            VOLS.remove(id);
            PRET.put(id, maintenant + RECHARGE_RATE);
            monde.playSound(null, BlockPos.containing(fin), SoundEvents.TRIDENT_HIT_GROUND, SoundSource.PLAYERS, 1F, 1.5F);
            envoyer(monde, fin, new Kunai(id, 1, v(fin), v(vol.vitesse)));
            return;
        }
        vol.pos = b;
        vol.vitesse = vol.vitesse.add(0, -0.03, 0);
        if (++vol.age > VOL) {
            VOLS.remove(id);
            PRET.put(id, maintenant + RECHARGE_RATE);
            envoyer(monde, b, new Kunai(id, 2, v(b), v(vol.vitesse)));
        }
    }

    // ================================================================ la marque : le Dieu du Tonnerre Volant à volonté

    /**
     * Les marques, gardées dans le monde (elles survivent au sommeil du pouvoir, aux déconnexions, aux redémarrages).
     * Chaque Minato en a deux, indépendantes : celle posée (accroupi + clic droit, on y va en s'accroupissant deux fois)
     * et le kunai lancé à la main une fois planté (accroupi + clic gauche pour y aller). Chacune est un kunai planté
     * quelque part (dimension, position, et d'où il est arrivé s'il a été lancé), ou sur quelqu'un (son UUID et son nom),
     * et ne sert qu'une fois.
     */
    public static final class Marques extends SavedData {
        private static final String NOM = "cabane_minato";

        record Marque(String dim, Vec3 pos, UUID cible, String nom, Vec3 dir) {
        }

        final Map<UUID, Marque> marques = new HashMap<>();
        final Map<UUID, Marque> lances = new HashMap<>();

        Map<UUID, Marque> de(boolean lance) {
            return lance ? lances : marques;
        }

        static Marques get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Marques::new, Marques::charger, null), NOM);
        }

        private static Marques charger(CompoundTag tag, HolderLookup.Provider registres) {
            Marques d = new Marques();
            lire(tag.getList("marques", Tag.TAG_COMPOUND), d.marques);
            lire(tag.getList("lances", Tag.TAG_COMPOUND), d.lances);
            return d;
        }

        private static void lire(ListTag liste, Map<UUID, Marque> dans) {
            for (Tag t : liste) {
                CompoundTag c = (CompoundTag) t;
                dans.put(c.getUUID("joueur"), new Marque(c.getString("dim"), new Vec3(c.getDouble("x"), c.getDouble("y"), c.getDouble("z")),
                        c.hasUUID("cible") ? c.getUUID("cible") : null, c.getString("nom"),
                        c.contains("dx") ? new Vec3(c.getDouble("dx"), c.getDouble("dy"), c.getDouble("dz")) : null));
            }
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registres) {
            tag.put("marques", ecrire(marques));
            tag.put("lances", ecrire(lances));
            return tag;
        }

        private static ListTag ecrire(Map<UUID, Marque> quoi) {
            ListTag liste = new ListTag();
            quoi.forEach((id, m) -> {
                CompoundTag c = new CompoundTag();
                c.putUUID("joueur", id);
                c.putString("dim", m.dim());
                c.putDouble("x", m.pos().x);
                c.putDouble("y", m.pos().y);
                c.putDouble("z", m.pos().z);
                if (m.cible() != null) {
                    c.putUUID("cible", m.cible());
                }
                if (m.dir() != null) {
                    c.putDouble("dx", m.dir().x);
                    c.putDouble("dy", m.dir().y);
                    c.putDouble("dz", m.dir().z);
                }
                c.putString("nom", m.nom());
                liste.add(c);
            });
            return liste;
        }
    }

    private static final Map<UUID, Integer> DERNIER_SAUT = new HashMap<>();

    /** Quelqu'un, où qu'il soit : un joueur connecté, ou une créature chargée dans n'importe quelle dimension. */
    private static Entity trouver(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) {
            return p;
        }
        for (ServerLevel monde : server.getAllLevels()) {
            Entity e = monde.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static MarqueEtat etat(MinecraftServer server, UUID joueur, boolean lance, Marques.Marque m) {
        if (m == null) {
            return new MarqueEtat(joueur, lance, 0, "", new Vector3f(), new Vector3f(), -1, "");
        }
        if (m.cible() == null) {
            return new MarqueEtat(joueur, lance, 1, m.dim(), v(m.pos()), m.dir() == null ? new Vector3f() : v(m.dir()), -1, "");
        }
        Entity e = trouver(server, m.cible());
        return new MarqueEtat(joueur, lance, 2, e == null ? m.dim() : e.level().dimension().location().toString(),
                v(e == null ? m.pos() : e.position()), new Vector3f(), e == null ? -1 : e.getId(), m.nom());
    }

    /** La nouvelle marque (posée, ou kunai lancé) de ce joueur, ou plus rien (m nulle), connue de tous. */
    private static void marquer(MinecraftServer server, UUID joueur, boolean lance, Marques.Marque m) {
        Marques d = Marques.get(server);
        if (m == null) {
            d.de(lance).remove(joueur);
        } else {
            d.de(lance).put(joueur, m);
        }
        d.setDirty();
        PacketDistributor.sendToAllPlayers(etat(server, joueur, lance, m));
    }

    /** La téléportation, avec ses éclairs et son flash au départ et à l'arrivée. */
    private static void sauter(ServerPlayer joueur, ServerLevel ou, Vec3 vers) {
        DERNIER_SAUT.put(joueur.getUUID(), joueur.server.getTickCount());
        eclairs(joueur.serverLevel(), joueur.position());
        joueur.teleportTo(ou, vers.x, vers.y, vers.z, joueur.getYRot(), joueur.getXRot());
        joueur.fallDistance = 0;
        eclairs(ou, vers);
    }

    private static boolean sauteTropTot(ServerPlayer joueur) {
        return joueur.server.getTickCount() - DERNIER_SAUT.getOrDefault(joueur.getUUID(), -100) < 10;
    }

    private static void eclairs(ServerLevel monde, Vec3 p) {
        monde.sendParticles(jaune(), p.x, p.y + 1, p.z, 30, 0.35, 0.8, 0.35, 0.15);
        monde.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 1, p.z, 16, 0.4, 0.7, 0.4, 0.3);
        monde.playSound(null, BlockPos.containing(p), fr.cabane.CabaneSons.MINATO_FLASH.get(), SoundSource.PLAYERS, 1F, 1.1F + monde.random.nextFloat() * 0.2F);
    }

    /** Accroupi + clic droit : la marque va sur la créature visée (à portée de main), sinon un kunai se plante à ses pieds. */
    private static void poser(ServerPlayer joueur) {
        if (!ELUS.contains(joueur.getUUID()) || fige(joueur)) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 regard = joueur.getLookAngle();
        HitResult bloc = monde.clip(new ClipContext(oeil, oeil.add(regard.scale(4.5)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 loin = bloc.getType() == HitResult.Type.MISS ? oeil.add(regard.scale(4.5)) : bloc.getLocation();
        EntityHitResult vise = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, loin, joueur.getBoundingBox().expandTowards(regard.scale(4.5)).inflate(1),
                e -> e instanceof LivingEntity && e != joueur && e.isAlive(), 0.3F);
        String dim = monde.dimension().location().toString();
        Marques.Marque m;
        if (vise != null) {
            Entity e = vise.getEntity();
            m = new Marques.Marque(dim, e.position(), e.getUUID(), e.getName().getString(), null);
            joueur.displayClientMessage(Component.literal("Marque posée sur " + m.nom() + ". Accroupis-toi deux fois vite pour le rejoindre."), true);
            monde.sendParticles(jaune(), e.getX(), e.getY(0.6), e.getZ(), 16, 0.3, 0.4, 0.3, 0.05);
        } else {
            m = new Marques.Marque(dim, joueur.position(), null, "", null);
            joueur.displayClientMessage(Component.literal("Kunai planté. Accroupis-toi deux fois vite pour y revenir, d'où que tu sois."), true);
        }
        joueur.swing(InteractionHand.MAIN_HAND, true);
        monde.playSound(null, joueur.blockPosition(), SoundEvents.TRIDENT_HIT_GROUND, SoundSource.PLAYERS, 0.8F, 1.6F);
        marquer(joueur.server, joueur.getUUID(), false, m);
    }

    /**
     * Accroupi + clic gauche : s'il y a déjà un kunai lancé, en vol ou planté, on y va ; sinon on en lance un (toutes
     * les 10 secondes), qui retombe comme une boule de neige et se plante dans le premier bloc ou la première créature.
     */
    private static void jeter(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (!ELUS.contains(id) || fige(joueur)) {
            return;
        }
        Envol en = ENVOLS.get(id);
        if (en != null) {
            allerEnVol(joueur, en);
            return;
        }
        if (Marques.get(joueur.server).lances.containsKey(id)) {
            rejoindre(joueur, true);
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int pret = PRET_JET.getOrDefault(id, 0);
        if (sauteTropTot(joueur) || maintenant < pret) {
            if (maintenant < pret) {
                joueur.displayClientMessage(Component.literal(String.format("Pas encore de kunai à lancer… (%d s)", (pret - maintenant + 19) / 20)), true);
            }
            return;
        }
        PRET_JET.put(id, maintenant + RECHARGE_JET);
        Envol e = new Envol();
        Vec3 regard = joueur.getLookAngle();
        e.monde = joueur.serverLevel();
        e.pos = joueur.getEyePosition().add(regard.scale(0.3)).subtract(0, 0.1, 0);
        e.vitesse = regard.scale(JET_VITESSE);
        ENVOLS.put(id, e);
        joueur.swing(InteractionHand.MAIN_HAND, true);
        joueur.serverLevel().playSound(null, joueur.getX(), joueur.getEyeY(), joueur.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.7F, 1.9F);
        envoyer(joueur.serverLevel(), joueur.position(), new Jet(id, true, v(e.pos), v(e.vitesse)));
    }

    /** Accroupi + clic gauche pendant que le kunai vole : on l'attrape en plein vol (et on retombe). */
    private static void allerEnVol(ServerPlayer joueur, Envol e) {
        if (sauteTropTot(joueur)) {
            return;
        }
        ENVOLS.remove(joueur.getUUID());
        envoyer(e.monde, e.pos, new Jet(joueur.getUUID(), false, v(e.pos), v(e.vitesse)));
        Vec3 dir = e.vitesse.lengthSqr() < 1.0E-6 ? joueur.getLookAngle() : e.vitesse.normalize();
        sauter(joueur, e.monde, arrivee(e.monde, joueur, e.pos, dir));
    }

    /** Un pas du kunai lancé : il se plante dans quelqu'un ou dans un bloc, ou continue en retombant. */
    private static void envoler(ServerPlayer joueur, Envol e) {
        ServerLevel monde = e.monde;
        UUID id = joueur.getUUID();
        String dim = monde.dimension().location().toString();
        Vec3 a = e.pos;
        Vec3 b = a.add(e.vitesse);
        HitResult bloc = monde.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 fin = bloc.getType() == HitResult.Type.MISS ? b : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, a, fin, new AABB(a, fin).inflate(1),
                x -> x instanceof LivingEntity l && l != joueur && l.isAlive() && !l.isSpectator() && !fige(l), 0.3F);
        if (touche != null && touche.getEntity() instanceof LivingEntity cible) {
            ENVOLS.remove(id);
            envoyer(monde, a, new Jet(id, false, v(touche.getLocation()), v(e.vitesse)));
            if (Pouvoirs.cible(joueur, cible)) {
                cible.hurt(joueur.damageSources().thrown(joueur, joueur), JET_DEGATS);
            }
            monde.playSound(null, cible.blockPosition(), SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, 0.8F, 1.6F);
            joueur.displayClientMessage(Component.literal("Kunai planté dans " + cible.getName().getString() + ". Accroupi + clic gauche pour le rejoindre."), true);
            marquer(joueur.server, id, true, new Marques.Marque(dim, cible.position(), cible.getUUID(), cible.getName().getString(), null));
            return;
        }
        if (bloc.getType() != HitResult.Type.MISS) {
            ENVOLS.remove(id);
            envoyer(monde, fin, new Jet(id, false, v(fin), v(e.vitesse)));
            monde.playSound(null, BlockPos.containing(fin), SoundEvents.TRIDENT_HIT_GROUND, SoundSource.PLAYERS, 1F, 1.5F);
            joueur.displayClientMessage(Component.literal("Kunai planté. Accroupi + clic gauche pour y aller, d'où que tu sois."), true);
            marquer(joueur.server, id, true, new Marques.Marque(dim, fin, null, "", e.vitesse.normalize()));
            return;
        }
        e.pos = b;
        e.vitesse = e.vitesse.scale(JET_FREIN).add(0, -JET_GRAVITE, 0);
        if (++e.age > JET_VIE || b.y < monde.getMinBuildHeight() - 16) {
            ENVOLS.remove(id);
            envoyer(monde, b, new Jet(id, false, v(b), v(e.vitesse)));
            joueur.displayClientMessage(Component.literal("Ton kunai s'est perdu…"), true);
        }
    }

    /**
     * Où arriver pour attraper un kunai lancé, planté dans un mur, un plafond ou le sol : un peu en arrière de sa
     * trajectoire, la main à sa hauteur si la place est libre (on retombe ensuite), sinon plus bas.
     */
    private static Vec3 arrivee(ServerLevel monde, ServerPlayer joueur, Vec3 pointe, Vec3 dir) {
        Vec3 recul = pointe.subtract(dir.scale(0.5));
        for (double dy : new double[] {-1.2, -0.6, 0, -1.8, 0.4}) {
            Vec3 q = recul.add(0, dy, 0);
            if (monde.noCollision(joueur, joueur.getDimensions(Pose.STANDING).makeBoundingBox(q))) {
                return q;
            }
        }
        return sol(monde, recul);
    }

    /**
     * S'accroupir deux fois (la marque posée), ou accroupi + clic gauche (le kunai lancé, planté) : on s'y téléporte,
     * où qu'il soit (même dans une autre dimension). Il est alors utilisé.
     */
    private static void rejoindre(ServerPlayer joueur, boolean lance) {
        UUID id = joueur.getUUID();
        MinecraftServer server = joueur.server;
        if (!ELUS.contains(id) || fige(joueur) || sauteTropTot(joueur)) {
            return;
        }
        Marques.Marque m = Marques.get(server).de(lance).get(id);
        if (m == null) {
            return;
        }
        ServerLevel ou;
        Vec3 vers;
        if (m.cible() != null) {
            Entity e = trouver(server, m.cible());
            if (e == null || !e.isAlive()) {
                if (server.getPlayerList().getPlayer(m.cible()) == null && e != null) {
                    marquer(server, id, lance, null);
                }
                joueur.displayClientMessage(Component.literal(m.nom() + " est introuvable pour l'instant…"), true);
                return;
            }
            ou = (ServerLevel) e.level();
            // juste dans son dos, si la place est libre
            Vec3 dos = new Vec3(-Math.sin(Math.toRadians(e.getYRot())), 0, Math.cos(Math.toRadians(e.getYRot()))).scale(-1.1);
            Vec3 derriere = e.position().add(dos);
            vers = ou.noCollision(joueur, joueur.getBoundingBox().move(derriere.subtract(joueur.position()))) ? derriere : e.position();
        } else {
            ou = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(m.dim())));
            if (ou == null) {
                joueur.displayClientMessage(Component.literal("Ce kunai est dans un monde qui n'existe plus…"), true);
                return;
            }
            vers = m.dir() == null ? m.pos() : arrivee(ou, joueur, m.pos(), m.dir());
        }
        sauter(joueur, ou, vers);
        marquer(server, id, lance, null);
    }

    /** Celui qui arrive voit les kunais plantés (et connaît ses propres marques). */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer arrivant) {
            Marques d = Marques.get(arrivant.server);
            d.marques.forEach((id, m) -> PacketDistributor.sendToPlayer(arrivant, etat(arrivant.server, id, false, m)));
            d.lances.forEach((id, m) -> PacketDistributor.sendToPlayer(arrivant, etat(arrivant.server, id, true, m)));
        }
    }

    // ================================================================ le combo

    /** Un endroit où tenir debout, près de p (en cherchant un peu plus haut et plus bas), sinon p tel quel. */
    private static Vec3 sol(ServerLevel monde, Vec3 p) {
        int y0 = (int) Math.floor(p.y);
        for (int d : new int[] {0, 1, -1, 2, -2, 3, -3, -4, -5}) {
            Vec3 q = new Vec3(p.x, y0 + d, p.z);
            if (debout(monde, q)) {
                return q;
            }
        }
        return p;
    }

    private static boolean debout(ServerLevel monde, Vec3 q) {
        BlockPos dessous = BlockPos.containing(q.x, q.y - 0.5, q.z);
        return monde.noCollision(new AABB(q.x - 0.3, q.y, q.z - 0.3, q.x + 0.3, q.y + 1.8, q.z + 0.3))
                && !monde.getBlockState(dessous).getCollisionShape(monde, dessous).isEmpty();
    }

    /** Jusqu'où on peut aller tout droit depuis p (à hauteur de taille), au plus loin. */
    private static double libre(ServerLevel monde, Vec3 p, Vec3 f, double loin, Entity qui) {
        Vec3 a = p.add(0, 0.9, 0);
        HitResult r = monde.clip(new ClipContext(a, a.add(f.scale(loin)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, qui));
        return r.getType() == HitResult.Type.MISS ? loin : Math.max(0, r.getLocation().distanceTo(a) - 0.6);
    }

    /**
     * Les points du combo, autour de la cible (O), dans l'axe lanceur → cible (f) : P1 le coup de pied, AIR où elle
     * flotte pendant le zigzag, P2 où il atterrit avec le Rasengan, E2 où elle retombe, P4 d'où part le Rasengan, E3 où
     * le Rasengan la projette (et où le dôme explose), P3 où il lève son kunai.
     */
    private static void demarrer(ServerPlayer joueur, LivingEntity cible) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 o = cible.position();
        Vec3 depart = joueur.position();
        Vec3 f = new Vec3(o.x - depart.x, 0, o.z - depart.z);
        f = f.lengthSqr() < 1.0E-4 ? Pouvoirs.devant(joueur) : f.normalize();
        Vec3 g = new Vec3(f.z, 0, -f.x);
        Vec3[] p = new Vec3[9];
        p[O] = o;
        p[DEPART] = depart;
        p[P1] = sol(monde, o.subtract(f.scale(1.2)));
        p[AIR] = o.add(f.scale(3)).add(0, 3.2, 0);
        p[P2] = sol(monde, o.subtract(f.scale(0.8)).add(g.scale(1.6)));
        p[E2] = sol(monde, o.add(f.scale(libre(monde, o, f, 3.5, cible))));
        p[P4] = sol(monde, p[E2].subtract(f.scale(1)));
        p[E3] = sol(monde, p[E2].add(f.scale(libre(monde, p[E2], f, 6.5, cible))));
        p[P3] = sol(monde, o.subtract(f.scale(3)));
        Enchainement c = new Enchainement();
        c.cible = cible.getId();
        c.debut = joueur.server.getTickCount();
        c.points = p;
        COMBOS.put(joueur.getUUID(), c);
        List<Vector3f> points = new ArrayList<>();
        for (Vec3 q : p) {
            points.add(v(q));
        }
        envoyer(monde, o, new Combo(joueur.getUUID(), cible.getId(), points));
    }

    private static boolean fige(LivingEntity e) {
        if (COMBOS.containsKey(e.getUUID())) {
            return true;
        }
        for (Enchainement c : COMBOS.values()) {
            if (c.cible == e.getId()) {
                return true;
            }
        }
        return false;
    }

    private static void tenir(Entity e, Vec3 ici) {
        if (e.position().distanceToSqr(ici) > 0.01) {
            e.teleportTo(ici.x, ici.y, ici.z);
        }
        e.setDeltaMovement(Vec3.ZERO);
        e.fallDistance = 0;
    }

    private static void finir(MinecraftServer server, UUID id, Enchainement c, boolean net) {
        COMBOS.remove(id);
        PRET.put(id, server.getTickCount() + RECHARGE);
        ServerPlayer joueur = server.getPlayerList().getPlayer(id);
        if (joueur == null) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        if (net) {
            envoyer(monde, c.points[O], new Combo(id, c.cible, List.of()));
            return;
        }
        Entity cible = monde.getEntity(c.cible);
        if (cible != null && cible.isAlive()) {
            Vec3 e3 = debout(monde, c.points[E3]) ? c.points[E3] : c.points[O];
            cible.teleportTo(e3.x, e3.y, e3.z);
        }
        Vec3 p3 = debout(monde, c.points[P3]) ? c.points[P3] : c.points[DEPART];
        joueur.teleportTo(p3.x, p3.y, p3.z);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Vol> e : new ArrayList<>(VOLS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                VOLS.remove(e.getKey());
            } else {
                voler(joueur, e.getValue(), maintenant);
            }
        }
        for (Map.Entry<UUID, Envol> e : new ArrayList<>(ENVOLS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                ENVOLS.remove(e.getKey());
            } else {
                envoler(joueur, e.getValue());
            }
        }
        // le kunai en main suit ce qu'on tient (changer d'objet change ses modificateurs de main)
        for (UUID id : ELUS) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(id);
            if (joueur != null) {
                arme(joueur, true);
            }
        }
        COUPS.clear();
        for (Map.Entry<UUID, Enchainement> e : new ArrayList<>(COMBOS.entrySet())) {
            Enchainement c = e.getValue();
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                COMBOS.remove(e.getKey());
                continue;
            }
            int age = maintenant - c.debut;
            if (age >= DUREE) {
                finir(server, e.getKey(), c, false);
                continue;
            }
            Entity cible = joueur.serverLevel().getEntity(c.cible);
            tenir(joueur, c.points[DEPART]);
            if (cible instanceof LivingEntity vivant && vivant.isAlive()) {
                tenir(vivant, c.points[O]);
                while (c.coup < QUAND.length && age >= QUAND[c.coup]) {
                    frapper(joueur, vivant, NORMAL[c.coup], PERCANT[c.coup]);
                    c.coup++;
                }
            }
        }
    }

    /** Un coup du combo : la frappe normale, puis l'Éclair Jaune (les deux passent, même dans le même tick). */
    private static void frapper(ServerPlayer joueur, LivingEntity cible, float normal, float percant) {
        frappe = true;
        try {
            cible.invulnerableTime = 0;
            cible.hurt(joueur.damageSources().playerAttack(joueur), normal);
            if (percant > 0 && cible.isAlive()) {
                cible.invulnerableTime = 0;
                cible.hurt(new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(ECLAIR_JAUNE), joueur), percant);
            }
        } finally {
            frappe = false;
        }
    }

    /**
     * Pendant le combo, rien d'autre ne touche le lanceur ni sa cible. Et un coup au kunai prend son Tranchant II à la
     * place des enchantements de l'objet vraiment tenu.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        if (!frappe && !COMBOS.isEmpty() && fige(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        if (!frappe && event.getSource().is(DamageTypes.PLAYER_ATTACK) && event.getSource().getDirectEntity() instanceof ServerPlayer joueur) {
            Coup c = COUPS.remove(joueur.getUUID());
            if (c != null && c.cible() == event.getEntity().getId()) {
                event.setAmount(Math.max(0, event.getAmount() + (TRANCHANT - c.bonus()) * c.force()));
            }
        }
    }

    /** Juste avant un coup, Minato éveillé : ce que vaudra le Tranchant du kunai (la charge du coup va bientôt retomber à zéro). */
    @SubscribeEvent
    public static void attaque(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && ELUS.contains(joueur.getUUID())) {
            float base = (float) joueur.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float bonus = EnchantmentHelper.modifyDamage(joueur.serverLevel(), joueur.getWeaponItem(), event.getTarget(),
                    joueur.damageSources().playerAttack(joueur), base) - base;
            COUPS.put(joueur.getUUID(), new Coup(event.getTarget().getId(), joueur.getAttackStrengthScale(0.5F), bonus));
        }
    }

    /** Un kunai ne balaie pas, même si on tient une épée. */
    @SubscribeEvent
    public static void balayage(SweepAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && ELUS.contains(joueur.getUUID())) {
            event.setSweeping(false);
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && COMBOS.containsKey(joueur.getUUID())) {
            finir(joueur.server, joueur.getUUID(), COMBOS.get(joueur.getUUID()), true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            Enchainement c = COMBOS.get(joueur.getUUID());
            if (c != null) {
                finir(joueur.server, joueur.getUUID(), c, true);
            }
            VOLS.remove(joueur.getUUID());
            ENVOLS.remove(joueur.getUUID());
            ELUS.remove(joueur.getUUID());
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        ELUS.clear();
        VOLS.clear();
        ENVOLS.clear();
        PRET_JET.clear();
        COUPS.clear();
        COMBOS.clear();
        PRET.clear();
    }

    private Minato() {
    }
}
