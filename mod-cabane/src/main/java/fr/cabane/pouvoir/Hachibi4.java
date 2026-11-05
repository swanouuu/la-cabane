package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.hachibi4.ClientHachibi4;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
 * Hachibi 4 (liste blanche « hachibi4 », docs/hachibi4) : tout Hachibi 2 (l'Éveil passe par les quatre formes), plus un
 * ulti, sur sa touche à lui (J par défaut), aux huit tentacules (niveau 4). Le chakra rouge monte et se referme en une
 * boule autour de lui, qui gonfle jusqu'à tout avaler, se fissure et éclate (aucun dégât : du chakra) : il est devenu le
 * Hachibi entier, le modèle du géant aux 2/3 (30 blocs de corps, 51 avec les queues), pendant 45 secondes. Une seule
 * fois par éveil, puis 5 minutes de recharge, comme les ultimes des fruits ; l'éveil ne s'endort pas au milieu.
 * <ul>
 * <li>clic gauche : ses poings s'abattent devant lui, droite, gauche, droite… ; chaque coup fait une onde ;</li>
 * <li>clic droit : la Bijuu Dama, une énorme boule noire qu'il charge dans sa gueule puis tire là où il regarde, et qui
 * explose en dôme (toutes les 10 secondes) ;</li>
 * <li>accroupi (un appui court) : il lève ses huit queues et les abat devant lui, en éventail, sur 60 blocs ;</li>
 * <li>accroupi + clic droit : il crache de l'encre devant lui (aveugle et ralentit).</li>
 * </ul>
 * Il reste un joueur de taille normale, au creux de la bête, et il brille (on voit où viser à travers elle). Ce qu'on
 * voit est dessiné chez chaque joueur ({@link ClientHachibi4}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Hachibi4 {
    public static final String NOM = "hachibi4";
    /** Une unité de la maquette (et du géant) fait ce nombre de blocs : le géant est à 1,5, la bête de l'ulti aux 2/3. */
    public static final double G = 1.0;

    /** La transformation (la boule qui gonfle et éclate), puis la forme, puis la recharge ; tout en ticks. */
    public static final int TRANSFORMATION = 70;
    public static final int DUREE = 45 * 20;
    public static final int RECHARGE_ULTI = 5 * 60 * 20;
    /** Les poings : un coup toutes les 0,7 s ; le poing touche le sol 6 ticks après le clic. */
    public static final int RECHARGE_POING = 14;
    public static final int IMPACT_POING = 6;
    private static final float POING = 9;
    private static final double RAYON_POING = 9;
    /** La Bijuu Dama : 1 s de charge, puis elle file ; elle explose en dôme. */
    public static final int CHARGE_DAMA = 20;
    public static final int RECHARGE_DAMA = 200;
    public static final double VITESSE_DAMA = 2.0;
    public static final double RAYON_DAMA = 9;
    private static final int VOL_DAMA = 60;
    public static final double RAYON_EXPLOSION = 14;
    private static final float DAMA_MILIEU = 22;
    private static final float DAMA_BORD = 10;
    /** Les huit queues : levées, puis abattues au 19e tick. */
    public static final int IMPACT_QUEUES = 19;
    public static final int RECHARGE_QUEUES = 240;
    private static final float QUEUES = 18;
    /** L'encre : un jet de 30 blocs devant sa gueule. */
    public static final int RECHARGE_ENCRE = 300;
    private static final double PORTEE_ENCRE = 30;
    private static final float ENCRE = 3;

    /** Les touches (client → serveur). */
    public static final int ULTI = 0, POING_T = 1, DAMA = 2, QUEUES_T = 3, ENCRE_T = 4;
    /** Les gestes (serveur → clients). */
    public static final int DEBUT = 0, FIN = 1, GESTE_POING = 2, GESTE_CHARGE = 3, GESTE_TIR = 4, GESTE_BOUM = 5, GESTE_QUEUES = 6, GESTE_ENCRE = 7;

    /** Le bout de la gueule, dans le repère de la bête (x vers sa gauche, z devant), en blocs : la tête de la maquette. */
    public static final Vec3 BOUCHE;

    static {
        // la tête de la maquette (penchée de 12°, 1,4 fois plus grande), puis relevée comme la dessine ClientHachibi4 :
        // tournée de 18° vers le haut autour de la nuque, montée de 9 et avancée de 1,5
        double a = Math.toRadians(12), k = 1.4, h = Math.toRadians(-18);
        double y = (-2.2 * Math.cos(a) - 7.4 * Math.sin(a)) * k, z = (-2.2 * Math.sin(a) + 7.4 * Math.cos(a)) * k;
        double y2 = y * Math.cos(h) - z * Math.sin(h), z2 = y * Math.sin(h) + z * Math.cos(h);
        BOUCHE = new Vec3(0, 17 + y2 + 9, 7 + z2 + 1.5).scale(G);
    }

    /** Où naît la Bijuu Dama (son centre), dans le repère de la bête. */
    public static Vec3 centreDama() {
        return BOUCHE.add(0, 0, RAYON_DAMA + 1);
    }

    /** Où tombe un poing (s = −1 le droit, +1 le gauche), dans le repère de la bête. */
    public static Vec3 poing(int s) {
        return new Vec3(s * 9, 0, 15).scale(G);
    }

    // ================================================================ réseau

    public record Touche(int action) implements CustomPacketPayload {
        public static final Type<Touche> TYPE = new Type<>(Cabane.id("hachibi4_touche"));
        public static final StreamCodec<ByteBuf, Touche> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Touche::action, Touche::new);

        @Override
        public Type<Touche> type() {
            return TYPE;
        }
    }

    /** Un geste de la bête : {@code valeur} le bras (0 droit, 1 gauche) ou, au début, les ticks déjà passés ; a et b : une position, une direction. */
    public record Geste(UUID joueur, int geste, int valeur, Vector3f a, Vector3f b) implements CustomPacketPayload {
        public static final Type<Geste> TYPE = new Type<>(Cabane.id("hachibi4_geste"));
        public static final StreamCodec<ByteBuf, Geste> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Geste::joueur, ByteBufCodecs.VAR_INT, Geste::geste, ByteBufCodecs.VAR_INT, Geste::valeur,
                ByteBufCodecs.VECTOR3F, Geste::a, ByteBufCodecs.VECTOR3F, Geste::b, Geste::new);

        @Override
        public Type<Geste> type() {
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
                .playToClient(Geste.TYPE, Geste.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientHachibi4.geste(p)));
    }

    private static void envoyer(ServerPlayer joueur, int geste, int valeur, Vec3 a, Vec3 b) {
        PacketDistributor.sendToPlayersInDimension(joueur.serverLevel(), new Geste(joueur.getUUID(), geste, valeur,
                a == null ? new Vector3f() : a.toVector3f(), b == null ? new Vector3f() : b.toVector3f()));
    }

    // ================================================================ l'état

    /** Une bête en cours (tout en ticks du serveur ; -1 : rien en cours). */
    private static final class Bete {
        final int debut;
        int cote;
        int poing = -100;
        final List<int[]> impacts = new ArrayList<>();
        int charge = -1;
        Vec3 vol, direction;
        int age;
        int damaPrete;
        int queues = -1;
        float lacetQueues;
        int queuesPretes;
        int encrePrete;

        Bete(int debut) {
            this.debut = debut;
        }

        int fin() {
            return debut + TRANSFORMATION + DUREE;
        }
    }

    private static final Map<UUID, Bete> BETES = new HashMap<>();
    private static final Map<UUID, Integer> PRET = new HashMap<>();
    /** Ceux qui ont déjà fait leur ulti pendant cet éveil (une seule fois par éveil). */
    private static final Set<UUID> FAITS = new HashSet<>();
    private static final ResourceLocation RECUL = Cabane.id("hachibi4_recul");

    /** En ulti (y compris pendant la transformation) : ses autres touches de Hachibi ne font plus rien. */
    public static boolean actif(ServerPlayer joueur) {
        return BETES.containsKey(joueur.getUUID());
    }

    /** La bête est sortie de sa boule et peut frapper. */
    private static boolean sortie(Bete b, int maintenant) {
        return maintenant >= b.debut + TRANSFORMATION;
    }

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.RED), true);
    }

    // ================================================================ les touches

    private static void touche(ServerPlayer joueur, int action) {
        // la même touche lance l'orage de la Foudre 3
        if (action == ULTI && Pouvoirs.pouvoir(joueur).equals(Foudre.NOM3)) {
            Orage.lancer(joueur);
            return;
        }
        // et la tornade royale du Vent 3
        if (action == ULTI && Pouvoirs.pouvoir(joueur).equals(Vent.NOM3)) {
            Vent3.ulti(joueur);
            return;
        }
        // et le grand serpent de la marque 3
        if (action == ULTI && Pouvoirs.pouvoir(joueur).equals(Maudit.NOM3)) {
            Marque3.ulti(joueur);
            return;
        }
        if (!Pouvoirs.pouvoir(joueur).equals(NOM)) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        Bete b = BETES.get(joueur.getUUID());
        if (action == ULTI) {
            if (b == null) {
                commencer(joueur, maintenant);
            }
            return;
        }
        if (b == null || !sortie(b, maintenant)) {
            return;
        }
        switch (action) {
            case POING_T -> poing(joueur, b, maintenant);
            case DAMA -> charger(joueur, b, maintenant);
            case QUEUES_T -> queues(joueur, b, maintenant);
            case ENCRE_T -> encre(joueur, b, maintenant);
            default -> { }
        }
    }

    /** La touche de l'ulti : seulement aux huit tentacules (niveau 4), une fois par éveil, et rechargée. */
    private static void commencer(ServerPlayer joueur, int maintenant) {
        if (Pouvoirs.niveau(joueur) < 4) {
            message(joueur, "L'ulti se réveille aux huit tentacules (niveau 4).");
            return;
        }
        if (FAITS.contains(joueur.getUUID())) {
            message(joueur, "Le Hachibi entier, c'est une fois par éveil.");
            return;
        }
        int reste = PRET.getOrDefault(joueur.getUUID(), 0) - maintenant;
        if (reste > 0) {
            message(joueur, "Le démon se repose… encore " + (reste + 19) / 20 + " s");
            return;
        }
        FAITS.add(joueur.getUUID());
        PRET.put(joueur.getUUID(), maintenant + TRANSFORMATION + DUREE + RECHARGE_ULTI);
        Hachibi.lacherPrise(joueur);
        Bete b = new Bete(maintenant);
        BETES.put(joueur.getUUID(), b);
        recul(joueur, true);
        envoyer(joueur, DEBUT, 0, null, null);
        son(joueur, joueur.position(), SoundEvents.BEACON_ACTIVATE, 3F, 0.5F);
        son(joueur, joueur.position(), SoundEvents.WARDEN_HEARTBEAT, 3F, 0.7F);
    }

    /** Clic gauche : un poing s'abat devant lui (l'un, puis l'autre) ; il touche le sol un peu après. */
    private static void poing(ServerPlayer joueur, Bete b, int maintenant) {
        if (maintenant - b.poing < RECHARGE_POING) {
            return;
        }
        b.poing = maintenant;
        int cote = b.cote;
        b.cote = 1 - cote;
        b.impacts.add(new int[] {maintenant + IMPACT_POING, cote, Float.floatToIntBits(joueur.getYRot())});
        envoyer(joueur, GESTE_POING, cote, null, null);
        son(joueur, joueur.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 3F, 0.4F);
    }

    /** Le repère de la bête : sa gauche et son devant (d'après où le joueur regarde). */
    private static Vec3 monde(ServerPlayer joueur, float lacet, Vec3 local) {
        float a = lacet * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(a), 0, Mth.cos(a)), gauche = new Vec3(Mth.cos(a), 0, Mth.sin(a));
        return joueur.position().add(gauche.scale(local.x)).add(0, local.y, 0).add(devant.scale(local.z));
    }

    private static void impactPoing(ServerPlayer joueur, int cote, float lacet) {
        ServerLevel niveau = joueur.serverLevel();
        Vec3 c = monde(joueur, lacet, poing(cote == 0 ? -1 : 1));
        BlockState[] bloc = {null};
        double y = sol(niveau, c, bloc);
        c = new Vec3(c.x, y, c.z);
        for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RAYON_POING, 6, RAYON_POING),
                e -> Pouvoirs.cible(joueur, e))) {
            Vec3 loin = cible.position().subtract(c);
            if (loin.horizontalDistance() > RAYON_POING || loin.y > 8 || loin.y < -4) {
                continue;
            }
            cible.hurt(joueur.damageSources().playerAttack(joueur), POING);
            Vec3 elan = (loin.horizontalDistanceSqr() < 0.01 ? new Vec3(0, 0, 0) : new Vec3(loin.x, 0, loin.z).normalize()).scale(1.1).add(0, 0.7, 0);
            cible.setDeltaMovement(cible.getDeltaMovement().add(elan));
            cible.hurtMarked = true;
        }
        niveau.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.5, c.z, 1, 0, 0, 0, 0);
        niveau.sendParticles(ParticleTypes.CLOUD, c.x, c.y + 0.3, c.z, 50, 3.5, 0.2, 3.5, 0.15);
        if (bloc[0] != null) {
            niveau.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, bloc[0]), c.x, c.y + 0.3, c.z, 160, 3, 0.4, 3, 0.5);
        }
        son(joueur, c, SoundEvents.GENERIC_EXPLODE.value(), 3F, 0.55F);
        son(joueur, c, SoundEvents.ANVIL_LAND, 1.6F, 0.4F);
    }

    /** Clic droit : la Bijuu Dama se forme devant sa gueule ; elle part une seconde après. */
    private static void charger(ServerPlayer joueur, Bete b, int maintenant) {
        if (b.charge >= 0 || b.vol != null) {
            return;
        }
        if (maintenant < b.damaPrete) {
            message(joueur, "La Bijuu Dama se reforme… " + (b.damaPrete - maintenant + 19) / 20 + " s");
            return;
        }
        b.charge = maintenant;
        envoyer(joueur, GESTE_CHARGE, 0, null, null);
        son(joueur, monde(joueur, joueur.getYRot(), BOUCHE), SoundEvents.WARDEN_SONIC_CHARGE, 4F, 0.4F);
        son(joueur, monde(joueur, joueur.getYRot(), BOUCHE), SoundEvents.BEACON_POWER_SELECT, 3F, 0.5F);
    }

    private static void tirer(ServerPlayer joueur, Bete b, int maintenant) {
        b.charge = -1;
        b.vol = monde(joueur, joueur.getYRot(), centreDama());
        b.direction = joueur.getLookAngle().normalize();
        b.age = 0;
        b.damaPrete = maintenant + RECHARGE_DAMA;
        envoyer(joueur, GESTE_TIR, 0, b.vol, b.direction);
        son(joueur, b.vol, SoundEvents.WARDEN_SONIC_BOOM, 4F, 0.45F);
        son(joueur, b.vol, SoundEvents.ENDER_DRAGON_SHOOT, 3F, 0.5F);
    }

    /** Elle file tout droit ; elle explose dès que son cœur touche un bloc, qu'elle frôle quelqu'un, ou au bout de 3 s. */
    private static void voler(ServerPlayer joueur, Bete b) {
        ServerLevel niveau = joueur.serverLevel();
        b.age++;
        for (int pas = 0; pas < 4; pas++) {
            b.vol = b.vol.add(b.direction.scale(VITESSE_DAMA / 4));
            BlockPos ici = BlockPos.containing(b.vol);
            boolean bloc = !niveau.getBlockState(ici).getCollisionShape(niveau, ici).isEmpty();
            boolean quelquun = !niveau.getEntitiesOfClass(LivingEntity.class, new AABB(b.vol, b.vol).inflate(RAYON_DAMA * 0.7),
                    e -> Pouvoirs.cible(joueur, e)).isEmpty();
            if (bloc || quelquun || b.age > VOL_DAMA) {
                Vec3 boum = b.vol;
                b.vol = null;
                exploser(joueur, boum);
                return;
            }
        }
    }

    /** Le dôme : tout ce qui vit autour prend cher et vole (pas de trou dans le sol). */
    private static void exploser(ServerPlayer joueur, Vec3 c) {
        ServerLevel niveau = joueur.serverLevel();
        for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(RAYON_EXPLOSION), e -> Pouvoirs.cible(joueur, e))) {
            Vec3 loin = cible.position().add(0, cible.getBbHeight() / 2, 0).subtract(c);
            double d = loin.length();
            if (d > RAYON_EXPLOSION) {
                continue;
            }
            float k = (float) (1 - d / RAYON_EXPLOSION);
            cible.invulnerableTime = 0;
            cible.hurt(joueur.damageSources().playerAttack(joueur), DAMA_BORD + (DAMA_MILIEU - DAMA_BORD) * k);
            Vec3 elan = (d < 0.1 ? new Vec3(0, 1, 0) : loin.normalize()).scale(1.4 + 1.8 * k).add(0, 0.6, 0);
            cible.setDeltaMovement(cible.getDeltaMovement().add(elan));
            cible.hurtMarked = true;
        }
        niveau.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 6, 4, 2, 4, 0);
        niveau.sendParticles(new DustParticleOptions(new Vector3f(0.08F, 0.03F, 0.12F), 4F), c.x, c.y, c.z, 260, 6, 3, 6, 0.4);
        niveau.sendParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 80, 6, 1.5, 6, 0.3);
        son(joueur, c, SoundEvents.GENERIC_EXPLODE.value(), 6F, 0.4F);
        son(joueur, c, SoundEvents.WARDEN_SONIC_BOOM, 4F, 0.4F);
        envoyer(joueur, GESTE_BOUM, 0, c, null);
    }

    /** Accroupi : il lève ses huit queues ; elles s'abattent devant lui au 19e tick. */
    private static void queues(ServerPlayer joueur, Bete b, int maintenant) {
        if (b.queues >= 0) {
            return;
        }
        if (maintenant < b.queuesPretes) {
            message(joueur, "Ses queues se redressent… " + (b.queuesPretes - maintenant + 19) / 20 + " s");
            return;
        }
        b.queues = maintenant;
        b.lacetQueues = joueur.getYRot();
        b.queuesPretes = maintenant + RECHARGE_QUEUES;
        envoyer(joueur, GESTE_QUEUES, 0, null, null);
        son(joueur, joueur.position(), SoundEvents.ENDER_DRAGON_FLAP, 4F, 0.4F);
        son(joueur, joueur.position(), SoundEvents.RAVAGER_ROAR, 3F, 0.5F);
    }

    /**
     * Les queues s'écrasent : une bande en éventail devant lui (de 8 à 64 blocs, de plus en plus large) ; tout ce qui s'y
     * trouve est écrasé et projeté en l'air.
     */
    private static void abattre(ServerPlayer joueur, Bete b) {
        ServerLevel niveau = joueur.serverLevel();
        float a = b.lacetQueues * Mth.DEG_TO_RAD;
        Vec3 devant = new Vec3(-Mth.sin(a), 0, Mth.cos(a)), gauche = new Vec3(Mth.cos(a), 0, Mth.sin(a));
        Vec3 o = joueur.position();
        double loin = 64 * G;
        for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(loin, 10, loin), e -> Pouvoirs.cible(joueur, e))) {
            Vec3 d = cible.position().subtract(o);
            double z = d.dot(devant), x = d.dot(gauche);
            if (z < 8 * G || z > loin || Math.abs(x) > (6 + z * 0.4) * G || Math.abs(d.y) > 10) {
                continue;
            }
            cible.invulnerableTime = 0;
            cible.hurt(joueur.damageSources().playerAttack(joueur), QUEUES);
            cible.setDeltaMovement(cible.getDeltaMovement().add(devant.scale(0.6)).add(0, 1.1, 0));
            cible.hurtMarked = true;
        }
        // la poussière et la terre soulevées le long des huit queues
        for (int i = 0; i < 8; i++) {
            double cote = (i - 3.5) / 3.5;
            for (double z = 14; z <= 60; z += 6) {
                Vec3 p = o.add(devant.scale(z * G)).add(gauche.scale(cote * (4 + z * 0.33) * G));
                BlockState[] bloc = {null};
                double y = sol(niveau, p, bloc);
                if (bloc[0] != null) {
                    niveau.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, bloc[0]), p.x, y + 0.3, p.z, 30, 1.5, 0.3, 1.5, 0.4);
                }
                niveau.sendParticles(ParticleTypes.CLOUD, p.x, y + 0.3, p.z, 6, 1.5, 0.2, 1.5, 0.1);
            }
        }
        Vec3 milieu = o.add(devant.scale(36 * G));
        niveau.sendParticles(ParticleTypes.EXPLOSION_EMITTER, milieu.x, milieu.y + 1, milieu.z, 8, 10, 0.5, 10, 0);
        son(joueur, milieu, SoundEvents.GENERIC_EXPLODE.value(), 6F, 0.35F);
        son(joueur, milieu, SoundEvents.ANVIL_LAND, 3F, 0.3F);
        son(joueur, milieu, SoundEvents.SLIME_SQUISH, 4F, 0.4F);
    }

    /** Accroupi + clic droit : un jet d'encre noire devant sa gueule ; ce qui est dedans est aveuglé et ralenti. */
    private static void encre(ServerPlayer joueur, Bete b, int maintenant) {
        if (maintenant < b.encrePrete) {
            message(joueur, "Plus d'encre… " + (b.encrePrete - maintenant + 19) / 20 + " s");
            return;
        }
        b.encrePrete = maintenant + RECHARGE_ENCRE;
        ServerLevel niveau = joueur.serverLevel();
        Vec3 depart = monde(joueur, joueur.getYRot(), BOUCHE), regard = joueur.getLookAngle().normalize();
        envoyer(joueur, GESTE_ENCRE, 0, depart, regard);
        for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, new AABB(depart, depart).inflate(PORTEE_ENCRE), e -> Pouvoirs.cible(joueur, e))) {
            Vec3 vers = cible.getBoundingBox().getCenter().subtract(depart);
            double d = vers.length();
            if (d > PORTEE_ENCRE || d < 0.1) {
                continue;
            }
            // un cône qui s'ouvre (et le sol tout autour de là où retombe l'encre)
            double le = vers.dot(regard), travers = vers.subtract(regard.scale(le)).length();
            if (le < 0 || travers > 3 + le * 0.3) {
                continue;
            }
            cible.hurt(joueur.damageSources().playerAttack(joueur), ENCRE);
            cible.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0));
            cible.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 1));
        }
        for (int i = 0; i < 40; i++) {
            Vec3 p = depart.add(regard.scale(i * 0.75));
            double l = 0.3 + i * 0.06;
            niveau.sendParticles(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 8, l, l, l, 0.05);
        }
        son(joueur, depart, SoundEvents.SQUID_SQUIRT, 4F, 0.4F);
        son(joueur, depart, SoundEvents.GLOW_SQUID_SQUIRT, 3F, 0.5F);
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Bete> entree : new ArrayList<>(BETES.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(entree.getKey());
            Bete b = entree.getValue();
            if (joueur == null) {
                BETES.remove(entree.getKey());
                continue;
            }
            int age = maintenant - b.debut;
            if (age == 20) {
                son(joueur, joueur.position(), SoundEvents.WARDEN_SONIC_CHARGE, 4F, 0.35F);
            }
            if (age == 50) {
                son(joueur, joueur.position(), SoundEvents.BEACON_DEACTIVATE, 4F, 0.4F);
            }
            if (age == TRANSFORMATION) {
                // la boule éclate : rien que du chakra, aucun dégât ; le démon rugit
                Vec3 p = joueur.position();
                son(joueur, p, SoundEvents.GENERIC_EXPLODE.value(), 6F, 0.5F);
                son(joueur, p, SoundEvents.RAVAGER_ROAR, 6F, 0.45F);
                son(joueur, p, SoundEvents.ENDER_DRAGON_GROWL, 6F, 0.6F);
                joueur.addEffect(new MobEffectInstance(MobEffects.GLOWING, DUREE + 10, 0, false, false));
            }
            if (maintenant >= b.fin()) {
                finir(joueur);
                continue;
            }
            for (int[] impact : new ArrayList<>(b.impacts)) {
                if (maintenant >= impact[0]) {
                    b.impacts.remove(impact);
                    impactPoing(joueur, impact[1], Float.intBitsToFloat(impact[2]));
                }
            }
            if (b.charge >= 0 && maintenant - b.charge >= CHARGE_DAMA) {
                tirer(joueur, b, maintenant);
            }
            if (b.vol != null) {
                voler(joueur, b);
            }
            if (b.queues >= 0 && maintenant - b.queues >= IMPACT_QUEUES) {
                b.queues = -1;
                abattre(joueur, b);
            }
            joueur.fallDistance = 0;
            int reste = b.fin() - maintenant;
            if (reste == 10 * 20 || (reste <= 5 * 20 && reste % 20 == 0)) {
                joueur.displayClientMessage(Component.literal("Le démon : encore " + reste / 20 + " s").withStyle(ChatFormatting.GOLD), true);
            }
        }
    }

    /** Les 45 secondes sont passées (ou il s'endort) : le démon redevient chakra, et lui un joueur aux huit tentacules. */
    static void finir(ServerPlayer joueur) {
        if (BETES.remove(joueur.getUUID()) == null) {
            return;
        }
        recul(joueur, false);
        joueur.removeEffect(MobEffects.GLOWING);
        envoyer(joueur, FIN, 0, null, null);
        son(joueur, joueur.position(), SoundEvents.FIRE_EXTINGUISH, 4F, 0.4F);
        son(joueur, joueur.position(), SoundEvents.BEACON_DEACTIVATE, 3F, 0.6F);
    }

    /** Il s'endort : l'ulti s'arrête, et il pourra la refaire au prochain éveil (une fois rechargée). */
    static void endormir(ServerPlayer joueur) {
        finir(joueur);
        FAITS.remove(joueur.getUUID());
    }

    /** Dans la bête, rien ne le repousse. */
    private static void recul(ServerPlayer joueur, boolean bete) {
        AttributeInstance a = joueur.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (a == null) {
            return;
        }
        a.removeModifier(RECUL);
        if (bete) {
            a.addTransientModifier(new AttributeModifier(RECUL, 1, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void son(ServerPlayer joueur, Vec3 p, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, p.x, p.y, p.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    /** Le sol sous ce point (de 8 blocs au-dessus à 16 en dessous) : sa hauteur, et le bloc. */
    private static double sol(ServerLevel monde, Vec3 point, BlockState[] bloc) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(point.x, point.y + 8, point.z).mutable();
        for (int i = 0; i < 24; i++) {
            BlockState etat = monde.getBlockState(pos);
            if (!etat.getCollisionShape(monde, pos).isEmpty()) {
                bloc[0] = etat;
                return pos.getY() + 1;
            }
            pos.move(0, -1, 0);
        }
        return point.y;
    }

    /** Celui qui arrive voit les démons déjà là. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer arrivant)) {
            return;
        }
        int maintenant = arrivant.server.getTickCount();
        BETES.forEach((uuid, b) -> PacketDistributor.sendToPlayer(arrivant, new Geste(uuid, DEBUT, maintenant - b.debut, new Vector3f(), new Vector3f())));
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        BETES.clear();
        PRET.clear();
        FAITS.clear();
    }

    private Hachibi4() {
    }
}
