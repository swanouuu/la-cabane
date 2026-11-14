package fr.cabane.client.donjon;

import static fr.cabane.client.donjon.Formes.lisse;
import static fr.cabane.client.donjon.Formes.pave;
import static fr.cabane.client.donjon.Formes.tourner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.donjon.RoiFourmi;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Kar'Thax, le Roi des Fourmis : un guerrier insecte debout sur des pattes à l'envers, la carapace noire veinée d'orange
 * qui luit, une taille de guêpe, l'abdomen d'une fourmi derrière lui, une tête allongée aux grands yeux orange, des
 * mandibules qui claquent, deux antennes, quatre ailes d'ambre, et deux bras qui finissent en faux d'os au fil orange.
 * Endormi, il est accroupi et enveloppé dans ses ailes.
 */
public class RenduRoiFourmi extends EntityRenderer<RoiFourmi> {
    private static final int CHITINE = 0x1A1410, CHITINE2 = 0x2E2219, BRUN = 0x4A3220, LAME = 0xE6DCC2, AMBRE = 0xFFB35A;

    public RenduRoiFourmi(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.3F;
        shadowStrength = 0.8F;
    }

    private static final class P {
        float pas, amp, tL, tT, penche, bD, bDZ, cD, bG, bGZ, cG, cuD, tiD, cuG, tiG, accroupi, ailes, battement, mandibules, chute;
    }

    @Override
    public void render(RoiFourmi o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float temps = o.tickCount + partiel;
        float a = o.tickCount - o.debutClient + partiel;
        P p = new P();
        p.amp = Math.min(1F, o.walkAnimation.speed(partiel) * 1.5F);
        p.pas = o.walkAnimation.position(partiel) * 0.6F;
        float sw = Mth.sin(p.pas);
        p.tL = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, o.yHeadRotO, o.yHeadRot) - corps), -60F, 60F) * Mth.DEG_TO_RAD;
        p.penche = 0.18F + 0.15F * p.amp;
        p.bD = -0.6F - sw * 0.25F * p.amp;
        p.bG = -0.6F + sw * 0.25F * p.amp;
        p.bDZ = 0.25F;
        p.bGZ = 0.25F;
        p.cD = -1.0F;
        p.cG = -1.0F;
        p.cuD = sw * 0.6F * p.amp;
        p.tiD = Math.max(0, -sw) * 0.8F * p.amp;
        p.cuG = -sw * 0.6F * p.amp;
        p.tiG = Math.max(0, sw) * 0.8F * p.amp;
        p.ailes = 0.08F + Mth.sin(temps * 0.5F) * 0.03F;
        p.mandibules = 0.15F + Math.max(0, Mth.sin(temps * 0.13F)) * 0.25F;
        switch (o.etat()) {
            case RoiFourmi.SOMMEIL -> {
                p.accroupi = 1;
                p.ailes = -1;
                p.tT = 0.7F;
                p.bD = 0.3F;
                p.bG = 0.3F;
                p.cD = -2.0F;
                p.cG = -2.0F;
                p.mandibules = 0;
            }
            case RoiFourmi.REVEIL -> {
                p.accroupi = 1 - lisse((a - 4) / 14F);
                float ouvre = lisse((a - 22) / 4F), replie = lisse((a - 48) / 12F);
                p.ailes = Mth.lerp(ouvre, -1F, 1F) * (1 - replie) + 0.08F * replie;
                p.battement = ouvre * (1 - replie) * Mth.sin(a * 1.2F) * 0.3F;
                float r = lisse((a - 32) / 4F) * (1 - lisse((a - 50) / 8F));
                p.tT = 0.7F * (1 - lisse((a - 10) / 10F)) - 0.6F * r;
                p.mandibules = 0.15F + 0.85F * r;
                p.bD = Mth.lerp(r, -0.6F, -1.3F);
                p.bG = p.bD;
                p.bDZ = 0.25F + 1.0F * r;
                p.bGZ = p.bDZ;
                p.cD = Mth.lerp(r, -1.0F, -0.3F);
                p.cG = p.cD;
            }
            case RoiFourmi.COMBO -> {
                float c1 = lisse((a - 4) / 4F) * (1 - lisse((a - 12) / 4F));
                float c2 = lisse((a - 12) / 4F) * (1 - lisse((a - 20) / 4F));
                float l3 = lisse((a - 19) / 5F), a3 = lisse((a - 25) / 2F), r3 = lisse((a - 29) / 7F);
                // 1 : la faux droite, de l'extérieur vers l'intérieur
                float s1 = lisse((a - 6) / 2F);
                p.bD = Mth.lerp(c1, p.bD, -1.5F);
                p.bDZ = Mth.lerp(c1, p.bDZ, Mth.lerp(s1, 1.2F, -0.7F));
                // 2 : la faux gauche
                float s2 = lisse((a - 14) / 2F);
                p.bG = Mth.lerp(c2, p.bG, -1.5F);
                p.bGZ = Mth.lerp(c2, p.bGZ, Mth.lerp(s2, 1.2F, -0.7F));
                // 3 : les deux, d'en haut
                float haut = Mth.lerp(a3, -3.0F * l3, -0.5F) * (1 - r3) + p.bD * r3;
                if (a > 19) {
                    p.bD = haut;
                    p.bG = haut;
                    p.bDZ = 0.2F;
                    p.bGZ = 0.2F;
                    p.cD = -0.3F;
                    p.cG = -0.3F;
                    p.penche = Mth.lerp(a3, 0.0F, 0.6F) * (1 - r3) + 0.18F * r3;
                }
                p.mandibules = 0.6F;
            }
            case RoiFourmi.RUEE -> {
                float prep = lisse(a / 6F);
                boolean fonce = a >= RoiFourmi.PREPA_RUEE && a < RoiFourmi.DUREE_RUEE;
                p.penche = fonce ? 1.0F : 0.18F + 0.6F * prep;
                p.accroupi = fonce ? 0.2F : 0.5F * prep;
                p.bD = 0.9F;
                p.bG = 0.9F;
                p.cD = -0.2F;
                p.cG = -0.2F;
                p.bDZ = 0.4F;
                p.bGZ = 0.4F;
                p.ailes = fonce ? 0.5F : 0.3F * prep;
                p.battement = fonce ? Mth.sin(a * 2.5F) * 0.15F : 0;
                p.tT = -0.6F * (fonce ? 1 : prep);
                if (fonce) {
                    float c = Mth.sin(a * 1.6F);
                    p.cuD = c * 1.0F;
                    p.tiD = Math.max(0, -c) * 1.2F;
                    p.cuG = -c * 1.0F;
                    p.tiG = Math.max(0, c) * 1.2F;
                }
            }
            case RoiFourmi.PIQUE -> {
                p.ailes = 1;
                if (a < RoiFourmi.MONTEE_PIQUE + 8) {
                    p.battement = Mth.sin(a * 2.2F) * 0.45F;
                    p.cuD = 0.3F;
                    p.tiD = 0.6F;
                    p.cuG = 0.2F;
                    p.tiG = 0.7F;
                    p.bD = -0.3F;
                    p.bG = -0.3F;
                    p.bDZ = 0.6F;
                    p.bGZ = 0.6F;
                    p.tT = 0.3F;
                } else if (!o.onGround()) {
                    // le piqué : la tête en bas, les faux en avant
                    p.penche = 1.3F;
                    p.ailes = 0.4F;
                    p.bD = -2.6F;
                    p.bG = -2.6F;
                    p.cD = -0.1F;
                    p.cG = -0.1F;
                    p.mandibules = 1;
                    p.tT = -0.8F;
                } else {
                    p.accroupi = 0.7F * (1 - lisse((a - RoiFourmi.MONTEE_PIQUE - 10) / 12F));
                    p.bD = -0.4F;
                    p.bG = -0.4F;
                    p.penche = 0.6F;
                }
            }
            case RoiFourmi.ACIDE -> {
                float r = lisse(a / 6F) * (1 - lisse((a - 28) / 6F));
                float crache = Math.max(Math.max(bosse(a, 10), bosse(a, 16)), bosse(a, 22));
                p.tT = -0.5F * r + 0.6F * crache;
                p.penche = 0.18F - 0.3F * r + 0.3F * crache;
                p.mandibules = 0.2F + 0.8F * Math.max(r * 0.5F, crache);
                p.bDZ = 0.25F + 0.5F * r;
                p.bGZ = p.bDZ;
            }
            case RoiFourmi.RAGE -> {
                float r = lisse(a / 8F) * (1 - lisse((a - 34) / 10F));
                p.ailes = Mth.lerp(r, p.ailes, 1F);
                p.battement = r * Mth.sin(a * 1.5F) * 0.3F;
                p.tT = -0.7F * r;
                p.mandibules = 0.15F + 0.85F * r;
                p.bD = Mth.lerp(r, p.bD, -1.6F);
                p.bG = p.bD;
                p.bDZ = 0.25F + 1.1F * r;
                p.bGZ = p.bDZ;
                p.cD = -0.3F;
                p.cG = -0.3F;
            }
            case RoiFourmi.INVOCATION -> {
                float leve = lisse(a / 10F), plante = lisse((a - 22) / 4F), retour = lisse((a - 34) / 10F);
                p.bD = Mth.lerp(plante, -2.8F * leve, -0.2F) * (1 - retour) - 0.6F * retour;
                p.bG = p.bD;
                p.penche = 0.18F + 0.7F * plante * (1 - retour);
                p.accroupi = 0.6F * plante * (1 - retour);
                p.tT = -0.5F * leve * (1 - plante);
                p.mandibules = leve;
                p.ailes = 0.08F + 0.6F * leve * (1 - retour);
            }
            default -> {
            }
        }
        if (o.enrage()) {
            p.ailes = Math.max(p.ailes, 0.35F);
        }
        if (o.deathTime > 0) {
            float k = lisse(o.deathTime / 30F);
            p.chute = k;
            p.accroupi = Math.max(p.accroupi, k);
            p.ailes = Mth.lerp(k, p.ailes, -0.4F);
        }
        Formes.debut(buffers, lumiere, OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0)));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        dessiner(ps, p, o.enrage(), temps);
        ps.popPose();
    }

    private static float bosse(float a, float centre) {
        return lisse((a - centre + 3) / 3F) * (1 - lisse((a - centre) / 4F));
    }

    private static void dessiner(PoseStack ps, P p, boolean rage, float temps) {
        float pulse = 0.65F + 0.35F * Mth.sin(temps * (rage ? 0.45F : 0.12F));
        int veine = Formes.melange(0xB04A08, rage ? 0xFFE070 : 0xFF9A2A, pulse);
        ps.pushPose();
        if (p.chute > 0) {
            ps.mulPose(Axis.ZP.rotation(p.chute * 1.4F));
        }
        // accroupi, les genoux pliés : tout le corps descend
        ps.translate(0, -p.accroupi * 1.0F, 0);
        jambe(ps, -0.45F, p.cuG - p.accroupi * 1.2F, p.tiG + p.accroupi * 2.0F);
        jambe(ps, 0.45F, p.cuD - p.accroupi * 1.2F, p.tiD + p.accroupi * 2.0F);
        float bob = Math.abs(Mth.sin(p.pas)) * 0.08F * p.amp;
        ps.translate(0, bob, 0);
        // le bassin, et l'abdomen de fourmi derrière
        pave(ps, -0.45F, 1.85F, -0.35F, 0.45F, 2.25F, 0.3F, CHITINE2);
        ps.pushPose();
        tourner(ps, 0, 2.1F, -0.3F, 0.35F - p.penche * 0.3F, 0, 0);
        pave(ps, -0.55F, -0.5F, -1.9F, 0.55F, 0.55F, -0.2F, CHITINE);
        pave(ps, -0.7F, -0.6F, -1.6F, 0.7F, 0.65F, -0.6F, CHITINE);
        pave(ps, -0.71F, -0.15F, -1.4F, 0.71F, -0.05F, -0.8F, veine, true);
        pave(ps, -0.56F, 0.25F, -1.9F, 0.56F, 0.33F, -1.7F, veine, true);
        pave(ps, -0.1F, -0.15F, -2.1F, 0.1F, 0.05F, -1.85F, 0xE8A040);
        ps.popPose();
        // la taille de guêpe, puis le thorax, penché en avant
        ps.pushPose();
        tourner(ps, 0, 2.2F, 0, p.penche, 0, 0);
        pave(ps, -0.18F, 0, -0.15F, 0.18F, 0.35F, 0.15F, BRUN);
        pave(ps, -0.6F, 0.3F, -0.42F, 0.6F, 1.35F, 0.4F, CHITINE);
        pave(ps, -0.5F, 0.5F, 0.38F, 0.5F, 1.25F, 0.5F, CHITINE2);
        // les veines orange qui luisent sur la poitrine
        pave(ps, -0.42F, 0.7F, 0.5F, -0.34F, 1.15F, 0.53F, veine, true);
        pave(ps, 0.34F, 0.7F, 0.5F, 0.42F, 1.15F, 0.53F, veine, true);
        pave(ps, -0.34F, 1.1F, 0.5F, 0.34F, 1.17F, 0.53F, veine, true);
        // les épaulières, pointues
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            tourner(ps, s * 0.62F, 1.3F, 0, 0, 0, -s * 0.5F);
            pave(ps, -0.32F, -0.1F, -0.32F, 0.32F, 0.2F, 0.32F, CHITINE2);
            pave(ps, -0.06F, 0.2F, -0.06F, 0.06F, 0.6F, 0.06F, 0x2A2018);
            ps.popPose();
        }
        // la tête
        ps.pushPose();
        tourner(ps, 0, 1.35F, 0.1F, p.tT - p.penche * 0.6F, p.tL, 0);
        tete(ps, p, veine, rage, temps);
        ps.popPose();
        // les bras-faux
        bras(ps, false, p.bG, p.bGZ, p.cG, veine);
        bras(ps, true, p.bD, p.bDZ, p.cD, veine);
        // les ailes (translucides : en dernier)
        ailes(ps, p);
        ps.popPose();
        ps.popPose();
    }

    private static void jambe(PoseStack ps, float x, float cuisse, float tibia) {
        ps.pushPose();
        // pattes à l'envers : la cuisse part vers l'avant, le tibia revient vers l'arrière
        tourner(ps, x, 2.0F, 0, cuisse - 0.45F, 0, 0);
        pave(ps, -0.2F, -1.05F, -0.2F, 0.2F, 0.1F, 0.2F, CHITINE2);
        tourner(ps, 0, -1.0F, 0, tibia + 0.95F, 0, 0);
        pave(ps, -0.14F, -1.15F, -0.14F, 0.14F, 0.05F, 0.14F, CHITINE);
        tourner(ps, 0, -1.1F, 0, -0.5F, 0, 0);
        pave(ps, -0.12F, -0.12F, -0.1F, 0.12F, 0.05F, 0.55F, BRUN);
        pave(ps, -0.05F, -0.12F, 0.5F, 0.05F, 0F, 0.75F, LAME);
        ps.popPose();
    }

    /** La tête : allongée, deux grands yeux qui luisent, des mandibules qui s'ouvrent, deux antennes coudées. */
    private static void tete(PoseStack ps, P p, int veine, boolean rage, float temps) {
        pave(ps, -0.32F, 0, -0.25F, 0.32F, 0.55F, 0.75F, CHITINE);
        pave(ps, -0.25F, 0.05F, 0.7F, 0.25F, 0.42F, 0.95F, CHITINE2);
        int yeux = rage ? 0xFFF080 : 0xFF9A2A;
        pave(ps, -0.4F, 0.18F, 0.2F, -0.28F, 0.48F, 0.62F, yeux, true);
        pave(ps, 0.28F, 0.18F, 0.2F, 0.4F, 0.48F, 0.62F, yeux, true);
        // la crête
        pave(ps, -0.06F, 0.55F, -0.2F, 0.06F, 0.72F, 0.6F, veine, true);
        // les mandibules
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            tourner(ps, s * 0.2F, 0.1F, 0.9F, 0, s * p.mandibules * 0.7F, 0);
            pave(ps, -0.07F, -0.08F, 0, 0.07F, 0.1F, 0.5F, BRUN);
            tourner(ps, 0, 0, 0.45F, 0, -s * 0.9F, 0);
            pave(ps, -0.06F, -0.07F, 0, 0.06F, 0.08F, 0.35F, LAME);
            ps.popPose();
        }
        // les antennes : elles frémissent
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            float f = Mth.sin(temps * 0.3F + s) * 0.08F;
            tourner(ps, s * 0.15F, 0.5F, 0.6F, -0.9F + f, s * 0.35F, 0);
            pave(ps, -0.03F, 0, -0.03F, 0.03F, 0.8F, 0.03F, BRUN);
            tourner(ps, 0, 0.78F, 0, 1.2F + f, 0, 0);
            pave(ps, -0.03F, 0, -0.03F, 0.03F, 0.7F, 0.03F, BRUN);
            ps.popPose();
        }
    }

    /** Un bras : l'épaule, le coude, et au bout la faux, une lame d'os recourbée au fil orange. */
    private static void bras(PoseStack ps, boolean droit, float bras, float brasZ, float coude, int veine) {
        float s = droit ? 1 : -1;
        ps.pushPose();
        tourner(ps, s * 0.78F, 1.2F, 0, bras, 0, s * -brasZ);
        pave(ps, -0.17F, -1.0F, -0.17F, 0.17F, 0.12F, 0.17F, CHITINE2);
        tourner(ps, 0, -0.95F, 0, coude, 0, 0);
        pave(ps, -0.15F, -0.95F, -0.15F, 0.15F, 0.05F, 0.15F, CHITINE);
        pave(ps, -0.16F, -0.7F, 0.14F, 0.16F, -0.5F, 0.17F, veine, true);
        // la faux : elle part du poignet vers l'avant et s'incurve vers le bas
        tourner(ps, 0, -0.9F, 0, 1.2F, 0, 0);
        pave(ps, -0.05F, -0.12F, -0.1F, 0.05F, 0.22F, 1.2F, LAME);
        pave(ps, -0.03F, -0.16F, 0.1F, 0.03F, -0.1F, 1.15F, veine, true);
        tourner(ps, 0, 0, 1.15F, 0.55F, 0, 0);
        pave(ps, -0.045F, -0.1F, -0.05F, 0.045F, 0.16F, 0.75F, LAME);
        pave(ps, -0.03F, -0.13F, 0.05F, 0.03F, -0.08F, 0.7F, veine, true);
        ps.popPose();
    }

    /**
     * Les quatre ailes d'ambre, attachées dans le dos : {@code ailes} à 0, repliées le long du dos ; à 1, grandes
     * ouvertes ; à -1, enroulées autour de lui (il dort) ; {@code battement} les fait battre.
     */
    private static void ailes(PoseStack ps, P p) {
        for (int s : new int[] {-1, 1}) {
            for (int k = 0; k < 2; k++) {
                ps.pushPose();
                tourner(ps, s * 0.25F, 1.2F - k * 0.3F, -0.4F, 0, 0, 0);
                float ouvre = Math.max(0, p.ailes);
                float enroule = Math.max(0, -p.ailes);
                // repliées : vers l'arrière et le bas ; ouvertes : vers le côté ; enroulées : vers l'avant, autour de lui
                float lacetAile = s * (Mth.lerp(ouvre, 2.9F, 1.75F + k * 0.35F) - enroule * 2.3F);
                float roulis = s * (Mth.lerp(ouvre, 0.25F, 0.35F - k * 0.25F) + p.battement * (k == 0 ? 1 : 0.8F));
                ps.mulPose(Axis.YP.rotation(lacetAile));
                ps.mulPose(Axis.ZP.rotation(-roulis));
                ps.mulPose(Axis.XP.rotation(Mth.lerp(ouvre, 0.9F, 0.15F) * (1 - enroule) + enroule * 0.2F));
                float l = k == 0 ? 2.6F : 2.0F, w = k == 0 ? 0.55F : 0.45F;
                Formes.verre(ps, -0.02F, -w, 0, 0.02F, w, l, AMBRE, 0.42F, false);
                Formes.verre(ps, -0.025F, -0.04F, 0, 0.025F, 0.04F, l * 0.95F, 0x7A4A1A, 0.85F, false);
                ps.popPose();
            }
        }
    }

    @Override
    public ResourceLocation getTextureLocation(RoiFourmi o) {
        return Formes.GRAIN;
    }
}
