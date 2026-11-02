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
 * <p>
 * Hachibi 2 (liste blanche « hachibi2 », docs/hachibi2) : la touche Éveil passe par toutes les formes. Niveau 1, le
 * manteau à deux queues ; niveau 2, la bête à quatre queues ; niveau 3, la bête aux os (six queues, un squelette par-
 * dessus) : tout y est 1,25 fois plus fort, et sa bombe plus grosse ; niveau 4, il redevient lui-même avec huit
 * tentacules de poulpe dans le dos, aussi fort qu'au niveau 3 : clic gauche, une tentacule frappe loin une seule cible
 * (toutes les secondes) ; clic droit, une grande tentacule balaie tout ce qui est devant lui (5 secondes) ; accroupi
 * (un appui court), une tentacule attrape ce qui est devant lui, le soulève et le serre (toutes les 12 secondes) : clic
 * droit le serre fort et le lâche, clic gauche le balance ; accroupi + clic droit, une bombe noire, plus forte encore
 * (30 secondes). Ici,
 * la bête vient avec l'Éveil (le clic droit ne la fait pas) et elle ne s'arrête pas.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Hachibi {
    public static final String NOM = "hachibi";
    public static final String NOM2 = "hachibi2";
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
    /** Le niveau 3 (et le 4) de Hachibi 2 : 1,25 fois plus fort. */
    private static final float PLUS_FORT = 1.25F;
    /** Le niveau 4 : la tentacule (une cible, loin), le balayage (tout devant), la bombe noire. */
    public static final int RECHARGE_FOUET = 20;
    public static final int RECHARGE_BALAYAGE = 100;
    public static final int RECHARGE_BOMBE_NOIRE = 600;
    private static final double PORTEE_FOUET = 9;
    private static final double PORTEE_BALAYAGE = 7;
    private static final float BALAYAGE = 16;
    private static final float BOMBE_NOIRE = 30;
    private static final double RAYON_BOMBE_NOIRE = 9;
    /** La saisie à la tentacule : sa portée, sa recharge, combien de temps elle tient, ce qu'elle serre, ce qu'elle fait. */
    private static final double PORTEE_PRISE = 8;
    public static final int RECHARGE_PRISE = 240;
    private static final int DUREE_PRISE = 160;
    /** La tentacule met ce temps (en ticks) à ramener sa prise. */
    private static final int ARRACHER = 7;
    private static final float BROIE = 2.5F;
    private static final float SERRE = 16;
    private static final float LANCE = 10;

    public static final int GESTE_GRIFFE = 10;
    public static final int GESTE_BETE = 11;
    public static final int GESTE_COURSE = 12;
    /** Le niveau 4 : un coup de tentacule (cible : l'entité touchée, -1 si aucune), un balayage. */
    public static final int GESTE_FOUET = 13;
    public static final int GESTE_BALAYAGE = 14;
    /** La saisie : cible, l'entité attrapée (-1 : elle la lâche) ; puis serrer fort, ou lancer. */
    public static final int GESTE_PRISE = 15;
    public static final int GESTE_SERRE = 16;
    public static final int GESTE_LANCE = 17;

    /** Hachibi, de l'une ou l'autre liste. */
    public static boolean est(String pouvoir) {
        return pouvoir.equals(NOM) || pouvoir.equals(NOM2);
    }

    private static final ResourceLocation VITESSE = Cabane.id("hachibi_vitesse");
    private static final ResourceLocation PAS = Cabane.id("hachibi_pas");

    /** Où en est un Hachibi éveillé (tout en ticks du serveur ; -1 : pas en cours). */
    private static final class Etat {
        /** 0 : Hachibi ; 1 à 4 : le niveau de Hachibi 2. */
        int forme;
        boolean bete;
        int beteDebut = -1;
        int beteFin = -1;
        int beteRecharge = -1;
        int courseFin = -1;
        int courseRecharge = -1;
        int chargeDebut = -1;
        int bombeRecharge = -1;
        int griffe = -100;
        int main;
        int balayageRecharge = -1;
        /** Ce que tient la tentacule (-1 : rien), depuis quand, d'où elle l'a arraché ; quand elle pourra resservir. */
        int prise = -1;
        int priseDebut;
        Vec3 priseDepart;
        int priseRecharge = -1;
        Bombe vol;
    }

    /** Une bombe en vol : où elle est, où elle va, depuis quand, et laquelle (voir {@link Boule}). */
    private static final class Bombe {
        Vec3 pos;
        Vec3 direction;
        int age;
        int genre;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    /**
     * Serveur → tous : une bombe de chakra (0 : elle se charge, 1 : elle part de pos vers direction, 2 : elle explose à
     * pos). Son genre : 0 la bombe violette, 1 la grosse (bête aux os), 2 la noire (niveau 4).
     */
    public record Boule(UUID joueur, int etape, Vector3f pos, Vector3f direction, int genre) implements CustomPacketPayload {
        public static final Type<Boule> TYPE = new Type<>(Cabane.id("hachibi_boule"));
        public static final StreamCodec<ByteBuf, Boule> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Boule::joueur, ByteBufCodecs.VAR_INT, Boule::etape, ByteBufCodecs.VECTOR3F, Boule::pos,
                ByteBufCodecs.VECTOR3F, Boule::direction, ByteBufCodecs.VAR_INT, Boule::genre, Boule::new);

        @Override
        public Type<Boule> type() {
            return TYPE;
        }
    }

    /** Client → serveur : accroupi + clic droit, au niveau 4 de Hachibi 2 (la bombe noire). */
    public record ClicAccroupi() implements CustomPacketPayload {
        public static final Type<ClicAccroupi> TYPE = new Type<>(Cabane.id("hachibi_clic_accroupi"));
        public static final StreamCodec<ByteBuf, ClicAccroupi> CODEC = StreamCodec.unit(new ClicAccroupi());

        @Override
        public Type<ClicAccroupi> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void reseau(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Boule.TYPE, Boule.CODEC, (p, ctx) -> ctx.enqueueWork(() -> ClientHachibi.boule(p)))
                .playToServer(ClicAccroupi.TYPE, ClicAccroupi.CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer joueur) {
                        Etat e = ETATS.get(joueur.getUUID());
                        if (e != null && e.forme == 4) {
                            bombe(joueur, e, joueur.server.getTickCount());
                        }
                    }
                }));
    }

    private static void envoyer(ServerPlayer joueur, Boule boule) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(joueur, boule);
    }

    // ================================================================ s'éveiller, s'endormir

    /** forme : 0 pour Hachibi ; pour Hachibi 2, le niveau atteint (de 1 à 4). */
    static void eveiller(ServerPlayer joueur, int forme) {
        Etat avant = ETATS.get(joueur.getUUID());
        if (avant != null) {
            lacher(joueur, avant);
        }
        Etat e = new Etat();
        e.forme = forme;
        int maintenant = joueur.server.getTickCount();
        if (avant != null) {
            // on change de niveau : les recharges restent
            e.bombeRecharge = avant.bombeRecharge;
            e.courseRecharge = avant.courseRecharge;
            e.balayageRecharge = avant.balayageRecharge;
        }
        if (forme == 2 || forme == 3) {
            // la bête, qui ne s'arrête plus (elle met quand même le temps de sortir de sa bulle)
            e.bete = true;
            e.beteDebut = maintenant;
            e.beteFin = Integer.MAX_VALUE;
        }
        ETATS.put(joueur.getUUID(), e);
        vitesse(joueur, e);
        ServerLevel monde = joueur.serverLevel();
        monde.sendParticles(rouge(), joueur.getX(), joueur.getY(1), joueur.getZ(), 20 + 15 * forme, 0.6, 0.9, 0.6, 0.1);
    }

    /** Le niveau 3 et le 4 de Hachibi 2 sont plus forts. */
    private static float force(Etat e) {
        return e.forme >= 3 ? PLUS_FORT : 1;
    }

    static void endormir(ServerPlayer joueur) {
        Etat e = ETATS.remove(joueur.getUUID());
        if (e != null) {
            lacher(joueur, e);
        }
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

    /**
     * La vitesse : un peu plus vif en bête, beaucoup plus pendant la course (bien au-delà d'une potion de vitesse II) ;
     * aux niveaux 3 et 4 de Hachibi 2, 1,25 fois plus que la bête.
     */
    private static void vitesse(ServerPlayer joueur, Etat e) {
        boolean course = e.courseFin >= 0;
        boolean fort = e.forme >= 3;
        modif(joueur, Attributes.MOVEMENT_SPEED, VITESSE, course ? 1.5 : fort ? 0.65 * PLUS_FORT : e.bete ? 0.65 : 0.35);
        modif(joueur, Attributes.STEP_HEIGHT, PAS, course || e.bete || fort ? 0.6 : 0);
    }

    // ================================================================ les touches

    /** Clic gauche : un coup de griffe devant (plus fort et plus large en bête) ; au niveau 4, un coup de tentacule. */
    static void griffe(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        int maintenant = joueur.server.getTickCount();
        if (e != null && e.forme == 4) {
            if (e.prise >= 0) {
                lancer(joueur, e);
            } else {
                fouet(joueur, e, maintenant);
            }
            return;
        }
        if (e == null || maintenant - e.griffe < RECHARGE_GRIFFE) {
            return;
        }
        e.griffe = maintenant;
        e.main = 1 - e.main;
        Pouvoirs.geste(joueur, GESTE_GRIFFE, e.main);
        double portee = e.bete ? 4.5 : 3.5;
        float degats = (e.bete ? GRIFFE_BETE : GRIFFE) * force(e);
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

    /**
     * Niveau 4, clic gauche : une tentacule fouette tout droit, loin, la créature la plus dans l'axe de son regard (une
     * seule). Sans personne en face, elle fouette quand même (dans le vide).
     */
    private static void fouet(ServerPlayer joueur, Etat e, int maintenant) {
        if (maintenant - e.griffe < RECHARGE_FOUET) {
            return;
        }
        e.griffe = maintenant;
        ServerLevel monde = joueur.serverLevel();
        Vec3 oeil = joueur.getEyePosition();
        Vec3 regard = joueur.getLookAngle();
        LivingEntity cible = null;
        double meilleur = Double.MAX_VALUE;
        for (LivingEntity c : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_FOUET), c -> Pouvoirs.cible(joueur, c))) {
            Vec3 vers = c.getBoundingBox().getCenter().subtract(oeil);
            double d = vers.length();
            double angle = d < 1e-3 ? 1 : vers.scale(1 / d).dot(regard);
            if (d > PORTEE_FOUET || angle < 0.93) {
                continue;
            }
            if (monde.clip(new net.minecraft.world.level.ClipContext(oeil, c.getBoundingBox().getCenter(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, joueur)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                continue;
            }
            double score = (1 - angle) * 40 + d * 0.1;
            if (score < meilleur) {
                meilleur = score;
                cible = c;
            }
        }
        Pouvoirs.geste(joueur, GESTE_FOUET, cible == null ? -1 : cible.getId());
        monde.playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1F, 0.6F);
        if (cible == null) {
            return;
        }
        cible.hurt(joueur.damageSources().playerAttack(joueur), GRIFFE_BETE * PLUS_FORT);
        cible.knockback(1.0, -regard.x, -regard.z);
        monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, cible.getX(), cible.getY(0.6), cible.getZ(), 5, 0.3, 0.3, 0.3, 0.1);
        monde.playSound(null, cible.getX(), cible.getY() + 1, cible.getZ(), SoundEvents.SLIME_ATTACK, SoundSource.PLAYERS, 1.2F, 0.6F);
    }

    /**
     * Niveau 4, clic droit : une grande tentacule balaie un large arc devant lui ; tout ce qui s'y trouve prend cher et
     * vole loin.
     */
    private static void balayage(ServerPlayer joueur, Etat e, int maintenant) {
        if (maintenant < e.balayageRecharge) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("La tentacule revient… "
                    + (e.balayageRecharge - maintenant + 19) / 20 + " s").withStyle(net.minecraft.ChatFormatting.RED), true);
            return;
        }
        e.balayageRecharge = maintenant + RECHARGE_BALAYAGE;
        Pouvoirs.geste(joueur, GESTE_BALAYAGE, 1);
        Vec3 devant = Pouvoirs.devant(joueur);
        ServerLevel monde = joueur.serverLevel();
        monde.playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.6F, 0.5F);
        monde.playSound(null, joueur.getX(), joueur.getY() + 1, joueur.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.2F, 0.7F);
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_BALAYAGE, 2.5, PORTEE_BALAYAGE),
                c -> Pouvoirs.cible(joueur, c))) {
            Vec3 vers = cible.position().subtract(joueur.position());
            Vec3 plat = new Vec3(vers.x, 0, vers.z);
            if (plat.length() > PORTEE_BALAYAGE || (plat.lengthSqr() > 0.01 && plat.normalize().dot(devant) < -0.2)) {
                continue;
            }
            cible.hurt(joueur.damageSources().playerAttack(joueur), BALAYAGE);
            Vec3 elan = (plat.lengthSqr() < 0.01 ? devant : plat.normalize()).scale(2.2).add(0, 0.65, 0);
            cible.setDeltaMovement(cible.getDeltaMovement().add(elan));
            cible.hurtMarked = true;
            monde.sendParticles(ParticleTypes.SWEEP_ATTACK, cible.getX(), cible.getY(0.5), cible.getZ(), 1, 0, 0, 0, 0);
        }
    }

    /** Niveau 4, accroupi (un appui court) : une tentacule attrape ce qui est le plus près devant lui. */
    private static void saisir(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || e.forme != 4 || e.prise >= 0) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (maintenant < e.priseRecharge) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("La tentacule se repose… "
                    + (e.priseRecharge - maintenant + 19) / 20 + " s").withStyle(net.minecraft.ChatFormatting.RED), true);
            return;
        }
        Vec3 devant = Pouvoirs.devant(joueur);
        LivingEntity proie = null;
        double meilleure = Double.MAX_VALUE;
        for (LivingEntity c : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(PORTEE_PRISE, 3, PORTEE_PRISE),
                c -> Pouvoirs.cible(joueur, c) && ETATS.values().stream().noneMatch(x -> x.prise == c.getId()))) {
            Vec3 plat = new Vec3(c.getX() - joueur.getX(), 0, c.getZ() - joueur.getZ());
            double d = plat.length();
            if (d <= PORTEE_PRISE && d < meilleure && (d < 0.5 || plat.normalize().dot(devant) > 0.3)) {
                meilleure = d;
                proie = c;
            }
        }
        if (proie == null) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("Personne à attraper devant toi.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            return;
        }
        e.prise = proie.getId();
        e.priseDebut = maintenant;
        e.priseDepart = proie.position();
        e.priseRecharge = maintenant + RECHARGE_PRISE;
        Pouvoirs.geste(joueur, GESTE_PRISE, proie.getId());
        joueur.serverLevel().playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SLIME_ATTACK, SoundSource.PLAYERS, 1.4F, 0.5F);
    }

    /** Où la tentacule tient sa prise : devant lui, en hauteur, qui se balance un peu. */
    public static Vec3 main(LivingEntity joueur, int age) {
        float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
        double balance = Math.sin(age * 0.15) * 0.3;
        return joueur.position().add(-Mth.sin(lacet) * 3.0 - Mth.cos(lacet) * balance, 2.3, Mth.cos(lacet) * 3.0 - Mth.sin(lacet) * balance);
    }

    private static void lacher(ServerPlayer joueur, Etat e) {
        if (e.prise >= 0) {
            e.prise = -1;
            Pouvoirs.geste(joueur, GESTE_PRISE, -1);
        }
    }

    /** Avec une prise, clic droit : la tentacule la serre de toutes ses forces, puis la laisse tomber. */
    private static void serrer(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        if (joueur.serverLevel().getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), SERRE);
            ServerLevel monde = joueur.serverLevel();
            monde.sendParticles(ParticleTypes.DAMAGE_INDICATOR, proie.getX(), proie.getY(0.6), proie.getZ(), 10, 0.4, 0.4, 0.4, 0.1);
            monde.sendParticles(rouge(), proie.getX(), proie.getY(0.6), proie.getZ(), 20, 0.4, 0.5, 0.4, 0.05);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SKELETON_HURT, SoundSource.PLAYERS, 1.2F, 0.4F);
            monde.playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.4F, 0.5F);
        }
        Pouvoirs.geste(joueur, GESTE_SERRE, prise);
        lacher(joueur, e);
    }

    /** Avec une prise, clic gauche : la tentacule la balance loin, là où il regarde. */
    private static void lancer(ServerPlayer joueur, Etat e) {
        int prise = e.prise;
        if (joueur.serverLevel().getEntity(prise) instanceof LivingEntity proie && proie.isAlive()) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), LANCE);
            proie.setDeltaMovement(joueur.getLookAngle().scale(2.6).add(0, 0.5, 0));
            proie.hurtMarked = true;
            proie.fallDistance = 0;
            joueur.serverLevel().playSound(null, proie.getX(), proie.getY() + 1, proie.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.4F, 0.6F);
        }
        Pouvoirs.geste(joueur, GESTE_LANCE, prise);
        e.prise = -1;
    }

    /** La prise suit la tentacule (d'abord arrachée jusqu'à elle), et se fait broyer ; au bout de 8 secondes, elle tombe. */
    private static void tenir(ServerPlayer joueur, Etat e, int maintenant) {
        int age = maintenant - e.priseDebut;
        if (!(joueur.serverLevel().getEntity(e.prise) instanceof LivingEntity proie) || !proie.isAlive() || proie.distanceTo(joueur) > 16
                || age > DUREE_PRISE) {
            lacher(joueur, e);
            return;
        }
        Vec3 ici = main(joueur, age);
        if (age < ARRACHER) {
            float k = age / (float) ARRACHER;
            ici = e.priseDepart.lerp(ici, k * k * (3 - 2 * k));
        }
        proie.teleportTo(ici.x, ici.y - proie.getBbHeight() / 2, ici.z);
        proie.setDeltaMovement(Vec3.ZERO);
        proie.fallDistance = 0;
        if (age > ARRACHER && age % 20 == 0) {
            proie.invulnerableTime = 0;
            proie.hurt(joueur.damageSources().playerAttack(joueur), BROIE);
        }
    }

    /** Clic droit : devenir la bête (si elle est rechargée), ou redevenir soi-même ; au niveau 4 de Hachibi 2, le balayage. */
    static void bete(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (e.forme == 4) {
            if (e.prise >= 0) {
                serrer(joueur, e);
            } else {
                balayage(joueur, e, maintenant);
            }
            return;
        }
        if (e.forme > 0) {
            // Hachibi 2 : la bête vient avec l'Éveil, pas avec le clic droit
            return;
        }
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

    /** La bombe que lance ce Hachibi : la noire au niveau 4, la grosse au niveau 3, sinon la violette. */
    private static int genre(Etat e) {
        return e.forme == 4 ? 2 : e.forme == 3 ? 1 : 0;
    }

    /** S'accroupir : la course (manteau), la bombe de chakra (bête), ou au niveau 4 de Hachibi 2 la tentacule qui attrape. */
    static void accroupi(ServerPlayer joueur) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null) {
            return;
        }
        int maintenant = joueur.server.getTickCount();
        if (e.forme == 4) {
            saisir(joueur);
            return;
        }
        if (e.bete) {
            bombe(joueur, e, maintenant);
            return;
        }
        if (e.courseFin < 0 && maintenant >= e.courseRecharge) {
            e.courseFin = maintenant + DUREE_COURSE;
            vitesse(joueur, e);
            Pouvoirs.geste(joueur, GESTE_COURSE, 1);
        }
    }

    /** La bombe de chakra de la bête (accroupi), ou la bombe noire du niveau 4 (accroupi + clic droit) : elle se charge. */
    private static void bombe(ServerPlayer joueur, Etat e, int maintenant) {
        boolean sortie = !e.bete || e.forme > 0 ? maintenant >= e.beteDebut + TRANSFORMATION : maintenant >= e.beteFin - DUREE_BETE;
        if (e.chargeDebut < 0 && e.vol == null && maintenant >= e.bombeRecharge && sortie) {
            e.chargeDebut = maintenant;
            envoyer(joueur, new Boule(joueur.getUUID(), 0, new Vector3f(), new Vector3f(), genre(e)));
            joueur.serverLevel().playSound(null, joueur.getX(), joueur.getY(), joueur.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.5F,
                    e.forme == 4 ? 0.5F : 0.7F);
        } else if (e.forme == 4 && maintenant < e.bombeRecharge) {
            joueur.displayClientMessage(net.minecraft.network.chat.Component.literal("La bombe noire se recharge… "
                    + (e.bombeRecharge - maintenant + 19) / 20 + " s").withStyle(net.minecraft.ChatFormatting.RED), true);
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
            if (e.prise >= 0) {
                tenir(joueur, e, maintenant);
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
        b.genre = genre(e);
        e.vol = b;
        e.bombeRecharge = maintenant + (b.genre == 2 ? RECHARGE_BOMBE_NOIRE : RECHARGE_BOMBE);
        envoyer(joueur, new Boule(joueur.getUUID(), 1, b.pos.toVector3f(), b.direction.toVector3f(), b.genre));
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
            boolean quelquun = !monde.getEntitiesOfClass(LivingEntity.class, new AABB(b.pos, b.pos).inflate(1.2 + 0.4 * b.genre), c -> Pouvoirs.cible(joueur, c)).isEmpty();
            if (bloc || quelquun || b.age > 60) {
                e.vol = null;
                exploser(joueur, b.pos, b.genre);
                return;
            }
        }
    }

    /**
     * BOUM : pas de feu, pas de trou dans le sol, mais tout ce qui vit autour prend très cher et vole. La grosse bombe
     * (niveau 3) est 1,25 fois plus forte et plus large ; la noire (niveau 4) encore plus.
     */
    private static void exploser(ServerPlayer joueur, Vec3 centre, int genre) {
        ServerLevel monde = joueur.serverLevel();
        double rayon = genre == 2 ? RAYON_BOMBE_NOIRE : genre == 1 ? RAYON_BOMBE * PLUS_FORT : RAYON_BOMBE;
        float max = genre == 2 ? BOMBE_NOIRE : genre == 1 ? BOMBE * PLUS_FORT : BOMBE;
        for (LivingEntity cible : monde.getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(rayon),
                c -> Pouvoirs.cible(joueur, c))) {
            Vec3 loin = cible.position().add(0, cible.getBbHeight() / 2, 0).subtract(centre);
            double d = loin.length();
            if (d > rayon) {
                continue;
            }
            float k = (float) (1 - d / rayon);
            cible.hurt(joueur.damageSources().playerAttack(joueur), 6 + (max - 6) * k);
            Vec3 elan = (d < 0.1 ? new Vec3(0, 1, 0) : loin.normalize()).scale(1.2 + 1.6 * k).add(0, 0.5, 0);
            cible.setDeltaMovement(cible.getDeltaMovement().add(elan));
            cible.hurtMarked = true;
        }
        monde.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 3, 1.2, 0.8, 1.2, 0);
        Vector3f teinte = genre == 2 ? new Vector3f(0.08F, 0.03F, 0.1F) : new Vector3f(0.45F, 0.12F, 0.65F);
        monde.sendParticles(new DustParticleOptions(teinte, 3F), centre.x, centre.y, centre.z, 120 + 40 * genre, 2.5 + genre, 1.5, 2.5 + genre, 0.3);
        monde.sendParticles(ParticleTypes.CLOUD, centre.x, centre.y, centre.z, 60, 2.5, 0.8, 2.5, 0.2);
        BlockPos sol = BlockPos.containing(centre).below();
        BlockState etat = monde.getBlockState(sol);
        if (!etat.isAir()) {
            monde.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, etat), centre.x, centre.y, centre.z, 150, 2.5, 1, 2.5, 0.5);
        }
        monde.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 3F, 0.5F);
        monde.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2F, 0.5F);
        envoyer(joueur, new Boule(joueur.getUUID(), 2, centre.toVector3f(), new Vector3f(), genre));
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        ETATS.clear();
    }

    private Hachibi() {
    }
}
