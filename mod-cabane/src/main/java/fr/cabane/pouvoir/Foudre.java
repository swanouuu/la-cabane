package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import fr.cabane.client.pouvoir.ClientFoudre;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Le pouvoir Foudre, d'après les maquettes de docs/foudre. Deux listes blanches : « foudre » (le niveau 1 seulement) et
 * « foudre2 » (la touche Éveil passe au niveau 1, puis au niveau 2). Éveillé, la foudre lui tombe dessus et des éclairs
 * courent sur lui (voir {@link ClientFoudre}) ; il va plus vite et il est protégé comme par du fer (deux fois au niveau 2).
 * <ul>
 * <li>clic gauche : toute arme frappe plus fort et électrise (un peu plus de dégâts, la cible ralentit) ;</li>
 * <li>clic droit, niveau 1 : un éclair en chaîne, qui saute de sa cible sur deux autres ;</li>
 * <li>clic droit maintenu, niveau 2 : le Kirin. Il lève la main et crie, un gros nuage noir se forme au-dessus de l'endroit
 * visé (marqué au sol de petits nuages blancs), la tête du Kirin en sort et rugit. Plus il garde la main levée (une barre se
 * remplit, 4 s), plus le Kirin fera mal ; mais il ne bouge pas, et le moindre coup reçu annule tout. Il lâche : la main
 * baisse, le Kirin plonge d'un coup, un dôme de lumière ;</li>
 * <li>accroupi : le pas de l'éclair. Il file là où il regarde (25 blocs, 50 au niveau 2), sans dégâts de chute, et la
 * foudre tombe là où il arrive.</li>
 * </ul>
 * La foudre ne met pas le feu et ne casse rien : tout ce qu'on voit est dessiné.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Foudre {
    public static final String NOM = "foudre";
    /** Avec le niveau 2 (l'aura bleue, le Kirin). */
    public static final String NOM2 = "foudre2";

    public static final int RECHARGE_CHAINE = 8 * 20;
    public static final int RECHARGE_PAS = 15 * 20;
    public static final int RECHARGE_PAS_2 = 10 * 20;
    public static final int RECHARGE_KIRIN = 30 * 20;
    /** Un Kirin annulé (un coup reçu pendant la charge) : on peut recommencer plus vite. */
    public static final int RECHARGE_ANNULE = 8 * 20;
    /** La barre du Kirin se remplit en 4 s ; au bout de 6 s, il lâche tout seul. */
    public static final int CHARGE_KIRIN = 4 * 20;
    private static final int CHARGE_MAX = 6 * 20;
    /** Après le lâcher (en ticks) : la main a fini de baisser et le Kirin plonge ; puis il touche le sol. */
    public static final int PLONGEE = 10;
    public static final int IMPACT = 16;

    private static final double PORTEE_CHAINE = 16;
    private static final double SAUT_CHAINE = 6;
    private static final float DEGATS_CHAINE = 6;
    private static final float DEGATS_SAUT = 4;
    private static final double PORTEE_PAS = 25;
    private static final double PORTEE_PAS_2 = 50;
    private static final double RAYON_PAS = 3;
    private static final double RAYON_PAS_2 = 4;
    private static final float DEGATS_PAS = 6;
    private static final float DEGATS_PAS_2 = 8;
    private static final float BONUS = 2;
    private static final float CHOC = 1;
    private static final float BONUS_2 = 4;
    private static final float CHOC_2 = 2;
    private static final double PORTEE_KIRIN = 40;
    public static final double RAYON_KIRIN = 7;
    /** Les dégâts du Kirin au centre : de 6 (lâché tout de suite) à 16 (la barre pleine) ; la moitié au bord. */
    private static final float KIRIN_MIN = 6;
    private static final float KIRIN_MAX = 16;
    private static final double[] VITESSE = {0, 0.2, 0.4};

    private static final ResourceKey<DamageType> FOUDRE = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("foudre"));
    private static final ResourceKey<DamageType> KIRIN = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("kirin"));
    private static final ResourceLocation VITE = Cabane.id("foudre_vitesse");

    public static boolean est(String pouvoir) {
        return NOM.equals(pouvoir) || NOM2.equals(pouvoir);
    }

    /** Qui est éveillé (et à quel niveau), quand son clic droit et son pas seront prêts (ticks du serveur). */
    private static final Map<UUID, Integer> EVEILLES = new HashMap<>();
    private static final Map<UUID, Integer> PRET_DROIT = new HashMap<>();
    private static final Map<UUID, Integer> PRET_PAS = new HashMap<>();
    /** Ceux qui viennent de filer : pas de dégâts de chute avant d'avoir touché le sol (et le tick du départ). */
    private static final Map<UUID, Integer> SANS_CHUTE = new HashMap<>();

    /** Un Kirin qui se charge : où il tombera, depuis quand, et où le lanceur doit rester. */
    private record Charge(ServerLevel monde, Vec3 zone, int debut, Vec3 pos) {
    }

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();

    /** Un Kirin lâché, qui va tomber : qui, où, combien chargé, et quand il a été lâché. */
    private record Chute(UUID lanceur, ServerLevel monde, Vec3 zone, float charge, int lache) {
    }

    private static final List<Chute> CHUTES = new ArrayList<>();
    /** Le temps de nos propres coups : la frappe de l'arme ne doit pas les compter deux fois. */
    private static boolean dansPouvoir;

    // ================================================================ réseau

    /** Client → serveur : clic droit, niveau 1 (l'éclair en chaîne). */
    public record Tir() implements CustomPacketPayload {
        public static final Type<Tir> TYPE = new Type<>(Cabane.id("foudre_tir"));
        public static final StreamCodec<ByteBuf, Tir> CODEC = StreamCodec.unit(new Tir());

        @Override
        public Type<Tir> type() {
            return TYPE;
        }
    }

    /** Client → serveur : clic droit, niveau 2 : il lève la main (tenu) ou la baisse (lâché). */
    public record Kirin(boolean tenu) implements CustomPacketPayload {
        public static final Type<Kirin> TYPE = new Type<>(Cabane.id("foudre_kirin"));
        public static final StreamCodec<ByteBuf, Kirin> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Kirin::tenu, Kirin::new);

        @Override
        public Type<Kirin> type() {
            return TYPE;
        }
    }

    /** Client → serveur : on vient de s'accroupir (le pas de l'éclair). */
    public record Pas() implements CustomPacketPayload {
        public static final Type<Pas> TYPE = new Type<>(Cabane.id("foudre_pas"));
        public static final StreamCodec<ByteBuf, Pas> CODEC = StreamCodec.unit(new Pas());

        @Override
        public Type<Pas> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un éclair en chaîne, de sa main à la première cible, puis aux suivantes. */
    public record Chaine(UUID joueur, List<Vector3f> points) implements CustomPacketPayload {
        public static final Type<Chaine> TYPE = new Type<>(Cabane.id("foudre_chaine"));
        public static final StreamCodec<ByteBuf, Chaine> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Chaine::joueur, ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), Chaine::points, Chaine::new);

        @Override
        public Type<Chaine> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : il a filé de « de » à « a », et la foudre tombe là (plus grosse au niveau 2). */
    public record Saut(UUID joueur, Vector3f de, Vector3f a, boolean fort) implements CustomPacketPayload {
        public static final Type<Saut> TYPE = new Type<>(Cabane.id("foudre_saut"));
        public static final StreamCodec<ByteBuf, Saut> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Saut::joueur, ByteBufCodecs.VECTOR3F, Saut::de, ByteBufCodecs.VECTOR3F, Saut::a, ByteBufCodecs.BOOL, Saut::fort, Saut::new);

        @Override
        public Type<Saut> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui la voient : cette entité vient de prendre un choc électrique (plus fort au niveau 2). */
    public record Choc(int entite, boolean fort) implements CustomPacketPayload {
        public static final Type<Choc> TYPE = new Type<>(Cabane.id("foudre_choc"));
        public static final StreamCodec<ByteBuf, Choc> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Choc::entite, ByteBufCodecs.BOOL, Choc::fort, Choc::new);

        @Override
        public Type<Choc> type() {
            return TYPE;
        }
    }

    public static final int CHARGE = 0;
    public static final int ANNULE = 1;
    public static final int LACHE = 2;

    /**
     * Serveur → ceux qui sont autour : le Kirin de ce joueur commence à se charger au-dessus de « zone » (CHARGE), est
     * annulé (ANNULE), ou est lâché, chargé à {@code charge} (LACHE : il plonge PLONGEE ticks après, touche à IMPACT).
     */
    public record EtatKirin(UUID joueur, int phase, Vector3f zone, float charge) implements CustomPacketPayload {
        public static final Type<EtatKirin> TYPE = new Type<>(Cabane.id("foudre_etat_kirin"));
        public static final StreamCodec<ByteBuf, EtatKirin> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, EtatKirin::joueur, ByteBufCodecs.VAR_INT, EtatKirin::phase, ByteBufCodecs.VECTOR3F, EtatKirin::zone,
                ByteBufCodecs.FLOAT, EtatKirin::charge, EtatKirin::new);

        @Override
        public Type<EtatKirin> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Tir.TYPE, Tir.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        chaine(joueur);
                    }
                }))
                .playToServer(Kirin.TYPE, Kirin.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        if (p.tenu()) {
                            leverLaMain(joueur);
                        } else {
                            lacher(joueur);
                        }
                    }
                }))
                .playToServer(Pas.TYPE, Pas.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        pas(joueur);
                    }
                }))
                .playToClient(Chaine.TYPE, Chaine.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFoudre.chaine(p)))
                .playToClient(Saut.TYPE, Saut.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFoudre.saut(p)))
                .playToClient(Choc.TYPE, Choc.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFoudre.choc(p)))
                .playToClient(EtatKirin.TYPE, EtatKirin.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientFoudre.kirin(p)));
    }

    /** À tous ceux qui sont à moins de 128 blocs de l'un de ces points. */
    private static void envoyer(ServerLevel monde, CustomPacketPayload paquet, Vec3... autour) {
        for (ServerPlayer p : monde.players()) {
            for (Vec3 a : autour) {
                if (p.distanceToSqr(a) < 128 * 128) {
                    PacketDistributor.sendToPlayer(p, paquet);
                    break;
                }
            }
        }
    }

    private static Vector3f v(Vec3 p) {
        return new Vector3f((float) p.x, (float) p.y, (float) p.z);
    }

    // ================================================================ s'éveiller, s'endormir

    /** Il s'éveille (1) ou passe au niveau 2 : la foudre lui tombe dessus (ClientFoudre la dessine), il va plus vite. */
    static void eveiller(ServerPlayer joueur, int niveau) {
        EVEILLES.put(joueur.getUUID(), niveau);
        vitesse(joueur, niveau);
        // le coup de tonnerre vient plus tard, au moment où la foudre tombe (ClientTransfoFoudre) ; ici, ça se charge
        son(joueur.serverLevel(), joueur.position(), SoundEvents.BEACON_ACTIVATE, 0.9F, 1.8F);
    }

    static void endormir(ServerPlayer joueur) {
        if (EVEILLES.remove(joueur.getUUID()) != null && joueur.isAlive()) {
            son(joueur.serverLevel(), joueur.position(), SoundEvents.BEACON_DEACTIVATE, 0.8F, 1.6F);
        }
        annuler(joueur, false);
        vitesse(joueur, 0);
        // le repos (1 ou 2 minutes) dure plus que les recharges : au réveil, tout est prêt
        PRET_DROIT.remove(joueur.getUUID());
        PRET_PAS.remove(joueur.getUUID());
    }

    private static void vitesse(ServerPlayer joueur, int niveau) {
        AttributeInstance a = joueur.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) {
            return;
        }
        a.removeModifier(VITE);
        if (niveau > 0) {
            a.addTransientModifier(new AttributeModifier(VITE, VITESSE[Math.min(2, niveau)], AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static boolean eveille(ServerPlayer joueur) {
        return EVEILLES.containsKey(joueur.getUUID()) && est(Pouvoirs.pouvoir(joueur));
    }

    /** Son niveau en ce moment : 0 endormi, 1, 2 (l'aura bleue, le Kirin). */
    private static int niveau(ServerPlayer joueur) {
        return eveille(joueur) ? EVEILLES.get(joueur.getUUID()) : 0;
    }

    private static void son(ServerLevel monde, Vec3 ou, SoundEvent son, float volume, float hauteur) {
        monde.playSound(null, ou.x, ou.y, ou.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static DamageSource source(ServerPlayer joueur, ResourceKey<DamageType> type) {
        return new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), joueur);
    }

    private static void blesser(LivingEntity cible, DamageSource source, float degats) {
        dansPouvoir = true;
        try {
            cible.hurt(source, degats);
        } finally {
            dansPouvoir = false;
        }
    }

    /** Sa main droite, bras tendu devant. */
    private static Vec3 main(ServerPlayer joueur) {
        Vec3 regard = joueur.getLookAngle();
        Vec3 droite = new Vec3(-regard.z, 0, regard.x).normalize();
        return joueur.getEyePosition().add(regard.scale(0.55)).add(droite.scale(0.34)).add(0, -0.4, 0);
    }

    private static boolean pret(Map<UUID, Integer> recharges, ServerPlayer joueur) {
        return joueur.server.getTickCount() >= recharges.getOrDefault(joueur.getUUID(), 0);
    }

    // ================================================================ clic droit, niveau 1 : l'éclair en chaîne

    private static void chaine(ServerPlayer joueur) {
        if (niveau(joueur) != 1 || !pret(PRET_DROIT, joueur)) {
            return;
        }
        PRET_DROIT.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE_CHAINE);
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(PORTEE_CHAINE));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 fin = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, fin, new AABB(oeil, fin).inflate(1),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !l.isSpectator(), 0.8F);
        List<Vector3f> points = new ArrayList<>();
        points.add(v(main(joueur)));
        son(monde, joueur.position(), CabaneSons.FOUDRE_ZAP.get(), 1.2F, 0.8F);
        son(monde, joueur.position(), SoundEvents.LIGHTNING_BOLT_IMPACT, 0.6F, 1.8F);
        if (touche == null || !(touche.getEntity() instanceof LivingEntity premiere)) {
            points.add(v(fin));
            envoyer(monde, new Chaine(joueur.getUUID(), points), joueur.position());
            return;
        }
        // il saute de cible en cible : chaque fois sur la plus proche qui n'a pas encore été touchée (deux sauts)
        Set<Integer> touches = new HashSet<>();
        LivingEntity cible = premiere;
        for (int saut = 0; saut < 3 && cible != null; saut++) {
            touches.add(cible.getId());
            points.add(v(cible.position().add(0, cible.getBbHeight() * 0.55, 0)));
            blesser(cible, source(joueur, FOUDRE), saut == 0 ? DEGATS_CHAINE : DEGATS_SAUT);
            cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
            son(monde, cible.position(), CabaneSons.FOUDRE_ZAP.get(), 0.9F, 1.2F);
            LivingEntity d = cible;
            cible = monde.getEntitiesOfClass(LivingEntity.class, d.getBoundingBox().inflate(SAUT_CHAINE),
                            e -> !touches.contains(e.getId()) && Pouvoirs.cible(joueur, e) && !e.isSpectator() && e.distanceTo(d) <= SAUT_CHAINE)
                    .stream().min((a, b) -> Double.compare(a.distanceToSqr(d), b.distanceToSqr(d))).orElse(null);
        }
        envoyer(monde, new Chaine(joueur.getUUID(), points), joueur.position(), premiere.position());
    }

    // ================================================================ clic droit maintenu, niveau 2 : le Kirin

    /** Il lève la main : le Kirin commence à se charger au-dessus de là où il vise (40 blocs au plus, posé au sol). */
    private static void leverLaMain(ServerPlayer joueur) {
        if (niveau(joueur) < 2 || CHARGES.containsKey(joueur.getUUID()) || !pret(PRET_DROIT, joueur)) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(PORTEE_KIRIN));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 vise = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        EntityHitResult touche = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, vise, new AABB(oeil, vise).inflate(1),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !l.isSpectator(), 1F);
        if (touche != null) {
            vise = touche.getEntity().position();
        }
        // posé au sol (s'il vise le ciel, le Kirin tombe à 40 blocs devant, là où est le sol)
        HitResult sol = monde.clip(new ClipContext(vise.add(0, 0.5, 0), vise.add(0, -60, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, joueur));
        Vec3 zone = sol.getType() == HitResult.Type.MISS ? vise : sol.getLocation();
        CHARGES.put(joueur.getUUID(), new Charge(monde, zone, joueur.server.getTickCount(), joueur.position()));
        envoyer(monde, new EtatKirin(joueur.getUUID(), CHARGE, v(zone), 0), joueur.position(), zone);
        // il crie « Kirin » (sa voix, dans le pack privé) ; l'orage gronde au loin
        monde.playSound(null, joueur.getX(), joueur.getEyeY(), joueur.getZ(), CabaneSons.FOUDRE_KIRIN.get(), SoundSource.PLAYERS, 2F, 1F);
        monde.playSound(null, zone.x, zone.y + 20, zone.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2F, 0.55F);
    }

    /** De 0 à 1 : la barre du Kirin. */
    private static float charge(Charge c, int maintenant) {
        return Math.min(1F, (maintenant - c.debut()) / (float) CHARGE_KIRIN);
    }

    /** Il baisse la main : le Kirin plongera, d'autant plus fort que la barre était pleine. */
    private static void lacher(ServerPlayer joueur) {
        Charge c = CHARGES.remove(joueur.getUUID());
        if (c == null) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        float charge = charge(c, maintenant);
        PRET_DROIT.put(joueur.getUUID(), maintenant + RECHARGE_KIRIN);
        CHUTES.add(new Chute(joueur.getUUID(), c.monde(), c.zone(), charge, maintenant));
        envoyer(c.monde(), new EtatKirin(joueur.getUUID(), LACHE, v(c.zone()), charge), joueur.position(), c.zone());
    }

    /** Un coup reçu (ou il s'endort) pendant la charge : le nuage se défait, le Kirin ne tombera pas. */
    private static void annuler(ServerPlayer joueur, boolean coup) {
        Charge c = CHARGES.remove(joueur.getUUID());
        if (c == null) {
            return;
        }
        if (coup) {
            PRET_DROIT.put(joueur.getUUID(), joueur.server.getTickCount() + RECHARGE_ANNULE);
        }
        envoyer(c.monde(), new EtatKirin(joueur.getUUID(), ANNULE, v(c.zone()), 0), joueur.position(), c.zone());
        son(c.monde(), joueur.position(), SoundEvents.BEACON_DEACTIVATE, 1.2F, 0.7F);
        son(c.monde(), c.zone().add(0, 20, 0), SoundEvents.LIGHTNING_BOLT_THUNDER, 1F, 1.6F);
    }

    /** Les Kirins qui tombent : le rugissement quand il plonge, puis l'impact (le tonnerre, les dégâts). */
    private static void chutes(MinecraftServer server, int maintenant) {
        CHUTES.removeIf(c -> {
            int t = maintenant - c.lache();
            if (t == PLONGEE) {
                // le même rugissement que Hachibi, très fort : tout le monde l'entend venir
                son(c.monde(), c.zone().add(0, 18, 0), SoundEvents.RAVAGER_ROAR, 4F, 0.7F);
                son(c.monde(), c.zone().add(0, 18, 0), SoundEvents.ENDER_DRAGON_GROWL, 3F, 1.2F);
            }
            if (t < IMPACT) {
                return false;
            }
            son(c.monde(), c.zone(), SoundEvents.LIGHTNING_BOLT_THUNDER, 6F, 0.6F);
            son(c.monde(), c.zone(), SoundEvents.LIGHTNING_BOLT_IMPACT, 4F, 0.6F);
            son(c.monde(), c.zone(), SoundEvents.GENERIC_EXPLODE.value(), 2F, 0.6F);
            ServerPlayer lanceur = server.getPlayerList().getPlayer(c.lanceur());
            if (lanceur == null || lanceur.level() != c.monde()) {
                return true;
            }
            float centre = KIRIN_MIN + (KIRIN_MAX - KIRIN_MIN) * c.charge();
            for (LivingEntity e : c.monde().getEntitiesOfClass(LivingEntity.class, new AABB(c.zone(), c.zone()).inflate(RAYON_KIRIN, 6, RAYON_KIRIN),
                    e -> Pouvoirs.cible(lanceur, e) && !e.isSpectator())) {
                Vec3 loin = e.position().subtract(c.zone());
                double d = loin.horizontalDistance();
                if (d > RAYON_KIRIN) {
                    continue;
                }
                blesser(e, source(lanceur, KIRIN), centre * (float) (1 - 0.5 * d / RAYON_KIRIN));
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                Vec3 dehors = d < 0.1 ? Vec3.ZERO : new Vec3(loin.x, 0, loin.z).normalize();
                e.setDeltaMovement(dehors.x * 0.9, 0.55, dehors.z * 0.9);
                e.hurtMarked = true;
            }
            return true;
        });
    }

    // ================================================================ accroupi : le pas de l'éclair

    private static void pas(ServerPlayer joueur) {
        int niveau = niveau(joueur);
        if (niveau == 0 || CHARGES.containsKey(joueur.getUUID()) || !pret(PRET_PAS, joueur)) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        boolean fort = niveau >= 2;
        Vec3 oeil = joueur.getEyePosition(), regard = joueur.getLookAngle();
        Vec3 loin = oeil.add(regard.scale(fort ? PORTEE_PAS_2 : PORTEE_PAS));
        BlockHitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 depart = joueur.position();
        Vec3 pieds;
        if (bloc.getType() == HitResult.Type.MISS) {
            pieds = loin.subtract(0, joueur.getEyeHeight(), 0);
        } else if (bloc.getDirection() == Direction.UP) {
            pieds = bloc.getLocation();
        } else {
            // contre un mur : il s'arrête juste devant, à hauteur du point visé
            pieds = bloc.getLocation().subtract(regard.scale(0.6)).subtract(0, joueur.getBbHeight() * 0.5, 0);
        }
        // une place libre : un bloc plus haut (une marche), sinon on recule le long du regard
        AABB boite = joueur.getBoundingBox();
        for (int i = 0; i < 100; i++) {
            if (monde.noCollision(joueur, boite.move(pieds.subtract(depart)))) {
                break;
            }
            if (monde.noCollision(joueur, boite.move(pieds.add(0, 1, 0).subtract(depart)))) {
                pieds = pieds.add(0, 1, 0);
                break;
            }
            pieds = pieds.subtract(regard.scale(0.5));
            if (pieds.distanceToSqr(depart) < 1) {
                return;
            }
        }
        if (pieds.distanceToSqr(depart) < 4) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        PRET_PAS.put(joueur.getUUID(), maintenant + (fort ? RECHARGE_PAS_2 : RECHARGE_PAS));
        // le bruit des téléportations de Minato, avec un éclair en plus
        son(monde, depart, CabaneSons.MINATO_FLASH.get(), 1F, 1F);
        joueur.stopRiding();
        joueur.teleportTo(pieds.x, pieds.y, pieds.z);
        joueur.setDeltaMovement(Vec3.ZERO);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        SANS_CHUTE.put(joueur.getUUID(), maintenant);
        envoyer(monde, new Saut(joueur.getUUID(), v(depart), v(pieds), fort), depart, pieds);
        son(monde, pieds, CabaneSons.MINATO_FLASH.get(), 1F, 1F);
        son(monde, pieds, SoundEvents.LIGHTNING_BOLT_IMPACT, fort ? 2.2F : 1.6F, 0.9F);
        son(monde, pieds, SoundEvents.LIGHTNING_BOLT_THUNDER, fort ? 1.6F : 1F, 1.4F);
        // la foudre qui tombe là où il arrive
        double rayon = fort ? RAYON_PAS_2 : RAYON_PAS;
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(rayon, 2, rayon),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 loinDeLui = e.position().subtract(pieds);
            if (loinDeLui.horizontalDistance() > rayon) {
                continue;
            }
            blesser(e, source(joueur, FOUDRE), fort ? DEGATS_PAS_2 : DEGATS_PAS);
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, fort ? 60 : 40, fort ? 1 : 0));
            Vec3 dehors = loinDeLui.horizontalDistanceSqr() < 0.01 ? Vec3.ZERO : new Vec3(loinDeLui.x, 0, loinDeLui.z).normalize();
            e.setDeltaMovement(dehors.x * 0.5, 0.3, dehors.z * 0.5);
            e.hurtMarked = true;
        }
    }

    // ================================================================ ce qui dure

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        // pendant la charge, il ne bouge pas (il peut regarder autour de lui) ; au bout de 6 s, il lâche tout seul
        for (Map.Entry<UUID, Charge> e : new ArrayList<>(CHARGES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            Charge c = e.getValue();
            if (joueur == null || !joueur.isAlive() || joueur.level() != c.monde() || niveau(joueur) < 2) {
                CHARGES.remove(e.getKey());
                continue;
            }
            if (maintenant - c.debut() >= CHARGE_MAX) {
                lacher(joueur);
                continue;
            }
            Vec3 p = c.pos();
            if (joueur.position().distanceToSqr(p) > 0.01) {
                joueur.teleportTo(p.x, Math.min(p.y, joueur.getY()), p.z);
            }
            joueur.setDeltaMovement(0, Math.min(0, joueur.getDeltaMovement().y), 0);
            joueur.hurtMarked = true;
        }
        chutes(server, maintenant);
        // une fois retombé au sol après un pas, les chutes comptent de nouveau
        SANS_CHUTE.entrySet().removeIf(e -> {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            return joueur == null || maintenant - e.getValue() > 10 * 20 || maintenant - e.getValue() > 3 && joueur.onGround();
        });
    }

    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && SANS_CHUTE.containsKey(joueur.getUUID())) {
            event.setCanceled(true);
        }
    }

    /**
     * Le clic gauche, éveillé : n'importe quelle arme frappe plus fort, et un choc électrique ajoute un peu de dégâts et
     * ralentit la cible.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (dansPouvoir || !(source.getEntity() instanceof ServerPlayer joueur) || source.getDirectEntity() != joueur
                || !source.is(DamageTypes.PLAYER_ATTACK) || !eveille(joueur)) {
            return;
        }
        LivingEntity cible = event.getEntity();
        boolean fort = niveau(joueur) >= 2;
        event.setAmount(event.getAmount() + (fort ? BONUS_2 + CHOC_2 : BONUS + CHOC));
        cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, fort ? 60 : 40, fort ? 2 : 0));
        ServerLevel monde = joueur.serverLevel();
        son(monde, cible.position(), CabaneSons.FOUDRE_ZAP.get(), fort ? 1.1F : 0.8F, fort ? 0.8F : 1.1F);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(cible, new Choc(cible.getId(), fort));
    }

    /** Un coup reçu pendant la charge du Kirin : tout s'annule. */
    @SubscribeEvent
    public static void touche(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer joueur && event.getNewDamage() > 0 && CHARGES.containsKey(joueur.getUUID())) {
            annuler(joueur, true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        EVEILLES.remove(id);
        CHARGES.remove(id);
        SANS_CHUTE.remove(id);
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        EVEILLES.clear();
        PRET_DROIT.clear();
        PRET_PAS.clear();
        CHARGES.clear();
        CHUTES.clear();
        SANS_CHUTE.clear();
    }

    private Foudre() {
    }
}
