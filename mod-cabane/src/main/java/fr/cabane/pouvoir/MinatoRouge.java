package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.CabaneSons;
import fr.cabane.client.pouvoir.ClientMinatoRouge;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
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
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Minato 3 (liste blanche « minato3 ») : le même Minato que Minato 2 (Éveil une fois : Minato, deux fois : le mode
 * Kurama), et en mode Kurama, la touche Ulti (J) le met une minute en MODE ROUGE (le mode Baryon : sa tenue en rouge,
 * un manteau de chakra, dix langues de chakra en 3D, deux cornes ; voir {@link ClientMinatoRouge}). Une fois par éveil.
 * Pendant la minute, ses touches changent :
 * <ul>
 * <li>clic gauche : le coup du mode Kurama (TP autour de la cible), et un double rouge frappe aussi de l'autre côté ;</li>
 * <li>clic droit : la ronde des flashs (6 kunai en cercle, il saute de l'un à l'autre en frappant, puis coup de talon du ciel) ;</li>
 * <li>accroupi + clic droit : la ligne rouge (TP en ligne droite, 25 blocs ; ceux qu'il traverse explosent juste après) ;</li>
 * <li>accroupi + clic gauche : le Rasengan éclair (TP sur ce qu'il vise, 40 blocs, et Rasengan rouge, la cible vole) ;</li>
 * <li>Technique : les doubles éclair (4 doubles rouges filent sur les 4 plus proches et frappent deux fois) ;</li>
 * <li>J : Mille Flashs, une fois par mode rouge (le sceau de 25 blocs, le temps arrêté, mille téléportations, le
 * Rasengan géant du ciel, et tous les coups qui tombent d'un coup quand le temps repart).</li>
 * </ul>
 * Chaque téléportation fait le bruit du flash de Minato ({@link CabaneSons#MINATO_FLASH}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class MinatoRouge {
    /** La minute du mode rouge, en ticks. */
    public static final int DUREE = 60 * 20;
    /** Les touches du mode : clic droit, accroupi + clic droit, accroupi + clic gauche, Technique. */
    public static final int DROIT = 0, LIGNE = 1, RASENGAN = 2, DOUBLES = 3;
    public static final int[] RECHARGES = {12 * 20, 8 * 20, 15 * 20, 20 * 20};

    /** La ronde des flashs, en ticks depuis le clic : les kunai volent, les 8 sauts, la cible soulevée, le talon. */
    public static final int RONDE_SAUT = 16, RONDE_PAS = 4, RONDE_LEVE = 48, RONDE_HAUT = 58, RONDE_TALON = 64, RONDE_FIN = 90;
    public static final int[] RONDE_ORDRE = {0, 2, 4, 0, 1, 3, 5, 1};
    public static final double RONDE_RAYON = 2.8;
    /** La ligne rouge : l'explosion des marques ; le Rasengan : la charge, l'impact. */
    public static final int LIGNE_EXPLOSION = 16, RASENGAN_TP = 10, RASENGAN_IMPACT = 18;
    /** Les doubles éclair : les deux coups, l'explosion des doubles. */
    public static final int DOUBLES_COUP1 = 5, DOUBLES_COUP2 = 12, DOUBLES_FIN = 22;
    /** Mille Flashs, en ticks : le sceau s'étend, le temps s'arrête, les flashs, le ciel, la descente, la reprise, la fin. */
    public static final int MILLE_SCEAU = 12, MILLE_ARRET = 34, MILLE_FLASH = 48, MILLE_FLASH_FIN = 116, MILLE_CIEL = 120,
            MILLE_DESCENTE = 160, MILLE_REPRISE = 170, MILLE_FIN = 250;
    public static final int MILLE_ARRETS = 30;
    public static final double MILLE_RAYON = 25, MILLE_HAUT = 32;

    /** Les effets dessinés chez chacun (voir {@link ClientMinatoRouge#effet}). */
    public static final int E_MODE = 0, E_FLASH = 1, E_DOUBLE = 2, E_RONDE = 3, E_TALON = 4, E_LIGNE = 5, E_EXPLOSE = 6, E_CHARGE = 7,
            E_IMPACT = 8, E_DOUBLES = 9, E_MILLE = 10, E_ARRET = 11;

    /** Les dégâts (avant l'équilibrage entre joueurs), au niveau des autres niveaux 3. */
    private static final float DOUBLE = 5, RONDE_COUP = 3, TALON = 6, TALON_PERCANT = 6, LIGNE_COUP = 10, LIGNE_PERCANT = 6,
            RASENGAN_COUP = 14, RASENGAN_PERCANT = 10, DOUBLES_COUP = 7, MILLE_COUP = 3, MILLE_FINAL = 10, MILLE_PERCANT = 10;
    private static final int MILLE_COUPS_MAX = 6;

    /** Qui est en mode rouge, et jusqu'à quand (tick du serveur). */
    private static final Map<UUID, Integer> FINS = new HashMap<>();
    /** Ceux qui ont déjà eu leur mode rouge pendant cet éveil, ceux qui ont lancé Mille Flashs pendant ce mode. */
    private static final Set<UUID> FAITS = new HashSet<>();
    private static final Set<UUID> ULTIS = new HashSet<>();
    private static final Map<UUID, int[]> PRETS = new HashMap<>();

    /** Une action prévue plus tard (un saut, un coup), pour un Minato. */
    private record Prevu(int quand, UUID joueur, Consumer<ServerPlayer> action) {
    }

    private static final List<Prevu> PREVUS = new ArrayList<>();

    /** Mille Flashs en cours : le centre, le monde, ceux qui sont pris (figés à leur place), les arrêts de Minato. */
    private static final class Mille {
        int debut;
        Vec3 centre;
        ServerLevel monde;
        final List<LivingEntity> pris = new ArrayList<>();
        final Map<Integer, Vec3> places = new HashMap<>();
        final Map<Integer, Boolean> ia = new HashMap<>();
        final Map<Integer, Integer> coups = new HashMap<>();
        final List<Vec3> arrets = new ArrayList<>();
    }

    private static final Map<UUID, Mille> MILLES = new HashMap<>();

    public static boolean actif(UUID joueur) {
        return FINS.containsKey(joueur);
    }

    // ================================================================ réseau

    /** Client → serveur : une touche du mode rouge (DROIT, LIGNE, RASENGAN, DOUBLES). */
    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("minato_rouge_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Serveur → tous : ce Minato est en mode rouge pour encore « reste » ticks (0 : il ne l'est plus). */
    public record Etat(UUID joueur, int reste, boolean annonce) implements CustomPacketPayload {
        public static final Type<Etat> TYPE = new Type<>(Cabane.id("minato_rouge_etat"));
        public static final StreamCodec<ByteBuf, Etat> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Etat::joueur,
                ByteBufCodecs.VAR_INT, Etat::reste, ByteBufCodecs.BOOL, Etat::annonce, Etat::new);

        @Override
        public Type<Etat> type() {
            return TYPE;
        }
    }

    /** Serveur → ceux qui sont autour : un effet à dessiner (sorte E_…), ses points et les entités concernées. */
    public record Effet(UUID joueur, int sorte, List<Vector3f> points, List<Integer> ids) implements CustomPacketPayload {
        public static final Type<Effet> TYPE = new Type<>(Cabane.id("minato_rouge_effet"));
        public static final StreamCodec<ByteBuf, Effet> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Effet::joueur,
                ByteBufCodecs.VAR_INT, Effet::sorte, ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list()), Effet::points,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Effet::ids, Effet::new);

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
                .playToClient(Etat.TYPE, Etat.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMinatoRouge.etat(p)))
                .playToClient(Effet.TYPE, Effet.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientMinatoRouge.effet(p)));
    }

    private static void effet(ServerPlayer joueur, int sorte, List<Vec3> points, List<Integer> ids) {
        List<Vector3f> pts = new ArrayList<>();
        for (Vec3 p : points) {
            pts.add(new Vector3f((float) p.x, (float) p.y, (float) p.z));
        }
        Effet e = new Effet(joueur.getUUID(), sorte, pts, ids);
        Vec3 autour = points.isEmpty() ? joueur.position() : points.get(0);
        for (ServerPlayer p : joueur.serverLevel().players()) {
            if (p.distanceToSqr(autour) < 160 * 160) {
                PacketDistributor.sendToPlayer(p, e);
            }
        }
    }

    private static void etat(ServerPlayer joueur, int reste, boolean annonce) {
        PacketDistributor.sendToAllPlayers(new Etat(joueur.getUUID(), reste, annonce));
    }

    // ================================================================ le mode rouge

    private static final ResourceLocation VITESSE = Cabane.id("minato_rouge_vitesse");
    private static final DustParticleOptions ROUGE = new DustParticleOptions(new Vector3f(1F, 0.16F, 0.08F), 1.8F);

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.RED), true);
    }

    /** La touche Ulti (J), Minato 3 : le mode rouge s'il ne l'est pas, sinon Mille Flashs. */
    public static void ulti(ServerPlayer joueur) {
        UUID id = joueur.getUUID();
        if (Minato.niveau(id) < 2) {
            message(joueur, "Il faut d'abord le mode Kurama (touche Éveil deux fois).");
            return;
        }
        if (Minato.occupe(joueur) || MILLES.containsKey(id)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (actif(id)) {
            if (ULTIS.contains(id)) {
                message(joueur, "Mille Flashs : une seule fois par mode rouge.");
                return;
            }
            mille(joueur, maintenant);
            return;
        }
        if (FAITS.contains(id)) {
            message(joueur, "Le mode rouge : une seule fois par éveil.");
            return;
        }
        FAITS.add(id);
        ULTIS.remove(id);
        FINS.put(id, maintenant + DUREE);
        PRETS.put(id, new int[4]);
        vitesse(joueur, true);
        etat(joueur, DUREE, true);
        ServerLevel monde = joueur.serverLevel();
        effet(joueur, E_MODE, List.of(joueur.position()), List.of());
        monde.playSound(null, joueur.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.4F, 0.5F);
        monde.playSound(null, joueur.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.7F, 1.6F);
        monde.playSound(null, joueur.blockPosition(), CabaneSons.MINATO_FLASH.get(), SoundSource.PLAYERS, 1.2F, 0.8F);
        monde.sendParticles(ROUGE, joueur.getX(), joueur.getY(1), joueur.getZ(), 80, 1.2, 1.2, 1.2, 0.2);
        monde.sendParticles(ParticleTypes.FLASH, joueur.getX(), joueur.getY(0.5), joueur.getZ(), 1, 0, 0, 0, 0);
    }

    private static void vitesse(ServerPlayer joueur, boolean oui) {
        AttributeInstance a = joueur.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a != null) {
            a.removeModifier(VITESSE);
            if (oui) {
                a.addTransientModifier(new AttributeModifier(VITESSE, 0.3, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        }
    }

    /** La fin du mode (la minute passée, l'éveil fini) ; endormi, il pourra le reprendre au prochain éveil. */
    static void arreter(ServerPlayer joueur, boolean endormi) {
        UUID id = joueur.getUUID();
        if (FINS.remove(id) != null) {
            vitesse(joueur, false);
            etat(joueur, 0, true);
            joueur.serverLevel().playSound(null, joueur.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1F, 0.7F);
        }
        ULTIS.remove(id);
        PRETS.remove(id);
        if (endormi) {
            FAITS.remove(id);
            Mille m = MILLES.remove(id);
            if (m != null) {
                liberer(m);
                joueur.setNoGravity(false);
            }
        }
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        UUID id = joueur.getUUID();
        if (!actif(id) || action < 0 || action > 3 || MILLES.containsKey(id) || Minato.occupe(joueur)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        int[] prets = PRETS.computeIfAbsent(id, k -> new int[4]);
        if (maintenant < prets[action]) {
            message(joueur, String.format("Pas encore… (%d s)", (prets[action] - maintenant + 19) / 20));
            return;
        }
        boolean fait = switch (action) {
            case DROIT -> ronde(joueur, maintenant);
            case LIGNE -> ligne(joueur, maintenant);
            case RASENGAN -> rasengan(joueur, maintenant);
            default -> doubles(joueur, maintenant);
        };
        prets[action] = maintenant + (fait ? RECHARGES[action] : 30);
    }

    private static void prevoir(ServerPlayer joueur, int quand, Consumer<ServerPlayer> action) {
        PREVUS.add(new Prevu(quand, joueur.getUUID(), action));
    }

    /** Ce qui vit le plus droit devant lui, à vue, à « portee » blocs au plus. */
    private static LivingEntity viser(ServerPlayer joueur, double portee) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 loin = oeil.add(joueur.getLookAngle().scale(portee));
        HitResult bloc = monde.clip(new ClipContext(oeil, loin, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        Vec3 fin = bloc.getType() == HitResult.Type.MISS ? loin : bloc.getLocation();
        EntityHitResult vise = ProjectileUtil.getEntityHitResult(monde, joueur, oeil, fin, new AABB(oeil, fin).inflate(1.5),
                e -> e instanceof LivingEntity l && Pouvoirs.cible(joueur, l) && !e.isSpectator(), 0.6F);
        return vise != null && vise.getEntity() instanceof LivingEntity l ? l : null;
    }

    /** Debout sur le sol sous p (ou juste au-dessus s'il est dans un bloc). */
    private static Vec3 sol(ServerLevel monde, Vec3 p) {
        BlockPos b = BlockPos.containing(p);
        for (int i = 0; i < 4 && !monde.getBlockState(b).getCollisionShape(monde, b).isEmpty(); i++) {
            b = b.above();
        }
        for (int i = 0; i < 8; i++) {
            BlockPos dessous = b.below();
            if (!monde.getBlockState(dessous).getCollisionShape(monde, dessous).isEmpty()) {
                return new Vec3(p.x, b.getY(), p.z);
            }
            b = dessous;
        }
        return p;
    }

    private static boolean libre(ServerPlayer joueur, Vec3 p) {
        return joueur.serverLevel().noCollision(joueur, joueur.getBoundingBox().move(p.subtract(joueur.position())));
    }

    /** Le flash d'une téléportation : le bruit du flash de Minato, des éclats rouges, et l'effet chez chacun. */
    private static void flash(ServerPlayer joueur, Vec3 p, boolean son) {
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(ROUGE, p.x, p.y + 1, p.z, 18, 0.35, 0.7, 0.35, 0.1);
        monde.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 1, p.z, 8, 0.4, 0.6, 0.4, 0.3);
        if (son) {
            monde.playSound(null, BlockPos.containing(p), CabaneSons.MINATO_FLASH.get(), SoundSource.PLAYERS, 1F, 1.05F + monde.random.nextFloat() * 0.25F);
        }
    }

    /** Il se téléporte en p, tourné vers « vers » (s'il y a la place), avec le flash au départ et à l'arrivée. */
    private static void sauter(ServerPlayer joueur, Vec3 p, Vec3 vers, boolean son) {
        if (!libre(joueur, p)) {
            return;
        }
        flash(joueur, joueur.position(), false);
        Vec3 d = vers.subtract(p.add(0, joueur.getEyeHeight(), 0));
        float y = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float x = (float) Math.toDegrees(-Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        joueur.teleportTo(joueur.serverLevel(), p.x, p.y, p.z, y, x);
        joueur.setYHeadRot(y);
        joueur.setDeltaMovement(Vec3.ZERO);
        joueur.hurtMarked = true;
        joueur.fallDistance = 0;
        flash(joueur, p, son);
    }

    private static Vec3 milieu(Entity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    private static boolean dansCoup;

    /** Un coup du mode : normal, plus une part qui traverse l'armure (l'Éclair Jaune de Minato). */
    private static void blesser(ServerPlayer joueur, LivingEntity cible, float normal, float percant) {
        if (!cible.isAlive()) {
            return;
        }
        dansCoup = true;
        try {
            cible.invulnerableTime = 0;
            cible.hurt(joueur.damageSources().playerAttack(joueur), normal);
            if (percant > 0 && cible.isAlive()) {
                cible.invulnerableTime = 0;
                cible.hurt(new DamageSource(joueur.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(Minato.ECLAIR_JAUNE), joueur), percant);
            }
        } finally {
            dansCoup = false;
        }
    }

    private static void pousser(LivingEntity e, Vec3 v) {
        e.setDeltaMovement(v);
        e.hurtMarked = true;
    }

    private static void explosion(ServerLevel monde, Vec3 p, float force) {
        monde.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, (int) (2 * force), 0.4 * force, 0.3 * force, 0.4 * force, 0);
        monde.sendParticles(ROUGE, p.x, p.y, p.z, (int) (30 * force), 0.6 * force, 0.5 * force, 0.6 * force, 0.2);
        monde.playSound(null, BlockPos.containing(p), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.6F + 0.3F * force, 1.3F);
    }

    // ---------------------------------------------------------------- le clic gauche : le double rouge

    /** Appelé quand un coup du mode Kurama vient de le téléporter autour de sa cible (voir {@link Minato}) : le double frappe aussi. */
    static void coupEclair(ServerPlayer joueur, LivingEntity cible) {
        if (!actif(joueur.getUUID())) {
            return;
        }
        prevoir(joueur, joueur.server.getTickCount() + 3, j -> {
            if (!cible.isAlive() || cible.distanceTo(j) > 10) {
                return;
            }
            Vec3 d = cible.position().subtract(j.position()).multiply(1, 0, 1);
            Vec3 ou = cible.position().add(d.lengthSqr() < 1.0E-4 ? new Vec3(1.6, 0, 0) : d.normalize().scale(1.6 + cible.getBbWidth() / 2));
            effet(j, E_DOUBLE, List.of(ou, cible.position()), List.of(cible.getId()));
            flash(j, ou, false);
            blesser(j, cible, DOUBLE, 0);
        });
    }

    // ---------------------------------------------------------------- clic droit : la ronde des flashs

    private static boolean ronde(ServerPlayer joueur, int maintenant) {
        LivingEntity cible = viser(joueur, 25);
        if (cible == null) {
            message(joueur, "Personne en vue.");
            return false;
        }
        ServerLevel monde = joueur.serverLevel();
        Vec3 c = cible.position();
        List<Vec3> kunais = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + Math.PI / 6;
            kunais.add(sol(monde, c.add(Math.sin(a) * RONDE_RAYON, 0.5, Math.cos(a) * RONDE_RAYON)));
        }
        List<Vec3> pts = new ArrayList<>();
        pts.add(c);
        pts.addAll(kunais);
        pts.add(joueur.getEyePosition().add(joueur.getLookAngle().scale(0.6)).subtract(0, 0.4, 0));
        effet(joueur, E_RONDE, pts, List.of(cible.getId()));
        joueur.swing(InteractionHand.MAIN_HAND, true);
        monde.playSound(null, joueur.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1F, 1.4F);
        for (int s = 0; s < RONDE_ORDRE.length; s++) {
            Vec3 k = kunais.get(RONDE_ORDRE[s]);
            prevoir(joueur, maintenant + RONDE_SAUT + s * RONDE_PAS, j -> {
                if (!cible.isAlive()) {
                    return;
                }
                sauter(j, k, cible.getEyePosition(), true);
                j.swing(InteractionHand.MAIN_HAND, true);
                blesser(j, cible, RONDE_COUP, 0);
                pousser(cible, Vec3.ZERO);
            });
        }
        prevoir(joueur, maintenant + RONDE_LEVE, j -> {
            if (cible.isAlive()) {
                pousser(cible, new Vec3(0, 1.05, 0));
                monde.playSound(null, cible.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1F, 1.6F);
            }
        });
        prevoir(joueur, maintenant + RONDE_HAUT, j -> {
            if (cible.isAlive()) {
                j.setNoGravity(true);
                sauter(j, cible.position().add(0, cible.getBbHeight() + 0.6, 0), cible.position(), true);
            }
        });
        prevoir(joueur, maintenant + RONDE_TALON, j -> {
            j.setNoGravity(false);
            if (!cible.isAlive()) {
                return;
            }
            j.swing(InteractionHand.MAIN_HAND, true);
            blesser(j, cible, TALON, TALON_PERCANT);
            pousser(cible, new Vec3(0, -2.4, 0));
            Vec3 bas = sol(monde, cible.position());
            effet(j, E_TALON, List.of(bas), List.of());
            explosion(monde, bas.add(0, 0.3, 0), 1.6F);
            sauter(j, sol(monde, kunais.get(1)), cible.getEyePosition(), true);
        });
        return true;
    }

    // ---------------------------------------------------------------- accroupi + clic droit : la ligne rouge

    private static boolean ligne(ServerPlayer joueur, int maintenant) {
        ServerLevel monde = joueur.serverLevel();
        Vec3 regard = joueur.getLookAngle().multiply(1, 0, 1);
        if (regard.lengthSqr() < 1.0E-4) {
            message(joueur, "Regarde devant toi.");
            return false;
        }
        Vec3 d = regard.normalize();
        Vec3 depart = joueur.position();
        Vec3 haut = depart.add(0, 0.6, 0);
        HitResult bloc = monde.clip(new ClipContext(haut, haut.add(d.scale(25)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, joueur));
        double loin = bloc.getType() == HitResult.Type.MISS ? 25 : Math.max(0, bloc.getLocation().distanceTo(haut) - 0.7);
        Vec3 fin = depart;
        for (double l = loin; l > 1; l -= 0.5) {
            Vec3 essai = sol(monde, depart.add(d.scale(l)).add(0, 0.6, 0));
            if (Math.abs(essai.y - depart.y) < 6 && libre(joueur, essai)) {
                fin = essai;
                break;
            }
        }
        if (fin.distanceToSqr(depart) < 4) {
            message(joueur, "Pas de place devant toi.");
            return false;
        }
        Vec3 a = depart.add(0, 1, 0), b = fin.add(0, 1, 0);
        List<LivingEntity> marques = new ArrayList<>();
        for (LivingEntity e : monde.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(2), e -> Pouvoirs.cible(joueur, e) && !e.isSpectator())) {
            Vec3 m = milieu(e);
            Vec3 ab = b.subtract(a);
            double u = Mth.clamp(m.subtract(a).dot(ab) / ab.lengthSqr(), 0, 1);
            if (a.add(ab.scale(u)).distanceTo(m) < 1.6 + e.getBbWidth() / 2) {
                marques.add(e);
            }
        }
        List<Vec3> pts = new ArrayList<>(List.of(depart, fin));
        List<Integer> ids = new ArrayList<>();
        for (LivingEntity e : marques) {
            pts.add(milieu(e));
            ids.add(e.getId());
        }
        effet(joueur, E_LIGNE, pts, ids);
        flash(joueur, depart, false);
        joueur.teleportTo(monde, fin.x, fin.y, fin.z, joueur.getYRot(), joueur.getXRot());
        joueur.fallDistance = 0;
        flash(joueur, fin, true);
        prevoir(joueur, maintenant + LIGNE_EXPLOSION, j -> {
            for (LivingEntity e : marques) {
                if (!e.isAlive()) {
                    continue;
                }
                blesser(j, e, LIGNE_COUP, LIGNE_PERCANT);
                pousser(e, d.scale(1.1).add(0, 0.45, 0));
                effet(j, E_EXPLOSE, List.of(milieu(e)), List.of());
                explosion(monde, milieu(e), 1F);
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- accroupi + clic gauche : le Rasengan éclair

    private static boolean rasengan(ServerPlayer joueur, int maintenant) {
        LivingEntity cible = viser(joueur, 40);
        if (cible == null) {
            message(joueur, "Personne en vue.");
            return false;
        }
        ServerLevel monde = joueur.serverLevel();
        effet(joueur, E_CHARGE, List.of(joueur.position()), List.of());
        monde.playSound(null, joueur.blockPosition(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 1.4F, 1.8F);
        prevoir(joueur, maintenant + RASENGAN_TP, j -> {
            if (!cible.isAlive() || cible.distanceTo(j) > 48) {
                return;
            }
            Vec3 cote = j.position().subtract(cible.position()).multiply(1, 0, 1);
            Vec3 devant = cible.position().add(cote.lengthSqr() < 1.0E-4 ? new Vec3(1.3, 0, 0) : cote.normalize().scale(1.3 + cible.getBbWidth() / 2));
            sauter(j, libre(j, devant) ? devant : sol(monde, devant), cible.getEyePosition(), true);
            j.swing(InteractionHand.MAIN_HAND, true);
            pousser(cible, Vec3.ZERO);
            effet(j, E_IMPACT, List.of(milieu(cible), cible.position().subtract(j.position()).multiply(1, 0, 1)), List.of(cible.getId()));
            monde.playSound(null, cible.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1F, 1.8F);
        });
        prevoir(joueur, maintenant + RASENGAN_IMPACT, j -> {
            if (!cible.isAlive()) {
                return;
            }
            Vec3 d = cible.position().subtract(j.position()).multiply(1, 0, 1);
            d = d.lengthSqr() < 1.0E-4 ? Pouvoirs.devant(j) : d.normalize();
            blesser(j, cible, RASENGAN_COUP, RASENGAN_PERCANT);
            pousser(cible, d.scale(2.6).add(0, 0.75, 0));
            effet(j, E_EXPLOSE, List.of(milieu(cible)), List.of(1));
            explosion(monde, milieu(cible), 2F);
        });
        return true;
    }

    // ---------------------------------------------------------------- Technique : les doubles éclair

    private static boolean doubles(ServerPlayer joueur, int maintenant) {
        ServerLevel monde = joueur.serverLevel();
        List<LivingEntity> proches = monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(12),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator() && e.distanceTo(joueur) < 12);
        proches.sort((x, y) -> Double.compare(x.distanceToSqr(joueur), y.distanceToSqr(joueur)));
        if (proches.isEmpty()) {
            message(joueur, "Personne autour.");
            return false;
        }
        List<LivingEntity> cibles = proches.subList(0, Math.min(4, proches.size()));
        List<Vec3> pts = new ArrayList<>(List.of(joueur.position()));
        List<Integer> ids = new ArrayList<>();
        for (LivingEntity e : cibles) {
            pts.add(e.position());
            ids.add(e.getId());
        }
        effet(joueur, E_DOUBLES, pts, ids);
        joueur.swing(InteractionHand.MAIN_HAND, true);
        flash(joueur, joueur.position(), true);
        for (int coup : new int[] {DOUBLES_COUP1, DOUBLES_COUP2}) {
            prevoir(joueur, maintenant + coup, j -> {
                for (LivingEntity e : cibles) {
                    blesser(j, e, DOUBLES_COUP, 0);
                    flash(j, e.position(), false);
                }
                monde.playSound(null, j.blockPosition(), CabaneSons.MINATO_FLASH.get(), SoundSource.PLAYERS, 0.9F, 1.3F);
            });
        }
        prevoir(joueur, maintenant + DOUBLES_FIN, j -> {
            for (LivingEntity e : cibles) {
                monde.sendParticles(ROUGE, e.getX(), e.getY(1), e.getZ(), 20, 0.5, 0.6, 0.5, 0.15);
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- J dans le mode : Mille Flashs

    private static void mille(ServerPlayer joueur, int maintenant) {
        UUID id = joueur.getUUID();
        ULTIS.add(id);
        ServerLevel monde = joueur.serverLevel();
        Mille m = new Mille();
        m.debut = maintenant;
        m.centre = joueur.position();
        m.monde = monde;
        MILLES.put(id, m);
        // le mode ne s'arrête pas au milieu de l'ulti
        FINS.computeIfPresent(id, (k, fin) -> Math.max(fin, maintenant + MILLE_FIN + 20));
        joueur.swing(InteractionHand.MAIN_HAND, true);
        effet(joueur, E_MILLE, List.of(m.centre), List.of());
        monde.playSound(null, joueur.blockPosition(), SoundEvents.TRIDENT_HIT_GROUND, SoundSource.PLAYERS, 1.4F, 0.7F);
        monde.playSound(null, joueur.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.6F, 0.6F);
    }

    /** Le temps s'arrête : tous ceux qui sont dans le sceau sont figés ; les arrêts de Minato sont tirés autour d'eux. */
    private static void arreterTemps(ServerPlayer joueur, Mille m) {
        for (LivingEntity e : m.monde.getEntitiesOfClass(LivingEntity.class, new AABB(m.centre, m.centre).inflate(MILLE_RAYON, 12, MILLE_RAYON),
                e -> Pouvoirs.cible(joueur, e) && !e.isSpectator() && e.position().distanceTo(m.centre) < MILLE_RAYON)) {
            m.pris.add(e);
            m.places.put(e.getId(), e.position());
            if (e instanceof Mob mob) {
                m.ia.put(e.getId(), mob.isNoAi());
                mob.setNoAi(true);
            }
        }
        // les arrêts : tour à tour autour de chacun (ou en cercle autour du centre s'il n'y a personne)
        for (int s = 0; s < MILLE_ARRETS; s++) {
            double a = (s * 2.39996) % (Math.PI * 2), r = 1.8 + (s * 0.37) % 1.0;
            Vec3 autour = m.pris.isEmpty() ? m.centre.add(Math.sin(a) * 8, 0, Math.cos(a) * 8) : m.places.get(m.pris.get(s % m.pris.size()).getId());
            m.arrets.add(autour.add(Math.sin(a) * r, (s % 3) * 0.7, Math.cos(a) * r));
        }
        List<Vec3> pts = new ArrayList<>(List.of(m.centre));
        List<Integer> ids = new ArrayList<>();
        for (LivingEntity e : m.pris) {
            pts.add(m.places.get(e.getId()));
            ids.add(e.getId());
        }
        pts.addAll(m.arrets);
        effet(joueur, E_ARRET, pts, ids);
        m.monde.playSound(null, BlockPos.containing(m.centre), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 2F, 0.6F);
    }

    private static void liberer(Mille m) {
        for (LivingEntity e : m.pris) {
            if (e instanceof Mob mob && m.ia.containsKey(e.getId())) {
                mob.setNoAi(m.ia.get(e.getId()));
            }
        }
    }

    private static void millePas(ServerPlayer joueur, Mille m, int age) {
        joueur.fallDistance = 0;
        if (age == MILLE_ARRET) {
            arreterTemps(joueur, m);
        }
        // figés : à leur place, sans bouger
        if (age >= MILLE_ARRET && age < MILLE_REPRISE) {
            for (LivingEntity e : m.pris) {
                Vec3 p = m.places.get(e.getId());
                if (e.isAlive() && p != null) {
                    if (e.position().distanceToSqr(p) > 0.01) {
                        if (e instanceof ServerPlayer sp) {
                            sp.teleportTo(m.monde, p.x, p.y, p.z, sp.getYRot(), sp.getXRot());
                        } else {
                            e.teleportTo(p.x, p.y, p.z);
                        }
                    }
                    e.setDeltaMovement(Vec3.ZERO);
                    e.hurtMarked = true;
                }
            }
        }
        // les mille flashs : un arrêt tous les 2 ou 3 ticks, un coup à chaque fois
        if (age >= MILLE_FLASH && age < MILLE_FLASH_FIN && !m.arrets.isEmpty()) {
            int s = (age - MILLE_FLASH) * MILLE_ARRETS / (MILLE_FLASH_FIN - MILLE_FLASH);
            int avant = (age - 1 - MILLE_FLASH) * MILLE_ARRETS / (MILLE_FLASH_FIN - MILLE_FLASH);
            if (age == MILLE_FLASH || s != avant) {
                LivingEntity cible = m.pris.isEmpty() ? null : m.pris.get(s % m.pris.size());
                Vec3 vers = cible != null ? cible.getEyePosition() : m.centre.add(0, 1, 0);
                Vec3 p = m.arrets.get(Math.min(s, m.arrets.size() - 1));
                joueur.setNoGravity(true);
                if (libre(joueur, p)) {
                    flash(joueur, joueur.position(), false);
                    Vec3 d = vers.subtract(p.add(0, joueur.getEyeHeight(), 0));
                    joueur.teleportTo(m.monde, p.x, p.y, p.z, (float) Math.toDegrees(Math.atan2(-d.x, d.z)), 10);
                    flash(joueur, p, s % 2 == 0);
                }
                joueur.swing(InteractionHand.MAIN_HAND, true);
                if (cible != null && cible.isAlive() && m.coups.merge(cible.getId(), 1, Integer::sum) <= MILLE_COUPS_MAX) {
                    blesser(joueur, cible, MILLE_COUP, 0);
                }
            }
        }
        if (age == MILLE_CIEL) {
            Vec3 haut = m.centre;
            for (double h = MILLE_HAUT; h > 4; h -= 2) {
                Vec3 essai = m.centre.add(0, h, 0);
                if (libre(joueur, essai)) {
                    haut = essai;
                    break;
                }
            }
            flash(joueur, joueur.position(), false);
            joueur.teleportTo(m.monde, haut.x, haut.y, haut.z, joueur.getYRot(), 30);
            flash(joueur, haut, true);
            m.monde.playSound(null, BlockPos.containing(haut), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 3F, 0.6F);
        }
        if (age > MILLE_CIEL && age < MILLE_DESCENTE) {
            joueur.setDeltaMovement(Vec3.ZERO);
            joueur.hurtMarked = true;
        }
        if (age == MILLE_DESCENTE) {
            Vec3 bas = sol(m.monde, m.centre);
            flash(joueur, joueur.position(), true);
            joueur.teleportTo(m.monde, bas.x, bas.y, bas.z, joueur.getYRot(), 60);
            joueur.setNoGravity(false);
            flash(joueur, bas, true);
            m.monde.playSound(null, BlockPos.containing(bas), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 2F, 0.8F);
        }
        if (age == MILLE_REPRISE) {
            liberer(m);
            for (LivingEntity e : m.pris) {
                if (!e.isAlive()) {
                    continue;
                }
                blesser(joueur, e, MILLE_FINAL, MILLE_PERCANT);
                Vec3 d = e.position().subtract(m.centre).multiply(1, 0, 1);
                d = d.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : d.normalize();
                pousser(e, d.scale(1.7).add(0, 1.0, 0));
                explosion(m.monde, milieu(e), 1.2F);
            }
            explosion(m.monde, m.centre.add(0, 1, 0), 4F);
            m.monde.playSound(null, BlockPos.containing(m.centre), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3F, 0.6F);
            m.monde.playSound(null, BlockPos.containing(m.centre), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2F, 0.7F);
        }
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Integer> e : new ArrayList<>(FINS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            if (joueur == null) {
                FINS.remove(e.getKey());
                continue;
            }
            joueur.fallDistance = 0;
            if (maintenant >= e.getValue() || Minato.niveau(e.getKey()) < 2) {
                arreter(joueur, false);
            } else if (maintenant % 20 == 0) {
                // de temps en temps, le reste du mode (pour les nouveaux venus et le panneau)
                etat(joueur, e.getValue() - maintenant, false);
            }
        }
        if (!PREVUS.isEmpty()) {
            List<Prevu> prets = new ArrayList<>();
            PREVUS.removeIf(p -> {
                if (p.quand() <= maintenant) {
                    prets.add(p);
                    return true;
                }
                return false;
            });
            for (Prevu p : prets) {
                ServerPlayer joueur = server.getPlayerList().getPlayer(p.joueur());
                if (joueur != null && joueur.isAlive()) {
                    p.action().accept(joueur);
                }
            }
        }
        for (Map.Entry<UUID, Mille> e : new ArrayList<>(MILLES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(e.getKey());
            Mille m = e.getValue();
            int age = maintenant - m.debut;
            if (joueur == null || !joueur.isAlive() || age > MILLE_FIN) {
                liberer(m);
                if (joueur != null) {
                    joueur.setNoGravity(false);
                }
                MILLES.remove(e.getKey());
                continue;
            }
            millePas(joueur, m, age);
        }
    }

    /** Pendant le temps arrêté, ceux qui sont figés ne peuvent rien toucher. */
    @SubscribeEvent
    public static void degats(LivingIncomingDamageEvent event) {
        if (dansCoup || MILLES.isEmpty() || !(event.getSource().getEntity() instanceof LivingEntity auteur)) {
            return;
        }
        for (Mille m : MILLES.values()) {
            int age = auteur.level().getServer() == null ? 0 : auteur.level().getServer().getTickCount() - m.debut;
            if (age >= MILLE_ARRET && age < MILLE_REPRISE && m.pris.contains(auteur)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** En mode rouge, il ne se fait jamais mal en tombant (il saute partout). */
    @SubscribeEvent
    public static void chute(LivingFallEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur && actif(joueur.getUUID())) {
            event.setDistance(0);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer nouveau) {
            int maintenant = nouveau.server.getTickCount();
            for (Map.Entry<UUID, Integer> e : FINS.entrySet()) {
                PacketDistributor.sendToPlayer(nouveau, new Etat(e.getKey(), e.getValue() - maintenant, false));
            }
        }
    }

    @SubscribeEvent
    public static void deconnexion(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer joueur) {
            arreter(joueur, true);
            PREVUS.removeIf(p -> p.joueur().equals(joueur.getUUID()));
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        for (Mille m : MILLES.values()) {
            liberer(m);
        }
        MILLES.clear();
        FINS.clear();
        FAITS.clear();
        ULTIS.clear();
        PRETS.clear();
        PREVUS.clear();
    }

    private MinatoRouge() {
    }
}
