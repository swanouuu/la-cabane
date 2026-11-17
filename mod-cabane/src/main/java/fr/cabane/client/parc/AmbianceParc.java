package fr.cabane.client.parc;

import fr.cabane.Cabane;
import fr.cabane.parc.AutoParc;
import fr.cabane.parc.Guirlandes;
import fr.cabane.parc.Maneges;
import fr.cabane.parc.NacelleParc;
import fr.cabane.parc.Parc;
import fr.cabane.parc.Tournants;
import fr.cabane.parc.Trajet;
import fr.cabane.parc.VisiteurParc;
import fr.cabane.parc.WagonParc;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * L'ambiance du parc chez le joueur. Aux autos-tamponneuses, le son du pack privé (voix/cinematique/parc_autos.ogg →
 * cabane:cine.parc_autos) passe en boucle, pas trop fort, et la valse d'orgue se tait quand on s'en approche. Partout
 * ailleurs, des bruits qui font vivre le parc, chacun à sa place : la foule qui papote près des promeneurs, les wagons
 * qui grondent et les passagers qui crient dans les descentes, l'eau de la fontaine et du lac, les stands qui grésillent
 * et font éclater le pop-corn, les loups et les grincements de la Maison Hantée, les gouttes de la Mine, les carillons
 * du Cosmos et du carrousel. Et en l'air, des feux d'artifice ; sur la place, un ballet de jets d'eau. Les feux et le
 * ballet suivent l'heure du monde : tout le monde les voit en même temps.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class AmbianceParc {
    private static final ResourceLocation SON_AUTOS = Cabane.id("cine.parc_autos");
    /** Le tempo du son des autos (mesuré sur le morceau) et l'instant du premier temps. */
    private static final double BPM = 133.6, PREMIER_TEMPS = 0.297;
    /** Le hall des autos-tamponneuses (avec son toit), pour savoir à quelle distance on en est. */
    private static final double HALL_X0 = AutoParc.PISTE_X - AutoParc.PISTE_W - 2, HALL_X1 = AutoParc.PISTE_X + AutoParc.PISTE_W + 2,
            HALL_Z0 = AutoParc.PISTE_Z - AutoParc.PISTE_D - 2, HALL_Z1 = AutoParc.PISTE_Z + AutoParc.PISTE_D + 2;
    /** Le volume du son des autos sous le toit, et la distance où on ne l'entend plus. */
    private static final float VOLUME_AUTOS = 0.42F, PORTEE_AUTOS = 30F;

    private static Radio radio;
    /** Le temps écoulé dans le morceau (ticks), et pour le tempo sans le morceau. */
    private static long ticksRadio, ticksLibres;
    /** 0 loin des autos, 1 sous le toit. */
    private static float presAutos;
    private static final Map<Integer, Grondement> GRONDEMENTS = new HashMap<>();
    private static final Map<Integer, Boolean> DESCENTE = new HashMap<>();
    private static final long[] CRI_MANEGE = new long[8];
    private static final List<VisiteurParc> FOULE = new ArrayList<>();
    private static final List<Fusee> FUSEES = new ArrayList<>();
    private static long dernierCreneauFeux = -1;
    private static long[] tirsFeux = new long[0];

    private static boolean ici() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Parc.DIMENSION;
    }

    // ------------------------------------------------------------------ le tempo (pour les lumières des autos)

    /** Le temps écoulé en secondes sur le tempo : celui du morceau s'il passe, sinon un tempo libre. */
    private static double secondes(float partiel) {
        return radio != null ? (ticksRadio + partiel) / 20.0 - PREMIER_TEMPS : (ticksLibres + partiel) / 20.0;
    }

    /** Où on en est dans le temps (0 pile sur le temps, vers 1 juste avant le suivant). */
    static float phase(float partiel) {
        double b = secondes(partiel) * BPM / 60.0;
        return (float) (b - Math.floor(b));
    }

    /** Le numéro du temps en cours. */
    static long temps(float partiel) {
        return (long) Math.floor(secondes(partiel) * BPM / 60.0);
    }

    /** La pulsation : fort pile sur le temps, puis ça retombe. */
    static float pulsation(float partiel) {
        return 0.45F + 0.55F * (float) Math.exp(-5.5 * phase(partiel));
    }

    /** 0 loin des autos-tamponneuses, 1 dessous (pour baisser la valse d'orgue). */
    static float presAutos() {
        return radio != null ? presAutos : 0F;
    }

    // ------------------------------------------------------------------ chaque tick

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ici()) {
            if (radio != null) {
                mc.getSoundManager().stop(radio);
                radio = null;
            }
            GRONDEMENTS.clear();
            FUSEES.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticksLibres++;
        Vec3 j = mc.player.position();
        double dx = Math.max(0, Math.max(HALL_X0 - j.x, j.x - HALL_X1)), dz = Math.max(0, Math.max(HALL_Z0 - j.z, j.z - HALL_Z1));
        presAutos = Mth.clamp(1F - (float) Math.sqrt(dx * dx + dz * dz) / PORTEE_AUTOS, 0F, 1F);
        radio(mc);
        RandomSource r = mc.level.random;
        if (mc.player.tickCount % 20 == 0) {
            FOULE.clear();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof VisiteurParc v && v.distanceToSqr(j) < 30 * 30) {
                    FOULE.add(v);
                }
            }
        }
        foule(mc, r);
        manegesQuiRoulent(mc, j);
        lieux(mc, j, r);
        feux(mc, j);
        fontaine(mc, j);
    }

    // ------------------------------------------------------------------ le son des autos-tamponneuses

    private static void radio(Minecraft mc) {
        if (radio == null) {
            if (mc.getSoundManager().getSoundEvent(SON_AUTOS) == null) {
                return;
            }
            radio = new Radio();
            ticksRadio = 0;
            mc.getSoundManager().play(radio);
        } else if (radio.isStopped() || !mc.getSoundManager().isActive(radio)) {
            // (rattrapé : un son qui s'est arrêté sans nous, on le relance au début)
            mc.getSoundManager().stop(radio);
            radio = null;
            return;
        }
        ticksRadio++;
        long duree = 5466;
        if (ticksRadio >= duree) {
            ticksRadio -= duree;
        }
    }

    /** Le son des autos, en boucle tout le temps qu'on est au parc (silencieux de loin, pour ne pas le recommencer). */
    private static final class Radio extends AbstractTickableSoundInstance {
        Radio() {
            super(SoundEvent.createVariableRangeEvent(SON_AUTOS), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.0001F;
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (!ici()) {
                stop();
                return;
            }
            // de plus en plus fort en approchant, comme une sono qu'on entend de loin
            volume = Math.max(0.0001F, VOLUME_AUTOS * presAutos * presAutos);
        }
    }

    // ------------------------------------------------------------------ la foule

    private static void foule(Minecraft mc, RandomSource r) {
        if (FOULE.isEmpty() || r.nextFloat() > 0.09F) {
            return;
        }
        VisiteurParc v = FOULE.get(r.nextInt(FOULE.size()));
        if (v.isRemoved()) {
            return;
        }
        float x = r.nextFloat();
        SoundEvent s = x < 0.55F ? SoundEvents.VILLAGER_AMBIENT : x < 0.7F ? SoundEvents.VILLAGER_YES : x < 0.8F ? SoundEvents.VILLAGER_TRADE
                : x < 0.9F ? SoundEvents.WANDERING_TRADER_AMBIENT : x < 0.96F ? SoundEvents.VILLAGER_CELEBRATE : SoundEvents.WITCH_CELEBRATE;
        son(mc, v.getX(), v.getEyeY(), v.getZ(), s, 0.32F, 0.85F + r.nextFloat() * 0.5F);
    }

    // ------------------------------------------------------------------ les grands huit et les manèges

    private static void manegesQuiRoulent(Minecraft mc, Vec3 j) {
        Trajet.Point p = new Trajet.Point();
        long now = mc.level.getGameTime();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof WagonParc w && w.index() == 0 && w.distanceToSqr(j) < 110 * 110) {
                // le grondement du train sur ses rails (le tronc du Splash, lui, glisse sur l'eau)
                if (w.manege() != Maneges.SPLASH && !GRONDEMENTS.containsKey(w.getId())) {
                    Grondement g = new Grondement(w);
                    GRONDEMENTS.put(w.getId(), g);
                    mc.getSoundManager().play(g);
                }
                // les cris dans les grandes descentes
                w.trajet().a(w.distance(1F), p);
                boolean descente = p.fy < -0.45 && w.vitesse() > 7;
                if (descente && !DESCENTE.getOrDefault(w.getId(), false)) {
                    cris(mc, e.getX(), e.getY() + 1, e.getZ(), w.manege() == Maneges.SPLASH ? 2 : 3);
                }
                DESCENTE.put(w.getId(), descente);
            } else if (e instanceof NacelleParc n && n.index() == 0) {
                byte m = n.manege();
                float seuil = m == Tournants.TOUR ? 13 : m == Tournants.BATEAU ? 11 : m == Tournants.TOUPIE ? 13 : 999;
                if (n.vitesse() > seuil && now - CRI_MANEGE[m] > 110) {
                    CRI_MANEGE[m] = now;
                    cris(mc, n.getX(), n.getY() + 1, n.getZ(), 3);
                }
            }
        }
        GRONDEMENTS.values().removeIf(AbstractTickableSoundInstance::isStopped);
        if (DESCENTE.size() > 64) {
            DESCENTE.clear();
        }
    }

    /** Des passagers qui hurlent de joie (et un qui rigole). */
    private static void cris(Minecraft mc, double x, double y, double z, int n) {
        RandomSource r = mc.level.random;
        for (int i = 0; i < n; i++) {
            son(mc, x + r.nextGaussian(), y, z + r.nextGaussian(), SoundEvents.VILLAGER_CELEBRATE, 2.2F, 1.05F + r.nextFloat() * 0.45F);
        }
        son(mc, x, y, z, SoundEvents.WITCH_CELEBRATE, 1.8F, 1.25F + r.nextFloat() * 0.3F);
    }

    /** Un train qui gronde sur ses rails : plus fort et plus aigu quand il va vite. */
    private static final class Grondement extends AbstractTickableSoundInstance {
        private final WagonParc w;

        Grondement(WagonParc w) {
            super(SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.w = w;
            looping = true;
            delay = 0;
            volume = 0.0001F;
            x = w.getX();
            y = w.getY();
            z = w.getZ();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (w.isRemoved() || !ici() || mc.player == null || w.distanceToSqr(mc.player) > 120 * 120) {
                stop();
                return;
            }
            x = w.getX();
            y = w.getY();
            z = w.getZ();
            float v = w.vitesse();
            volume = Mth.clamp(v / 14F, 0F, 1F) * 1.6F + 0.0001F;
            pitch = 0.55F + Mth.clamp(v / 40F, 0F, 0.9F);
        }
    }

    // ------------------------------------------------------------------ les bruits des lieux

    private static boolean pres(Vec3 j, double x, double z, double r) {
        return (j.x - x) * (j.x - x) + (j.z - z) * (j.z - z) < r * r;
    }

    private static void son(Minecraft mc, double x, double y, double z, SoundEvent s, float volume, float pitch) {
        mc.level.playLocalSound(x, y, z, s, SoundSource.AMBIENT, volume, pitch, false);
    }

    private static void lieux(Minecraft mc, Vec3 j, RandomSource r) {
        int sol = Maneges.SOL;
        // la fontaine
        if (pres(j, 0.5, 0.5, 34)) {
            if (r.nextFloat() < 0.07F) {
                son(mc, 0.5 + r.nextGaussian() * 3, sol + 1, 0.5 + r.nextGaussian() * 3, SoundEvents.WATER_AMBIENT, 0.9F, 0.8F + r.nextFloat() * 0.5F);
            }
            if (r.nextFloat() < 0.02F) {
                son(mc, 0.5, sol + 2, 0.5, SoundEvents.GENERIC_SPLASH, 0.25F, 1.4F + r.nextFloat() * 0.4F);
            }
        }
        // le lac de la Rivière Splash
        if (pres(j, Maneges.SPLASH_X, Maneges.SPLASH_Z, 70) && r.nextFloat() < 0.05F) {
            double a = r.nextDouble() * Math.PI * 2;
            son(mc, Maneges.SPLASH_X + Math.cos(a) * 40, sol, Maneges.SPLASH_Z + Math.sin(a) * 32, SoundEvents.WATER_AMBIENT, 1.2F, 0.7F + r.nextFloat() * 0.3F);
        }
        // les stands : la friture qui grésille, le pop-corn qui éclate, les bouteilles des boissons
        for (int k = 0; k < Guirlandes.STANDS.length; k++) {
            int[] st = Guirlandes.STANDS[k];
            if (!pres(j, st[0], st[1], 22)) {
                continue;
            }
            double x = st[0] + 0.5, z = st[1] + 0.5;
            switch (k) {
                case 1 -> {
                    if (r.nextFloat() < 0.04F) {
                        for (int i = 0; i < 4; i++) {
                            son(mc, x, sol + 1.2, z, SoundEvents.CHICKEN_EGG, 0.35F, 1.7F + r.nextFloat() * 0.5F);
                        }
                    }
                }
                case 5 -> {
                    if (r.nextFloat() < 0.012F) {
                        son(mc, x, sol + 1.2, z, SoundEvents.BOTTLE_FILL, 0.6F, 1.1F + r.nextFloat() * 0.3F);
                    }
                }
                case 3 -> {
                }
                default -> {
                    if (r.nextFloat() < 0.05F) {
                        son(mc, x, sol + 1.2, z, SoundEvents.CAMPFIRE_CRACKLE, 0.7F, 0.9F + r.nextFloat() * 0.3F);
                    }
                }
            }
        }
        // la Maison Hantée : des loups au loin, des portes qui grincent, des murmures
        if (pres(j, Maneges.MAISON_X, Maneges.MAISON_Z, 55)) {
            double x = Maneges.MAISON_X + r.nextGaussian() * 10, z = Maneges.MAISON_Z + r.nextGaussian() * 10;
            float t = r.nextFloat();
            if (t < 0.004F) {
                son(mc, x, sol + 8, z, SoundEvents.WOLF_HOWL, 1.6F, 0.75F + r.nextFloat() * 0.2F);
            } else if (t < 0.009F) {
                son(mc, x, sol + 2, z, SoundEvents.WOODEN_DOOR_OPEN, 1F, 0.45F);
            } else if (t < 0.013F) {
                son(mc, x, sol + 2, z, SoundEvents.SOUL_ESCAPE.value(), 1.4F, 0.6F + r.nextFloat() * 0.3F);
            } else if (t < 0.0145F) {
                son(mc, x, sol + 2, z, SoundEvents.AMBIENT_CAVE.value(), 1.2F, 1F);
            }
        }
        // la Mine : la montagne qui goutte, et parfois un grondement de caverne
        if (pres(j, Maneges.MINE_X, Maneges.MINE_Z, 62)) {
            if (r.nextFloat() < 0.04F) {
                son(mc, Maneges.MINE_X + r.nextGaussian() * 18, sol + 4, Maneges.MINE_Z + r.nextGaussian() * 18, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, 1F,
                        0.8F + r.nextFloat() * 0.4F);
            }
            if (r.nextFloat() < 0.002F) {
                son(mc, Maneges.MINE_X, sol + 6, Maneges.MINE_Z, SoundEvents.AMBIENT_CAVE.value(), 1.3F, 0.9F);
            }
        }
        // le Cosmos : des carillons d'améthyste, comme des étoiles
        if (pres(j, Maneges.COSMOS_X, Maneges.COSMOS_Z, 45) && r.nextFloat() < 0.05F) {
            son(mc, Maneges.COSMOS_X + r.nextGaussian() * 8, sol + 6 + r.nextFloat() * 10, Maneges.COSMOS_Z + r.nextGaussian() * 8,
                    SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 0.6F + r.nextFloat() * 1.2F);
        }
        // le carrousel : un petit carillon (en gamme pentatonique)
        if (pres(j, Tournants.CARROUSEL_X, Tournants.CARROUSEL_Z, 26) && r.nextFloat() < 0.035F) {
            float[] gamme = {0.75F, 0.84F, 1F, 1.12F, 1.26F, 1.5F, 1.68F};
            son(mc, Tournants.CARROUSEL_X, sol + 5, Tournants.CARROUSEL_Z, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.45F, gamme[r.nextInt(gamme.length)]);
        }
    }

    // ------------------------------------------------------------------ les feux d'artifice

    /** Une fusée qui monte (sa traînée d'étincelles) puis éclate. */
    private static final class Fusee {
        double x, y, z, vx, vy, vz;
        int vie;
        List<FireworkExplosion> bouquet;
    }

    private static final int CRENEAU_FEUX = 500;
    private static final int[] COULEURS_FEUX = {0xFF3B3B, 0xFFD23F, 0x3BC9FF, 0x7BFF6A, 0xFF5CE1, 0xFFFFFF, 0xFF8A00, 0xA06BFF};

    private static void feux(Minecraft mc, Vec3 j) {
        long t = mc.level.getGameTime();
        long creneau = t / CRENEAU_FEUX;
        Random r = new Random(creneau * 7919L + 13);
        if (creneau != dernierCreneauFeux) {
            dernierCreneauFeux = creneau;
            // 2 à 5 fusées par créneau, à des moments tirés de l'heure du monde (pareil chez tout le monde)
            tirsFeux = new long[2 + r.nextInt(4)];
            for (int i = 0; i < tirsFeux.length; i++) {
                tirsFeux[i] = r.nextInt(CRENEAU_FEUX - 60);
            }
        }
        long dans = t % CRENEAU_FEUX;
        for (int i = 0; i < tirsFeux.length; i++) {
            if (tirsFeux[i] == dans) {
                Random f = new Random(creneau * 31L + i * 977L);
                lancer(mc, f, j);
            }
        }
        for (Iterator<Fusee> it = FUSEES.iterator(); it.hasNext(); ) {
            Fusee f = it.next();
            f.x += f.vx;
            f.y += f.vy;
            f.z += f.vz;
            f.vy *= 0.985;
            mc.level.addParticle(ParticleTypes.FIREWORK, f.x, f.y, f.z, mc.level.random.nextGaussian() * 0.03, -0.12, mc.level.random.nextGaussian() * 0.03);
            if (--f.vie <= 0) {
                mc.level.createFireworks(f.x, f.y, f.z, 0, 0, 0, f.bouquet);
                it.remove();
            }
        }
    }

    private static void lancer(Minecraft mc, Random r, Vec3 j) {
        Fusee f = new Fusee();
        f.x = -120 + r.nextDouble() * 250;
        f.z = -110 + r.nextDouble() * 220;
        f.y = Maneges.SOL + 1;
        if (j.distanceToSqr(f.x, f.y, f.z) > 200 * 200) {
            return;
        }
        f.vx = (r.nextDouble() - 0.5) * 0.12;
        f.vz = (r.nextDouble() - 0.5) * 0.12;
        f.vy = 1.5 + r.nextDouble() * 0.5;
        f.vie = 26 + r.nextInt(10);
        FireworkExplosion.Shape[] formes = {FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.BURST, FireworkExplosion.Shape.STAR,
                FireworkExplosion.Shape.SMALL_BALL, FireworkExplosion.Shape.LARGE_BALL};
        List<FireworkExplosion> bouquet = new ArrayList<>();
        int n = 1 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            int a = COULEURS_FEUX[r.nextInt(COULEURS_FEUX.length)], b = COULEURS_FEUX[r.nextInt(COULEURS_FEUX.length)];
            bouquet.add(new FireworkExplosion(formes[r.nextInt(formes.length)], IntList.of(a, b), IntList.of(0xFFFFFF), r.nextBoolean(), r.nextBoolean()));
        }
        f.bouquet = bouquet;
        FUSEES.add(f);
        mc.level.playLocalSound(f.x, f.y, f.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.AMBIENT, 3F, 1F, false);
    }

    // ------------------------------------------------------------------ le ballet de la fontaine

    /** Le ballet de la fontaine : 10 s toutes les 30 s (à l'heure du monde). */
    static final int CRENEAU_BALLET = 600, DUREE_BALLET = 200;

    /** Où en est le ballet (0 à 1), ou -1 s'il n'y en a pas. */
    static double ballet(Minecraft mc, float partiel) {
        double t = (mc.level.getGameTime() % CRENEAU_BALLET) + partiel;
        return t < DUREE_BALLET ? t / DUREE_BALLET : -1;
    }

    private static void fontaine(Minecraft mc, Vec3 j) {
        if (!pres(j, 0.5, 0.5, 70)) {
            return;
        }
        double k = ballet(mc, 0F);
        if (k < 0) {
            return;
        }
        RandomSource r = mc.level.random;
        int sol = Maneges.SOL;
        // huit jets en arc depuis le bord du bassin vers le milieu, qui tournent ; au sommet du ballet, un geyser
        double force = Math.sin(Math.PI * k);
        for (int i = 0; i < 8; i++) {
            double a = i / 8.0 * Math.PI * 2 + k * Math.PI * 2;
            double x = 0.5 + Math.cos(a) * 5.6, z = 0.5 + Math.sin(a) * 5.6;
            for (int q = 0; q < 2; q++) {
                mc.level.addParticle(ParticleTypes.SNOWFLAKE, x, sol + 0.9, z, -Math.cos(a) * 0.16 * force + r.nextGaussian() * 0.01,
                        0.45 + 0.35 * force + r.nextGaussian() * 0.02, -Math.sin(a) * 0.16 * force + r.nextGaussian() * 0.01);
            }
        }
        if (k > 0.35 && k < 0.75) {
            for (int q = 0; q < 6; q++) {
                mc.level.addParticle(q % 3 == 0 ? ParticleTypes.CLOUD : ParticleTypes.SNOWFLAKE, 0.5 + r.nextGaussian() * 0.2, sol + 7.2, 0.5 + r.nextGaussian() * 0.2,
                        r.nextGaussian() * 0.06, 1.1 + r.nextDouble() * 0.3, r.nextGaussian() * 0.06);
            }
        }
        if (mc.level.getGameTime() % 8 == 0) {
            son(mc, 0.5, sol + 2, 0.5, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, 1.2F, 0.9F + r.nextFloat() * 0.2F);
        }
    }

    private AmbianceParc() {
    }
}
