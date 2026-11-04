package fr.cabane.client.musique;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cabane.Cabane;
import fr.cabane.musique.Musique;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * La musique du menu Échap, côté jeu : le bouton « Musique » du menu pause, la liste des morceaux (le catalogue du pack
 * privé, rangé par catégorie), et ce qu'on entend. « Pour moi » ne regarde que ce jeu ; « autour de moi » passe par le
 * serveur (Musique) et chacun l'entend s'il est à moins de {@link Musique#RAYON} blocs de celui qui la joue, plus fort
 * de près. Pendant qu'un morceau s'entend, la musique de Minecraft se tait.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientMusique {
    private static final ResourceLocation CATALOGUE = Cabane.id("musique/catalogue.json");

    /** Un morceau du pack : cabane:ost.{@code id}, rangé dans une ou plusieurs catégories, {@code duree} en ticks. */
    public record Morceau(String id, List<String> categories, String anime, String titre, int duree) {
        public String nom() {
            return anime.isEmpty() ? titre : anime + " — " + titre;
        }
    }

    /** Un morceau qui joue (le sien ou celui d'un voisin) : tout est sur l'horloge du monde, {@code debut} compris. */
    static final class Lecture {
        final UUID source;
        final String id;
        final long debut;
        final int duree;
        final boolean boucle;
        final boolean solo;
        Son son;
        int prochainEssai;
        boolean absent;
        boolean annonce;

        Lecture(UUID source, String id, long debut, int duree, boolean boucle, boolean solo) {
            this.source = source;
            this.id = id;
            this.debut = debut;
            this.duree = duree;
            this.boucle = boucle;
            this.solo = solo;
        }

        /** Où on en est dans le morceau, en ticks. */
        long position(long maintenant) {
            long ecoule = Math.max(0, maintenant - debut);
            return boucle ? ecoule % duree : Math.min(ecoule, duree);
        }

        /**
         * Fait sonner le morceau à {@code volume} (0 : trop loin pour l'entendre mais on garde le fil, moins de 0 : on
         * coupe, on reprendra au bon endroit). Faux quand il est fini.
         */
        boolean suivre(Minecraft mc, long maintenant, float volume) {
            if (!boucle && maintenant - debut >= duree) {
                couper();
                return false;
            }
            if (volume < 0) {
                couper();
                return true;
            }
            SoundManager sons = mc.getSoundManager();
            if (son == null || !sons.isActive(son)) {
                son = null;
                long position = position(maintenant);
                // pas le pack, la fin toute proche, ou on vient d'essayer : on attend
                if (volume == 0 || absent || duree - position < 40 || ticks < prochainEssai) {
                    return true;
                }
                ResourceLocation nom = Cabane.id("ost." + id);
                if (sons.getSoundEvent(nom) == null) {
                    absent = true;
                    return true;
                }
                son = new Son(nom, position, boucle);
                sons.play(son);
                prochainEssai = ticks + 40;
                if (!solo && !annonce && !source.equals(mc.player.getUUID())) {
                    annonce = true;
                    Morceau m = morceau(id);
                    Player p = mc.level.getPlayerByUUID(source);
                    String qui = p == null ? "" : " (" + p.getGameProfile().getName() + ")";
                    mc.gui.setNowPlaying(Component.literal((m == null ? id : m.nom()) + qui));
                }
            }
            son.cible = volume;
            return true;
        }

        void couper() {
            if (son != null) {
                Minecraft.getInstance().getSoundManager().stop(son);
                son = null;
            }
        }

        boolean audible() {
            return son != null && son.cible > 0;
        }
    }

    /** Les morceaux « autour » qu'on connaît, par joueur qui les joue (le sien compris). */
    private static final Map<UUID, Lecture> AUTOUR = new HashMap<>();
    @Nullable
    private static Lecture solo;
    @Nullable
    private static List<Morceau> catalogue;
    /** Les ticks du jeu, pour ne pas réessayer un morceau à chaque tick. */
    private static int ticks;

    /** Les choix du menu : pour soi ou autour de soi, en boucle ou pas. */
    static boolean autour;
    static boolean boucle;

    /**
     * Les deux volumes de chacun (de 0, coupée, à 1), gardés dans config/cabane-musique.properties : celui de sa propre
     * musique, et celui de la musique que les autres jouent autour d'eux. Ils remplacent les réglages de son du jeu
     * (seul le volume général compte en plus).
     */
    static double volumeMoi = 1;
    static double volumeAutres = 1;
    private static boolean volumesLus;

    // ------------------------------------------------------------------ le catalogue

    /** Les morceaux du pack privé (vide sans le pack), dans l'ordre du pack. */
    public static List<Morceau> catalogue() {
        if (catalogue == null) {
            catalogue = lire();
        }
        return catalogue;
    }

    /** Relit le catalogue (le pack a pu changer depuis : F3+T, autre pack). */
    static void recharger() {
        catalogue = null;
    }

    @Nullable
    static Morceau morceau(String id) {
        for (Morceau m : catalogue()) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    private static List<Morceau> lire() {
        List<Morceau> liste = new ArrayList<>();
        Optional<Resource> fichier = Minecraft.getInstance().getResourceManager().getResource(CATALOGUE);
        if (fichier.isEmpty()) {
            return liste;
        }
        try (Reader lecteur = fichier.get().openAsReader()) {
            for (JsonElement e : JsonParser.parseReader(lecteur).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                int duree = (int) Math.ceil(o.get("duree").getAsDouble() * 20);
                List<String> categories = new ArrayList<>();
                o.getAsJsonArray("categories").forEach(c -> categories.add(c.getAsString()));
                if (duree > 0) {
                    liste.add(new Morceau(o.get("id").getAsString(), List.copyOf(categories), o.get("anime").getAsString(),
                            o.get("titre").getAsString(), duree));
                }
            }
        } catch (IOException | RuntimeException e) {
            Cabane.LOGGER.warn("Catalogue de musique illisible : {}", e.toString());
        }
        return liste;
    }

    // ------------------------------------------------------------------ les volumes

    private static Path fichierVolumes() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("cabane-musique.properties");
    }

    static void lireVolumes() {
        if (volumesLus) {
            return;
        }
        volumesLus = true;
        Properties p = new Properties();
        try (Reader lecteur = Files.newBufferedReader(fichierVolumes())) {
            p.load(lecteur);
            volumeMoi = Mth.clamp(Double.parseDouble(p.getProperty("moi", "1")), 0, 1);
            volumeAutres = Mth.clamp(Double.parseDouble(p.getProperty("autres", "1")), 0, 1);
        } catch (IOException | NumberFormatException e) {
            // pas encore de fichier : tout à fond
        }
    }

    static void garderVolumes() {
        Properties p = new Properties();
        p.setProperty("moi", Double.toString(volumeMoi));
        p.setProperty("autres", Double.toString(volumeAutres));
        try {
            Files.createDirectories(fichierVolumes().getParent());
            try (Writer ecrivain = Files.newBufferedWriter(fichierVolumes())) {
                p.store(ecrivain, "Volumes du menu Musique (0 : coupée, 1 : à fond)");
            }
        } catch (IOException e) {
            Cabane.LOGGER.warn("Volumes de musique pas enregistrés : {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ jouer, arrêter

    private static long maintenant() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    /**
     * L'horloge de « pour moi » : les ticks de ce jeu hors pause. L'heure du monde vient du serveur et recule quand il
     * rame ; pour soi seul, pas besoin d'être à l'heure des autres.
     */
    private static long horlogeSolo;

    /** Ce que soi-même on joue (pour soi ou autour), ou null. */
    @Nullable
    static Lecture maLecture() {
        Minecraft mc = Minecraft.getInstance();
        if (solo != null || mc.player == null) {
            return solo;
        }
        return AUTOUR.get(mc.player.getUUID());
    }

    /** Ce qu'on entend d'un voisin, ou null. */
    @Nullable
    static Lecture voisin() {
        Minecraft mc = Minecraft.getInstance();
        for (Lecture l : AUTOUR.values()) {
            if (mc.player != null && !l.source.equals(mc.player.getUUID()) && l.audible()) {
                return l;
            }
        }
        return null;
    }

    static long position(Lecture l) {
        return l.position(l.solo ? horlogeSolo : maintenant());
    }

    /** Un morceau en pause : on le reprend là où on l'a laissé (pour soi ou autour, selon le choix du menu à ce moment). */
    record Pause(Morceau morceau, long position) {
    }

    @Nullable
    static Pause pause;

    /** Met sa musique en pause (les autres ne l'entendent plus non plus). */
    static void mettreEnPause() {
        Lecture l = maLecture();
        Morceau m = l == null ? null : morceau(l.id);
        if (m != null) {
            couper();
            pause = new Pause(m, position(l));
        }
    }

    static void reprendre() {
        if (pause != null) {
            jouer(pause.morceau(), pause.position());
        }
    }

    /** Avance (ou recule, {@code ticks} négatif) dans le morceau, qu'il joue ou qu'il soit en pause. */
    static void decaler(long ticks) {
        Lecture l = maLecture();
        if (pause != null) {
            aller(pause.morceau(), pause.position() + ticks);
        } else if (l != null && morceau(l.id) != null) {
            aller(morceau(l.id), position(l) + ticks);
        }
    }

    /** Va à {@code position} dans le morceau : il y joue, ou il y reste en pause. */
    static void aller(Morceau m, long position) {
        long p = Math.max(0, Math.min(position, m.duree() - 20));
        if (pause != null) {
            pause = new Pause(m, p);
        } else {
            jouer(m, p);
        }
    }

    /** Lance un morceau à partir de {@code decalage} ticks, pour soi ou autour de soi selon le choix du menu. */
    static void jouer(Morceau m, long decalage) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        pause = null;
        int depart = (int) Math.max(0, Math.min(decalage, m.duree() - 1));
        if (solo != null) {
            solo.couper();
            solo = null;
        }
        if (autour) {
            PacketDistributor.sendToServer(new Musique.Jouer(m.id(), m.duree(), depart, boucle));
        } else {
            couperLaMienne();
            solo = new Lecture(mc.player.getUUID(), m.id(), horlogeSolo - depart, m.duree(), boucle, true);
        }
    }

    /** Relance ce qu'on joue au même endroit, avec les nouveaux choix (pour soi / autour, boucle). */
    static void relancer() {
        Lecture l = maLecture();
        Morceau m = l == null ? null : morceau(l.id);
        if (m != null) {
            jouer(m, position(l));
        }
    }

    static void arreter() {
        pause = null;
        couper();
    }

    private static void couper() {
        if (solo != null) {
            solo.couper();
            solo = null;
        }
        couperLaMienne();
    }

    private static void couperLaMienne() {
        Minecraft mc = Minecraft.getInstance();
        Lecture l = mc.player == null ? null : AUTOUR.remove(mc.player.getUUID());
        if (l != null) {
            l.couper();
            PacketDistributor.sendToServer(new Musique.Arreter());
        }
    }

    /** Le serveur : quelqu'un lance (ou coupe) sa musique autour de lui. */
    public static void recevoir(Musique.Etat etat) {
        Lecture ancienne = AUTOUR.remove(etat.source());
        if (ancienne != null) {
            ancienne.couper();
        }
        if (!etat.id().isEmpty() && etat.duree() > 0) {
            AUTOUR.put(etat.source(), new Lecture(etat.source(), etat.id(), etat.debut(), etat.duree(), etat.boucle(), false));
        }
    }

    private static void toutCouper() {
        pause = null;
        if (solo != null) {
            solo.couper();
            solo = null;
        }
        AUTOUR.values().forEach(Lecture::couper);
        AUTOUR.clear();
    }

    // ------------------------------------------------------------------ ce qu'on entend

    /**
     * Le volume d'un morceau joué par {@code source} : le sien à son volume ; celui d'un autre au volume « les autres »,
     * à fond de près, nul au bord du rayon, et moins de 0 bien plus loin.
     */
    private static float volume(Minecraft mc, UUID source) {
        if (source.equals(mc.player.getUUID())) {
            return (float) volumeMoi;
        }
        Player p = mc.level.getPlayerByUUID(source);
        if (p == null) {
            return -1;
        }
        double d = mc.player.distanceTo(p);
        if (d > Musique.RAYON + 3) {
            return -1;
        }
        return (float) (volumeAutres * Math.max(0, Math.min(1, (Musique.RAYON - d) / (Musique.RAYON - Musique.PRES))));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (mc.level == null || mc.player == null) {
            return;
        }
        lireVolumes();
        if (!mc.isPaused()) {
            horlogeSolo++;
        }
        long t = mc.level.getGameTime();
        if (solo != null && !solo.suivre(mc, horlogeSolo, (float) volumeMoi)) {
            solo = null;
        }
        AUTOUR.values().removeIf(l -> !l.suivre(mc, t, volume(mc, l.source)));
    }

    @SubscribeEvent
    public static void deconnexion(ClientPlayerNetworkEvent.LoggingOut event) {
        toutCouper();
        recharger();
    }

    /** La musique de Minecraft se tait tant qu'on entend un morceau (en dernier : on a le dernier mot). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void musiqueDuJeu(SelectMusicEvent event) {
        if ((solo != null && solo.audible()) || AUTOUR.values().stream().anyMatch(Lecture::audible)) {
            event.setMusic(null);
        }
    }

    /** Le bouton « Musique » du menu Échap : à côté de « Mods », qui passe à demi-largeur. */
    @SubscribeEvent
    public static void menuPause(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pause) || !pause.showsPauseMenu()) {
            return;
        }
        Button mods = null;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("fml.menu.mods")) {
                mods = b;
            }
        }
        Button.OnPress ouvrir = b -> Minecraft.getInstance().setScreen(new MusiqueScreen());
        Component nom = Component.literal("♪ Musique");
        if (mods != null && mods.getWidth() > 150) {
            int demi = (mods.getWidth() - 8) / 2;
            mods.setWidth(demi);
            event.addListener(Button.builder(nom, ouvrir).bounds(mods.getX() + mods.getWidth() + 8, mods.getY(), demi, mods.getHeight()).build());
        } else {
            event.addListener(Button.builder(nom, ouvrir).bounds(6, 6, 90, 20).build());
        }
    }

    // ------------------------------------------------------------------ le son

    /**
     * Un morceau, en stéréo et sans position (le volume suit la distance à celui qui le joue), qui commence {@code saut}
     * ticks dans le morceau : on lit et on jette le début (et le temps passé à le lire).
     */
    static final class Son extends AbstractTickableSoundInstance {
        private final long saut;
        float cible;

        Son(ResourceLocation nom, long saut, boolean boucle) {
            // MASTER : seuls le volume général du jeu et les deux volumes du menu comptent
            super(SoundEvent.createVariableRangeEvent(nom), SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.saut = saut;
            this.looping = boucle;
            this.delay = 0;
            this.volume = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            volume += (cible - volume) * 0.3F;
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary sons, Sound son, boolean boucle) {
            if (saut <= 0) {
                return super.getStream(sons, son, boucle);
            }
            return sons.getStream(son.getPath(), boucle).thenApply(flux -> {
                try {
                    long debut = System.nanoTime();
                    sauter(flux, saut / 20.0);
                    sauter(flux, (System.nanoTime() - debut) / 1e9);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
                return flux;
            });
        }

        private static void sauter(AudioStream flux, double secondes) throws IOException {
            AudioFormat format = flux.getFormat();
            long octets = (long) (secondes * format.getSampleRate()) * format.getFrameSize();
            while (octets > 0) {
                ByteBuffer lu = flux.read((int) Math.min(octets, 1 << 16));
                if (!lu.hasRemaining()) {
                    return;
                }
                octets -= lu.remaining();
            }
        }
    }

    private ClientMusique() {
    }
}
