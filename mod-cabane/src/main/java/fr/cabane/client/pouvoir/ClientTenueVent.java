package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Ce que porte celui que le Vent a éveillé (docs/vent/planche-formes.png), sur son propre corps :
 * <ul>
 * <li>niveau 1 : son skin, et le col de fourrure bleu nuit ;</li>
 * <li>niveau 2 : les cheveux verts, l'uniforme rouge et blanc (posé du haut vers le bas quand la boule de vent éclate),
 * l'écharpe et la grande cape bleues, la couronne de lumière, les ailes de la fée en grand dans le dos, les runes vertes au
 * bras droit ;</li>
 * <li>niveau 3 : l'esprit du vent, tout vert (des pieds à la tête) et qui brille, le long manteau à fourrure, la cape verte,
 * la haute couronne de lances, la hallebarde en main et les quatre lances croisées dans le dos.</li>
 * </ul>
 * Les pièces viennent des mêmes dessins que les planches (docs/vent/generateur/jeu.mjs) ; la cape vole au vent.
 */
public final class ClientTenueVent {
    private static final int ETAPES = 12;

    // ================================================================ la peau

    /** « texture|mode|étape » → la peau à cette étape (une texture à nous) ; les tenues entières, par texture de base. */
    private static final Map<String, ResourceLocation> PEAUX = new HashMap<>();
    private static final Map<ResourceLocation, int[]> UNIFORMES = new HashMap<>();
    private static final Map<ResourceLocation, int[]> ESPRITS = new HashMap<>();
    private static final Map<String, Long> RATEES = new HashMap<>();
    private static final Map<ResourceLocation, PlayerSkin> REMPLACEES = new HashMap<>();
    private static int[] uniformeCorps, espritCorps;
    private static boolean lus;

    /** Ce qui recouvre son skin : l'uniforme posé depuis la tête, l'esprit depuis les pieds, ou l'un ou l'autre qui s'en va. */
    private static final int UNI = 0, ESP = 1, FIN_UNI = 2, FIN_ESP = 3;

    /** Appelé à chaque {@code getSkin()} (AbstractClientPlayerMixin). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        ClientVent.Etat e = ClientVent.etat(joueur.getUUID());
        if (e == null) {
            return skin;
        }
        int mode;
        double k;
        if (e.fin >= 0) {
            if (e.niveauFin < 2) {
                return skin;
            }
            mode = e.niveauFin >= 3 ? FIN_ESP : FIN_UNI;
            k = ClientVent.lisse(ClientVent.secondes(e.fin) / 0.8);
            if (k >= 1) {
                return skin;
            }
        } else if (e.niveau >= 3) {
            mode = ESP;
            k = ClientVent.lisse((ClientVent.secondes(e.debut3) - 1.0) / 0.8);
        } else if (e.niveau == 2) {
            mode = UNI;
            k = ClientVent.lisse((ClientVent.secondes(e.debut2) - 1.2) / 0.4);
            if (k <= 0) {
                return skin;
            }
        } else {
            return skin;
        }
        ResourceLocation id = texture(skin, mode, (int) Math.round(k * ETAPES));
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

    private static ResourceLocation texture(PlayerSkin vraie, int mode, int etape) {
        String cle = vraie.texture() + "|" + mode + "|" + etape;
        ResourceLocation id = PEAUX.get(cle);
        if (id != null || System.nanoTime() < RATEES.getOrDefault(cle, 0L)) {
            return id;
        }
        int[] base = ClientMaudit.lire(vraie.texture());
        lireTenues();
        if (base == null || uniformeCorps == null || espritCorps == null) {
            RATEES.put(cle, System.nanoTime() + 2_000_000_000L);
            return null;
        }
        int[] uni = UNIFORMES.computeIfAbsent(vraie.texture(), t -> composer(base, null, false));
        int[] esp = ESPRITS.computeIfAbsent(vraie.texture(), t -> composer(base, espritCorps, true));
        // ce qui est dessous, ce qui est dessus, et d'où ça vient : l'uniforme descend de la tête, l'esprit monte des
        // pieds ; en s'en allant, le skin revient depuis la tête
        int[] dessous = switch (mode) {
            case UNI -> base;
            case ESP -> uni;
            case FIN_UNI -> uni;
            default -> esp;
        };
        int[] dessus = switch (mode) {
            case UNI -> uni;
            case ESP -> esp;
            default -> base;
        };
        boolean depuisHaut = mode != ESP;
        float ligne = depuisHaut ? 33F * (ETAPES - etape) / ETAPES : 33F * etape / ETAPES;
        int[] px = dessous.clone();
        for (int i = 0; i < px.length; i++) {
            float h = ClientKurama.HAUTEUR[i];
            if (Float.isNaN(h)) {
                continue;
            }
            if (depuisHaut ? h >= ligne : h < ligne) {
                px[i] = dessus[i];
            }
        }
        NativeImage image = new NativeImage(64, 64, true);
        for (int i = 0; i < px.length; i++) {
            image.setPixelRGBA(i % 64, i / 64, px[i]);
        }
        id = Cabane.id("vent/tenue_" + PEAUX.size());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        PEAUX.put(cle, id);
        RATEES.remove(cle);
        return id;
    }

    /** Les tenues du corps (faites par le générateur des planches), lues une fois. */
    private static void lireTenues() {
        if (lus) {
            return;
        }
        lus = true;
        uniformeCorps = lirePng(Cabane.id("textures/entity/vent/uniforme.png"));
        espritCorps = lirePng(Cabane.id("textures/entity/vent/esprit.png"));
    }

    private static int[] lirePng(ResourceLocation id) {
        try (InputStream flux = Minecraft.getInstance().getResourceManager().open(id); NativeImage image = NativeImage.read(flux)) {
            int[] px = new int[64 * 64];
            for (int i = 0; i < px.length; i++) {
                px[i] = image.getPixelRGBA(i % 64, i / 64);
            }
            return px;
        } catch (IOException | RuntimeException e) {
            Cabane.LOGGER.warn("Vent : tenue illisible {}", id, e);
            return null;
        }
    }

    // ---- composer une tenue sur son skin (comme peindre, dans le générateur)

    static final double[][] ESPRIT = {{0, 16, 70, 40}, {0.3, 40, 150, 80}, {0.55, 80, 205, 120}, {0.8, 150, 245, 175}, {1, 220, 255, 228}};
    private static final int[][] VISAGE = {{9, 13}, {10, 13}, {13, 13}, {14, 13}, {9, 14}, {10, 14}, {13, 14}, {14, 14}, {9, 15}, {14, 15}};

    /** Une couleur sur une rampe (comme rampe() dans le générateur), en r, g, b. */
    static double[] rampe(double[][] arrets, double t) {
        for (int k = 1; k < arrets.length; k++) {
            if (t <= arrets[k][0] || k == arrets.length - 1) {
                double[] p = arrets[k - 1], q = arrets[k];
                double u = Mth.clamp((t - p[0]) / (q[0] - p[0]), 0, 1);
                return new double[]{p[1] + (q[1] - p[1]) * u, p[2] + (q[2] - p[2]) * u, p[3] + (q[3] - p[3]) * u};
            }
        }
        return new double[]{255, 255, 255};
    }

    private static int abgr(double r, double g, double b) {
        return 0xFF000000 | (int) Mth.clamp(Math.round(b), 0, 255) << 16 | (int) Mth.clamp(Math.round(g), 0, 255) << 8 | (int) Mth.clamp(Math.round(r), 0, 255);
    }

    private static int abgr(double[] c) {
        return abgr(c[0], c[1], c[2]);
    }

    /** La couleur de sa peau : la médiane de quelques pixels du visage. */
    private static int[] peauDe(int[] px) {
        int[][] l = new int[3][VISAGE.length];
        int n = 0;
        for (int[] p : VISAGE) {
            int c = px[p[1] * 64 + p[0]];
            if ((c >>> 24) > 128) {
                l[0][n] = c & 255;
                l[1][n] = c >> 8 & 255;
                l[2][n] = c >> 16 & 255;
                n++;
            }
        }
        if (n == 0) {
            return new int[]{200, 150, 120};
        }
        int[] r = new int[3];
        for (int k = 0; k < 3; k++) {
            int[] t = Arrays.copyOf(l[k], n);
            Arrays.sort(t);
            r[k] = t[n / 2];
        }
        return r;
    }

    /** La tenue entière sur ce skin : la tête recoloriée (sa peau reste, ou devient vert pâle), le reste vient du générateur. */
    private static int[] composer(int[] base, int[] corps, boolean esprit) {
        int[] px = base.clone();
        int[] peau = peauDe(base);
        for (int i = 0; i < px.length; i++) {
            float h = ClientKurama.HAUTEUR[i];
            if (Float.isNaN(h)) {
                continue;
            }
            int c = base[i];
            if (ClientKurama.PARTIE[i] == ClientKurama.TETE) {
                if ((c >>> 24) < 128) {
                    continue;
                }
                int r = c & 255, g = c >> 8 & 255, b = c >> 16 & 255;
                boolean premiere = i % 64 < 32;
                boolean saPeau = premiere && Math.sqrt((r - peau[0]) * (r - peau[0]) + (g - peau[1]) * (g - peau[1]) + (b - peau[2]) * (b - peau[2])) < 52;
                double lum = 0.3 * r + 0.59 * g + 0.11 * b;
                // les pupilles (rangée 12 du visage, le pixel intérieur de chaque œil) : jaunes, aux niveaux 2 et 3
                int x = i % 64, y = i / 64;
                if (y == 12 && (x == 10 || x == 13)) {
                    px[i] = lum < 90 ? abgr(200, 140, 10) : abgr(255, 236, 90);
                    continue;
                }
                if (esprit) {
                    px[i] = saPeau ? abgr(196, 255, 210) : lum < 40 ? abgr(20, 90, 50) : abgr(rampe(ESPRIT, 0.45 + 0.55 * lum / 255));
                }
                // l'uniforme (niveau 2) : la tête garde son skin (pas de cheveux verts)
                continue;
            }
            if (corps == null) {
                // le niveau 2 : son skin, tel quel
                continue;
            }
            int t = corps[i];
            if ((t >>> 24) == 0) {
                // la seconde couche du corps : vide (la tenue la recouvre)
                px[i] = 0;
            } else if ((t & 0xFFFFFF) == (3 << 16 | 2 << 8 | 1)) {
                // la peau des mains
                px[i] = abgr(peau[0], peau[1], peau[2]);
            } else {
                px[i] = t | 0xFF000000;
            }
        }
        return px;
    }

    // ================================================================ les pièces portées

    /** Une pièce du générateur, posée sur les parties du joueur, à l'échelle k (elle pousse) et à l'opacité alpha. */
    private static void piece(String nom, PoseStack ps, MultiBufferSource buffers, int lumiere, ModelPart[] parties, float k, float alpha) {
        if (k <= 0.001F || alpha <= 0.01F) {
            return;
        }
        ModeleVent[] l = ModeleVent.lire(nom);
        for (int i = 0; i < l.length && i < parties.length; i++) {
            if (!parties[i].visible && i == 0) {
                continue;
            }
            ps.pushPose();
            parties[i].translateAndRotate(ps);
            ps.scale(k, k, k);
            l[i].dessiner(buffers, ps.last(), lumiere, 1 / 16F, alpha);
            ps.popPose();
        }
    }

    /** Ce qui pousse entre « de » et « a » secondes : 0 avant, 1 après (un peu au-delà au milieu : ça jaillit, puis ça se pose). */
    private static float pousse(double t, double de, double a) {
        double k = Mth.clamp((t - de) / (a - de), 0, 1);
        return (float) (k * (1 + 0.25 * Math.sin(k * Math.PI)));
    }

    /** Sur le joueur (ClientVent.Couche) : selon son niveau et où en est sa transformation. */
    static void dessiner(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> modele,
            ClientVent.Etat e, float partiel) {
        ModelPart[] parties = {modele.head, modele.body, modele.rightArm, modele.leftArm, modele.rightLeg, modele.leftLeg};
        // à la fin de l'éveil, tout s'envole en feuilles : les pièces s'effacent vite
        float efface = e.fin >= 0 ? (float) Math.max(0, 1 - ClientVent.secondes(e.fin) / 0.35) : 1;
        if (efface <= 0) {
            return;
        }
        int niveau = e.fin >= 0 ? e.niveauFin : e.niveau;
        double t2 = e.debut2 < 0 ? 99 : ClientVent.secondes(e.debut2);
        double t3 = e.debut3 < 0 ? 99 : ClientVent.secondes(e.debut3);
        boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
        float battement = battement(joueur, e, niveau, partiel);
        // niveau 1 : rien sur lui (la fée et le vent suffisent)
        boolean deux = niveau == 2 || niveau >= 3 && t3 < ClientVent.ILLUMINE;
        if (deux) {
            // au niveau 3, la tenue du niveau 2 disparaît dans la lumière verte
            float sort = niveau >= 3 ? (float) Math.max(0, 1 - (t3 - (ClientVent.ILLUMINE - 0.3)) / 0.3) : 1;
            piece("runes", ps, buffers, lumiere, parties, pousse(t2, 1.4, 1.75), efface * sort);
            // l'aile de la fée, du côté droit seulement
            aile("aile2_d", -1, ps, buffers, lumiere, modele, pousse(t2, 1.55, 2.0) * sort, efface, battement);
            // la moitié droite de la couronne, verte et qui brille : elle monte se poser au-dessus de la tête
            float kc = pousse(t2, 1.6, 2.0) * sort;
            if (kc > 0) {
                ps.pushPose();
                ps.translate(0, (float) (3 * (1 - Mth.clamp((t2 - 1.6) / 0.4, 0, 1))) / 16, 0);
                piece("couronne2_demi", ps, buffers, lumiere, new ModelPart[]{modele.head}, kc, efface);
                ps.popPose();
            }
            // une petite aura verte
            float monte = (float) Mth.clamp((t2 - 1.2) / 0.6, 0, 1) * efface * sort;
            if (monte > 0) {
                float vacille = 0.8F + 0.2F * Mth.sin((joueur.tickCount + partiel) * 0.35F);
                VertexConsumer aura = buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientHachibi.MANTEAU));
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.04F, (Math.round(34 * monte * vacille) << 24) | 0x7CFFA4);
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.16F, (Math.round(12 * monte * vacille) << 24) | 0xB8FFCC);
            }
        }
        if (niveau == 2 && t2 > 1.3 || niveau >= 3 && t3 > ClientVent.ILLUMINE) {
            yeux(ps, buffers, modele, efface);
        }
        if (niveau >= 3) {
            double a = ClientVent.ILLUMINE;
            piece("manteau_court", ps, buffers, lumiere, parties, pousse(t3, a, a + 0.4), efface);
            piece("col_esprit", ps, buffers, lumiere, parties, pousse(t3, a, a + 0.4), efface);
            piece("couronne3", ps, buffers, lumiere, parties, pousse(t3, a + 0.1, a + 0.9), efface);
            if (!ClientVent.lancesParties(joueur.getUUID())) {
                piece("lances_dos", ps, buffers, lumiere, parties, pousse(t3, a + 0.3, a + 1.0), efface);
            }
            // les ailes de la fée, pleines et vert vif, des deux côtés
            float ka = pousse(t3, a + 0.3, a + 1.1);
            aile("aile3_d", -1, ps, buffers, lumiere, modele, ka, efface, battement, 5.5F);
            aile("aile3_g", 1, ps, buffers, lumiere, modele, ka, efface, battement, 5.5F);
            // la cape : enlevée (pas de cape sur les pouvoirs, demandé le 2026-11-08)
            float ouvre = 0;
            if (ouvre > 0) {
                float[] c = f(rampe(ESPRIT, 0.5)), cl = f(rampe(ESPRIT, 0.85)), bord = f(rampe(ESPRIT, 1));
                cape(ps, buffers, lumiere, modele, joueur, c, cl, bord, true, true, 34, 18, ouvre, efface, partiel);
            }
            float vacille = 0.85F + 0.15F * Mth.sin((joueur.tickCount + partiel) * 0.3F);
            VertexConsumer aura = buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientHachibi.MANTEAU));
            // la transformation : il s'illumine de vert, longtemps, puis la lumière retombe sur sa forme
            double lumineux = ClientVent.lisse((t3 - 0.15) / 0.5) * (1 - ClientVent.lisse((t3 - ClientVent.ECLAT3) / 0.9));
            if (lumineux > 0.01) {
                float l = (float) lumineux * efface;
                float pulse = 0.85F + 0.15F * Mth.sin((joueur.tickCount + partiel) * 0.9F);
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.02F, (Math.round(235 * l) << 24) | 0xA8FFC4);
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.18F, (Math.round(120 * l * pulse) << 24) | 0x6CFF9C);
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.45F, (Math.round(50 * l * pulse) << 24) | 0x9CFFB8);
            }
            // ensuite, il brille doucement
            float monte = (float) Mth.clamp((t3 - a) / 0.8, 0, 1) * efface;
            if (monte > 0) {
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1F, (Math.round(54 * monte * vacille) << 24) | 0x8CFFB4);
                ClientHachibi.enveloppe(modele, ps, aura, fin, 1.14F, (Math.round(20 * monte * vacille) << 24) | 0xC8FFD8);
            }
        }
    }

    /** Les pupilles qui brillent en jaune (un seul pixel par œil). */
    private static void yeux(PoseStack ps, MultiBufferSource buffers, PlayerModel<AbstractClientPlayer> modele, float alpha) {
        if (!modele.head.visible) {
            return;
        }
        ps.pushPose();
        modele.head.translateAndRotate(ps);
        PoseStack.Pose pose = ps.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucentEmissive(ModeleVent.BLANC));
        float z = -4.55F / 16;
        int a = Math.round(255 * alpha);
        for (float x0 : new float[]{-2, 1}) {
            for (float[] p : new float[][]{{x0, -4}, {x0 + 1, -4}, {x0 + 1, -3}, {x0, -3}}) {
                c.addVertex(pose, p[0] / 16, p[1] / 16, z).setColor(255, 226, 70, a).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 0, -1);
            }
        }
        ps.popPose();
    }

    /**
     * Le battement des ailes (un angle, en radians) : elles bougent un peu quand il marche, battent quand il saute (l'aile
     * du niveau 2 aussi) et battent grand quand il vole (niveau 3).
     */
    private static float battement(AbstractClientPlayer j, ClientVent.Etat e, int niveau, float partiel) {
        double t = (j.tickCount + partiel) / 20.0;
        double v = Mth.clamp(Math.hypot(j.getX() - j.xo, j.getZ() - j.zo) * 4, 0, 1);
        double amp, freq;
        if (!j.onGround() && niveau >= 3) {
            amp = 0.5;
            freq = 9;
        } else if (!j.onGround()) {
            amp = 0.42;
            freq = 11;
        } else {
            amp = 0.05 + 0.2 * v;
            freq = 3 + 6 * v;
        }
        // chaque saut en l'air (niveau 2) : un grand coup d'aile
        if (e.saut >= 0 && ClientVent.secondes(e.saut) < 0.6) {
            amp += 0.55 * (1 - ClientVent.secondes(e.saut) / 0.6);
        }
        return (float) (0.12 + amp * Math.sin(t * freq));
    }

    /** Une aile (son groupe « corps »), qui tourne autour de sa racine dans le dos : elle bat. côté : -1 droite, 1 gauche. */
    private static void aile(String nom, int cote, PoseStack ps, MultiBufferSource buffers, int lumiere, PlayerModel<AbstractClientPlayer> modele,
            float k, float alpha, float angle) {
        aile(nom, cote, ps, buffers, lumiere, modele, k, alpha, angle, 1.5F);
    }

    /** recul : de combien de pixels l'aile est écartée du dos (au niveau 3, elle passe par-dessus la cape). */
    private static void aile(String nom, int cote, PoseStack ps, MultiBufferSource buffers, int lumiere, PlayerModel<AbstractClientPlayer> modele,
            float k, float alpha, float angle, float recul) {
        if (k <= 0.001F || alpha <= 0.01F) {
            return;
        }
        ModeleVent[] l = ModeleVent.lire(nom);
        if (l.length < 2) {
            return;
        }
        ps.pushPose();
        modele.body.translateAndRotate(ps);
        ps.translate(0, 0, recul / 16);
        float px = cote * 1.2F / 16, py = 4.5F / 16, pz = 2.6F / 16;
        ps.translate(px, py, pz);
        ps.mulPose(com.mojang.math.Axis.YP.rotation(cote * angle));
        ps.mulPose(com.mojang.math.Axis.ZP.rotation(-cote * angle * 0.35F));
        ps.scale(k, k, k);
        ps.translate(-px, -py, -pz);
        l[1].dessiner(buffers, ps.last(), lumiere, 1 / 16F, alpha);
        ps.popPose();
    }

    private static float[] f(double[] c) {
        return new float[]{(float) c[0], (float) c[1], (float) c[2]};
    }

    // ================================================================ la cape

    /**
     * La cape accrochée aux épaules, qui vole au vent (comme cape() dans le générateur) : ondulée, plus couchée quand il
     * court ou qu'il vole ; une bordure dorée (niveau 2) ou de fourrure (niveau 3). {@code ouvre} : elle s'ouvre (0 à 1).
     */
    private static void cape(PoseStack ps, MultiBufferSource buffers, int lumiere, PlayerModel<AbstractClientPlayer> modele, AbstractClientPlayer j,
            float[] couleur, float[] clair, float[] bord, boolean fourrure, boolean brille, float longueur, float evase, float ouvre, float alpha, float partiel) {
        if (alpha < 0.99F) {
            return;
        }
        int nu = 10, nv = 14;
        double temps = (j.tickCount + partiel) / 20.0;
        Vec3 dm = j.getDeltaMovement();
        double vite = Mth.clamp(dm.horizontalDistance() * 3.5 + (j.getAbilities().flying ? 0.5 : 0), 0, 1);
        double ph = temps * (3 + 3 * vite);
        // le sens du vent, dans le repère du corps (y en haut, le dos vers -z) : vers le bas et l'arrière, plus couché s'il va vite
        Vec3 v = new Vec3(0.12 * Math.sin(temps * 0.9), -0.85 + 0.6 * vite, -0.45 - 0.7 * vite).normalize();
        // couché en avant (il vole comme un super-héros) : elle file vers ses pieds, à peine décollée du dos
        float vol = ClientVent.vol(j.getUUID());
        if (vol > 0) {
            v = v.lerp(new Vec3(0.1 * Math.sin(temps * 3), -1, -0.18).normalize(), vol).normalize();
        }
        Vec3[][] g = new Vec3[nv + 1][nu + 1];
        for (int jj = 0; jj <= nv; jj++) {
            double vv = jj / (double) nv;
            for (int i = 0; i <= nu; i++) {
                double u = i / (double) nu;
                double x = Mth.lerp(u, -4.4, 4.4) + (u - 0.5) * evase * vv * ouvre;
                double y = 23.6 + v.y * longueur * vv * ouvre + Math.sin(u * 4 + vv * 6 + ph) * 1.8 * vv * vv * ouvre;
                double z = -2.4 + v.z * longueur * vv * ouvre - (1.2 * vv + Math.sin(u * 7.5 + vv * 5 + ph) * 2.2 * vv) * ouvre;
                x += v.x * longueur * vv * ouvre;
                // du repère des planches à celui du corps dans le jeu (y vers le bas, le dos vers +z), en blocs
                g[jj][i] = new Vec3(x / 16, (24 - y) / 16, -z / 16);
            }
        }
        ps.pushPose();
        modele.body.translateAndRotate(ps);
        PoseStack.Pose pose = ps.last();
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutout(ModeleVent.BLANC));
        int l = brille ? LightTexture.FULL_BRIGHT : lumiere;
        for (int jj = 0; jj < nv; jj++) {
            for (int i = 0; i < nu; i++) {
                Vec3 p0 = g[jj][i], p1 = g[jj][i + 1], p2 = g[jj + 1][i + 1], p3 = g[jj + 1][i];
                Vec3 n = p1.subtract(p0).cross(p3.subtract(p0)).normalize();
                double vv = (jj + 0.5) / nv;
                float m = (float) (0.15 + 0.45 * vv * (0.6 + 0.4 * Math.sin(i * 1.3 + jj * 0.4)));
                float[] col = {Mth.lerp(m, couleur[0], clair[0]), Mth.lerp(m, couleur[1], clair[1]), Mth.lerp(m, couleur[2], clair[2])};
                // l'extérieur, puis l'intérieur (plus sombre), chacun de son côté
                face(c, pose, p0, p1, p2, p3, n, col, 1F, l);
                face(c, pose, p0, p3, p2, p1, n.scale(-1), col, 0.62F, l);
            }
        }
        // la bordure : une bande dorée, ou des touffes de fourrure
        for (int i = 0; i < nu; i++) {
            Vec3 a = g[nv][i], b = g[nv][i + 1];
            Vec3 bas = new Vec3(0, (fourrure ? 2.2 : 0.9) / 16, 0);
            Vec3 n = b.subtract(a).cross(bas).normalize();
            float[] col = bord;
            int lb = LightTexture.FULL_BRIGHT;
            face(c, pose, a, b, b.add(bas), a.add(bas), n, col, 1F, lb);
            face(c, pose, a, a.add(bas), b.add(bas), b, n.scale(-1), col, 1F, lb);
            if (fourrure) {
                Vec3 m = a.add(b).scale(0.5).add(0, 1.6 / 16, 0);
                Vec3 e = new Vec3(0, 0, 1.1 / 16);
                face(c, pose, m.subtract(e).add(-1.0 / 16, 0, 0), m.subtract(e).add(1.0 / 16, 0, 0), m.add(e).add(1.0 / 16, 0, 0), m.add(e).add(-1.0 / 16, 0, 0),
                        new Vec3(0, 1, 0), col, 0.9F, lb);
            }
        }
        ps.popPose();
    }

    private static void face(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 d, Vec3 e, Vec3 n, float[] col, float ombre, int lumiere) {
        int r = (int) (col[0] * ombre), g = (int) (col[1] * ombre), bl = (int) (col[2] * ombre);
        for (Vec3 p : new Vec3[]{a, b, d, e}) {
            c.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, bl, 255).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(lumiere).setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    /** Un autre joueur, un autre skin : on oublie tout (au changement de monde). */
    static void vider() {
        REMPLACEES.clear();
    }

    /** La couche du joueur : ses pièces (ClientVent.Couche s'en sert). */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            ClientVent.Etat e = ClientVent.etat(joueur.getUUID());
            if (e == null || joueur.isInvisible()) {
                return;
            }
            dessiner(ps, buffers, lumiere, joueur, getParentModel(), e, partiel);
        }
    }

    private ClientTenueVent() {
    }
}
