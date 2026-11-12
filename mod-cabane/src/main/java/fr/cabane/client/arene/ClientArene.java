package fr.cabane.client.arene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.arene.Arene;
import fr.cabane.arene.AreneBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Ce que le jeu dessine lui-même dans l'arène :
 * <ul>
 * <li>l'écran géant (4 faces de 24 × 13) et le petit écran de la salle d'entrée : une caméra qui suit le combat — les vrais
 * combattants, avec leur skin, leur armure et leurs gestes, sur un fond d'arène — avec les pseudos, les cœurs, le score,
 * le chrono et la bannière VICTOIRE ;</li>
 * <li>les panneaux d'attente bleu et rouge de la salle d'entrée, l'enseigne des gradins et le tableau du kit ;</li>
 * <li>trois dragons qui tournent dans le ciel, autour des îles (seulement dessinés : ils ne font rien).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientArene {
    private static Arene.EtatArene etat;
    private static long recu;
    /** La caméra de l'écran : angle de vue, centre et zoom, lissés. */
    private static double vue = Math.PI, cx, cz, zoomNorme = 0.05;
    private static long derniereImage;
    private static final List<EnderDragon> DRAGONS = new ArrayList<>();
    private static final double[] PROCHAIN_BATTEMENT = new double[3];

    public static void etat(Arene.EtatArene e) {
        etat = e;
        recu = System.currentTimeMillis();
    }

    private static boolean ici() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Arene.DIMENSION;
    }

    @SubscribeEvent
    public static void dessiner(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || !ici()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        long maintenant = System.currentTimeMillis();
        double dt = derniereImage == 0 ? 0 : Math.min(0.1, (maintenant - derniereImage) / 1000.0);
        derniereImage = maintenant;
        dragons(ps, buf, cam, partiel, maintenant / 1000.0);
        cadrer(partiel, dt);
        double sol = AreneBuilder.SOL;
        // l'écran géant : seulement les faces tournées vers nous
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2, nx = Math.sin(a), nz = Math.cos(a);
            Vec3 centre = new Vec3(nx * (AreneBuilder.DEMI + 0.03), sol + AreneBuilder.ECRAN_Y, nz * (AreneBuilder.DEMI + 0.03));
            if ((cam.x - centre.x) * nx + (cam.z - centre.z) * nz <= 0) {
                continue;
            }
            ps.pushPose();
            ps.translate(centre.x - cam.x, centre.y - cam.y, centre.z - cam.z);
            ps.mulPose(Axis.YP.rotation((float) a));
            image(ps, buf, 24F, 13F, partiel);
            ps.popPose();
        }
        // la salle d'entrée : ses panneaux et son petit écran, quand on n'en est pas loin
        Vec3 salle = new Vec3(0, sol + 4, 86);
        if (cam.distanceToSqr(salle) < 60 * 60) {
            ps.pushPose();
            ps.translate(-cam.x, -cam.y, -cam.z);
            salle(ps, buf, partiel, maintenant / 1000.0);
            ps.popPose();
        }
    }

    // ------------------------------------------------------------------ la caméra qui suit le combat

    private record Vu(LivingEntity e, int cote) {
    }

    private static List<Vu> combattants() {
        List<Vu> l = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (etat == null || mc.level == null) {
            return l;
        }
        for (int i = 0; i < etat.ids().size(); i++) {
            Entity e = mc.level.getEntity(etat.ids().get(i));
            if (e instanceof LivingEntity le && !e.isRemoved()) {
                l.add(new Vu(le, etat.cotes().get(i)));
            }
        }
        return l;
    }

    /** Vue de côté, perpendiculaire à la ligne entre les deux premiers adversaires ; seul, la caméra tourne doucement autour. */
    private static void cadrer(float partiel, double dt) {
        List<Vu> l = combattants();
        if (l.isEmpty()) {
            vue += dt * 0.1;
            return;
        }
        double mx = 0, mz = 0;
        for (Vu v : l) {
            mx += v.e().getX();
            mz += v.e().getZ();
        }
        mx /= l.size();
        mz /= l.size();
        Vu a = l.stream().filter(v -> v.cote() == 0).findFirst().orElse(null), b = l.stream().filter(v -> v.cote() == 1).findFirst().orElse(null);
        double cible = vue;
        if (a != null && b != null && a.e().distanceToSqr(b.e()) > 0.04) {
            double dx = b.e().getX() - a.e().getX(), dz = b.e().getZ() - a.e().getZ();
            // l'avant de la caméra, perpendiculaire à la ligne ; des deux, celui qui garde le bleu à gauche
            // un peu de trois quarts : on voit les visages, pas seulement les profils
            cible = Math.atan2(dz, -dx) + 0.55;
        } else {
            cible = vue + dt * 0.15;
        }
        double ecart = Math.atan2(Math.sin(cible - vue), Math.cos(cible - vue));
        vue += ecart * Math.min(1, dt * 1.2);
        double k = Math.min(1, dt * 2.5);
        cx += (mx - cx) * k;
        cz += (mz - cz) * k;
    }

    /** L'image d'un écran, dans le repère de sa face (x à droite, y en haut, z vers nous, centre au milieu), de L × H blocs. */
    private static void image(PoseStack ps, MultiBufferSource.BufferSource buf, float L, float H, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        float k = L / 640F, hpx = H / k;
        // le fond (en pixels d'une image de 640 de large, y vers le bas)
        ps.pushPose();
        ps.scale(k, -k, k);
        ps.translate(-320, -hpx / 2, 0);
        Matrix4f m = ps.last().pose();
        VertexConsumer c = buf.getBuffer(RenderType.debugQuads());
        float horizon = hpx * 0.42F, gradins = hpx * 0.2F;
        degrade(c, m, 0, 0, 640, gradins, 0xFF5E8FD0, 0xFFB7D1EA, 0);
        // les gradins au loin : bandes de bois, tentures bleues à gauche, rouges à droite
        degrade(c, m, 0, gradins, 640, horizon, 0xFF6B4F2E, 0xFF8A6A40, 0);
        for (int r = 0; r < 6; r++) {
            float y = gradins + (horizon - gradins) * r / 6F;
            rect(c, m, 0, y, 640, y + 1.5F, 0xFF3D2A17, 0.1F);
        }
        rect(c, m, 0, gradins + 4, 290, gradins + 9, 0xFF3A5BC7, 0.1F);
        rect(c, m, 350, gradins + 4, 640, gradins + 9, 0xFFB8322C, 0.1F);
        rect(c, m, 0, horizon - 6, 640, horizon, 0xFF77746F, 0.1F);
        degrade(c, m, 0, horizon, 640, hpx, 0xFFC9B27A, 0xFFE0C98F, 0);
        buf.endBatch(RenderType.debugQuads());
        ps.popPose();

        // les combattants, à plat sur l'écran, vus de côté
        List<Vu> l = combattants();
        float pied = -H / 2 + H * 0.13F;
        // le haut de la place libre : sous la barre des pseudos
        float plafond = H / 2 - 64 * k;
        if (!l.isEmpty()) {
            double fx = Math.sin(vue), fz = Math.cos(vue), rx = -fz, rz = fx;
            double portee = 1.2, hauteur = 2.2;
            for (Vu v : l) {
                double u = (v.e().getX() - cx) * rx + (v.e().getZ() - cz) * rz;
                portee = Math.max(portee, Math.abs(u) + 1.2);
                hauteur = Math.max(hauteur, v.e().getY() - AreneBuilder.SOL + 2.1);
            }
            // le zoom est retenu par bloc de largeur d'écran : le grand écran et le petit de la salle cadrent pareil
            double voulu = Math.max(0.012, Math.min(0.1, Math.min((L / 2 - 0.8) / portee, (plafond - pied) * 0.92 / hauteur) / L));
            zoomNorme += (voulu - zoomNorme) * 0.05;
            double zoom = zoomNorme * L;
            float theta = (float) Math.atan2(fx, -fz);
            List<Vu> tries = new ArrayList<>(l);
            tries.sort(Comparator.comparingDouble(v -> -((v.e().getX() - cx) * fx + (v.e().getZ() - cz) * fz)));
            EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
            d.setRenderShadow(false);
            int ordre = 0;
            for (Vu v : tries) {
                LivingEntity e = v.e();
                double ex = e.xo + (e.getX() - e.xo) * partiel, ey = e.yo + (e.getY() - e.yo) * partiel, ez = e.zo + (e.getZ() - e.zo) * partiel;
                double u = (ex - cx) * rx + (ez - cz) * rz, w = (ex - cx) * fx + (ez - cz) * fz, h = ey - AreneBuilder.SOL;
                float per = (float) Math.max(0.7, Math.min(1.3, 1 / (1 + w * 0.025)));
                float s = (float) zoom * per;
                float x = (float) Math.max(-L / 2 + 0.6, Math.min(L / 2 - 0.6, u * zoom));
                float y = (float) Math.min(plafond - 1.95 * s, pied + h * zoom);
                ps.pushPose();
                ps.translate(x, y, 0.05F + ordre++ * 0.02F);
                ps.scale(s, s, s * 0.015F);
                ps.mulPose(Axis.YP.rotation(theta));
                d.render(e, 0, 0, 0, e.getYRot(), partiel, ps, buf, LightTexture.FULL_BRIGHT);
                ps.popPose();
            }
            buf.endBatch();
            d.setRenderShadow(mc.options.entityShadows().get());
        }

        // par-dessus : pseudos, cœurs, score, chrono, bannière
        ps.pushPose();
        ps.translate(0, 0, 0.6F);
        ps.scale(k, -k, k);
        ps.translate(-320, -hpx / 2, 0);
        hud(ps, buf, hpx, l);
        ps.popPose();
    }

    private static void hud(PoseStack ps, MultiBufferSource.BufferSource buf, float hpx, List<Vu> l) {
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        Matrix4f m = ps.last().pose();
        VertexConsumer c = buf.getBuffer(RenderType.debugQuads());
        Arene.EtatArene e = etat;
        boolean combat = e != null && e.phase() == 3;
        long ms = System.currentTimeMillis();
        if (combat) {
            rect(c, m, 0, 0, 640, 62, 0xCC0A0C14, 0);
            rect(c, m, 0, 0, 8, 62, 0xFF3A5BC7, 0.1F);
            rect(c, m, 632, 0, 640, 62, 0xFFB8322C, 0.1F);
            for (int cote = 0; cote < 2; cote++) {
                float[] pv = pvCote(e, cote);
                for (int i = 0; i < 10; i++) {
                    float x = cote == 0 ? 18 + i * 19 : 606 - i * 19;
                    float plein = Math.max(0, Math.min(1, (pv[0] / Math.max(1, pv[1]) * 20 - i * 2) / 2F));
                    rect(c, m, x, 36, x + 16, 50, 0xFF2A0A0A, 0.1F);
                    if (plein > 0) {
                        rect(c, m, x + 1, 37, x + 1 + 14 * plein, 49, 0xFFE5302A, 0.2F);
                    }
                }
            }
            rect(c, m, 12, hpx - 42, 176, hpx - 12, 0xBF0A0C14, 0);
            rect(c, m, 22, hpx - 33, 34, hpx - 21, (ms / 500) % 2 == 0 ? 0xFFE5302A : 0xFF7A1A16, 0.1F);
        }
        boolean victoire = e != null && e.victoireReste() > 0 && !e.victoire().isEmpty() && (ms - recu) < e.victoireReste() * 50L + 500;
        if (victoire) {
            rect(c, m, 0, hpx / 2 - 55, 640, hpx / 2 + 55, 0x8C000000, 0.05F);
        } else if (!combat) {
            rect(c, m, 60, hpx / 2 - 50, 580, hpx / 2 + 50, 0x99101018, 0.05F);
        }
        buf.endBatch(RenderType.debugQuads());
        if (combat) {
            texte(ps, buf, f, nomCote(e, 0), 18, 9, ajuste(f, nomCote(e, 0), 2.3F, 230), 0xFF9FB8FF, false);
            texte(ps, buf, f, nomCote(e, 1), 622, 9, ajuste(f, nomCote(e, 1), 2.3F, 230), 0xFFFFA49D, true);
            centre(ps, buf, f, e.scoreBleu() + " – " + e.scoreRouge(), 320, 4, 3.6F, 0xFFFFFFFF);
            int s = e.chrono();
            centre(ps, buf, f, s / 60 + ":" + String.format("%02d", s % 60), 320, 40, 1.8F, 0xFFFFE1A1);
            texte(ps, buf, f, "EN DIRECT · ARÈNE", 42, hpx - 34, 1.6F, 0xFFFFFFFF, false);
        }
        if (victoire) {
            centre(ps, buf, f, "VICTOIRE", 320, hpx / 2 - 46, 6F, 0xFFF0B43C);
            centre(ps, buf, f, e.victoire(), 320, hpx / 2 + 12, ajuste(f, e.victoire(), 3F, 600), e.victoireCote() == 0 ? 0xFF8FB0FF : 0xFFFF8A80);
        } else if (!combat) {
            String haut, bas;
            if (e != null && e.phase() == 2) {
                haut = "COMBAT DANS " + e.rebours();
                bas = "Bleu : " + String.join(", ", e.fileBleu()) + "   ·   Rouge : " + String.join(", ", e.fileRouge());
            } else if (e != null && e.phase() == 1) {
                haut = "EN ATTENTE D'UN ADVERSAIRE";
                bas = !e.fileBleu().isEmpty() ? "Côté bleu : " + String.join(", ", e.fileBleu()) : "Côté rouge : " + String.join(", ", e.fileRouge());
            } else {
                haut = "L'ARÈNE";
                bas = "Salle d'entrée : choisis ton côté";
            }
            centre(ps, buf, f, haut, 320, hpx / 2 - 40, ajuste(f, haut, 5F, 500), 0xFFF0B43C);
            centre(ps, buf, f, bas, 320, hpx / 2 + 16, ajuste(f, bas, 1.8F, 500), 0xFFF1EEE6);
        }
        buf.endBatch();
    }

    private static float[] pvCote(Arene.EtatArene e, int cote) {
        for (int i = 0; i < e.cotes().size(); i++) {
            if (e.cotes().get(i) == cote) {
                return new float[] {e.pv().get(i), e.pvMax().get(i)};
            }
        }
        return new float[] {0, 20};
    }

    private static String nomCote(Arene.EtatArene e, int cote) {
        String premier = null;
        int n = 0;
        for (int i = 0; i < e.cotes().size(); i++) {
            if (e.cotes().get(i) == cote) {
                if (premier == null) {
                    premier = e.noms().get(i);
                }
                n++;
            }
        }
        if (premier == null) {
            return "en attente…";
        }
        return n > 1 ? premier + " +" + (n - 1) : premier;
    }

    // ------------------------------------------------------------------ la salle d'entrée

    private static void salle(PoseStack ps, MultiBufferSource.BufferSource buf, float partiel, double t) {
        double sol = AreneBuilder.SOL;
        // les deux panneaux d'attente, sur leur poteau, tournés vers l'arrivée et un peu penchés en arrière
        for (int cote = 0; cote < 2; cote++) {
            double x = cote == 0 ? -8.5 : 8.5;
            ps.pushPose();
            ps.translate(x, sol + 6.6, 86.5);
            ps.mulPose(Axis.YP.rotation((float) Math.atan2(0 - x, 95 - 86.5)));
            ps.mulPose(Axis.XP.rotation(-0.3F));
            ps.scale(0.0125F, -0.0125F, 0.0125F);
            ps.translate(-300, -130, 0);
            panneau(ps, buf, cote, t);
            ps.popPose();
        }
        // l'enseigne des gradins sur le linteau, et le petit écran du combat au-dessus
        ps.pushPose();
        ps.translate(0, sol + 4.5, 77.03);
        ps.scale(0.01F, -0.01F, 0.01F);
        ps.translate(-300, -45, 0);
        Matrix4f m = ps.last().pose();
        VertexConsumer c = buf.getBuffer(RenderType.debugQuads());
        cadre(c, m, 600, 90);
        buf.endBatch(RenderType.debugQuads());
        centre(ps, buf, Minecraft.getInstance().font, "GRADINS · appuie sur le bouton doré", 300, 32, 3.2F, 0xFFFFE1A1);
        buf.endBatch();
        ps.popPose();
        ps.pushPose();
        ps.translate(0, sol + 7.5, 77.03);
        image(ps, buf, 8F, 5F, partiel);
        ps.popPose();
        // le tableau du kit, au mur de l'arrivée
        ps.pushPose();
        ps.translate(6, sol + 3.4, 95.97);
        ps.mulPose(Axis.YP.rotation((float) Math.PI));
        ps.scale(0.01F, -0.01F, 0.01F);
        ps.translate(-310, -180, 0);
        m = ps.last().pose();
        c = buf.getBuffer(RenderType.debugQuads());
        cadre(c, m, 620, 360);
        buf.endBatch(RenderType.debugQuads());
        Font f = Minecraft.getInstance().font;
        texte(ps, buf, f, "POUR COMBATTRE", 40, 30, 3.6F, 0xFFFFE1A1, false);
        String[] lignes = {"Tout le monde reçoit le même kit :", "armure en fer, épée, bouclier, arc,", "pommes dorées et steaks.",
            "Tu gardes : les lames de la Brume", "et les armes de ton éveil.", "Le reste t'est rendu en revenant ici."};
        for (int i = 0; i < lignes.length; i++) {
            texte(ps, buf, f, lignes[i], 40, 92 + i * 41, 2.7F, i == 3 || i == 4 ? 0xFF9FE08A : 0xFFF1EEE6, false);
        }
        buf.endBatch();
        ps.popPose();
    }

    /** Un panneau d'attente (600 × 260 pixels) : CÔTÉ BLEU / ROUGE, qui attend, et où on en est. */
    private static void panneau(PoseStack ps, MultiBufferSource.BufferSource buf, int cote, double t) {
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        Matrix4f m = ps.last().pose();
        VertexConsumer c = buf.getBuffer(RenderType.debugQuads());
        cadre(c, m, 600, 260);
        rect(c, m, 20, 20, 580, 82, cote == 0 ? 0xFF2F4FA8 : 0xFFA52C27, 0.1F);
        buf.endBatch(RenderType.debugQuads());
        Arene.EtatArene e = etat;
        int phase = e == null ? 0 : e.phase();
        List<String> file = e == null ? List.of() : cote == 0 ? e.fileBleu() : e.fileRouge();
        List<String> noms = new ArrayList<>();
        if (phase == 3) {
            for (int i = 0; i < e.cotes().size(); i++) {
                if (e.cotes().get(i) == cote) {
                    noms.add(e.noms().get(i));
                }
            }
        } else {
            noms.addAll(file);
        }
        String statut;
        int couleur = 0xFFF1EEE6;
        float taille = 3.3F;
        if (phase == 0) {
            statut = "Appuie sur le bouton pour combattre";
        } else if (phase == 1 && !file.isEmpty()) {
            statut = "En attente d'un adversaire" + ".".repeat(1 + (int) (t * 2) % 3);
            couleur = 0xFFFFD27A;
        } else if (phase == 1) {
            statut = "Un adversaire t'attend ! Appuie";
            couleur = 0xFF9FD0FF;
        } else if (phase == 2) {
            statut = "Combat dans " + e.rebours();
            couleur = 0xFFFFB04A;
            taille = 5F;
        } else {
            statut = "Combat en cours " + e.scoreBleu() + " – " + e.scoreRouge() + " · appuie pour y aller";
            couleur = 0xFF9FE08A;
            taille = 2.7F;
        }
        centre(ps, buf, f, cote == 0 ? "CÔTÉ BLEU" : "CÔTÉ ROUGE", 300, 33, 4.6F, 0xFFFFFFFF);
        String ligne = noms.isEmpty() ? (phase == 3 ? "Personne de ce côté" : "Personne") : String.join(", ", noms);
        centre(ps, buf, f, ligne, 300, 104, ajuste(f, ligne, 3.6F, 540), noms.isEmpty() ? 0xFFB3A58A : 0xFFFFE1A1);
        taille = ajuste(f, statut, taille, 540);
        centre(ps, buf, f, statut, 300, 198 - taille * 4.5F, taille, couleur);
        buf.endBatch();
    }

    // ------------------------------------------------------------------ les dragons

    /** Trois dragons qui tournent autour de l'arène, au-dessus des îles ; on entend battre leurs ailes quand ils passent près. */
    private static void dragons(PoseStack ps, MultiBufferSource.BufferSource buf, Vec3 cam, float partiel, double t) {
        Minecraft mc = Minecraft.getInstance();
        if (DRAGONS.isEmpty() || DRAGONS.get(0).level() != mc.level) {
            DRAGONS.clear();
            for (int i = 0; i < 3; i++) {
                EnderDragon d = EntityType.ENDER_DRAGON.create(mc.level);
                if (d == null) {
                    return;
                }
                DRAGONS.add(d);
            }
        }
        double[][] vols = {{132, 78, 0.055, 1, 0}, {168, 100, 0.04, -1, 2.1}, {112, 62, 0.07, 1, 4.2}};
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        for (int i = 0; i < 3; i++) {
            EnderDragon d = DRAGONS.get(i);
            double[] v = vols[i];
            double a = v[4] + t * v[2] * v[3];
            double x = Math.cos(a) * v[0], z = Math.sin(a) * v[0] * 0.85 + 10, y = AreneBuilder.SOL + v[1] + Math.sin(t * 0.3 + i) * 6;
            // la direction du vol : la tangente du cercle
            double tx = -Math.sin(a) * v[3], tz = Math.cos(a) * 0.85 * v[3];
            float lacet = (float) Math.toDegrees(Math.atan2(tx, tz));
            d.oFlapTime = d.flapTime;
            d.flapTime = (float) (t / 1.6 + i * 0.3);
            ps.pushPose();
            ps.translate(x - cam.x, y - cam.y, z - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees(180 - lacet));
            ps.mulPose(Axis.XP.rotationDegrees((float) Math.sin(t * 0.3 + i) * 6));
            dispatcher.render(d, 0, 0, 0, 0, partiel, ps, buf, LightTexture.FULL_BRIGHT);
            ps.popPose();
            double dist = cam.distanceTo(new Vec3(x, y, z));
            if (dist < 90 && t > PROCHAIN_BATTEMENT[i]) {
                PROCHAIN_BATTEMENT[i] = t + 1.6;
                mc.level.playLocalSound(x, y, z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.AMBIENT, 4F, 0.8F + i * 0.1F, false);
            }
        }
        buf.endBatch();
        dispatcher.setRenderShadow(mc.options.entityShadows().get());
    }

    // ------------------------------------------------------------------ dessin

    private static void cadre(VertexConsumer c, Matrix4f m, float l, float h) {
        rect(c, m, 0, 0, l, h, 0xFF2A1D10, 0);
        rect(c, m, 0, 0, l, 9, 0xFFD9A72C, 0.05F);
        rect(c, m, 0, h - 9, l, h, 0xFFD9A72C, 0.05F);
        rect(c, m, 0, 0, 9, h, 0xFFD9A72C, 0.05F);
        rect(c, m, l - 9, 0, l, h, 0xFFD9A72C, 0.05F);
    }

    /** Un rectangle en pixels (y vers le bas), {@code z} un peu devant pour passer au-dessus de ce qui est dessous. */
    private static void rect(VertexConsumer c, Matrix4f m, float x0, float y0, float x1, float y1, int argb, float z) {
        degrade(c, m, x0, y0, x1, y1, argb, argb, z);
    }

    private static void degrade(VertexConsumer c, Matrix4f m, float x0, float y0, float x1, float y1, int haut, int bas, float z) {
        float zz = z * 20;
        sommet(c, m, x0, y0, zz, haut);
        sommet(c, m, x0, y1, zz, bas);
        sommet(c, m, x1, y1, zz, bas);
        sommet(c, m, x1, y0, zz, haut);
    }

    private static void sommet(VertexConsumer c, Matrix4f m, float x, float y, float z, int argb) {
        c.addVertex(m, x, y, z).setColor((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255);
    }

    private static void texte(PoseStack ps, MultiBufferSource buf, Font f, String s, float x, float y, float taille, int couleur, boolean droite) {
        ps.pushPose();
        ps.translate(x, y, 5F);
        ps.scale(taille, taille, 1);
        f.drawInBatch(s, droite ? -f.width(s) : 0, 0, couleur, false, ps.last().pose(), buf, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

/** La taille du texte, réduite s'il le faut pour qu'il tienne dans {@code largeur} pixels. */
    private static float ajuste(Font f, String s, float taille, float largeur) {
        return Math.min(taille, largeur / Math.max(1, f.width(s)));
    }

    private static void centre(PoseStack ps, MultiBufferSource buf, Font f, String s, float x, float y, float taille, int couleur) {
        ps.pushPose();
        ps.translate(x, y, 5F);
        ps.scale(taille, taille, 1);
        f.drawInBatch(s, -f.width(s) / 2F, 0, couleur, false, ps.last().pose(), buf, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    private ClientArene() {
    }
}
