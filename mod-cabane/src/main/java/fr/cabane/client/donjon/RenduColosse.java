package fr.cabane.client.donjon;

import static fr.cabane.client.donjon.Formes.lisse;
import static fr.cabane.client.donjon.Formes.pave;
import static fr.cabane.client.donjon.Formes.tourner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.donjon.Colosse;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Hrimgar, le Colosse de Givre : des jambes comme des piliers, un torse immense de glace tassée autour d'un cœur de
 * pierre bleue, un cristal cyan qui bat au milieu de la poitrine, des épaules et un dos hérissés de cristaux, une petite
 * tête enfoncée entre les épaules (deux fentes qui brillent, une barbe de glaçons), et des bras qui descendent jusqu'aux
 * genoux, finis par des poings de glace gros comme des rochers. Endormi, il est pris dans un bloc de glace.
 */
public class RenduColosse extends EntityRenderer<Colosse> {
    private static final int GLACE = 0xBFE4FB, GLACE_S = 0x7DB6E8, BLEU = 0x3F78C8, PIERRE = 0x3D4E63, NEIGE = 0xF5FAFF, SOMBRE = 0x2B3E5A;

    public RenduColosse(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 2.5F;
        shadowStrength = 0.9F;
    }

    /** La pose : angles en radians, distances en blocs. */
    private static final class P {
        float pas, amp, tL, tT, abaisse, penche, recul, bD, bDZ, cD, bG, bGZ, cG, cuD, tiD, cuG, tiG, souffle, glace, fissures, chute;
    }

    @Override
    public void render(Colosse o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float temps = o.tickCount + partiel;
        float a = o.tickCount - o.debutClient + partiel;
        P p = new P();
        p.amp = Math.min(1F, o.walkAnimation.speed(partiel) * 1.6F);
        p.pas = o.walkAnimation.position(partiel) * 0.38F;
        float sw = Mth.sin(p.pas);
        p.tL = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, o.yHeadRotO, o.yHeadRot) - corps), -40F, 40F) * Mth.DEG_TO_RAD;
        p.penche = 0.12F + Mth.sin(temps * 0.06F) * 0.02F;
        p.bD = -sw * 0.3F * p.amp;
        p.bG = sw * 0.3F * p.amp;
        p.bDZ = 0.15F;
        p.bGZ = 0.15F;
        p.cD = -0.2F;
        p.cG = -0.2F;
        p.cuD = sw * 0.45F * p.amp;
        p.tiD = Math.max(0, -sw) * 0.6F * p.amp;
        p.cuG = -sw * 0.45F * p.amp;
        p.tiG = Math.max(0, sw) * 0.6F * p.amp;
        switch (o.etat()) {
            case Colosse.SOMMEIL -> {
                // un genou à terre, la tête basse, les poings posés au sol : figé dans la glace
                p.glace = 1;
                accroupi(p, 1);
            }
            case Colosse.REVEIL -> {
                p.glace = 1 - lisse((a - 30) / 3F);
                p.fissures = lisse(a / 30F);
                float k = lisse((a - 34) / 12F);
                accroupi(p, 1 - k);
                if (a > 40) {
                    float r = lisse((a - 40) / 6F) * (1 - lisse((a - 62) / 8F));
                    p.tT = -0.6F * r;
                    p.souffle = r;
                    p.bD = -1.2F * r;
                    p.bG = -1.2F * r;
                    p.bDZ = 0.15F + 0.9F * r;
                    p.bGZ = 0.15F + 0.9F * r;
                    p.recul = 0.2F * r;
                }
            }
            case Colosse.FRAPPE -> {
                float leve = lisse(a / 12F), abat = lisse((a - 12) / 4F), retour = lisse((a - 20) / 9F);
                float bras = Mth.lerp(abat, -3.0F * leve, -0.9F) * (1 - retour);
                p.bD = bras;
                p.bG = bras;
                p.bDZ = 0.15F - 0.1F * leve;
                p.bGZ = p.bDZ;
                p.cD = -0.5F * leve * (1 - abat);
                p.cG = p.cD;
                p.penche = Mth.lerp(abat, 0.12F - 0.25F * leve, 0.6F) * (1 - retour) + 0.12F * retour;
                p.recul = 0.15F * leve * (1 - abat);
                p.abaisse = 0.4F * abat * (1 - retour);
                p.cuD = -0.4F * abat * (1 - retour);
                p.tiD = 0.6F * abat * (1 - retour);
                p.cuG = p.cuD;
                p.tiG = p.tiD;
            }
            case Colosse.PICS -> {
                float leve = lisse(a / 10F), plonge = lisse((a - 10) / 4F), retour = lisse((a - 40) / 10F);
                p.bD = Mth.lerp(plonge, -2.8F * leve, -0.55F) * (1 - retour);
                p.cD = -0.3F * (1 - plonge);
                p.bG = 0.5F * plonge * (1 - retour);
                p.bGZ = 0.15F + 0.3F * plonge * (1 - retour);
                p.penche = 0.12F + 0.7F * plonge * (1 - retour);
                p.abaisse = 0.7F * plonge * (1 - retour);
                p.cuD = -0.6F * plonge * (1 - retour);
                p.tiD = 0.9F * plonge * (1 - retour);
                p.cuG = 0.2F * plonge * (1 - retour);
                p.tiG = 0.7F * plonge * (1 - retour);
                p.tT = -0.3F * plonge * (1 - retour);
            }
            case Colosse.SOUFFLE -> {
                float gonfle = lisse(a / 14F) * (1 - lisse((a - 15) / 3F)), souffle = lisse((a - 15) / 3F) * (1 - lisse((a - 54) / 8F));
                p.recul = 0.3F * gonfle;
                p.tT = -0.5F * gonfle + 0.25F * souffle;
                p.penche = 0.12F - 0.2F * gonfle + 0.25F * souffle;
                p.bD = 0.5F * (gonfle + souffle);
                p.bG = p.bD;
                p.bDZ = 0.15F + 0.5F * (gonfle + souffle);
                p.bGZ = p.bDZ;
                p.souffle = souffle;
            }
            case Colosse.GRELE -> {
                float leve = lisse(a / 10F), abat = lisse((a - 32) / 4F), retour = lisse((a - 42) / 10F);
                float bras = Mth.lerp(abat, -3.05F * leve, -0.5F) * (1 - retour);
                p.bD = bras;
                p.bG = bras;
                p.bDZ = 0.15F + 0.45F * leve * (1 - abat);
                p.bGZ = p.bDZ;
                p.tT = -0.7F * leve * (1 - abat);
                p.recul = 0.2F * leve * (1 - abat);
                p.penche = 0.12F + 0.4F * abat * (1 - retour);
                p.souffle = 0.6F * leve * (1 - abat);
            }
            case Colosse.RAGE -> {
                float r = lisse(a / 10F) * (1 - lisse((a - 38) / 10F));
                p.tT = -0.7F * r;
                p.souffle = r;
                p.recul = 0.25F * r;
                p.bD = -1.0F * r;
                p.bG = -1.0F * r;
                p.bDZ = 0.15F + 1.1F * r;
                p.bGZ = p.bDZ;
                p.cD = -0.7F * r;
                p.cG = -0.7F * r;
                p.fissures = r;
            }
            case Colosse.INVOCATION -> {
                float leve = lisse(a / 12F), abat = lisse((a - 22) / 4F), retour = lisse((a - 34) / 10F);
                p.bD = Mth.lerp(abat, -3.0F * leve, -0.6F) * (1 - retour);
                p.penche = 0.12F + 0.5F * abat * (1 - retour);
                p.tT = -0.5F * leve * (1 - abat);
                p.souffle = leve * (1 - abat);
                p.abaisse = 0.4F * abat * (1 - retour);
            }
            default -> {
            }
        }
        if (o.deathTime > 0) {
            float k = lisse(o.deathTime / 40F);
            p.chute = k * k;
            p.fissures = 1;
        }
        Formes.debut(buffers, lumiere, OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0)));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        dessiner(ps, p, o.enrage(), temps);
        ps.popPose();
    }

    private static void accroupi(P p, float k) {
        p.abaisse = 1.2F * k;
        p.cuG = p.cuG * (1 - k) - 1.5F * k;
        p.tiG = p.tiG * (1 - k) + 1.5F * k;
        p.cuD = p.cuD * (1 - k) + 0.2F * k;
        p.tiD = p.tiD * (1 - k) + 1.6F * k;
        p.penche = Mth.lerp(k, p.penche, 0.55F);
        p.tT = Mth.lerp(k, p.tT, 0.5F);
        p.bD = Mth.lerp(k, p.bD, -0.35F);
        p.bG = Mth.lerp(k, p.bG, -0.35F);
        p.cD = Mth.lerp(k, p.cD, -0.2F);
        p.cG = p.cD;
    }

    private static void dessiner(PoseStack ps, P p, boolean rage, float temps) {
        float pulse = 0.7F + 0.3F * Mth.sin(temps * (rage ? 0.4F : 0.12F));
        int coeur = Formes.melange(0x5FD8FF, 0xE8FFFF, pulse);
        int yeux = rage ? 0xFFFFFF : 0x9FF4FF;
        ps.pushPose();
        if (p.chute > 0) {
            ps.mulPose(Axis.XP.rotation(-p.chute * 1.4F));
        }
        ps.translate(0, -p.abaisse, 0);
        // les jambes
        jambe(ps, -1.0F, p.cuG, p.tiG);
        jambe(ps, 1.0F, p.cuD, p.tiD);
        float bob = Math.abs(Mth.sin(p.pas)) * 0.1F * p.amp;
        ps.translate(0, bob, 0);
        // le bassin : de la pierre sous une ceinture de glace
        pave(ps, -1.5F, 2.2F, -0.9F, 1.5F, 3.2F, 0.9F, PIERRE);
        pave(ps, -1.6F, 2.9F, -1.0F, 1.6F, 3.3F, 1.0F, GLACE_S);
        // le torse
        ps.pushPose();
        tourner(ps, 0, 3.1F, 0, p.penche - p.recul, 0, 0);
        pave(ps, -1.45F, 0, -1.0F, 1.45F, 1.4F, 1.0F, PIERRE);
        pave(ps, -2.1F, 1.1F, -1.25F, 2.1F, 3.6F, 1.25F, GLACE);
        pave(ps, -2.2F, 2.6F, -1.3F, 2.2F, 3.4F, 1.15F, GLACE_S);
        pave(ps, -1.7F, 1.3F, 1.2F, 1.7F, 3.2F, 1.4F, BLEU);
        // le cœur : un losange de cristal qui bat au milieu de la poitrine, et ses fissures
        ps.pushPose();
        tourner(ps, 0, 2.2F, 1.38F, 0, 0, Mth.PI / 4);
        pave(ps, -0.42F, -0.42F, 0, 0.42F, 0.42F, 0.14F, coeur, true);
        ps.popPose();
        if (p.fissures > 0.05F || rage) {
            int f = Formes.melange(BLEU, coeur, Math.max(p.fissures, rage ? 0.8F : 0));
            pave(ps, -1.2F, 2.15F, 1.4F, -0.5F, 2.25F, 1.43F, f, true);
            pave(ps, 0.5F, 1.7F, 1.4F, 0.6F, 2.5F, 1.43F, f, true);
            pave(ps, -0.1F, 1.3F, 1.4F, 0.0F, 1.8F, 1.43F, f, true);
            pave(ps, 0.6F, 2.5F, 1.4F, 1.3F, 2.6F, 1.43F, f, true);
        }
        // la neige sur les épaules
        pave(ps, -2.2F, 3.6F, -1.2F, -0.9F, 3.8F, 1.1F, NEIGE);
        pave(ps, 0.9F, 3.6F, -1.2F, 2.2F, 3.8F, 1.1F, NEIGE);
        // les cristaux des épaules et du dos
        cristal(ps, -1.7F, 3.6F, -0.3F, 0.35F, 1.6F, -0.3F, 0.4F, rage);
        cristal(ps, -1.3F, 3.6F, 0.4F, 0.28F, 1.1F, 0.25F, 0.3F, rage);
        cristal(ps, 1.7F, 3.6F, -0.3F, 0.35F, 1.7F, -0.3F, -0.4F, rage);
        cristal(ps, 1.25F, 3.6F, 0.45F, 0.28F, 1.0F, 0.25F, -0.25F, rage);
        cristal(ps, 0, 3.0F, -1.2F, 0.45F, 2.2F, -0.75F, 0, rage);
        cristal(ps, -0.9F, 2.6F, -1.2F, 0.35F, 1.6F, -0.9F, 0.35F, rage);
        cristal(ps, 0.9F, 2.6F, -1.2F, 0.35F, 1.6F, -0.9F, -0.35F, rage);
        cristal(ps, 0, 1.6F, -1.2F, 0.3F, 1.2F, -1.2F, 0, rage);
        // la tête, enfoncée entre les épaules
        ps.pushPose();
        tourner(ps, 0, 3.55F, 0.45F, p.tT, p.tL, 0);
        tete(ps, p, yeux);
        ps.popPose();
        bras(ps, false, p.bG, p.bGZ, p.cG);
        bras(ps, true, p.bD, p.bDZ, p.cD);
        ps.popPose();
        ps.popPose();
        // le bloc de glace qui l'enferme quand il dort
        if (p.glace > 0.02F) {
            float al = 0.5F * p.glace;
            Formes.verre(ps, -2.9F, 0, -2.4F, 2.9F, 7.6F, 2.4F, 0xA8E4FF, al, false);
            Formes.verre(ps, -2.6F, 7.6F, -2.1F, 2.6F, 8.1F, 2.1F, 0xDDF6FF, al, false);
        }
    }

    private static void jambe(PoseStack ps, float x, float cuisse, float tibia) {
        ps.pushPose();
        tourner(ps, x, 2.5F, 0, cuisse, 0, 0);
        pave(ps, -0.7F, -1.35F, -0.7F, 0.7F, 0.2F, 0.7F, GLACE);
        pave(ps, -0.6F, -0.9F, 0.65F, 0.6F, -0.3F, 0.78F, GLACE_S);
        tourner(ps, 0, -1.25F, 0, tibia, 0, 0);
        pave(ps, -0.62F, -1.15F, -0.62F, 0.62F, 0.05F, 0.62F, PIERRE);
        pave(ps, -0.72F, -1.25F, -0.75F, 0.72F, -0.6F, 0.95F, GLACE_S);
        pave(ps, -0.66F, -0.75F, -0.68F, 0.66F, -0.6F, 0.8F, NEIGE);
        ps.popPose();
    }

    /** Une petite tête carrée, deux fentes qui brillent, et une barbe de glaçons ; la bouche s'illumine quand il souffle. */
    private static void tete(PoseStack ps, P p, int yeux) {
        pave(ps, -0.6F, 0, -0.5F, 0.6F, 1.1F, 0.7F, GLACE_S);
        pave(ps, -0.66F, 0.55F, 0.55F, 0.66F, 0.85F, 0.8F, SOMBRE);
        pave(ps, -0.45F, 0.62F, 0.78F, -0.14F, 0.74F, 0.82F, yeux, true);
        pave(ps, 0.14F, 0.62F, 0.78F, 0.45F, 0.74F, 0.82F, yeux, true);
        // la bouche : une fente sombre, ou une lueur bleue quand il souffle
        int bouche = p.souffle > 0.1F ? Formes.melange(SOMBRE, 0xBFF8FF, p.souffle) : SOMBRE;
        pave(ps, -0.3F, 0.22F, 0.68F, 0.3F, 0.34F, 0.74F, bouche, p.souffle > 0.1F);
        // la barbe de glaçons
        for (int k = -2; k <= 2; k++) {
            float l = 0.35F + (2 - Math.abs(k)) * 0.15F;
            pave(ps, k * 0.2F - 0.07F, -l, 0.45F, k * 0.2F + 0.07F, 0.1F, 0.62F, k % 2 == 0 ? GLACE : 0xEAF8FF);
        }
        // la couronne de pointes
        for (int k = -1; k <= 1; k++) {
            ps.pushPose();
            tourner(ps, k * 0.35F, 1.05F, 0.05F, -0.2F, 0, -k * 0.35F);
            pave(ps, -0.1F, 0, -0.1F, 0.1F, 0.55F - Math.abs(k) * 0.15F, 0.1F, 0x9FE8FF, true);
            ps.popPose();
        }
    }

    private static void bras(PoseStack ps, boolean droit, float bras, float brasZ, float coude) {
        float s = droit ? 1 : -1;
        ps.pushPose();
        tourner(ps, s * 2.5F, 3.15F, 0, bras, 0, s * -brasZ);
        pave(ps, -0.65F, -2.3F, -0.65F, 0.65F, 0.4F, 0.65F, GLACE);
        // l'épaulière : un gros bloc bleu
        pave(ps, -0.85F, -0.3F, -0.85F, 0.85F, 0.6F, 0.85F, BLEU);
        pave(ps, -0.75F, -1.6F, -0.72F, 0.75F, -1.2F, 0.72F, GLACE_S);
        tourner(ps, 0, -2.2F, 0, coude, 0, 0);
        pave(ps, -0.62F, -2.0F, -0.62F, 0.62F, 0.1F, 0.62F, PIERRE);
        pave(ps, -0.72F, -1.6F, -0.72F, 0.72F, -0.8F, 0.72F, GLACE_S);
        // le poing de glace, et ses jointures de cristal
        pave(ps, -0.95F, -3.4F, -0.9F, 0.95F, -1.75F, 1.0F, GLACE);
        pave(ps, -0.85F, -3.5F, 0.9F, 0.85F, -2.0F, 1.15F, 0xE6F7FF);
        for (int k = -1; k <= 1; k++) {
            pave(ps, k * 0.55F - 0.16F, -2.6F, 1.1F, k * 0.55F + 0.16F, -2.15F, 1.32F, 0x9FF4FF, true);
        }
        ps.popPose();
    }

    /** Un cristal : un prisme bleu clair qui pointe dans cette direction (penché en x et en z), la pointe brillante. */
    private static void cristal(PoseStack ps, float x, float y, float z, float l, float h, float rx, float rz, boolean rage) {
        ps.pushPose();
        tourner(ps, x, y, z, rx, 0, rz);
        pave(ps, -l, 0, -l, l, h * 0.75F, l, 0x86D6F2);
        pave(ps, -l * 0.6F, h * 0.7F, -l * 0.6F, l * 0.6F, h, l * 0.6F, rage ? 0xFFFFFF : 0xBDF4FF, true);
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Colosse o) {
        return Formes.GRAIN;
    }
}
