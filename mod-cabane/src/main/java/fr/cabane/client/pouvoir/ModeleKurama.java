package fr.cabane.client.pouvoir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Kurama en chakra, pour la Bijuu Dama de Minato 2 : le modèle et la texture du Kurama d'« AHZNB's Naruto ShinobiCraft »
 * (texture du mode Kurama, avec les yeux rouges de sa texture de base). Ils sont à leur auteur : ils ne sont pas dans le
 * mod, seulement dans le pack privé « Beauvais - voix » (assets/cabane/kurama/modele.json, l'arbre des pièces rejoué
 * depuis son modèle 1.12, et textures/entity/kurama/kurama.png). Sans le pack, une silhouette de chakra le remplace.
 * <p>
 * Pour le contre de Minato ({@link ClientContre}), le même Kurama en renard orange (textures/entity/kurama/kurama_orange.png,
 * sa texture de base, aussi dans le pack privé), accroupi, la tête basse entre ses pattes de devant, prêt à bondir.
 */
final class ModeleKurama {
    private static final ResourceLocation MODELE = Cabane.id("kurama/modele.json");
    private static final ResourceLocation TEXTURE = Cabane.id("textures/entity/kurama/kurama.png");
    /** Le modèle fait environ 51 pixels de haut (queues comprises) : 3,2 blocs, avant l'échelle. */
    static final double HAUTEUR_MODELE = 51 / 16.0;

    /** Une face : ses quatre coins (en pixels du modèle, y vers le bas, l'avant vers -z), leur texture, si elle brille (les yeux). */
    private record Face(float[] x, float[] y, float[] z, float[] u, float[] v, boolean brille, Vector3f centre) {
    }

    private static List<Face> faces;
    private static boolean absent;

    private static List<Face> faces() {
        if (faces == null && !absent) {
            try (var flux = Minecraft.getInstance().getResourceManager().open(MODELE)) {
                JsonObject racine = JsonParser.parseReader(new InputStreamReader(flux, StandardCharsets.UTF_8)).getAsJsonObject();
                List<Face> l = new ArrayList<>();
                for (JsonElement r : racine.getAsJsonArray("racines")) {
                    JsonObject n = r.getAsJsonObject();
                    lire(n, new Matrix3f(), new Vector3f(), "field_178720_f".equals(n.get("nom").getAsString()), l, null, null);
                }
                faces = l;
                arbre = racine;
            } catch (Exception e) {
                // pas de pack privé : la silhouette
                absent = true;
            }
        }
        return faces;
    }

    private static float[] tableau(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = a.get(i).getAsFloat();
        }
        return f;
    }

    /** Comme ModelRenderer.render : la translation du point de rotation, puis les rotations Z, Y, X ; puis les enfants. */
    private static void lire(JsonObject n, Matrix3f m, Vector3f o, boolean brille, List<Face> l, Map<String, Float> ajuste, Map<String, Vector3f> centres) {
        float[] rp = tableau(n.getAsJsonArray("rp"));
        float[] rot = tableau(n.getAsJsonArray("rot"));
        String nom = n.has("nom") && !n.get("nom").isJsonNull() ? n.get("nom").getAsString() : "";
        if (ajuste != null && ajuste.containsKey(nom)) {
            rot[0] += ajuste.get(nom);
        }
        Vector3f o2 = new Vector3f(rp[0], rp[1], rp[2]).mul(m).add(o);
        Matrix3f r = new Matrix3f(m).rotateZ(rot[2]).rotateY(rot[1]).rotateX(rot[0]);
        // le contre grossit sa tête autour de son point de rotation (ses enfants suivent)
        if (ajuste != null && ajuste.containsKey("echelle:" + nom)) {
            r.scale(ajuste.get("echelle:" + nom));
        }
        for (JsonElement e : n.getAsJsonArray("boites")) {
            JsonObject b = e.getAsJsonObject();
            float g = b.get("gonfle").getAsFloat();
            float x0 = b.get("x").getAsFloat() - g, y0 = b.get("y").getAsFloat() - g, z0 = b.get("z").getAsFloat() - g;
            int dx = b.get("dx").getAsInt(), dy = b.get("dy").getAsInt(), dz = b.get("dz").getAsInt();
            float x1 = x0 + dx + 2 * g, y1 = y0 + dy + 2 * g, z1 = z0 + dz + 2 * g;
            if (b.get("miroir").getAsBoolean()) {
                float t = x0;
                x0 = x1;
                x1 = t;
            }
            float u = b.get("u").getAsFloat(), v = b.get("v").getAsFloat();
            float[][] s = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            Vector3f centre = new Vector3f((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2).mul(r).add(o2);
            if (centres != null) {
                centres.putIfAbsent(nom, centre);
            }
            // les six faces de ModelBox (1.12) : leurs coins (v7 = s0, v = s1, v1 = s2, v2 = s3, v3 = s4, v4 = s5, v5 = s6, v6 = s7) et leur rectangle de texture
            int[][] coins = {{5, 1, 2, 6}, {0, 4, 7, 3}, {5, 4, 0, 1}, {2, 3, 7, 6}, {1, 0, 3, 2}, {4, 5, 6, 7}};
            float[][] uv = {{u + dz + dx, v + dz, u + dz + dx + dz, v + dz + dy}, {u, v + dz, u + dz, v + dz + dy}, {u + dz, v, u + dz + dx, v + dz},
                    {u + dz + dx, v + dz, u + dz + dx + dx, v}, {u + dz, v + dz, u + dz + dx, v + dz + dy}, {u + dz + dx + dz, v + dz, u + dz + dx + dz + dx, v + dz + dy}};
            for (int f = 0; f < 6; f++) {
                if (uv[f][0] == uv[f][2] || uv[f][1] == uv[f][3]) {
                    continue;
                }
                float[] fx = new float[4], fy = new float[4], fz = new float[4];
                for (int k = 0; k < 4; k++) {
                    Vector3f p = new Vector3f(s[coins[f][k]]).mul(r).add(o2);
                    fx[k] = p.x;
                    fy[k] = p.y;
                    fz[k] = p.z;
                }
                // TexturedQuad : coin 0 → (u2, v1), 1 → (u1, v1), 2 → (u1, v2), 3 → (u2, v2), sur une texture de 64 de côté
                float[] fu = {uv[f][2] / 64, uv[f][0] / 64, uv[f][0] / 64, uv[f][2] / 64};
                float[] fv = {uv[f][1] / 64, uv[f][1] / 64, uv[f][3] / 64, uv[f][3] / 64};
                l.add(new Face(fx, fy, fz, fu, fv, brille, centre));
            }
        }
        for (JsonElement e : n.getAsJsonArray("enfants")) {
            lire(e.getAsJsonObject(), r, o2, brille, l, ajuste, centres);
        }
    }

    /**
     * Kurama debout en pieds (relatif à la caméra), tourné vers f (horizontal, unitaire), haut de hauteur blocs, grandi de
     * k (0 à 1 : il apparaît). Tout brille : c'est du chakra.
     */
    static void dessiner(PoseStack pose, MultiBufferSource buffers, Vec3 pieds, Vec3 f, double hauteur, double k) {
        if (k <= 0.01) {
            return;
        }
        List<Face> l = faces();
        if (l == null) {
            silhouette(pose, buffers, pieds, f, hauteur * k);
            return;
        }
        double e = hauteur / HAUTEUR_MODELE / 16 * k;
        // le repère du modèle (x, y vers le bas, avant vers -z) → le monde : x' = côté, y' = haut, z' = vers f
        Vec3 cote = new Vec3(f.z, 0, -f.x);
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        PoseStack.Pose p = pose.last();
        for (Face face : l) {
            Vec3[] q = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                q[i] = place(pieds, f, cote, face.x()[i], face.y()[i], face.z()[i], e);
            }
            // la normale, tournée vers l'extérieur de sa boîte (pour l'éclairage des entités)
            Vec3 n = q[1].subtract(q[0]).cross(q[2].subtract(q[0]));
            n = n.lengthSqr() < 1.0E-12 ? new Vec3(0, 1, 0) : n.normalize();
            Vec3 milieu = q[0].add(q[1]).add(q[2]).add(q[3]).scale(0.25);
            if (n.dot(milieu.subtract(place(pieds, f, cote, face.centre().x, face.centre().y, face.centre().z, e))) < 0) {
                n = n.reverse();
            }
            int lumiere = LightTexture.FULL_BRIGHT;
            for (int i = 0; i < 4; i++) {
                c.addVertex(p, (float) q[i].x, (float) q[i].y, (float) q[i].z).setColor(255, 255, 255, 255).setUv(face.u()[i], face.v()[i])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(lumiere).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
            }
        }
    }

    /** Un point du modèle (pixels, y vers le bas, l'avant vers -z) dans le monde : x de côté, y en haut, l'avant vers f. */
    private static Vec3 place(Vec3 pieds, Vec3 f, Vec3 cote, float x, float y, float z, double e) {
        return pieds.add(cote.scale(x * e)).add(0, (24 - y) * e, 0).add(f.scale(-z * e));
    }

    /** Sans le pack privé : un grand renard de chakra en lumière (le corps, la tête, les oreilles et neuf queues). */
    private static void silhouette(PoseStack pose, MultiBufferSource buffers, Vec3 pieds, Vec3 f, double h) {
        Vec3 cote = new Vec3(f.z, 0, -f.x);
        Effets.sphere(pose, buffers, pieds.add(0, h * 0.32, 0), h * 0.26, 1F, 0.62F, 0.12F, 0.7F);
        Effets.sphere(pose, buffers, pieds.add(f.scale(h * 0.22)).add(0, h * 0.58, 0), h * 0.15, 1F, 0.7F, 0.15F, 0.8F);
        for (int s = -1; s <= 1; s += 2) {
            Vec3 base = pieds.add(f.scale(h * 0.2)).add(cote.scale(s * h * 0.08)).add(0, h * 0.7, 0);
            Effets.tube(pose, buffers, base, base.add(cote.scale(s * h * 0.06)).add(0, h * 0.14, 0).subtract(f.scale(h * 0.05)), h * 0.025, 1F, 0.55F, 0.1F, 0.8F);
        }
        for (int q = 0; q < 9; q++) {
            double a = (q - 4) / 4.0 * 1.4;
            Vec3 base = pieds.subtract(f.scale(h * 0.25)).add(0, h * 0.3, 0);
            Vec3 bout = base.add(cote.scale(Math.sin(a) * h * 0.45)).add(0, Math.cos(a) * h * 0.55, 0).subtract(f.scale(h * 0.15));
            Effets.tube(pose, buffers, base, bout, h * 0.04, 1F, 0.6F, 0.12F, 0.6F);
        }
    }

    /** La gueule (où se forme la Bijuu Dama), par rapport à ses pieds : en avant et en hauteur. */
    static Vec3 gueule(Vec3 pieds, Vec3 f, double hauteur, double k) {
        double e = hauteur * k;
        return pieds.add(f.scale(e * 0.31)).add(0, e * 0.53, 0);
    }

    // ================================================================ le contre de Minato : accroupi, la tête basse

    private static final ResourceLocation TEXTURE_ORANGE = Cabane.id("textures/entity/kurama/kurama_orange.png");
    private static JsonObject arbre;
    private static Boolean orange;
    /**
     * La pose validée (docs/minato2/generateur/anim-contre.mjs) : le buste penché en avant autour d'un point à PIVOT pixels
     * au-dessus des pieds, les pattes de devant tournées à plat vers l'avant, la tête relevée pour regarder devant.
     */
    private static final float PENCHE = 1.45F, BRAS = -2.1F, LEVE = -1.35F, PIVOT = 20 / 3.5F;
    /** Sa tête, deux fois et demie plus grosse (l'utilisateur la voulait 2 à 3 fois plus grosse). */
    private static final float GROSSE_TETE = 2.5F;
    /** Son museau et sa mâchoire, raccourcis : vu de face et d'en bas, un long museau cachait ses yeux (« un gros nez »). */
    private static final float MUSEAU = 1F, MACHOIRE = 1F;

    /**
     * Les faces posées, en pixels dans son repère (x de côté, y en haut depuis le sol, z vers l'avant), sa gueule, le milieu
     * de sa tête, et jusqu'où vont ses pattes vers l'avant.
     */
    record Pose(List<Face> faces, Vector3f gueule, Vector3f tete, float avant) {
    }

    private static final Map<Integer, Pose> POSES = new HashMap<>();

    /** Accroupi, la gueule ouverte de machoire (radians) ; null sans le pack privé. Gardé par vingtième de radian. */
    static Pose accroupi(double machoire) {
        if (faces() == null || arbre == null) {
            return null;
        }
        int cle = (int) Math.round(machoire * 20);
        return POSES.computeIfAbsent(cle, k -> poser(k / 20F));
    }

    private static Pose poser(float machoire) {
        Map<String, Float> ajuste = new HashMap<>();
        ajuste.put("jaw", machoire);
        ajuste.put("field_178723_h", BRAS);
        ajuste.put("field_178724_i", BRAS);
        ajuste.put("field_78116_c", LEVE);
        ajuste.put("eyes", LEVE);
        ajuste.put("echelle:field_78116_c", GROSSE_TETE);
        ajuste.put("echelle:eyes", GROSSE_TETE);
        ajuste.put("echelle:snout", MUSEAU);
        ajuste.put("echelle:jaw", MACHOIRE);
        List<Face> brutes = new ArrayList<>();
        Map<String, Vector3f> centres = new HashMap<>();
        for (JsonElement r : arbre.getAsJsonArray("racines")) {
            JsonObject n = r.getAsJsonObject();
            String nom = n.get("nom").getAsString();
            if ("field_178720_f".equals(nom)) {
                lire(n, new Matrix3f(), new Vector3f(), true, brutes, ajuste, centres);
            } else if ("body".equals(nom)) {
                // seulement la tête, la poitrine et les pattes de devant
                JsonObject corps = n.deepCopy();
                JsonArray garde = new JsonArray();
                for (JsonElement e : n.getAsJsonArray("enfants")) {
                    JsonObject enfant = e.getAsJsonObject();
                    String en = enfant.get("nom").getAsString();
                    if ("field_78116_c".equals(en)) {
                        garde.add(enfant.deepCopy());
                    } else if ("field_78115_e".equals(en)) {
                        JsonObject torse = enfant.deepCopy();
                        torse.add("boites", new JsonArray());
                        JsonArray bras = new JsonArray();
                        for (JsonElement b : enfant.getAsJsonArray("enfants")) {
                            String bn = b.getAsJsonObject().get("nom").getAsString();
                            if ("chest".equals(bn) || "field_178723_h".equals(bn) || "field_178724_i".equals(bn)) {
                                bras.add(b.deepCopy());
                            }
                        }
                        torse.add("enfants", bras);
                        garde.add(torse);
                    }
                }
                corps.add("enfants", garde);
                lire(corps, new Matrix3f(), new Vector3f(), false, brutes, ajuste, centres);
            }
        }
        // du repère du modèle (y vers le bas, l'avant vers -z) au sien (y en haut, l'avant vers +z), penché en avant
        float bas = Float.MAX_VALUE, avant = -Float.MAX_VALUE;
        List<Face> l = new ArrayList<>();
        for (Face f : brutes) {
            float[] x = new float[4], y = new float[4], z = new float[4];
            for (int i = 0; i < 4; i++) {
                Vector3f p = penche(f.x()[i], f.y()[i], f.z()[i]);
                x[i] = p.x;
                y[i] = p.y;
                z[i] = p.z;
                bas = Math.min(bas, p.y);
                avant = Math.max(avant, p.z);
            }
            l.add(new Face(x, y, z, f.u(), f.v(), f.brille(), penche(f.centre().x, f.centre().y, f.centre().z)));
        }
        // posé au sol
        List<Face> sol = new ArrayList<>();
        for (Face f : l) {
            float[] y = f.y().clone();
            for (int i = 0; i < 4; i++) {
                y[i] -= bas;
            }
            sol.add(new Face(f.x(), y, f.z(), f.u(), f.v(), f.brille(), new Vector3f(f.centre()).sub(0, bas, 0)));
        }
        Vector3f gueule = centres.containsKey("jaw") ? penche(centres.get("jaw")).sub(0, bas, 0) : new Vector3f(0, 8, avant * 0.5F);
        Vector3f tete = centres.containsKey("field_78116_c") ? penche(centres.get("field_78116_c")).sub(0, bas, 0) : new Vector3f(0, 10, avant * 0.4F);
        return new Pose(sol, gueule, tete, avant);
    }

    private static Vector3f penche(Vector3f p) {
        return penche(p.x, p.y, p.z);
    }

    private static Vector3f penche(float x, float y, float z) {
        float h = 24 - y - PIVOT, a = -z;
        float c = (float) Math.cos(PENCHE), s = (float) Math.sin(PENCHE);
        return new Vector3f(x, h * c - a * s + PIVOT, h * s + a * c);
    }

    /** Le centre d'une de ses faces, au hasard (pour les éclats quand il se défait). */
    static Vector3f auHasard(Pose p, net.minecraft.util.RandomSource r) {
        return p.faces().isEmpty() ? new Vector3f() : p.faces().get(r.nextInt(p.faces().size())).centre();
    }

    /** Un point de son repère accroupi (en pixels) dans le monde : pieds, tourné vers f, e blocs par pixel. */
    static Vec3 point(Vec3 pieds, Vec3 f, double e, Vector3f p) {
        Vec3 cote = new Vec3(f.z, 0, -f.x);
        return pieds.add(cote.scale(p.x * e)).add(0, p.y * e, 0).add(f.scale(p.z * e));
    }

    /**
     * Kurama accroupi (relatif à la caméra), tourné vers f, e blocs par pixel du modèle, la gueule ouverte de machoire.
     * Tout brille, en orange (le jaune du mode Kurama si le pack privé n'a pas encore la texture orange).
     */
    static void dessinerAccroupi(PoseStack pose, MultiBufferSource buffers, Vec3 pieds, Vec3 f, double e, double machoire) {
        Pose p = accroupi(machoire);
        if (p == null) {
            silhouette(pose, buffers, pieds, f, 20 * e);
            return;
        }
        if (orange == null) {
            orange = Minecraft.getInstance().getResourceManager().getResource(TEXTURE_ORANGE).isPresent();
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(orange ? TEXTURE_ORANGE : TEXTURE));
        PoseStack.Pose m = pose.last();
        for (Face face : p.faces()) {
            Vec3[] q = new Vec3[4];
            for (int i = 0; i < 4; i++) {
                q[i] = point(pieds, f, e, new Vector3f(face.x()[i], face.y()[i], face.z()[i]));
            }
            Vec3 n = q[1].subtract(q[0]).cross(q[2].subtract(q[0]));
            n = n.lengthSqr() < 1.0E-12 ? new Vec3(0, 1, 0) : n.normalize();
            Vec3 milieu = q[0].add(q[1]).add(q[2]).add(q[3]).scale(0.25);
            if (n.dot(milieu.subtract(point(pieds, f, e, face.centre()))) < 0) {
                n = n.reverse();
            }
            for (int i = 0; i < 4; i++) {
                c.addVertex(m, (float) q[i].x, (float) q[i].y, (float) q[i].z).setColor(255, 255, 255, 255).setUv(face.u()[i], face.v()[i])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(m, (float) n.x, (float) n.y, (float) n.z);
            }
        }
    }


    private ModeleKurama() {
    }
}
