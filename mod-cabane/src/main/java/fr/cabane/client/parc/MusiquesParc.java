package fr.cabane.client.parc;

import fr.cabane.Cabane;
import fr.cabane.parc.Maneges;
import fr.cabane.parc.NacelleParc;
import fr.cabane.parc.Parc;
import fr.cabane.parc.Tournants;
import fr.cabane.parc.VisiteurParc;
import fr.cabane.parc.WagonParc;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Les morceaux des manèges, seulement dans le pack privé (voix/cinematique/parc_roue.ogg → cabane:cine.parc_roue…) :
 * Gazo à la Grande Roue, Booba et Kaaris sur le Dragon, GMT aux Tasses Folles. Chacun passe en boucle tout le temps
 * qu'on est au parc, muet de loin : on l'entend en s'approchant du manège, et à fond quand on est dessus (sur le Dragon,
 * tout le long du parcours). Et Rachida, devant la fontaine, qui répète ses répliques (cine.rachida_1…4), trois
 * secondes de pause entre deux. Sans le pack privé, rien ne joue.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class MusiquesParc {
    private static final float VOLUME = 0.45F;
    /** Rachida : jusqu'où on l'entend, et la pause entre deux répliques. */
    private static final double PORTEE_RACHIDA = 26;
    private static final int PAUSE_RACHIDA = 3 * 20, REPLIQUES = 4;

    /** Un manège et son morceau : une zone où on l'entend à fond (une boîte autour du manège) et la distance où il s'éteint. */
    private record Morceau(ResourceLocation son, double x, double z, double demiX, double demiZ, double portee, Predicate<Entity> dessus) {
        float presence(LocalPlayer j) {
            Entity v = j.getVehicle();
            if (v != null && dessus.test(v)) {
                return 1F;
            }
            double dx = Math.max(0, Math.abs(j.getX() - x) - demiX), dz = Math.max(0, Math.abs(j.getZ() - z) - demiZ);
            float p = Mth.clamp(1F - (float) Math.sqrt(dx * dx + dz * dz) / (float) portee, 0F, 1F);
            return p * p;
        }
    }

    private static final double R_TASSES = Tournants.TASSES_R1 + Tournants.TASSES_R2 + 2;
    private static final Morceau[] MORCEAUX = {
            new Morceau(Cabane.id("cine.parc_roue"), Tournants.ROUE_X, Tournants.ROUE_Z, Tournants.ROUE_R + 3, 8, 26,
                    e -> e instanceof NacelleParc n && n.manege() == Tournants.ROUE),
            new Morceau(Cabane.id("cine.parc_dragon"), 114, 33.5, 9, 9, 22,
                    e -> e instanceof WagonParc w && w.manege() == Maneges.DRAGON),
            new Morceau(Cabane.id("cine.parc_tasses"), Tournants.TASSES_X, Tournants.TASSES_Z, R_TASSES, R_TASSES, 18,
                    e -> e instanceof NacelleParc n && n.manege() == Tournants.TASSES),
    };
    private static final Radio[] RADIOS = new Radio[MORCEAUX.length];
    private static final float[] PRESENCES = new float[MORCEAUX.length];

    private static SoundInstance replique;
    private static int pause, derniere = -1;

    private static boolean ici() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Parc.DIMENSION;
    }

    /** 0 loin de tous les manèges qui ont leur morceau, 1 dessus (pour baisser la valse d'orgue). */
    static float presence() {
        float p = 0F;
        for (int k = 0; k < MORCEAUX.length; k++) {
            if (RADIOS[k] != null) {
                p = Math.max(p, PRESENCES[k]);
            }
        }
        return p;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ici()) {
            for (int k = 0; k < RADIOS.length; k++) {
                if (RADIOS[k] != null) {
                    mc.getSoundManager().stop(RADIOS[k]);
                    RADIOS[k] = null;
                }
                PRESENCES[k] = 0F;
            }
            if (replique != null) {
                mc.getSoundManager().stop(replique);
                replique = null;
            }
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        for (int k = 0; k < MORCEAUX.length; k++) {
            PRESENCES[k] = MORCEAUX[k].presence(mc.player);
            Radio r = RADIOS[k];
            if (r == null) {
                if (mc.getSoundManager().getSoundEvent(MORCEAUX[k].son()) != null) {
                    RADIOS[k] = new Radio(k);
                    mc.getSoundManager().play(RADIOS[k]);
                }
            } else if (r.isStopped() || !mc.getSoundManager().isActive(r)) {
                // (un son qui s'est arrêté sans nous : on le relance au tick suivant)
                mc.getSoundManager().stop(r);
                RADIOS[k] = null;
            }
        }
        rachida(mc);
    }

    /** Le morceau d'un manège, en boucle, silencieux de loin pour ne pas le recommencer à chaque fois. */
    private static final class Radio extends AbstractTickableSoundInstance {
        private final int k;

        Radio(int k) {
            super(SoundEvent.createVariableRangeEvent(MORCEAUX[k].son()), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.k = k;
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
            volume = Math.max(0.0001F, VOLUME * PRESENCES[k]);
        }
    }

    // ------------------------------------------------------------------ Rachida

    private static void rachida(Minecraft mc) {
        if (replique != null) {
            if (mc.getSoundManager().isActive(replique)) {
                return;
            }
            replique = null;
            pause = PAUSE_RACHIDA;
        }
        if (pause > 0) {
            pause--;
            return;
        }
        VisiteurParc elle = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof VisiteurParc v && v.role() == VisiteurParc.RACHIDA && v.distanceToSqr(mc.player) < PORTEE_RACHIDA * PORTEE_RACHIDA) {
                elle = v;
                break;
            }
        }
        if (elle == null) {
            return;
        }
        // une réplique au hasard, jamais deux fois la même de suite
        int n = mc.level.random.nextInt(derniere < 0 ? REPLIQUES : REPLIQUES - 1);
        if (derniere >= 0 && n >= derniere) {
            n++;
        }
        ResourceLocation son = Cabane.id("cine.rachida_" + (n + 1));
        if (mc.getSoundManager().getSoundEvent(son) == null) {
            pause = 200;
            return;
        }
        derniere = n;
        Vec3 bouche = elle.getEyePosition();
        replique = new SimpleSoundInstance(son, SoundSource.NEUTRAL, 1.6F, 1F, SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.LINEAR, bouche.x, bouche.y, bouche.z, false);
        mc.getSoundManager().play(replique);
    }
}
