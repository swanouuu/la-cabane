package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientChidori;
import fr.cabane.client.pouvoir.ClientMaudit;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * La marque maudite (listes blanches « marque » et « marque2 »). La touche Éveil la réveille : les marques se répandent
 * sur son corps, une aura violette l'entoure (dessin côté client). Au niveau 1, il va un peu plus vite, il encaisse
 * comme dans une armure de fer complète avec Protection II (l'armure vient de {@link Pouvoirs}, la protection d'ici), et
 * tout ce qu'il frappe prend 1,5 dégât de plus, quoi qu'il ait en main.
 * <p>
 * Avec « marque2 », la touche Éveil passe ensuite au niveau 2 : la peau devient claire, les cheveux gris, et deux mains
 * géantes jaillissent de son dos. Tout est doublé : la vitesse, l'armure (Protection IV), les dégâts. Accroupi au sol, un
 * grand coup d'ailes le lance en l'air ; il plane ensuite (dessin et vol côté client), et ne prend plus de dégâts de
 * chute. Le clic droit lance le Chidori sur ce qu'il vise ({@link #chidori}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Maudit {
    public static final String NOM = "marque";
    public static final String NOM2 = "marque2";

    /** Ce qui reste des dégâts : Protection II sur les quatre pièces (32 % en moins), Protection IV au niveau 2 (64 %). */
    private static final float[] PROTECTION = {1, 0.68F, 0.36F};
    private static final double[] VITESSE_BONUS = {0, 0.2, 0.4};
    private static final double[] FORCE_BONUS = {0, 1.5, 3};

    private static final ResourceLocation VITESSE = Cabane.id("marque_vitesse");
    private static final ResourceLocation FORCE = Cabane.id("marque_force");
    /** Qui est marqué, et à quel niveau. */
    private static final Map<UUID, Integer> MARQUES = new HashMap<>();

    /** La marque maudite, de l'une ou l'autre liste. */
    public static boolean est(String pouvoir) {
        return pouvoir.equals(NOM) || pouvoir.equals(NOM2);
    }

    static int niveau(ServerPlayer joueur) {
        return MARQUES.getOrDefault(joueur.getUUID(), 0);
    }

    static void eveiller(ServerPlayer joueur, int niveau) {
        MARQUES.put(joueur.getUUID(), niveau);
        modif(joueur, Attributes.MOVEMENT_SPEED, VITESSE, VITESSE_BONUS[niveau], AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modif(joueur, Attributes.ATTACK_DAMAGE, FORCE, FORCE_BONUS[niveau], AttributeModifier.Operation.ADD_VALUE);
    }

    static void endormir(ServerPlayer joueur) {
        MARQUES.remove(joueur.getUUID());
        modif(joueur, Attributes.MOVEMENT_SPEED, VITESSE, 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modif(joueur, Attributes.ATTACK_DAMAGE, FORCE, 0, AttributeModifier.Operation.ADD_VALUE);
        Scene s = SCENES.get(joueur.getUUID());
        if (s != null) {
            finir(joueur.server, joueur.getUUID(), s, true);
        }
    }

    private static void modif(ServerPlayer joueur, Holder<Attribute> attribut, ResourceLocation id, double valeur, AttributeModifier.Operation operation) {
        AttributeInstance a = joueur.getAttribute(attribut);
        if (a == null) {
            return;
        }
        a.removeModifier(id);
        if (valeur != 0) {
            a.addTransientModifier(new AttributeModifier(id, valeur, operation));
        }
    }

    // ================================================================ réseau

    /** Client → serveur : accroupi au sol, niveau 2 (le coup d'ailes). */
    public record Envol() implements CustomPacketPayload {
        public static final Type<Envol> TYPE = new Type<>(Cabane.id("marque_envol"));
        public static final StreamCodec<ByteBuf, Envol> CODEC = StreamCodec.unit(new Envol());

        @Override
        public Type<Envol> type() {
            return TYPE;
        }
    }

    /** Client → serveur : le clic droit, niveau 2 (le Chidori). */
    public record Lancer() implements CustomPacketPayload {
        public static final Type<Lancer> TYPE = new Type<>(Cabane.id("marque_chidori_lancer"));
        public static final StreamCodec<ByteBuf, Lancer> CODEC = StreamCodec.unit(new Lancer());

        @Override
        public Type<Lancer> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui le voient : il vient de donner un grand coup d'ailes. */
    public record Battement(UUID joueur) implements CustomPacketPayload {
        public static final Type<Battement> TYPE = new Type<>(Cabane.id("marque_battement"));
        public static final StreamCodec<ByteBuf, Battement> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Battement::joueur, Battement::new);

        @Override
        public Type<Battement> type() {
            return TYPE;
        }
    }

    /**
     * Serveur → ceux qui sont autour : un Chidori commence (de {@code depart}, sur la cible, qu'il frappe depuis
     * {@code impact}) ou s'arrête net ({@code arret}).
     */
    public record Chidori(UUID lanceur, int cible, Vector3f depart, Vector3f impact, Vector3f centre, boolean arret) implements CustomPacketPayload {
        public static final Type<Chidori> TYPE = new Type<>(Cabane.id("marque_chidori"));
        public static final StreamCodec<ByteBuf, Chidori> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Chidori::lanceur, ByteBufCodecs.VAR_INT, Chidori::cible, ByteBufCodecs.VECTOR3F, Chidori::depart,
                ByteBufCodecs.VECTOR3F, Chidori::impact, ByteBufCodecs.VECTOR3F, Chidori::centre, ByteBufCodecs.BOOL, Chidori::arret, Chidori::new);

        @Override
        public Type<Chidori> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(Envol.TYPE, Envol.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        envol(joueur);
                    }
                }))
                .playToServer(Lancer.TYPE, Lancer.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        chidori(joueur);
                    }
                }))
                .playToClient(Battement.TYPE, Battement.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMaudit.battement(p)))
                .playToClient(Chidori.TYPE, Chidori.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientChidori.recevoir(p)));
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

    // ================================================================ le coup d'ailes

    private static final Map<UUID, Integer> DERNIER_ENVOL = new HashMap<>();
    /** Entre deux coups d'ailes : 10 secondes. */
    public static final int RECHARGE_ENVOL = 200;

    /** Accroupi au sol, au niveau 2 : un grand coup d'ailes le lance très haut (une vingtaine de blocs) et un peu en avant. */
    private static void envol(ServerPlayer joueur) {
        int maintenant = joueur.server.getTickCount();
        if (niveau(joueur) < 2 || SCENES.containsKey(joueur.getUUID())
                || maintenant - DERNIER_ENVOL.getOrDefault(joueur.getUUID(), -RECHARGE_ENVOL) < RECHARGE_ENVOL) {
            return;
        }
        DERNIER_ENVOL.put(joueur.getUUID(), maintenant);
        Vec3 devant = Pouvoirs.devant(joueur);
        joueur.setDeltaMovement(devant.scale(0.45).add(0, 2.0, 0));
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.4F, 0.8F);
        monde.playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1F, 0.6F);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, new Battement(joueur.getUUID()));
    }

    // ================================================================ le Chidori

    /**
     * Le déroulé, en ticks : il charge le Chidori la main au sol, puis (gros plan sur son œil) un coup d'ailes le jette
     * sur sa cible ; l'impact, la sphère d'éclairs noirs qui enfle, le flash blanc. Le client joue les mêmes temps.
     */
    public static final int RUEE = 40, ARRIVEE = 50, IMPACT = 58, SPHERE = 74, FLASH = 92, FIN = 116;
    /** Entre deux Chidori : 45 secondes. */
    public static final int RECHARGE_CHIDORI = 900;
    /** Jusqu'où il peut viser. */
    private static final double PORTEE = 40;
    /**
     * Les coups, comme le combo de Minato : la part qui passe par l'armure, et celle qui la traverse (Protection
     * comprise). Sans armure : 16 (8 cœurs).
     */
    private static final int[] QUAND = {IMPACT, SPHERE};
    private static final float[] NORMAL = {6, 3};
    private static final float[] PERCANT = {4, 3};
    /** Les dégâts du Chidori (data/cabane/damage_type) : ils traversent l'armure et ses enchantements ({@link #degats}). */
    public static final ResourceKey<DamageType> CHIDORI = ResourceKey.create(Registries.DAMAGE_TYPE, Cabane.id("chidori"));

    /** Un Chidori en cours : la cible, le tick de départ, d'où il part, d'où il frappe, le prochain coup. */
    private static final class Scene {
        int cible;
        int debut;
        Vec3 depart;
        Vec3 impact;
        Vec3 centre;
        int coup;
    }

    private static final Map<UUID, Scene> SCENES = new HashMap<>();
    private static final Map<UUID, Integer> PRET = new HashMap<>();
    private static boolean frappe;

    /** Le clic droit au niveau 2 : la cible est ce qui vit le plus droit devant lui, à vue, à 40 blocs au plus. */
    private static void chidori(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (niveau(joueur) < 2 || SCENES.containsKey(id)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int reste = PRET.getOrDefault(id, 0) - maintenant;
        if (reste > 0) {
            joueur.displayClientMessage(Component.literal("Le Chidori se recharge… " + (reste + 19) / 20 + " s").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 regard = joueur.getLookAngle();
        LivingEntity cible = null;
        double meilleur = Double.MAX_VALUE;
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE), e -> Pouvoirs.cible(joueur, e)
                && !SCENES.containsKey(e.getUUID()) && SCENES.values().stream().noneMatch(s -> s.cible == e.getId()))) {
            Vec3 vers = e.getBoundingBox().getCenter().subtract(oeil);
            double d = vers.length();
            double angle = d < 1e-3 ? 1 : vers.scale(1 / d).dot(regard);
            if (d > PORTEE || angle < 0.94) {
                continue;
            }
            HitResult mur = monde.clip(new ClipContext(oeil, e.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
            if (mur.getType() != HitResult.Type.MISS) {
                continue;
            }
            // le plus dans l'axe d'abord, puis le plus près
            double score = (1 - angle) * 40 + d * 0.1;
            if (score < meilleur) {
                meilleur = score;
                cible = e;
            }
        }
        if (cible == null) {
            joueur.displayClientMessage(Component.literal("Personne à frapper devant toi.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        Scene s = new Scene();
        s.cible = cible.getId();
        s.debut = maintenant;
        s.depart = joueur.position();
        s.centre = cible.position();
        Vec3 f = new Vec3(s.centre.x - s.depart.x, 0, s.centre.z - s.depart.z);
        f = f.lengthSqr() < 1.0E-4 ? Pouvoirs.devant(joueur) : f.normalize();
        Vec3 impact = s.centre.subtract(f.scale(1.1));
        s.impact = debout(monde, impact) ? impact : s.centre;
        // il se tourne vers sa cible
        joueur.teleportTo(monde, s.depart.x, s.depart.y, s.depart.z, (float) Math.toDegrees(Math.atan2(-f.x, f.z)), 10);
        SCENES.put(id, s);
        envoyer(monde, s.centre, new Chidori(id, s.cible, v(s.depart), v(s.impact), v(s.centre), false));
    }

    private static boolean debout(ServerLevel monde, Vec3 q) {
        return monde.noCollision(new AABB(q.x - 0.3, q.y, q.z - 0.3, q.x + 0.3, q.y + 1.8, q.z + 0.3));
    }

    private static void tenir(Entity e, Vec3 ici) {
        if (e.position().distanceToSqr(ici) > 0.01) {
            e.teleportTo(ici.x, ici.y, ici.z);
        }
        e.setDeltaMovement(Vec3.ZERO);
        e.fallDistance = 0;
    }

    private static boolean fige(LivingEntity e) {
        if (SCENES.containsKey(e.getUUID())) {
            return true;
        }
        for (Scene s : SCENES.values()) {
            if (s.cible == e.getId()) {
                return true;
            }
        }
        return false;
    }

    /** La fin : la cible est soufflée loin devant lui ; ou, s'il est mort ou parti, tout s'arrête net. */
    private static void finir(MinecraftServer server, UUID id, Scene s, boolean net) {
        SCENES.remove(id);
        PRET.put(id, server.getTickCount() + RECHARGE_CHIDORI);
        ServerPlayer joueur = server.getPlayerList().getPlayer(id);
        if (joueur == null) {
            return;
        }
        ServerLevel monde = joueur.serverLevel();
        if (net) {
            envoyer(monde, s.centre, new Chidori(id, s.cible, v(s.depart), v(s.impact), v(s.centre), true));
            return;
        }
        if (monde.getEntity(s.cible) instanceof LivingEntity cible && cible.isAlive()) {
            Vec3 f = s.centre.subtract(s.impact);
            f = new Vec3(f.x, 0, f.z).normalize();
            cible.setDeltaMovement(f.scale(1.6).add(0, 0.55, 0));
            cible.hurtMarked = true;
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Scene> e : new ArrayList<>(SCENES.entrySet())) {
            Scene s = e.getValue();
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                SCENES.remove(e.getKey());
                continue;
            }
            int age = maintenant - s.debut;
            if (age >= FLASH) {
                finir(server, e.getKey(), s, false);
                continue;
            }
            ServerLevel monde = joueur.serverLevel();
            // la ruée : il file de son départ jusqu'à la cible
            float k = age <= RUEE ? 0 : age >= ARRIVEE ? 1 : (age - RUEE) / (float) (ARRIVEE - RUEE);
            tenir(joueur, s.depart.lerp(s.impact, k * k));
            Entity cible = monde.getEntity(s.cible);
            if (cible instanceof LivingEntity vivant && vivant.isAlive()) {
                tenir(vivant, s.centre);
                while (s.coup < QUAND.length && age >= QUAND[s.coup]) {
                    frapper(joueur, vivant, NORMAL[s.coup], PERCANT[s.coup]);
                    s.coup++;
                }
            }
            if (age == IMPACT) {
                monde.playSound(null, s.centre.x, s.centre.y + 1, s.centre.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 2F, 0.6F);
                monde.playSound(null, s.centre.x, s.centre.y + 1, s.centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2F, 0.7F);
            }
            if (age == SPHERE) {
                monde.playSound(null, s.centre.x, s.centre.y + 1, s.centre.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.6F, 0.8F);
            }
        }
    }

    /** Un coup du Chidori : la frappe normale, puis les éclairs qui traversent l'armure (les deux passent dans le même tick). */
    private static void frapper(ServerPlayer joueur, LivingEntity cible, float normal, float percant) {
        frappe = true;
        try {
            cible.invulnerableTime = 0;
            cible.hurt(joueur.damageSources().playerAttack(joueur), normal);
            if (cible.isAlive()) {
                cible.invulnerableTime = 0;
                cible.hurt(new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(CHIDORI), joueur), percant);
            }
        } finally {
            frappe = false;
        }
    }

    // ================================================================ les dégâts

    /**
     * La protection : comme les enchantements, elle ne joue pas contre ce qui les ignore (la faim, le vide…). Au niveau
     * 2, plus de dégâts de chute. Pendant un Chidori, rien d'autre ne touche le lanceur ni sa cible ; et les éclairs du
     * Chidori traversent l'armure comme ses enchantements.
     */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        if (!frappe && !SCENES.isEmpty() && fige(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        if (event.getSource().is(CHIDORI)) {
            event.addReductionModifier(DamageContainer.Reduction.ARMOR, (c, r) -> 0);
            event.addReductionModifier(DamageContainer.Reduction.ENCHANTMENTS, (c, r) -> 0);
        }
        if (!(event.getEntity() instanceof ServerPlayer joueur)) {
            return;
        }
        int niveau = niveau(joueur);
        if (niveau >= 2 && event.getSource().is(DamageTypeTags.IS_FALL)) {
            event.setCanceled(true);
            return;
        }
        if (niveau > 0 && !event.getSource().is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            event.setAmount(event.getAmount() * PROTECTION[niveau]);
        }
    }

    @SubscribeEvent
    public static void mort(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && SCENES.containsKey(joueur.getUUID())) {
            finir(joueur.server, joueur.getUUID(), SCENES.get(joueur.getUUID()), true);
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            Scene s = SCENES.get(joueur.getUUID());
            if (s != null) {
                finir(joueur.server, joueur.getUUID(), s, true);
            }
            DERNIER_ENVOL.remove(joueur.getUUID());
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        MARQUES.clear();
        SCENES.clear();
        PRET.clear();
        DERNIER_ENVOL.clear();
    }

    private Maudit() {
    }
}
