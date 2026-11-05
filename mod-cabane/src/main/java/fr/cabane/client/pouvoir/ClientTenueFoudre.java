package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.NativeImage;
import fr.cabane.Cabane;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * La tenue de la Foudre 3 (docs/foudre3/planche-tenue.png) : la tenue du mode Kurama de Minato 2 (sa tête passée dans la
 * couleur du mode, voir {@link ClientKurama#composer}), mais en bleu clair, avec les traits noirs du skin que l'utilisateur
 * a envoyé (devant : le col, l'échelle au milieu, les jambes bordées de noir ; dos : la goutte noire et ses deux traits),
 * et sur le visage seulement la bouche. La transformation : la foudre lui tombe sur le crâne et la tenue file de la tête
 * aux pieds (0,3 s) ; à la fin, elle s'enlève de la tête aux pieds.
 */
public final class ClientTenueFoudre {
    /** Quand la tenue commence à descendre sur lui (la foudre vient de tomber), et combien de temps elle met. */
    private static final double ATTENTE = ClientTransfoFoudre.TENUE, MONTEE = ClientTransfoFoudre.TENUE_DUREE, DESCENTE = 1.0;
    private static final int ETAPES = 12;

    private static final Set<UUID> MODES = new HashSet<>();
    /** Le temps du monde où il est passé au niveau 3, où il en est sorti. */
    private static final Map<UUID, Long> DEBUTS = new HashMap<>();
    private static final Map<UUID, Long> FINS = new HashMap<>();
    /** « texture|étape » → la peau à cette étape (une texture à nous) ; la tenue entière, par texture de base. */
    private static final Map<String, ResourceLocation> PEAUX = new HashMap<>();
    private static final Map<ResourceLocation, int[]> ENTIERES = new HashMap<>();
    private static final Map<String, Long> RATEES = new HashMap<>();
    private static final Map<ResourceLocation, PlayerSkin> REMPLACEES = new HashMap<>();

    private static long maintenant() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    /** Appelé quand le serveur dit où en est ce joueur (ClientFoudre.recevoir) : niveau 3 de la Foudre 3, ou non. */
    static void etat(UUID joueur, boolean trois, boolean annonce) {
        if (trois && MODES.add(joueur)) {
            FINS.remove(joueur);
            // déjà au niveau 3 quand on arrive : la tenue est déjà là
            DEBUTS.put(joueur, annonce ? maintenant() : maintenant() - 100);
        } else if (!trois && MODES.remove(joueur)) {
            DEBUTS.remove(joueur);
            FINS.put(joueur, maintenant());
        }
    }

    static void oublier(UUID joueur) {
        MODES.remove(joueur);
        DEBUTS.remove(joueur);
        FINS.remove(joueur);
    }

    /**
     * L'étape de la tenue : de 1 à ETAPES quand elle descend sur lui (ETAPES : entière), de -1 à -ETAPES+1 quand elle
     * s'enlève (combien il en reste en bas), 0 s'il n'y en a pas.
     */
    private static int etape(UUID joueur) {
        Long f = FINS.get(joueur);
        if (f != null) {
            double t = (maintenant() - f) / 20.0;
            if (t >= DESCENTE) {
                FINS.remove(joueur);
                return 0;
            }
            int reste = (int) Math.ceil((ETAPES - 1) * (1 - t / DESCENTE));
            return reste <= 0 ? 0 : -reste;
        }
        Long d = DEBUTS.get(joueur);
        if (d == null) {
            return 0;
        }
        double t = (maintenant() - d) / 20.0;
        return (int) Mth.clamp(Math.round(ETAPES * (t - ATTENTE) / MONTEE), 0, ETAPES);
    }

    // ================================================================ la peau

    /** Appelé à chaque {@code getSkin()} (AbstractClientPlayerMixin). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        if (MODES.isEmpty() && FINS.isEmpty()) {
            return skin;
        }
        int e = etape(joueur.getUUID());
        if (e == 0) {
            return skin;
        }
        ResourceLocation id = texture(skin, e);
        if (id == null) {
            return skin;
        }
        PlayerSkin s = REMPLACEES.get(id);
        if (s == null || s.model() != skin.model()) {
            s = new PlayerSkin(id, skin.textureUrl(), null, skin.elytraTexture(), skin.model(), skin.secure());
            REMPLACEES.put(id, s);
        }
        return s;
    }

    private static ResourceLocation texture(PlayerSkin vraie, int etape) {
        String cle = vraie.texture() + "|" + etape;
        ResourceLocation id = PEAUX.get(cle);
        if (id != null || System.nanoTime() < RATEES.getOrDefault(cle, 0L)) {
            return id;
        }
        int[] base = ClientMaudit.lire(vraie.texture());
        int[] entiere = base == null ? null : ENTIERES.computeIfAbsent(vraie.texture(), t -> entiere(base));
        if (entiere == null) {
            RATEES.put(cle, System.nanoTime() + 2_000_000_000L);
            return null;
        }
        // en descendant : la tenue au-dessus de la ligne ; en s'enlevant : ce qui reste en dessous
        float ligne = 33F * (ETAPES - Math.abs(etape)) / ETAPES;
        int[] px = base.clone();
        for (int i = 0; i < px.length; i++) {
            float h = ClientKurama.HAUTEUR[i];
            if (Float.isNaN(h)) {
                continue;
            }
            boolean tenue = etape > 0 ? h >= ligne : h < 33F * Math.abs(etape) / ETAPES;
            if (tenue) {
                px[i] = entiere[i];
            }
        }
        NativeImage image = new NativeImage(64, 64, true);
        for (int i = 0; i < px.length; i++) {
            image.setPixelRGBA(i % 64, i / 64, px[i]);
        }
        id = Cabane.id("foudre/tenue_" + PEAUX.size());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        PEAUX.put(cle, id);
        RATEES.remove(cle);
        return id;
    }

    // ---- la tenue entière

    // Les traits du skin de l'utilisateur, vus de face et de dos (12 rangées par partie). . = le clair, o = l'orange,
    // # = un trait. De face, le bras droit est à gauche ; de dos, c'est le gauche.
    private static final String[] DEVANT_BRAS_D = {"o..#", "o..#", "o..#", "o...", "o..#", "o...", "o...", "o...", "oooo", "####", "....", "...."};
    private static final String[] DEVANT_CORPS = {"..#..#..", "#o#..#o#", "#o#..#o#", ".##..##.", "#.#..#.#", "..#..#..", "..####..", "..#..#..", "..#..#..", "..####..", "..#..#..", "...##..."};
    private static final String[] DEVANT_BRAS_G = {"#..o", "#..o", "#..o", "...o", "#..o", "...o", "...o", "...o", "oooo", "####", "....", "...."};
    private static final String[] DEVANT_JAMBES = {".#.oo.#.", "..#oo#..", "..#oo#..", ".#.oo.#.", "..#oo#..", "..#oo#..", "..#oo#..", "##.oo.##", "##.oo.##", ".##oo##.", "########", "ooo##ooo"};
    private static final String[] DOS_BRAS_G = {"...o", "...o", "...o", "...o", "...o", "...o", "...o", "...o", "oooo", "####", "....", "...."};
    private static final String[] DOS_CORPS = {"...##...", "..####..", "..####..", "..####..", ".#.##.#.", ".#.##.#.", ".#.##.#.", "#..##..#", "#..##..#", "#..##..#", "#..##..#", "#..##..#"};
    private static final String[] DOS_BRAS_D = {"o...", "o...", "o...", "o...", "o...", "o...", "o...", "o...", "oooo", "####", "....", "...."};
    private static final String[] DOS_JAMBES = {"........", "........", "........", "........", "........", "........", "........", ".###.##.", "#..##..#", "o......o", "########", "oooooooo"};
    /** Les moustaches du skin Kurama : deux traits par joue (rangées 13 et 15), effacés (le visage n'a que la bouche). */
    private static final int[] MOUSTACHES_X = {6, 7, 8, 15, 16, 17};

    private static String[] coupe(String[] l, int a, int b) {
        String[] r = new String[l.length];
        for (int i = 0; i < l.length; i++) {
            r[i] = l[i].substring(a, b);
        }
        return r;
    }

    /** Une partie (son coin de texture u, v, sa largeur w ; profondeur 4, hauteur 12) peinte avec son devant et son dos. */
    private static void peindre(int[] px, int u, int v, int w, String[] devant, String[] dos) {
        int d = 4;
        for (int j = 0; j < 12; j++) {
            String av = devant[j], ar = dos[j];
            char cote = av.chars().allMatch(c -> c == '#') && ar.chars().allMatch(c -> c == '#') ? '#'
                    : av.chars().allMatch(c -> c == 'o') && ar.chars().allMatch(c -> c == 'o') ? 'o' : '.';
            for (int i = 0; i < w; i++) {
                px[(v + d + j) * 64 + u + d + i] = couleur(av.charAt(i));
                px[(v + d + j) * 64 + u + d + w + d + i] = couleur(ar.charAt(i));
            }
            for (int i = 0; i < d; i++) {
                px[(v + d + j) * 64 + u + i] = couleur(cote);
                px[(v + d + j) * 64 + u + d + w + i] = couleur(cote);
            }
        }
        // le dessus et le dessous : du clair
        for (int j = 0; j < d; j++) {
            for (int i = 0; i < w; i++) {
                px[(v + j) * 64 + u + d + i] = couleur('.');
                px[(v + j) * 64 + u + d + w + i] = couleur('.');
            }
        }
    }

    private static int[] entiere(int[] base) {
        int[] kcm = ClientKurama.tenue();
        if (kcm == null) {
            return null;
        }
        int[] px = ClientKurama.composer(base, kcm, 99);
        for (int x : MOUSTACHES_X) {
            for (int y : new int[]{13, 15}) {
                px[y * 64 + x] = px[12 * 64 + x];
            }
        }
        // la tête (cheveux, visage, yeux) passée en bleu, à luminosité égale
        for (int i = 0; i < px.length; i++) {
            if (ClientKurama.PARTIE[i] == ClientKurama.TETE && !Float.isNaN(ClientKurama.HAUTEUR[i]) && (px[i] >>> 24) > 128) {
                px[i] = bleuir(px[i]);
            }
        }
        // le corps, les bras et les jambes : la tenue bleue et ses traits (la seconde couche reste vide, comme le skin)
        peindre(px, 16, 16, 8, DEVANT_CORPS, DOS_CORPS);
        peindre(px, 40, 16, 4, DEVANT_BRAS_D, DOS_BRAS_D);
        peindre(px, 32, 48, 4, DEVANT_BRAS_G, DOS_BRAS_G);
        peindre(px, 0, 16, 4, coupe(DEVANT_JAMBES, 0, 4), coupe(DOS_JAMBES, 4, 8));
        peindre(px, 16, 48, 4, coupe(DEVANT_JAMBES, 4, 8), coupe(DOS_JAMBES, 0, 4));
        return px;
    }

    // ---- les couleurs (ABGR, comme NativeImage)

    private static final double[][] BLEUS = {{0, 10, 30, 80}, {90, 30, 90, 190}, {140, 50, 130, 225}, {185, 90, 170, 245}, {225, 135, 205, 255}, {255, 178, 228, 255}};
    private static final int NUIT = abgr(16, 24, 46);
    private static final int IRIS = abgr(30, 140, 255);

    private static int abgr(double r, double g, double b) {
        return 0xFF000000 | (int) Mth.clamp(Math.round(b), 0, 255) << 16 | (int) Mth.clamp(Math.round(g), 0, 255) << 8 | (int) Mth.clamp(Math.round(r), 0, 255);
    }

    private static int couleur(char c) {
        return c == '#' ? NUIT : c == 'o' ? bleuir(abgr(245, 180, 100)) : bleuir(abgr(254, 253, 161));
    }

    /** Une couleur du mode Kurama passée en bleu : le rouge des yeux en bleu vif, les traits sombres en bleu nuit, le reste selon sa luminosité. */
    private static int bleuir(int c) {
        int r = c & 255, g = c >> 8 & 255, b = c >> 16 & 255;
        if (r > 150 && g < 110 && b < 110) {
            return IRIS;
        }
        double l = 0.3 * r + 0.59 * g + 0.11 * b;
        if (l < 70) {
            double k = l / 70;
            return abgr(16 + 24 * k, 24 + 36 * k, 46 + 54 * k);
        }
        for (int i = 1; i < BLEUS.length; i++) {
            if (l <= BLEUS[i][0] || i == BLEUS.length - 1) {
                double[] p = BLEUS[i - 1], q = BLEUS[i];
                double u = Mth.clamp((l - p[0]) / (q[0] - p[0]), 0, 1);
                return abgr(p[1] + (q[1] - p[1]) * u, p[2] + (q[2] - p[2]) * u, p[3] + (q[3] - p[3]) * u);
            }
        }
        return c;
    }

    private ClientTenueFoudre() {
    }
}
