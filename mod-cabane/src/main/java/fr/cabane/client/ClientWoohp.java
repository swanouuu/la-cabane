package fr.cabane.client;

import fr.cabane.Cabane;
import fr.cabane.client.ui.Ios;
import fr.cabane.labo.Woohp;
import fr.cabane.labo.WoohpTrajet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * Se faire « WOOHPer », côté espionne. Ce n'est pas une cinématique : c'est son vrai personnage qui est tiré en arrière
 * à toute vitesse, au ras du sol (par-dessus les obstacles, sur l'eau), si bien que tout le monde autour la voit filer.
 * Le jeu de l'espionne la déplace lui-même (c'est lui qui fait foi pour sa position), puis le serveur prend le relais :
 * l'anneau, le rétrécissement, le chariot dans le toboggan, l'arrivée. Ici, en plus, juste un flash « WOOHP ! » et un
 * fondu au noir le temps de changer de monde.
 */
public final class ClientWoohp {
    /** La distance maximale de l'aspiration (on s'arrête avant si un mur bloque) : elle est parcourue en un peu plus d'une seconde. */
    private static final double DISTANCE = 45;
    private static final double PAS = 0.5;

    private static boolean actif;
    /** 0 : le son, puis l'aspiration ; 1 : dans le chariot ; arrêté à l'arrivée. */
    private static int phase;
    private static int ticks;
    private static int ticksChariot;
    private static int tube;
    private static Vec3 depart = Vec3.ZERO;
    private static Vec3 recul = Vec3.ZERO;
    /** La hauteur des pieds tout le long du chemin (un point tous les demi-blocs), et sa longueur utile. */
    private static double[] hauteurs = new double[0];
    private static double longueur;

    public static void recevoir(int etape, int numeroTube) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        tube = numeroTube;
        switch (etape) {
            case 0 -> demarrer(mc);
            case 1 -> {
                actif = true;
                phase = 1;
                ticksChariot = 0;
                // dans le toboggan : le cliquetis des rails, et un souffle d'aspirateur
                mc.getSoundManager().play(new Boucle(SoundEvents.MINECART_INSIDE, 0.9F, 1.1F, 1));
                mc.getSoundManager().play(new Boucle(SoundEvents.ELYTRA_FLYING, 0.6F, 1.5F, 1));
            }
            default -> actif = false;
        }
    }

    private static void demarrer(Minecraft mc) {
        actif = true;
        phase = 0;
        ticks = 0;
        depart = mc.player.position();
        float y = mc.player.getYRot();
        recul = new Vec3(Mth.sin(y * Mth.DEG_TO_RAD), 0, -Mth.cos(y * Mth.DEG_TO_RAD));
        chemin(mc.level);
    }

    /** Le relief derrière soi : on monte sur les obstacles, on redescend dans les creux, on glisse sur l'eau. */
    private static void chemin(ClientLevel level) {
        int n = (int) (DISTANCE / PAS) + 1;
        double[] h = new double[n];
        int y = Mth.floor(depart.y + 0.01);
        longueur = 0;
        int k = 0;
        for (; k < n; k++) {
            Vec3 p = depart.add(recul.scale(k * PAS));
            int bx = Mth.floor(p.x), bz = Mth.floor(p.z);
            if (libre(level, bx, y, bz)) {
                for (int chute = 0; chute < 4 && libre(level, bx, y - 1, bz) && level.getFluidState(new BlockPos(bx, y - 1, bz)).isEmpty(); chute++) {
                    y--;
                }
            } else {
                int monte = 1;
                while (monte <= 3 && !libre(level, bx, y + monte, bz)) {
                    monte++;
                }
                if (monte > 3) {
                    break;
                }
                y += monte;
            }
            BlockPos dessous = new BlockPos(bx, y - 1, bz);
            var forme = level.getBlockState(dessous).getCollisionShape(level, dessous);
            h[k] = forme.isEmpty() ? y : y - 1 + forme.max(net.minecraft.core.Direction.Axis.Y);
            longueur = k * PAS;
        }
        // on monte un peu avant chaque marche, pour ne jamais accrocher un coin
        hauteurs = new double[Math.max(1, k)];
        for (int i = 0; i < hauteurs.length; i++) {
            double m = h[i];
            for (int j = i + 1; j <= Math.min(hauteurs.length - 1, i + 2); j++) {
                m = Math.max(m, h[j]);
            }
            hauteurs[i] = m + 0.05;
        }
    }

    /** Un son qui tourne en boucle sur l'espionne tant qu'elle est dans la phase {@code phaseSon}. */
    private static final class Boucle extends AbstractTickableSoundInstance {
        private final int phaseSon;

        Boucle(SoundEvent son, float volume, float hauteur, int phaseSon) {
            super(son, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = volume;
            this.pitch = hauteur;
            this.relative = true;
            this.phaseSon = phaseSon;
        }

        @Override
        public void tick() {
            if (!actif || phase != phaseSon) {
                stop();
            }
        }
    }

    /** Dans le chariot, le regard se tourne doucement dans le sens du toboggan (on peut quand même jeter un œil à côté). */
    private static void regarderDevant(Minecraft mc) {
        Vec3 d = WoohpTrajet.direction(tube, ticksChariot / 20.0 + 0.3);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        if (horizontal < 0.15) {
            return;
        }
        float lacet = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float tangage = Mth.clamp((float) Math.toDegrees(-Math.atan2(d.y, horizontal)), -40, 50);
        float y = mc.player.getYRot();
        mc.player.setYRot(y + Mth.wrapDegrees(lacet - y) * 0.2F);
        mc.player.setXRot(Mth.lerp(0.2F, mc.player.getXRot(), tangage));
    }

    private static boolean libre(ClientLevel level, int x, int y, int z) {
        BlockPos pieds = new BlockPos(x, y, z), tete = pieds.above();
        return level.getBlockState(pieds).getCollisionShape(level, pieds).isEmpty() && level.getBlockState(tete).getCollisionShape(level, tete).isEmpty();
    }

    /** Où en est l'aspiration : de plus en plus vite, jusqu'au trou (ensuite le serveur s'occupe de tout). */
    private static void tirer(Minecraft mc) {
        if (ticks < Woohp.TIRAGE || ticks > Woohp.ANNEAU) {
            return;
        }
        double k = Mth.clamp((ticks - Woohp.TIRAGE) / (double) (Woohp.ANNEAU - Woohp.TIRAGE), 0, 1);
        // ça part tout de suite très vite, et ça accélère encore
        double s = longueur * Math.pow(k, 1.3);
        int i = Math.min(hauteurs.length - 1, (int) (s / PAS));
        int j = Math.min(hauteurs.length - 1, i + 1);
        double y = Mth.lerp(s / PAS - i, hauteurs[i], hauteurs[j]);
        Vec3 p = depart.add(recul.scale(s));
        // une vitesse plutôt qu'une position : le joueur se déplace pendant son tick, et le mouvement reste fluide à l'écran
        mc.player.setDeltaMovement(new Vec3(p.x, y, p.z).subtract(mc.player.position()));
        mc.player.fallDistance = 0;
    }

    private static void dessinerEcran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth(), h = g.guiHeight();
        float flash = 0, noir = 0;
        String titre = "";
        if (phase == 0) {
            if (ticks >= Woohp.ANNEAU - 2) {
                titre = "WOOHP !";
                flash = Math.max(0, 1 - (ticks - Woohp.ANNEAU + 2) / 6F) * 0.55F;
                noir = Mth.clamp((ticks - Woohp.ANNEAU - 2) / 8F, 0, 1);
            }
        } else {
            noir = Mth.clamp(1 - ticksChariot / 12F, 0, 1);
        }
        if (flash > 0) {
            g.fill(0, 0, w, h, (Mth.clamp((int) (flash * 255), 0, 255) << 24) | 0xFFC8F0);
        }
        if (noir > 0) {
            g.fill(0, 0, w, h, Mth.clamp((int) (noir * 255), 0, 255) << 24);
        }
        if (!titre.isEmpty()) {
            Ios.texteCentre(g, mc.font, titre, w / 2F, h / 2F - 12, 3.2F, 0xFFFF5FD2, true);
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("woohp"), (g, delta) -> {
                if (actif) {
                    dessinerEcran(g);
                }
            });
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        /** Avant le tick du joueur : sa position est déjà la bonne quand il l'envoie au serveur. */
        @SubscribeEvent
        public static void tick(ClientTickEvent.Pre event) {
            if (!actif) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                actif = false;
                return;
            }
            if (phase == 0) {
                ticks++;
                tirer(mc);
                // le serveur n'a jamais pris le relais : on arrête là
                if (ticks > Woohp.TOBOGGAN + 120) {
                    actif = false;
                }
            } else {
                regarderDevant(mc);
                if (++ticksChariot > 20 * 30) {
                    actif = false;
                }
            }
        }

        /** Pendant le WOOHP, on ne marche pas et on ne descend pas du chariot (on peut regarder autour). */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (actif) {
                event.getInput().leftImpulse = 0;
                event.getInput().forwardImpulse = 0;
                event.getInput().jumping = false;
                event.getInput().shiftKeyDown = false;
                event.getInput().up = false;
                event.getInput().down = false;
                event.getInput().left = false;
                event.getInput().right = false;
            }
        }
    }

    private ClientWoohp() {
    }
}
