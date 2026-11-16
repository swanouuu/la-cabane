package fr.cabane.pouvoir;

import fr.cabane.Cabane;
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
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Le manteau de chakra de Hachibi 4 (docs/hachibi5) : aux huit tentacules, la touche de l'ulti le recouvre de chakra
 * rouge (ses huit queues deviennent du chakra) jusqu'à ce que l'éveil s'endorme ; la même touche, une deuxième fois, le
 * change en Hachibi entier ({@link Hachibi4}). Dans le manteau, il court plus vite, saute plus haut, encaisse mieux, et :
 * <ul>
 * <li>clic gauche : le bras de chakra saisit l'ennemi visé (8 blocs) et le ramène ;</li>
 * <li>clic droit : le Lariat, il fonce de 12 blocs et emporte le premier touché ;</li>
 * <li>accroupi (un appui court) : trois petites Bijuu Dama à la suite ;</li>
 * <li>accroupi + clic droit : la toupie des huit queues, tout autour de lui ;</li>
 * <li>accroupi + clic gauche : ses queues plongent dans le sol et ressortent en pics sous ceux qui sont devant ;</li>
 * <li>la touche Technique : le rugissement, qui repousse, ralentit et affaiblit ;</li>
 * <li>en l'air + clic droit : la foreuse, il traverse tout en ligne droite ; en l'air + clic gauche : la rafale de queues ;</li>
 * <li>la touche de la grande boule : il s'élève, charge 3 s une grosse Bijuu Dama et la lâche (une fois par manteau).</li>
 * </ul>
 * Les touches et les gestes passent par les paquets de {@link Hachibi4} ; le dessin est dans ClientManteau.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Manteau {
    /** Les touches (client → serveur), à la suite de celles du géant. */
    public static final int BRAS = 20, LARIAT = 21, DAMA = 22, TOUPIE = 23, PICS = 24, RUGIR = 25, FOREUSE = 26, RAFALE = 27, BOULE = 28;
    /** Les gestes (serveur → clients). */
    public static final int DEBUT = 30, FIN = 31, G_BRAS = 32, G_LARIAT = 33, G_TIR = 34, G_BOUM = 35, G_TOUPIE = 36, G_PLONGE = 37, G_PIC = 38,
            G_RUGIR = 39, G_FOREUSE = 40, G_RAFALE = 41, G_CHARGE = 42, G_GRANDE = 43;

    /** Les recharges (ticks), dans l'ordre des touches. */
    public static final int[] RECHARGE = {20, 120, 160, 200, 200, 300, 240, 160, 0};
    public static final int DUREE_LARIAT = 8, DUREE_FOREUSE = 10, DUREE_TOUPIE = 20, DUREE_RAFALE = 40, CHARGE_BOULE = 60, SORTIE_PICS = 10;
    public static final double VITESSE_DAMA = 1.4, VITESSE_GRANDE = 1.2, RAYON_PETITE = 0.5, RAYON_GRANDE = 2.5, DOME_PETIT = 4, DOME_GRAND = 9;
    /** La grande boule se forme à cette hauteur au-dessus de ses pieds. */
    public static final double HAUT_BOULE = 6.5;

    private static final class Balle {
        final int id;
        final boolean grande;
        Vec3 p;
        final Vec3 d;
        int age;

        Balle(int id, boolean grande, Vec3 p, Vec3 d) {
            this.id = id;
            this.grande = grande;
            this.p = p;
            this.d = d;
        }
    }

    private static final class Etat {
        final int[] pret = new int[9];
        int lariat = -1, foreuse = -1, toupie = -1, pics = -1, rafale = -1, boule = -1;
        Vec3 direction;
        float lacetPics;
        final Set<Integer> touches = new HashSet<>();
        int damas, damaTick, suivante;
        boolean bouleFaite;
        final List<Balle> balles = new ArrayList<>();
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();
    /** Ceux qui ont déjà pris le manteau pendant cet éveil. */
    private static final Set<UUID> FAITS = new HashSet<>();
    private static final ResourceLocation VITESSE = Cabane.id("manteau_vitesse"), SAUT = Cabane.id("manteau_saut"), CHUTE = Cabane.id("manteau_chute");

    public static boolean actif(ServerPlayer joueur) {
        return ETATS.containsKey(joueur.getUUID());
    }

    static boolean dejaPris(ServerPlayer joueur) {
        return FAITS.contains(joueur.getUUID());
    }

    private static void message(ServerPlayer joueur, String texte) {
        joueur.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.RED), true);
    }

    private static void son(ServerPlayer joueur, Vec3 p, SoundEvent son, float volume, float hauteur) {
        joueur.serverLevel().playSound(null, p.x, p.y, p.z, son, SoundSource.PLAYERS, volume, hauteur);
    }

    private static DustParticleOptions rouge(float taille) {
        return new DustParticleOptions(new Vector3f(0.95F, 0.14F, 0.06F), taille);
    }

    private static void modif(ServerPlayer joueur, Holder<Attribute> attribut, ResourceLocation id, double valeur, AttributeModifier.Operation op) {
        AttributeInstance a = joueur.getAttribute(attribut);
        if (a == null) {
            return;
        }
        a.removeModifier(id);
        if (valeur != 0) {
            a.addTransientModifier(new AttributeModifier(id, valeur, op));
        }
    }

    private static void bonus(ServerPlayer joueur, boolean oui) {
        modif(joueur, Attributes.MOVEMENT_SPEED, VITESSE, oui ? 0.3 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modif(joueur, Attributes.JUMP_STRENGTH, SAUT, oui ? 0.28 : 0, AttributeModifier.Operation.ADD_VALUE);
        modif(joueur, Attributes.SAFE_FALL_DISTANCE, CHUTE, oui ? 12 : 0, AttributeModifier.Operation.ADD_VALUE);
    }

    // ================================================================ le début, la fin

    /** La touche de l'ulti, aux huit tentacules, sans manteau : il le prend (une fois par éveil). */
    static void commencer(ServerPlayer joueur) {
        if (Pouvoirs.niveau(joueur) < 4) {
            message(joueur, "Le manteau de chakra se réveille aux huit tentacules (niveau 4).");
            return;
        }
        FAITS.add(joueur.getUUID());
        Hachibi.lacherPrise(joueur);
        ETATS.put(joueur.getUUID(), new Etat());
        bonus(joueur, true);
        Hachibi4.envoyer(joueur, DEBUT, 0, null, null);
        Vec3 p = joueur.position();
        son(joueur, p, SoundEvents.RAVAGER_ROAR, 3F, 0.7F);
        son(joueur, p, SoundEvents.BLAZE_SHOOT, 2F, 0.5F);
        son(joueur, p, SoundEvents.WARDEN_HEARTBEAT, 3F, 0.8F);
        joueur.serverLevel().sendParticles(rouge(2.4F), p.x, p.y + 1, p.z, 80, 0.8, 1, 0.8, 0.2);
    }

    /** Le manteau tombe (il devient le géant, ou il s'endort). */
    static void finir(ServerPlayer joueur) {
        if (ETATS.remove(joueur.getUUID()) == null) {
            return;
        }
        bonus(joueur, false);
        joueur.removeEffect(MobEffects.LEVITATION);
        Hachibi4.envoyer(joueur, FIN, 0, null, null);
    }

    static void endormir(ServerPlayer joueur) {
        finir(joueur);
        FAITS.remove(joueur.getUUID());
    }

    // ================================================================ les touches

    static void touche(ServerPlayer joueur, int action) {
        Etat e = ETATS.get(joueur.getUUID());
        if (e == null || action < BRAS || action > BOULE) {
            return;
        }
        int maintenant = joueur.server.getTickCount(), i = action - BRAS;
        // une attaque à la fois (les petites boules déjà parties continuent)
        if (e.lariat >= 0 || e.foreuse >= 0 || e.toupie >= 0 || e.pics >= 0 || e.rafale >= 0 || e.boule >= 0 || e.damas > 0) {
            return;
        }
        if (action == BOULE && e.bouleFaite) {
            message(joueur, "La grande boule, c'est une fois par manteau.");
            return;
        }
        if (maintenant < e.pret[i]) {
            if (i > 0) {
                message(joueur, "Le chakra se reforme… " + (e.pret[i] - maintenant + 19) / 20 + " s");
            }
            return;
        }
        e.pret[i] = maintenant + RECHARGE[i];
        Vec3 p = joueur.position();
        switch (action) {
            case BRAS -> bras(joueur);
            case LARIAT -> {
                e.lariat = maintenant;
                e.direction = Pouvoirs.devant(joueur);
                Hachibi4.envoyer(joueur, G_LARIAT, 0, null, e.direction);
                son(joueur, p, SoundEvents.RAVAGER_ROAR, 2F, 1.2F);
                son(joueur, p, SoundEvents.PLAYER_ATTACK_SWEEP, 2F, 0.5F);
            }
            case DAMA -> {
                e.damas = 3;
                e.damaTick = maintenant;
            }
            case TOUPIE -> {
                e.toupie = maintenant;
                Hachibi4.envoyer(joueur, G_TOUPIE, 0, null, null);
                son(joueur, p, SoundEvents.ENDER_DRAGON_FLAP, 2F, 1.3F);
                son(joueur, p, SoundEvents.PLAYER_ATTACK_SWEEP, 2F, 0.6F);
            }
            case PICS -> {
                e.pics = maintenant;
                e.lacetPics = joueur.getYRot();
                Hachibi4.envoyer(joueur, G_PLONGE, 0, null, null);
                son(joueur, p, SoundEvents.WARDEN_DIG, 2F, 1.2F);
            }
            case RUGIR -> rugir(joueur);
            case FOREUSE -> {
                e.foreuse = maintenant;
                e.direction = joueur.getLookAngle().normalize();
                e.touches.clear();
                joueur.startAutoSpinAttack(DUREE_FOREUSE + 2, 0, ItemStack.EMPTY);
                Hachibi4.envoyer(joueur, G_FOREUSE, 0, null, e.direction);
                son(joueur, p, SoundEvents.TRIDENT_RIPTIDE_3.value(), 2F, 0.6F);
                son(joueur, p, SoundEvents.BLAZE_SHOOT, 2F, 0.5F);
            }
            case RAFALE -> {
                e.rafale = maintenant;
                Hachibi4.envoyer(joueur, G_RAFALE, 0, null, null);
            }
            case BOULE -> {
                e.boule = maintenant;
                e.bouleFaite = true;
                joueur.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 22, 3, false, false));
                Hachibi4.envoyer(joueur, G_CHARGE, 0, null, null);
                son(joueur, p, SoundEvents.WARDEN_SONIC_CHARGE, 4F, 0.5F);
                son(joueur, p, SoundEvents.BEACON_POWER_SELECT, 3F, 0.5F);
            }
            default -> { }
        }
    }

    /** Ceux qui sont dans un cône devant ses yeux (jusqu'à loin, large de base + pente × distance). */
    private static List<LivingEntity> cone(ServerPlayer joueur, double loin, double base, double pente) {
        Vec3 yeux = joueur.getEyePosition(), regard = joueur.getLookAngle().normalize();
        List<LivingEntity> l = new ArrayList<>();
        for (LivingEntity c : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(loin), x -> Pouvoirs.cible(joueur, x))) {
            Vec3 vers = c.getBoundingBox().getCenter().subtract(yeux);
            double le = vers.dot(regard);
            if (le > 0 && le <= loin && vers.subtract(regard.scale(le)).length() <= base + le * pente) {
                l.add(c);
            }
        }
        l.sort((a, b) -> Double.compare(a.distanceToSqr(joueur), b.distanceToSqr(joueur)));
        return l;
    }

    private static void frapper(ServerPlayer joueur, LivingEntity cible, float degats, Vec3 elan) {
        cible.invulnerableTime = 0;
        cible.hurt(joueur.damageSources().playerAttack(joueur), degats);
        cible.setDeltaMovement(elan);
        cible.hurtMarked = true;
        cible.fallDistance = 0;
    }

    /** Clic gauche : le bras de chakra saisit celui qu'il vise et le ramène vers lui. */
    private static void bras(ServerPlayer joueur) {
        Vec3 yeux = joueur.getEyePosition();
        List<LivingEntity> l = cone(joueur, 8, 1.2, 0.15);
        Vec3 bout = yeux.add(joueur.getLookAngle().scale(8));
        if (!l.isEmpty()) {
            LivingEntity c = l.get(0);
            bout = c.getBoundingBox().getCenter();
            Vec3 vers = yeux.subtract(bout);
            frapper(joueur, c, 7, vers.normalize().scale(Math.min(1.3, 0.35 + vers.length() * 0.14)).add(0, 0.3, 0));
            joueur.serverLevel().sendParticles(rouge(1.6F), bout.x, bout.y, bout.z, 20, 0.3, 0.4, 0.3, 0.1);
            son(joueur, bout, SoundEvents.PLAYER_ATTACK_STRONG, 1.5F, 0.6F);
        }
        Hachibi4.envoyer(joueur, G_BRAS, l.isEmpty() ? 0 : 1, bout, null);
        son(joueur, joueur.position(), SoundEvents.BLAZE_SHOOT, 1.2F, 0.7F);
    }

    /** La touche Technique : une onde de chakra repousse, ralentit et affaiblit tout ce qui est à 8 blocs. */
    private static void rugir(ServerPlayer joueur) {
        Vec3 o = joueur.position();
        for (LivingEntity c : joueur.serverLevel().getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(8), x -> Pouvoirs.cible(joueur, x))) {
            Vec3 d = c.position().subtract(o);
            if (d.length() > 8) {
                continue;
            }
            Vec3 h = d.horizontalDistanceSqr() < 0.01 ? Pouvoirs.devant(joueur) : new Vec3(d.x, 0, d.z).normalize();
            frapper(joueur, c, 4, h.scale(1.6).add(0, 0.55, 0));
            c.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
            c.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
        }
        Hachibi4.envoyer(joueur, G_RUGIR, 0, null, null);
        ServerLevel niveau = joueur.serverLevel();
        niveau.sendParticles(ParticleTypes.CLOUD, o.x, o.y + 0.3, o.z, 60, 3, 0.2, 3, 0.2);
        niveau.sendParticles(rouge(2.5F), o.x, o.y + 1, o.z, 120, 3, 1, 3, 0.3);
        son(joueur, o, SoundEvents.RAVAGER_ROAR, 4F, 0.6F);
        son(joueur, o, SoundEvents.ENDER_DRAGON_GROWL, 3F, 0.8F);
        son(joueur, o, SoundEvents.WARDEN_SONIC_BOOM, 2F, 0.7F);
    }

    /** Les queues ressortent : un pic sous chacun de ceux qui sont devant lui (8 au plus), et d'autres pour le décor. */
    private static void sortirPics(ServerPlayer joueur, Etat e) {
        ServerLevel niveau = joueur.serverLevel();
        float a = e.lacetPics * (float) (Math.PI / 180);
        Vec3 devant = new Vec3(-Math.sin(a), 0, Math.cos(a)), gauche = new Vec3(Math.cos(a), 0, Math.sin(a)), o = joueur.position();
        int n = 0;
        for (LivingEntity c : niveau.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(13, 5, 13), x -> Pouvoirs.cible(joueur, x))) {
            Vec3 d = c.position().subtract(o);
            double z = d.dot(devant), x = d.dot(gauche);
            if (z < 1 || z > 12 || Math.abs(x) > 5 || n >= 8) {
                continue;
            }
            frapper(joueur, c, 9, new Vec3(0, 1.05, 0));
            Hachibi4.envoyer(joueur, G_PIC, n++, c.position(), null);
        }
        double[][] decor = {{-1.6, 5}, {1.8, 6}, {0.2, 7.5}, {-2.8, 8}, {2.9, 8.6}, {-0.8, 9.6}, {1.4, 10.6}, {0, 4}};
        for (int i = 0; n < 8 && i < decor.length; i++) {
            Vec3 p = o.add(gauche.scale(decor[i][0])).add(devant.scale(decor[i][1]));
            BlockState[] bloc = {null};
            Hachibi4.envoyer(joueur, G_PIC, n++, new Vec3(p.x, Hachibi4.sol(niveau, p, bloc), p.z), null);
        }
        Vec3 m = o.add(devant.scale(7));
        niveau.sendParticles(ParticleTypes.CLOUD, m.x, m.y + 0.3, m.z, 40, 3, 0.2, 3, 0.1);
        son(joueur, m, SoundEvents.GENERIC_EXPLODE.value(), 2F, 1.2F);
        son(joueur, m, SoundEvents.WARDEN_EMERGE, 2F, 1.4F);
    }

    private static void exploser(ServerPlayer joueur, Balle b) {
        ServerLevel niveau = joueur.serverLevel();
        double r = b.grande ? DOME_GRAND : DOME_PETIT;
        float milieu = b.grande ? 25 : 6, bord = b.grande ? 10 : 3;
        Vec3 c = b.p;
        for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r), x -> Pouvoirs.cible(joueur, x))) {
            Vec3 loin = cible.getBoundingBox().getCenter().subtract(c);
            double d = loin.length();
            if (d > r) {
                continue;
            }
            float k = (float) (1 - d / r);
            frapper(joueur, cible, bord + (milieu - bord) * k, cible.getDeltaMovement()
                    .add((d < 0.1 ? new Vec3(0, 1, 0) : loin.normalize()).scale((b.grande ? 1.2 : 0.6) + k).add(0, 0.45, 0)));
        }
        niveau.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, b.grande ? 5 : 1, r * 0.3, r * 0.15, r * 0.3, 0);
        niveau.sendParticles(rouge(3F), c.x, c.y, c.z, b.grande ? 220 : 50, r * 0.4, r * 0.25, r * 0.4, 0.3);
        son(joueur, c, SoundEvents.GENERIC_EXPLODE.value(), b.grande ? 6F : 3F, b.grande ? 0.45F : 0.9F);
        if (b.grande) {
            son(joueur, c, SoundEvents.WARDEN_SONIC_BOOM, 4F, 0.45F);
        }
        Hachibi4.envoyer(joueur, G_BOUM, b.id | (b.grande ? 1 << 16 : 0), c, null);
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
            if (Pouvoirs.niveau(joueur) < 4) {
                finir(joueur);
                continue;
            }
            if (maintenant % 40 == 0) {
                joueur.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 0, false, false));
            }
            ServerLevel niveau = joueur.serverLevel();
            // le Lariat : il fonce, et emporte le premier qu'il touche
            if (e.lariat >= 0) {
                joueur.setDeltaMovement(e.direction.scale(1.5).add(0, Math.max(-0.1, joueur.getDeltaMovement().y), 0));
                joueur.hurtMarked = true;
                Vec3 devant = joueur.position().add(e.direction.scale(0.9)).add(0, 0.9, 0);
                List<LivingEntity> l = niveau.getEntitiesOfClass(LivingEntity.class, new AABB(devant, devant).inflate(1.5, 1.2, 1.5), x -> Pouvoirs.cible(joueur, x));
                if (!l.isEmpty()) {
                    frapper(joueur, l.get(0), 10, e.direction.scale(2.3).add(0, 0.7, 0));
                    niveau.sendParticles(ParticleTypes.EXPLOSION, devant.x, devant.y, devant.z, 3, 0.3, 0.3, 0.3, 0);
                    son(joueur, devant, SoundEvents.PLAYER_ATTACK_CRIT, 2F, 0.5F);
                    son(joueur, devant, SoundEvents.ANVIL_LAND, 1F, 0.6F);
                }
                if (!l.isEmpty() || maintenant - e.lariat >= DUREE_LARIAT) {
                    e.lariat = -1;
                    joueur.setDeltaMovement(e.direction.scale(0.25));
                    joueur.hurtMarked = true;
                }
            }
            // la foreuse : tout droit là où il regardait, à travers tout le monde
            if (e.foreuse >= 0) {
                joueur.setDeltaMovement(e.direction.scale(1.5));
                joueur.hurtMarked = true;
                joueur.fallDistance = 0;
                Vec3 c = joueur.getBoundingBox().getCenter();
                for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(2.3), x -> Pouvoirs.cible(joueur, x))) {
                    if (e.touches.add(cible.getId())) {
                        Vec3 cote = cible.position().subtract(c);
                        cote = cote.subtract(e.direction.scale(cote.dot(e.direction)));
                        frapper(joueur, cible, 12, (cote.lengthSqr() < 0.01 ? new Vec3(0, 1, 0) : cote.normalize()).scale(1.1).add(0, 0.6, 0));
                    }
                }
                niveau.sendParticles(rouge(2F), c.x, c.y, c.z, 12, 0.6, 0.6, 0.6, 0.1);
                if (maintenant - e.foreuse >= DUREE_FOREUSE) {
                    e.foreuse = -1;
                    joueur.setDeltaMovement(e.direction.scale(0.3));
                    joueur.hurtMarked = true;
                }
            }
            // la toupie : deux tours de queues
            if (e.toupie >= 0) {
                int age = maintenant - e.toupie;
                if (age == 4 || age == 12) {
                    Vec3 o = joueur.position();
                    for (LivingEntity cible : niveau.getEntitiesOfClass(LivingEntity.class, joueur.getBoundingBox().inflate(6, 2.5, 6), x -> Pouvoirs.cible(joueur, x))) {
                        Vec3 d = cible.position().subtract(o);
                        if (d.horizontalDistance() <= 6.5) {
                            Vec3 h = d.horizontalDistanceSqr() < 0.01 ? new Vec3(1, 0, 0) : new Vec3(d.x, 0, d.z).normalize();
                            frapper(joueur, cible, 4, h.scale(1.25).add(0, 0.5, 0));
                        }
                    }
                    niveau.sendParticles(ParticleTypes.SWEEP_ATTACK, o.x, o.y + 1, o.z, 14, 3, 0.2, 3, 0);
                    son(joueur, o, SoundEvents.PLAYER_ATTACK_SWEEP, 2F, 0.7F);
                }
                if (age >= DUREE_TOUPIE) {
                    e.toupie = -1;
                }
            }
            if (e.pics >= 0 && maintenant - e.pics >= SORTIE_PICS) {
                e.pics = -1;
                sortirPics(joueur, e);
            }
            // la rafale : huit coups de queue, un toutes les 5 ticks
            if (e.rafale >= 0) {
                int age = maintenant - e.rafale;
                if (age % 5 == 2) {
                    Vec3 regard = joueur.getLookAngle();
                    for (LivingEntity cible : cone(joueur, 7, 1.5, 0.35)) {
                        frapper(joueur, cible, 2, cible.getDeltaMovement().scale(0.5).add(regard.scale(0.35)).add(0, 0.12, 0));
                    }
                    son(joueur, joueur.position(), SoundEvents.PLAYER_ATTACK_SWEEP, 1.2F, 1.2F + (age / 5) * 0.06F);
                }
                if (age >= DUREE_RAFALE) {
                    e.rafale = -1;
                }
            }
            // les trois petites Bijuu Dama, l'une après l'autre
            if (e.damas > 0 && maintenant >= e.damaTick) {
                e.damas--;
                e.damaTick = maintenant + 5;
                Vec3 d = joueur.getLookAngle().normalize(), p = joueur.getEyePosition().add(d.scale(0.9));
                Balle b = new Balle(e.suivante++, false, p, d);
                e.balles.add(b);
                Hachibi4.envoyer(joueur, G_TIR, b.id, p, d);
                son(joueur, p, SoundEvents.ENDER_DRAGON_SHOOT, 1.5F, 1.3F);
            }
            // la grande boule : il monte, elle grossit au-dessus de lui, puis part là où il regarde
            if (e.boule >= 0) {
                int age = maintenant - e.boule;
                joueur.fallDistance = 0;
                if (age == 22) {
                    joueur.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, CHARGE_BOULE, 0, false, false));
                }
                if (age > 22) {
                    joueur.setDeltaMovement(joueur.getDeltaMovement().x * 0.5, Math.max(joueur.getDeltaMovement().y, 0), joueur.getDeltaMovement().z * 0.5);
                    joueur.hurtMarked = true;
                }
                if (age >= CHARGE_BOULE) {
                    e.boule = -1;
                    Vec3 d = joueur.getLookAngle().normalize(), p = joueur.position().add(0, HAUT_BOULE, 0);
                    Balle b = new Balle(e.suivante++, true, p, d);
                    e.balles.add(b);
                    Hachibi4.envoyer(joueur, G_GRANDE, b.id, p, d);
                    son(joueur, p, SoundEvents.WARDEN_SONIC_BOOM, 4F, 0.5F);
                    son(joueur, p, SoundEvents.ENDER_DRAGON_SHOOT, 3F, 0.5F);
                }
            }
            for (Iterator<Balle> it = e.balles.iterator(); it.hasNext();) {
                Balle b = it.next();
                b.age++;
                double v = b.grande ? VITESSE_GRANDE : VITESSE_DAMA, r = b.grande ? RAYON_GRANDE : RAYON_PETITE;
                boolean boum = b.age > (b.grande ? 80 : 40);
                for (int pas = 0; pas < 4 && !boum; pas++) {
                    b.p = b.p.add(b.d.scale(v / 4));
                    BlockPos ici = BlockPos.containing(b.p);
                    boum = !niveau.getBlockState(ici).getCollisionShape(niveau, ici).isEmpty()
                            || !niveau.getEntitiesOfClass(LivingEntity.class, new AABB(b.p, b.p).inflate(r * 0.8 + 0.3), x -> Pouvoirs.cible(joueur, x)).isEmpty();
                }
                if (boum) {
                    it.remove();
                    exploser(joueur, b);
                }
            }
        }
    }

    /** Celui qui arrive voit les manteaux déjà là. */
    @SubscribeEvent
    public static void connexion(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer arrivant) {
            ETATS.keySet().forEach(uuid -> PacketDistributor.sendToPlayer(arrivant, new Hachibi4.Geste(uuid, DEBUT, 1, new Vector3f(), new Vector3f())));
        }
    }

    @SubscribeEvent
    public static void arret(ServerStoppingEvent event) {
        ETATS.clear();
        FAITS.clear();
    }

    private Manteau() {
    }
}
