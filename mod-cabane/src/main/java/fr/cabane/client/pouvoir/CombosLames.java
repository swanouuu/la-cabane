package fr.cabane.client.pouvoir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cabane.Cabane;
import fr.cabane.lames.Lames.Sorte;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Les combos et les clics droits des lames, tels que l'aperçu 3D les montre : assets/cabane/lames/combos.json, écrit par
 * docs/lames-brume/generateur/jeu.mjs depuis combos.mjs (la seule source). Chaque coup est une suite de poses clés ; une
 * pose a 18 valeurs (voir les indices ci-dessous ; angles en radians, pour un droitier, dans le repère du modèle de
 * Minecraft ; haut et avant en pixels).
 * <p>
 * Le squelette de l'aperçu est refait ici ({@link #bout}) : il donne la pointe de la lame à chaque instant, pour que la
 * traînée suive exactement la lame (même à la première personne, où le corps n'est pas dessiné).
 */
final class CombosLames {
    static final int BD = 0, BG = 3, CD = 6, CG = 7, JD = 8, JG = 9, GD = 10, GG = 11, PENCHE = 12, TOUR = 13, HAUT = 14, AVANT = 15,
            POIGNET = 16, ROULIS = 17, N = 18;
    private static final String[] CANAUX = {"cd", "cg", "jd", "jg", "gd", "gg", "penche", "tour", "haut", "avant", "poignet", "roulis"};

    /** Une pose clé : à ce tick, atteinte avec cet easing (lin, sortie, entree, doux). */
    record Cle(int t, String ease, float[] v) {
    }

    /** Un coup : son nom, sa durée (le tick de la dernière clé), quand la traînée suit la pointe, ses clés ; tour : il fait un tour complet. */
    record Coup(String nom, int duree, int trace0, int trace1, Cle[] cles, boolean tour) {
    }

    /** Une lame : la longueur de la prise à la pointe (pixels), la couleur de la traînée, ses coups, son clic droit. */
    record Lame(float pointe, int couleur, boolean deuxMains, String gauche, Coup[] coups, Coup special, String effet, int recharge) {
    }

    private static Map<Sorte, Lame> lames;

    static Lame de(Sorte s) {
        if (lames == null) {
            lames = lire();
        }
        return lames.get(s);
    }

    private static Map<Sorte, Lame> lire() {
        Map<Sorte, Lame> m = new EnumMap<>(Sorte.class);
        try (var r = new InputStreamReader(Minecraft.getInstance().getResourceManager().open(Cabane.id("lames/combos.json")),
                StandardCharsets.UTF_8)) {
            JsonObject tout = JsonParser.parseReader(r).getAsJsonObject();
            for (Sorte s : Sorte.values()) {
                JsonObject o = tout.getAsJsonObject(s.id);
                if (o == null) {
                    continue;
                }
                JsonArray cs = o.getAsJsonArray("coups");
                Coup[] coups = new Coup[cs.size()];
                for (int i = 0; i < coups.length; i++) {
                    coups[i] = coup(cs.get(i).getAsJsonObject());
                }
                JsonObject sp = o.getAsJsonObject("special");
                JsonElement g = o.get("gauche");
                m.put(s, new Lame(o.get("pointe").getAsFloat(), o.get("couleur").getAsInt(), o.get("deuxMains").getAsBoolean(),
                        g == null || g.isJsonNull() ? null : g.getAsString(), coups, coup(sp), sp.get("effet").getAsString(),
                        sp.get("recharge").getAsInt()));
            }
        } catch (Exception e) {
            Cabane.LOGGER.error("lames/combos.json illisible", e);
        }
        return m;
    }

    private static Coup coup(JsonObject o) {
        JsonArray ks = o.getAsJsonArray("cles");
        Cle[] cles = new Cle[ks.size()];
        for (int i = 0; i < cles.length; i++) {
            JsonObject k = ks.get(i).getAsJsonObject();
            float[] v = new float[N];
            for (int a = 0; a < 3; a++) {
                v[BD + a] = k.getAsJsonArray("bd").get(a).getAsFloat();
                v[BG + a] = k.getAsJsonArray("bg").get(a).getAsFloat();
            }
            for (int c = 0; c < CANAUX.length; c++) {
                if (k.has(CANAUX[c])) {
                    v[CD + c] = k.get(CANAUX[c]).getAsFloat();
                }
            }
            cles[i] = new Cle(k.get("t").getAsInt(), k.get("ease").getAsString(), v);
        }
        JsonElement tr = o.get("trace");
        int t0 = 999, t1 = 999;
        if (tr != null && tr.isJsonArray()) {
            t0 = tr.getAsJsonArray().get(0).getAsInt();
            t1 = tr.getAsJsonArray().get(1).getAsInt();
        }
        boolean tour = Math.abs(cles[cles.length - 1].v()[TOUR] - cles[0].v()[TOUR]) > 3;
        return new Coup(o.get("nom").getAsString(), cles[cles.length - 1].t(), t0, t1, cles, tour);
    }

    static float ease(String e, float u) {
        u = Mth.clamp(u, 0, 1);
        return switch (e) {
            case "lin" -> u;
            case "sortie" -> 1 - (1 - u) * (1 - u) * (1 - u);
            case "entree" -> u * u * u;
            default -> u * u * (3 - 2 * u);
        };
    }

    /** La pose du coup au tick t (comme l'aperçu : l'easing est celui de la clé d'arrivée). */
    static float[] pose(Coup c, float t) {
        Cle[] k = c.cles();
        int i = 0;
        while (i < k.length - 2 && t > k[i + 1].t()) {
            i++;
        }
        Cle a = k[i], b = k[i + 1];
        float u = ease(b.ease(), (t - a.t()) / Math.max(1e-4F, b.t() - a.t()));
        float[] r = new float[N];
        for (int q = 0; q < N; q++) {
            r[q] = Mth.lerp(u, a.v()[q], b.v()[q]);
        }
        return r;
    }

    /**
     * Comment la lame est tournée dans le poing (repère des croquis, depuis l'avant-bras) : L vers la pointe, W sa largeur.
     * poignet 0 : elle part devant le poing ; 1 : dans l'axe de l'avant-bras ; roulis : elle tourne sur elle-même ;
     * hache : la largeur à l'envers (le tranchant de la hache devant).
     */
    static Vector3f[] orienter(float poignet, float roulis, boolean hache) {
        Vector3f L = new Vector3f(0, 0.25F, 1).lerp(new Vector3f(0, -1, 0.08F), poignet).normalize();
        Vector3f W = new Vector3f(0, 1, -0.25F).lerp(new Vector3f(0, 0.08F, 1), poignet);
        if (hache) {
            W.negate();
        }
        W.sub(new Vector3f(L).mul(W.dot(L))).normalize().rotateAxis(roulis, L.x, L.y, L.z);
        return new Vector3f[]{L, W};
    }

    /**
     * La pointe et le milieu de la lame de la main droite, dans le repère du joueur (en blocs, depuis ses pieds ; y vers
     * le haut, l'avant vers +z, la gauche vers +x) : le squelette de l'aperçu (le corps, le pli à la taille, l'épaule, le
     * coude, le poing).
     */
    static Vector3f[] bout(float[] p, float pointe, boolean hache) {
        Matrix4f m = new Matrix4f().scale(1 / 16F)
                .translate(0, p[HAUT], p[AVANT])
                .rotateY(p[TOUR])
                .translate(0, 18, 0)
                .rotateX(-p[PENCHE])
                .translate(-5, 4, 0)
                .rotateZ(-p[BD + 2]).rotateY(-p[BD + 1]).rotateX(p[BD])
                .translate(-1, -4, 0)
                .rotateX(p[CD])
                .translate(0, -5, 0);
        Vector3f[] lw = orienter(p[POIGNET], p[ROULIS], hache);
        Vector3f L = lw[0];
        Vector3f bout = m.transformPosition(new Vector3f(L).mul(pointe));
        Vector3f milieu = m.transformPosition(new Vector3f(L).mul(pointe * 0.32F));
        return new Vector3f[]{bout, milieu};
    }

    private CombosLames() {
    }
}
