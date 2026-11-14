package fr.cabane.client.donjon;

import static fr.cabane.client.donjon.Formes.lisse;
import static fr.cabane.client.donjon.Formes.pave;
import static fr.cabane.client.donjon.Formes.tourner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.donjon.Reine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Arachna, la Reine-Araignée : un céphalothorax noir hérissé de pointes d'os, huit yeux rouges, deux crochets d'ivoire,
 * un énorme abdomen noir aux reflets violets marqué d'un sablier rouge qui luit, et huit longues pattes qui marchent par
 * groupes de quatre. Endormie, elle pend la tête en bas au bout de son fil, les pattes repliées.
 */
public class RenduReine extends EntityRenderer<Reine> {
    private static final int NOIR = 0x1C1820, NOIR2 = 0x2E2438, POIL = 0x3A2E44, VIOLET = 0x52306E, IVOIRE = 0xE8E0C8, OS = 0xCFC4A6;

    public RenduReine(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 3.2F;
        shadowStrength = 0.9F;
    }

    private static final class P {
        float pas, amp, cabre, abdomen, abaisse, crochets, replie, pendue, fil, dos, agite;
    }

    @Override
    public void render(Reine o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float temps = o.tickCount + partiel;
        float a = o.tickCount - o.debutClient + partiel;
        P p = new P();
        p.amp = Math.min(1F, o.walkAnimation.speed(partiel) * 1.6F);
        p.pas = o.walkAnimation.position(partiel) * 0.55F;
        p.crochets = 0.1F + Math.max(0, Mth.sin(temps * 0.07F)) * 0.15F;
        p.abdomen = Mth.sin(temps * 0.05F) * 0.04F;
        switch (o.etat()) {
            case Reine.SOMMEIL -> {
                p.pendue = 1;
                p.replie = 1;
                p.fil = 1;
            }
            case Reine.REVEIL -> {
                p.fil = 1 - lisse((a - Reine.DESCENTE) / 4F);
                float pose = lisse((a - Reine.DESCENTE + 6) / 8F);
                p.pendue = 1 - pose;
                p.replie = 1 - lisse((a - Reine.DESCENTE + 2) / 10F);
                if (a > Reine.DESCENTE + 4) {
                    float r = lisse((a - Reine.DESCENTE - 4) / 6F) * (1 - lisse((a - Reine.DESCENTE - 18) / 6F));
                    p.cabre = 0.6F * r;
                    p.crochets = 0.1F + 0.9F * r;
                    p.agite = r;
                }
            }
            case Reine.MORSURE -> {
                float leve = lisse(a / 8F) * (1 - lisse((a - 10) / 3F)), plonge = lisse((a - 10) / 3F) * (1 - lisse((a - 16) / 8F));
                p.cabre = 0.5F * leve - 0.25F * plonge;
                p.crochets = 0.1F + 0.9F * leve * (1 - lisse((a - 12) / 2F)) + 0.2F * plonge;
                p.agite = leve;
            }
            case Reine.TOILE -> {
                float vise = lisse(a / 10F) * (1 - lisse((a - 20) / 8F));
                p.abdomen = 0.75F * vise;
                p.cabre = -0.15F * vise;
                p.abaisse = 0.25F * vise;
            }
            case Reine.BOND -> {
                if (a < 12) {
                    p.abaisse = 0.6F * lisse(a / 8F);
                    p.replie = 0.3F * lisse(a / 8F);
                } else if (!o.onGround() || a < 16) {
                    p.agite = 1;
                    p.cabre = 0.2F;
                    p.crochets = 0.9F;
                } else {
                    p.abaisse = 0.5F * (1 - lisse((a - 16) / 12F));
                }
            }
            case Reine.VENIN -> {
                float r = lisse(a / 8F) * (1 - lisse((a - 26) / 10F));
                p.cabre = 0.75F * r;
                p.crochets = 0.1F + 0.9F * r;
                p.agite = 0.6F * r;
            }
            case Reine.RAGE -> {
                float r = lisse(a / 8F) * (1 - lisse((a - 32) / 10F));
                p.cabre = 0.8F * r;
                p.crochets = 0.1F + 0.9F * r;
                p.agite = r;
                p.abdomen = 0.3F * r;
            }
            case Reine.INVOCATION -> {
                float r = lisse(a / 10F) * (1 - lisse((a - 32) / 10F));
                p.abdomen = 0.5F * r + Mth.sin(a * 0.8F) * 0.08F * r;
                p.cabre = 0.3F * r;
            }
            default -> {
            }
        }
        if (o.deathTime > 0) {
            p.dos = lisse(o.deathTime / 24F);
            p.replie = Math.max(p.replie, lisse((o.deathTime - 10) / 30F));
        }
        Formes.debut(buffers, lumiere, OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0)));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        dessiner(ps, p, o.enrage(), temps);
        ps.popPose();
    }

    private static void dessiner(PoseStack ps, P p, boolean rage, float temps) {
        float pulse = 0.65F + 0.35F * Mth.sin(temps * (rage ? 0.35F : 0.1F));
        int rouge = Formes.melange(0x8A0A16, rage ? 0xFF5A3A : 0xFF2238, pulse);
        int yeux = rage ? 0xFF7050 : 0xFF2A2A;
        ps.pushPose();
        if (p.fil > 0.02F) {
            // le fil de soie, des filières jusqu'au plafond
            pave(ps, -0.04F, 5.4F, 1.86F, 0.04F, 5.4F + 30 * p.fil, 1.94F, 0xF4F4F4, true);
        }
        if (p.pendue > 0) {
            // tête en bas, pendue par l'abdomen
            ps.translate(0, 2.0F * p.pendue, 0);
            ps.mulPose(Axis.XP.rotation(p.pendue * 1.45F));
            ps.translate(0, 0, 1.0F * p.pendue);
        }
        if (p.dos > 0) {
            ps.translate(0, 1.6F * Mth.sin(p.dos * Mth.PI) * 0.5F, 0);
            ps.mulPose(Axis.ZP.rotation(p.dos * Mth.PI));
            ps.translate(0, -2.6F * p.dos, 0);
        }
        ps.translate(0, -p.abaisse, 0);
        // le corps se cabre autour de la jointure entre thorax et abdomen
        ps.pushPose();
        tourner(ps, 0, 1.8F, 0.2F, -p.cabre, 0, 0);
        thorax(ps, p, yeux);
        // les pattes, quatre de chaque côté
        float[] lacets = {0.55F, 0.18F, -0.22F, -0.62F};
        float[] places = {1.75F, 1.3F, 0.85F, 0.4F};
        for (int i = 0; i < 4; i++) {
            for (int s : new int[] {-1, 1}) {
                boolean groupeA = (i % 2 == 0) == (s < 0);
                float phase = p.pas + (groupeA ? 0 : Mth.PI);
                float balance = Mth.sin(phase) * 0.28F * p.amp;
                float leve = Math.max(0, Mth.cos(phase)) * 0.35F * p.amp;
                // les pattes avant battent l'air quand elle se cabre
                float agite = i == 0 ? p.agite * (0.9F + Mth.sin(temps * 0.6F + s) * 0.3F) : i == 1 ? p.agite * 0.4F : 0;
                patte(ps, s, places[i] - 0.2F, lacets[i] + balance, leve + agite, p.replie, p.cabre * (i < 2 ? 0 : 1));
            }
        }
        ps.popPose();
        // l'abdomen, derrière
        ps.pushPose();
        tourner(ps, 0, 1.9F, 0.15F, -p.abdomen, 0, 0);
        abdomen(ps, rouge);
        ps.popPose();
        ps.popPose();
    }

    private static void thorax(PoseStack ps, P p, int yeux) {
        pave(ps, -1.05F, -0.55F, 0, 1.05F, 0.55F, 2.1F, NOIR);
        pave(ps, -0.9F, 0.45F, 0.2F, 0.9F, 0.75F, 1.9F, NOIR2);
        // les pointes d'os sur le dos du thorax
        for (int k = 0; k < 3; k++) {
            for (int s : new int[] {-1, 1}) {
                ps.pushPose();
                tourner(ps, s * 0.45F, 0.7F, 0.5F + k * 0.55F, -0.35F, 0, s * 0.3F);
                pave(ps, -0.09F, 0, -0.09F, 0.09F, 0.55F - k * 0.1F, 0.09F, OS);
                ps.popPose();
            }
        }
        // la tête, et ses huit yeux
        pave(ps, -0.8F, -0.45F, 2.0F, 0.8F, 0.5F, 2.75F, NOIR2);
        pave(ps, -0.3F, 0.15F, 2.72F, -0.06F, 0.39F, 2.8F, yeux, true);
        pave(ps, 0.06F, 0.15F, 2.72F, 0.3F, 0.39F, 2.8F, yeux, true);
        for (int k = 0; k < 3; k++) {
            float x = 0.35F + k * 0.13F, y = 0.05F + k * 0.12F;
            pave(ps, x, y, 2.6F - k * 0.1F, x + 0.13F, y + 0.13F, 2.78F - k * 0.1F, yeux, true);
            pave(ps, -x - 0.13F, y, 2.6F - k * 0.1F, -x, y + 0.13F, 2.78F - k * 0.1F, yeux, true);
        }
        // les crochets : ils s'ouvrent (sur les côtés) et se referment
        for (int s : new int[] {-1, 1}) {
            ps.pushPose();
            tourner(ps, s * 0.3F, -0.3F, 2.6F, 0.2F, 0, s * p.crochets * 0.6F);
            pave(ps, -0.17F, -0.5F, -0.15F, 0.17F, 0.05F, 0.2F, NOIR2);
            tourner(ps, 0, -0.5F, 0.05F, 0.5F, 0, 0);
            pave(ps, -0.1F, -0.55F, -0.1F, 0.1F, 0, 0.1F, IVOIRE);
            ps.popPose();
            // les pédipalpes
            ps.pushPose();
            tourner(ps, s * 0.6F, -0.2F, 2.5F, -0.6F + p.crochets * 0.4F, s * 0.3F, 0);
            pave(ps, -0.08F, -0.08F, 0, 0.08F, 0.08F, 0.7F, POIL);
            ps.popPose();
        }
    }

    /**
     * Une patte : de la hanche, la cuisse monte vers l'extérieur, puis le tibia redescend jusqu'au sol. {@code lacet} :
     * vers l'avant (+) ou l'arrière (-) ; {@code leve} : la patte se soulève ; {@code replie} : repliée sous le corps.
     */
    private static void patte(PoseStack ps, int s, float z, float lacet, float leve, float replie, float cabre) {
        ps.pushPose();
        tourner(ps, s * 0.95F, 0, z, 0, s * lacet, 0);
        // la cuisse : vers l'extérieur et vers le haut
        float monte = 0.95F + leve + replie * 0.6F + cabre * 0.5F;
        ps.mulPose(Axis.ZP.rotation(s * monte));
        pave(ps, s > 0 ? 0 : -2.5F, -0.15F, -0.15F, s > 0 ? 2.5F : 0, 0.15F, 0.15F, NOIR);
        pave(ps, s > 0 ? 0.6F : -1.9F, 0.12F, -0.1F, s > 0 ? 1.9F : -0.6F, 0.2F, 0.1F, POIL);
        // le genou, puis le tibia, qui plonge vers le sol
        ps.translate(s * 2.45F, 0, 0);
        pave(ps, -0.19F, -0.19F, -0.19F, 0.19F, 0.19F, 0.19F, VIOLET);
        float descend = 2.25F + replie * 0.9F - leve * 0.4F;
        ps.mulPose(Axis.ZP.rotation(-s * descend));
        pave(ps, s > 0 ? 0 : -3.3F, -0.12F, -0.12F, s > 0 ? 3.3F : 0, 0.12F, 0.12F, NOIR2);
        pave(ps, s > 0 ? 3.0F : -3.9F, -0.07F, -0.07F, s > 0 ? 3.9F : -3.0F, 0.07F, 0.07F, OS);
        ps.popPose();
    }

    /** L'abdomen : un œuf noir aux reflets violets, le sablier rouge qui luit, les filières au bout. */
    private static void abdomen(PoseStack ps, int rouge) {
        pave(ps, -1.25F, -0.7F, -1.0F, 1.25F, 1.2F, 0.1F, NOIR);
        pave(ps, -1.7F, -0.9F, -3.4F, 1.7F, 1.75F, -0.8F, NOIR);
        pave(ps, -1.45F, -0.7F, -4.1F, 1.45F, 1.5F, -3.2F, NOIR);
        pave(ps, -1.5F, 1.7F, -3.2F, 1.5F, 1.95F, -1.0F, NOIR2);
        // les reflets violets, en bandes
        pave(ps, -1.72F, 0.3F, -3.0F, 1.72F, 0.5F, -1.2F, VIOLET);
        pave(ps, -1.47F, 0.1F, -3.9F, 1.47F, 0.3F, -3.4F, VIOLET);
        // le sablier rouge sur le dos
        pave(ps, -0.55F, 1.96F, -2.9F, 0.55F, 2.0F, -2.55F, rouge, true);
        pave(ps, -0.18F, 1.96F, -2.55F, 0.18F, 2.0F, -2.05F, rouge, true);
        pave(ps, -0.55F, 1.96F, -2.05F, 0.55F, 2.0F, -1.7F, rouge, true);
        // des poils raides
        for (int k = 0; k < 6; k++) {
            float x = -1.2F + k * 0.48F;
            pave(ps, x, 1.95F, -1.2F - (k % 2) * 0.4F, x + 0.06F, 2.35F, -1.14F - (k % 2) * 0.4F, POIL);
        }
        // les filières
        pave(ps, -0.3F, 0.2F, -4.4F, 0.3F, 0.6F, -4.05F, NOIR2);
    }

    @Override
    public ResourceLocation getTextureLocation(Reine o) {
        return Formes.GRAIN;
    }

    @Override
    public boolean shouldRender(Reine o, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        // son fil monte bien plus haut que sa boîte
        return o.etat() == Reine.SOMMEIL || o.etat() == Reine.REVEIL || super.shouldRender(o, f, x, y, z);
    }
}
