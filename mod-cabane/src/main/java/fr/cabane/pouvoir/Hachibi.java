package fr.cabane.pouvoir;

import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.ClientHachibi;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.joml.Vector3f;

/**
 * Hachibi, phase 1 (liste blanche « hachibi », toujours rouge). La touche Éveil le couvre d'un manteau de chakra rouge
 * avec deux queues ; il se déplace à quatre pattes. Clic gauche : un coup de griffe. Accroupi : il court, très vite,
 * 8 secondes (puis 4,5 secondes de recharge). Clic droit : il devient une bête de chakra à quatre pattes, opaque,
 * incontrôlable, avec deux queues de plus, pendant 30 secondes (une minute de recharge). En bête, la griffe fait plus
 * mal, et s'accroupir charge une bombe de chakra devant sa gueule : elle grossit 3,5 secondes, part, et explose
 * (toutes les 10 secondes). Ce qu'on voit est dessiné chez chaque joueur ({@link ClientHachibi}).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Hachibi {
    public static final String NOM = "hachibi";
    /** La bête met ce temps à sortir (ça ne compte pas dans ses 30 secondes), puis elle dure, puis elle se recharge. */
    public static final int TRANSFORMATION = 40;
    public static final int DUREE_BETE = 600;
    public static final int RECHARGE_BETE = 1200;
    public static final int DUREE_COURSE = 160;
    public static final int RECHARGE_COURSE = 90;
    public static final int CHARGE_BOMBE = 70;
    public static final int RECHARGE_BOMBE = 200;
    private static final int RECHARGE_GRIFFE = 10;
    private static final float GRIFFE = 9;
    private static final float GRIFFE_BETE = 13;
    private static final float BOMBE = 18;
    private static final double RAYON_BOMBE = 6;

    public static final int GESTE_GRIFFE = 10;
    public static final int GESTE_BETE = 11;
    public static final int GESTE_COURSE = 12;

    private static final ResourceLocation VITESSE = Cabane.id("hachibi_vitesse");
    private static final ResourceLocation PAS = Cabane.id("hachibi_pas");

    /** Où en est un Hachibi éveillé (tout en ticks du serveur ; -1 : pas en cours). */
    private static final class Etat {
        boolean bete;
        int beteFin = -1;
        int beteRecharge = -1;
        int courseFin = -1;
        int courseRecharge = -1;
        int chargeDebut = -1;
        int bombeRecharge = -1;
        int griffe = -100;
        int main;
        Bombe vol;
    }

    /** Une bombe en vol : où elle est, où elle va, depuis quand. */
    private static final class Bombe {
        Vec3 pos;
        Vec3 direction;
        int age;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    /** Serveur → tous : une bombe de chakra (0 : elle se charge, 1 : elle part de pos vers direction, 2 : elle explose à pos). */
    public record Boule(UUID joueur, int etape, Vector3f pos, Vector3f direction) implements CustomPacketPayload {
        public static final Type<Boule> TYPE = new Type<>(Cabane.id("hachibi_boule"));
        public static final StreamCodec<ByteBuf, Boule> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Boule::joueur, ByteBufCodecs.VAR_INT, Boule::etape, ByteBufCodecs.VECTOR3F, Boule::pos,
                ByteBufCodecs.VECTOR3F, Boule::direction, Boule::new);

        @Override
        public Type<Boule> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Boule.TYPE, Boule.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientHachibi.boule(p)));
    }

    private static void envoyer(ServerPlayer joueur, Boule boule) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, boule);
    }

    // ================================================================ s'éveiller, s'endormir

    static void eveiller(ServerPlayer joueur) {
        Etat e = new Etat();
        ETATS.put(joueur.getUUID(), e);
        vitesse(joueur, e);
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(rouge(), joueur.getX(), joueur.getY(1), joueur.getZ(), 20, 0.6, 0.9, 0.6, 0.1);
    }

    static void endormir(ServerPlayer joueur) {
        ETATS.remove(joueur.getUUID());
        modif(joueur, Attributes.MOVEMENT_SPEED, VITESSE, 0);
        modif(joueur, Attributes.STEP_HEIGHT, PAS, 0);
    }

    private static DustParticleOptions rouge() {
        return new DustParticleOptions(new Vector3f(0.95F, 0.12F, 0.05F), 1.8F);
    }

    private static void modif(ServerPlayer joueur, Holder<Attribute> attribut, ResourceLocation id, double valeur) {
        AttributeInstance a = joueur.getAttribute(attribut);
        if (a == null) {
            return;
        }
        a.removeModifier(id);
        if (valeur != 0) {
            a.addTransientModifier(new AttributeModifier(id, valeur, attribut == Attributes.STEP_HEIGHT
                    ? AttributeModifier.Operation.ADD_VALUE : AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** La vitesse : un peu plus vif en bête, beaucoup plus pendant la course (bien au-delà d'une potion de vitesse II). */
    private static void vitesse(ServerPlayer joueur, Etat e) {
        boolean course = e.courseFin >= 0;
        modif(joueur, Attributes.MOVEMENT_SPEED, VITESSE, course ? 1.5 : e.bete ? 0.65 : 0.35);
        modif(joueur, Attributes.STEP_HEIGHT, PAS, course || e.bete ? 0.6 : 0);
    }

    // ================================================================ les touches

    /** Clic gauche : un coup de griffe devant (plus fort et plus large en bête). */
    static void griffe(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        int maintenant = joueur.server.getTickCount();
        if (e == null || maintenant - e.griffe < RECHARGE_GRIFFE) {
            return;
        }
        e.griffe = maintenant;
        e.main = 1 - e.main;
        Pouvoirs.geste(joueur, GESTE_GRIFFE, e.main);
        double portee = e.bete ? 4.5 : 3.5;
        float degats = e.bete ? GRIFFE_BETE : GRIFFE;
        Vec3 devant = Pouvoirs.devant(joueur);
        ServerLevel monde = joueur.serverLevel();
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(portee, 2, portee),
                c -> Pouvoirs.cible(joueur, c))) {
            Vec3 vers = cible.position().subtract(joueur.position());
            Vec3 plat = new Vec3(vers.x, 0, vers.z);
            if (plat.length() > portee || (plat.lengthSqr() > 0.01 && plat.normalize().dot(devant) < 0.2)) {
                continue;
            }
            cible.hurt(joueur.damageSources().playerAttack(joueur), degats);
            cible.knockback(e.bete ? 1.2 : 0.8, -devant.x, -devant.z);
            monde.sendParticles(rouge(), cible.getX(), cible.getY(0.6), cible.getZ(), 12, 0.3, 0.4, 0.3, 0.05);
            monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, cible.getX(), cible.getY(0.6), cible.getZ(), 4, 0.3, 0.3, 0.3, 0.1);
        }
    }

    /** Clic droit : devenir la bête (si elle est rechargée), ou redevenir soi-même. */
    static void bete(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (e.bete) {
            sortir(joueur, e, maintenant);
            return;
        }
        if (maintenant < e.beteRecharge) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("La bête dort encore… "
                    + (e.beteRecharge - maintenant + 19) / 20 + " s").withStyle(net.minecraft.ChatFormatting.RED), true);
            return;
        }
        e.bete = true;
        e.beteFin = maintenant + TRANSFORMATION + DUREE_BETE;
        e.courseFin = -1;
        vitesse(joueur, e);
        Pouvoirs.geste(joueur, GESTE_BETE, 1);
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(rouge(), joueur.getX(), joueur.getY(1), joueur.getZ(), 50, 1, 1, 1, 0.2);
        monde.sendParticles(ParticleTypes.EXPLOSION, joueur.getX(), joueur.getY(1), joueur.getZ(), 3, 0.8, 0.5, 0.8, 0);
    }

    private static void sortir(ServerPlayer joueur, Etat e, int maintenant) {
        e.bete = false;
        e.beteFin = -1;
        e.chargeDebut = -1;
        e.beteRecharge = maintenant + RECHARGE_BETE;
        vitesse(joueur, e);
        Pouvoirs.geste(joueur, GESTE_BETE, 0);
        joueur.serverLevel().sendParticles(rouge(), joueur.getX(), joueur.getY(1), joueur.getZ(), 60, 1, 1, 1, 0.15);
    }

    /** S'accroupir : la course (manteau), ou la bombe de chakra (bête). */
    static void accroupi(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (e.bete) {
            if (e.chargeDebut < 0 && e.vol == null && maintenant >= e.bombeRecharge && maintenant >= e.beteFin - DUREE_BETE) {
                e.chargeDebut = maintenant;
                envoyer(joueur, new Boule(joueur.getUUID(), 0, new Vector3f(), new Vector3f()));
                joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.5F, 0.7F);
            }
            return;
        }
        if (e.courseFin < 0 && maintenant >= e.courseRecharge) {
            e.courseFin = maintenant + DUREE_COURSE;
            vitesse(joueur, e);
            Pouvoirs.geste(joueur, GESTE_COURSE, 1);
        }
    }

    // ================================================================ chaque tick

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int maintenant = server.getTickCount();
        for (Map.Entry<UUID, Etat> entree : new ArrayList<>(ETATS.entrySet())) {
            ServerPlayer joueur = server.getPlayerList().getPlayer(entree.getKey());
            Etat e = entree.getValue();
            if (joueur == null) {
                ETATS.remove(entree.getKey());
                continue;
            }
            if (e.courseFin >= 0 && maintenant >= e.courseFin) {
                e.courseFin = -1;
                e.courseRecharge = maintenant + RECHARGE_COURSE;
                vitesse(joueur, e);
                Pouvoirs.geste(joueur, GESTE_COURSE, 0);
            }
            if (e.bete && maintenant >= e.beteFin) {
                sortir(joueur, e, maintenant);
            }
            if (e.chargeDebut >= 0 && maintenant - e.chargeDebut >= CHARGE_BOMBE) {
                e.chargeDebut = -1;
                tirer(joueur, e, maintenant);
            }
            if (e.vol != null) {
                voler(joueur, e);
            }
            if (maintenant % 10 == 0) {
                joueur.serverLevel().sendParticles(rouge(), joueur.getX(), joueur.getY(0.5), joueur.getZ(), e.bete ? 6 : 2, 0.5, 0.6, 0.5, 0.02);
            }
        }
    }

    /** La gueule de la bête : devant, en hauteur (là où la bombe se forme). */
    public static Vec3 gueule(LivingEntity joueur) {
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        return joueur.position().add(-Mth.sin(lacet) * 2.0, 1.3, Mth.cos(lacet) * 2.0);
    }

    private static void tirer(ServerPlayer joueur, Etat e, int maintenant) {
        Bombe b = new Bombe();
        b.pos = gueule(joueur);
        b.direction = joueur.getLookAngle().normalize();
        e.vol = b;
        e.bombeRecharge = maintenant + RECHARGE_BOMBE;
        envoyer(joueur, new Boule(joueur.getUUID(), 1, b.pos.toVector3f(), b.direction.toVector3f()));
        joueur.serverLevel().playSound(null, b.pos.x, b.pos.y, b.pos.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2F, 0.6F);
    }

    /** La bombe file tout droit ; elle explose dès qu'elle touche un bloc ou quelqu'un (ou au bout de 3 secondes). */
    private static void voler(ServerPlayer joueur, Etat e) {
        Bombe b = e.vol;
        ServerLevel monde = joueur.serverLevel();
        b.age++;
        for (int pas = 0; pas < 3; pas++) {
            b.pos = b.pos.add(b.direction.scale(0.45));
            BlockPos ici = BlockPos.containing(b.pos);
            boolean bloc = !monde.getBlockState(ici).getCollisionShape(monde, ici).isEmpty();
            boolean quelquun = !monde.getEntitiesOfClass(LivingEntity.class, new AABB(b.pos, b.pos).inflate(1.2), c -> Pouvoirs.cible(joueur, c)).isEmpty();
            if (bloc || quelquun || b.age > 60) {
                e.vol = null;
                exploser(joueur, b.pos);
                return;
            }
        }
    }

    /** BOUM : pas de feu, pas de trou dans le sol, mais tout ce qui vit autour prend très cher et vole. */
    private static void exploser(ServerPlayer joueur, Vec3 centre) {
        ServerLevel monde = joueur.serverLevel();
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(RAYON_BOMBE),
                c -> Pouvoirs.cible(joueur, c))) {
            Vec3 loin = cible.position().add(0, cible.getBbHeight() / 2, 0).subtract(centre);
            double d = loin.length();
            if (d > RAYON_BOMBE) {
                continue;
            }
            float k = (float) (1 - d / RAYON_BOMBE);
            cible.hurt(joueur.damageSources().playerAttack(joueur), 6 + (BOMBE - 6) * k);
            Vec3 elan = (d < 0.1 ? new Vec3(0, 1, 0) : loin.normalize()).scale(1.2 + 1.6 * k).add(0, 0.5, 0);
            cible.setDeltaMovement(cible.getDeltaMovement().add(elan));
            cible.hurtMarked = true;
        }
        monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 3, 1.2, 0.8, 1.2, 0);
        monde.sendParticles(new DustParticleOptions(new Vector3f(0.45F, 0.12F, 0.65F), 3F), centre.x, centre.y, centre.z, 120, 2.5, 1.5, 2.5, 0.3);
        monde.sendParticles(ParticleTypes.CLOUD, centre.x, centre.y, centre.z, 60, 2.5, 0.8, 2.5, 0.2);
        BlockPos sol = BlockPos.containing(centre).below();
        BlockState etat = monde.getBlockState(sol);
        if (!etat.isAir()) {
            monde.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, etat), centre.x, centre.y, centre.z, 150, 2.5, 1, 2.5, 0.5);
        }
        monde.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3F, 0.5F);
        monde.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2F, 0.5F);
        envoyer(joueur, new Boule(joueur.getUUID(), 2, centre.toVector3f(), new Vector3f()));
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        ETATS.clear();
    }

    private Hachibi() {
    }
}
