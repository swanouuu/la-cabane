package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * La grande flèche (docs/fruits/planche-attaques2.png), en quads de couleur (POSITION_COLOR) : une hampe, une longue
 * tête à facettes et ses quatre barbes, quatre pennes au bout, une spirale autour de la hampe ; elle tourne doucement
 * sur elle-même. La glace l'a en cristal (le clic droit du fruit, niveau 2), le Susano 3 en flammes noires de sa couleur.
 */
public final class GrandeFleche {
    /** Sa longueur, de la pointe au bout des pennes, à la taille 1 (4 blocs et demi), et celle de sa tête. */
    public static final double LONGUEUR = 4.5;
    private static final double TETE = 1.5;

    /** Ses couleurs (r, g, b, a) : la hampe, le pied et le bout des pointes (tête, barbes, pennes), la spirale. */
    public record Teintes(float[] hampe, float[] pied, float[] bout, float[] spirale) {
    }

    public static final Teintes GLACE = new Teintes(new float[]{0.72F, 0.88F, 1F, 0.85F}, new float[]{0.62F, 0.84F, 1F, 0.9F},
            new float[]{0.97F, 0.99F, 1F, 0.95F}, new float[]{1F, 1F, 1F, 0.9F});

    /** En flammes noires, bordées de cette couleur (celle du Susano). */
    public static Teintes feuNoir(float[] couleur) {
        return new Teintes(new float[]{0.05F, 0.02F, 0.07F, 0.97F}, new float[]{0.06F, 0.02F, 0.08F, 0.97F},
                new float[]{couleur[0], couleur[1], couleur[2], 0.95F}, new float[]{couleur[0], couleur[1], couleur[2], 0.9F});
    }

    /**
     * La flèche, la pointe en {@code bout} (déjà relative à la caméra), vers {@code dir}. {@code visible} : combien on en
     * voit depuis la pointe (au départ, elle sort encore de la main) ; {@code taille} : 1 pour 4 blocs et demi ;
     * {@code t} : le temps, en secondes (la spirale et la rotation).
     */
    public static void dessiner(VertexConsumer c, Matrix4f m, Vec3 bout, Vec3 dir, double visible, double taille, double t, Teintes teintes) {
        Vec3 f = dir.normalize();
        Vec3 e1 = Math.abs(f.y) < 0.95 ? f.cross(new Vec3(0, 1, 0)).normalize() : new Vec3(1, 0, 0);
        Vec3 e2 = e1.cross(f).normalize();
        double l = Math.min(visible, LONGUEUR * taille), tete = TETE * taille;
        // la tête
        Vec3 pied = bout.subtract(f.scale(tete));
        pic(c, m, pied, f, tete, 0.56 * taille, teintes.pied(), teintes.bout());
        // la hampe, jusqu'où on la voit
        if (l > tete) {
            poutre(c, m, pied.add(f.scale(0.1 * taille)), bout.subtract(f.scale(l)), e1, e2, 0.11 * taille, teintes.hampe());
        }
        double tour = t * 4;
        for (int k = 0; k < 4; k++) {
            double a = tour + k * Math.PI / 2 + Math.PI / 4;
            Vec3 r = e1.scale(Math.cos(a)).add(e2.scale(Math.sin(a)));
            // les barbes, au pied de la tête, qui partent en arrière
            pic(c, m, pied.add(f.scale(0.19 * taille)).add(r.scale(0.16 * taille)), r.scale(0.8).subtract(f), 0.75 * taille, 0.21 * taille,
                    teintes.pied(), teintes.bout());
            // les pennes, au bout de la hampe (quand on la voit toute)
            if (l >= LONGUEUR * taille - 1e-3) {
                pic(c, m, bout.subtract(f.scale(l - 1.0 * taille)).add(r.scale(0.11 * taille)), r.scale(0.6).subtract(f), 1.25 * taille, 0.31 * taille,
                        teintes.pied(), teintes.bout());
            }
        }
        // une spirale de givre (ou de braises) autour de la hampe
        float[] s = teintes.spirale();
        for (int q = 0; q < 16; q++) {
            double z = tete + (q + 0.5) / 16 * (LONGUEUR * taille - tete);
            if (z > l) {
                break;
            }
            double a = q * 0.95 + t * 9, r = (0.25 + 0.06 * Math.sin(q)) * taille;
            Vec3 p = bout.subtract(f.scale(z)).add(e1.scale(Math.cos(a) * r)).add(e2.scale(Math.sin(a) * r));
            pic(c, m, p.subtract(f.scale(0.05 * taille)), f, 0.1 * taille, 0.08 * taille, s, s);
        }
    }

    /** Une pointe à quatre pans, d'une couleur (r, g, b, a) au pied à une autre au bout ; les pans un peu plus ou moins clairs. */
    public static void pic(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 dir, double longueur, double largeur, float[] pied, float[] bout) {
        Vec3 d = dir.normalize();
        Vec3 e1 = Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)).normalize() : d.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 e2 = d.cross(e1).normalize();
        Vec3 cime = base.add(d.scale(longueur));
        Vec3[] coins = {base.add(e1.add(e2).scale(largeur / 2)), base.add(e1.subtract(e2).scale(largeur / 2)),
                base.add(e1.scale(-1).subtract(e2).scale(largeur / 2)), base.add(e2.subtract(e1).scale(largeur / 2))};
        for (int i = 0; i < 4; i++) {
            float k = 0.85F + 0.15F * (i % 2);
            Vec3 a = coins[i], b = coins[(i + 1) % 4];
            sommet(c, m, a, pied[0] * k, pied[1] * k, pied[2] * k, pied[3]);
            sommet(c, m, b, pied[0] * k, pied[1] * k, pied[2] * k, pied[3]);
            sommet(c, m, cime, bout[0], bout[1], bout[2], bout[3]);
            sommet(c, m, cime, bout[0], bout[1], bout[2], bout[3]);
        }
    }

    /** Une poutre carrée de {@code a} à {@code b} (de demi-largeur {@code r}), ses quatre faces un peu plus ou moins claires. */
    private static void poutre(VertexConsumer c, Matrix4f m, Vec3 a, Vec3 b, Vec3 e1, Vec3 e2, double r, float[] couleur) {
        Vec3[] coins = {e1.add(e2).scale(r), e1.subtract(e2).scale(r), e1.scale(-1).subtract(e2).scale(r), e2.subtract(e1).scale(r)};
        for (int i = 0; i < 4; i++) {
            float k = 0.8F + 0.2F * (i % 2);
            Vec3 u = coins[i], v = coins[(i + 1) % 4];
            sommet(c, m, a.add(u), couleur[0] * k, couleur[1] * k, couleur[2] * k, couleur[3]);
            sommet(c, m, a.add(v), couleur[0] * k, couleur[1] * k, couleur[2] * k, couleur[3]);
            sommet(c, m, b.add(v), couleur[0] * k, couleur[1] * k, couleur[2] * k, couleur[3]);
            sommet(c, m, b.add(u), couleur[0] * k, couleur[1] * k, couleur[2] * k, couleur[3]);
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private GrandeFleche() {
    }
}
