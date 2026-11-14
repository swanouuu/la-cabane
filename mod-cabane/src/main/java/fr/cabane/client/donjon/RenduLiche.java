package fr.cabane.client.donjon;

import static fr.cabane.client.donjon.Formes.lisse;
import static fr.cabane.client.donjon.Formes.pave;
import static fr.cabane.client.donjon.Formes.tourner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.client.cinema.Effets;
import fr.cabane.donjon.Liche;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Morvane, le Roi-Liche : un squelette immense et maigre dans une robe violette en lambeaux qui flotte au-dessus du
 * sol, un col d'os, des côtes qu'on voit sous la robe ouverte, un crâne coiffé d'une couronne d'or aux gemmes qui
 * brûlent, deux flammes cyan dans les orbites, de longues mains d'os, et un bâton tordu surmonté d'un orbe d'âme. Endormi,
 * il médite en tailleur au-dessus de son autel, la tête baissée.
 */
public class RenduLiche extends EntityRenderer<Liche> {
    private static final int OS = 0xE2D8BC, OS_S = 0xA89A7A, ROBE = 0x2E1A40, ROBE_S = 0x1A0E26, ROBE_C = 0x47285E, OR = 0xD8A53C, BOIS = 0x3A2A20;

    public RenduLiche(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.2F;
        shadowStrength = 0.5F;
    }

    private static final class P {
        float tL, tT, penche, bD, bDZ, cD, bG, bGZ, cG, baton, tailleur, yeux, orbe, chute, dissous, flotte;
    }

    @Override
    public void render(Liche o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        if (o.isInvisible()) {
            return;
        }
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float temps = o.tickCount + partiel;
        float a = o.tickCount - o.debutClient + partiel;
        P p = new P();
        p.tL = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, o.yHeadRotO, o.yHeadRot) - corps), -50F, 50F) * Mth.DEG_TO_RAD;
        p.penche = 0.08F;
        p.bD = -0.35F;
        p.bDZ = 0.12F;
        p.cD = -0.9F;
        p.bG = 0.1F + Mth.sin(temps * 0.05F) * 0.05F;
        p.bGZ = 0.18F;
        p.cG = -0.4F;
        p.yeux = 1;
        p.orbe = 0.6F;
        p.flotte = Mth.sin(temps * 0.08F) * 0.06F;
        switch (o.etat()) {
            case Liche.SOMMEIL -> {
                p.tailleur = 1;
                p.tT = 0.6F;
                p.yeux = 0;
                p.orbe = 0.2F;
            }
            case Liche.REVEIL -> {
                p.tailleur = 1 - lisse((a - 26) / 14F);
                p.tT = 0.6F * (1 - lisse((a - 18) / 8F));
                p.yeux = lisse((a - 20) / 4F);
                p.orbe = 0.2F + 0.8F * lisse((a - 30) / 10F);
                float r = lisse((a - 38) / 6F) * (1 - lisse((a - 60) / 8F));
                p.bG = Mth.lerp(r, p.bG, -1.6F);
                p.bGZ = Mth.lerp(r, p.bGZ, 0.9F);
                p.bD = Mth.lerp(r, p.bD, -2.4F);
                p.tT -= 0.4F * r;
            }
            case Liche.ORBES -> {
                float r = lisse(a / 8F) * (1 - lisse((a - 32) / 8F));
                p.bD = Mth.lerp(r, p.bD, -2.2F);
                p.cD = Mth.lerp(r, p.cD, -0.2F);
                float jet = Math.max(0, Mth.sin((a - 12) * 0.45F)) * r;
                p.bG = Mth.lerp(r, p.bG, -1.3F - 0.4F * jet);
                p.cG = -0.2F;
                p.orbe = 0.6F + 0.4F * r;
            }
            case Liche.BLINK -> {
                p.dissous = lisse(a / 6F) * (1 - lisse((a - 12) / 4F));
                p.bG = -0.8F;
                p.bGZ = 0.7F;
            }
            case Liche.CERCLES -> {
                float leve = lisse(a / 10F), plante = lisse((a - 28) / 4F), retour = lisse((a - 40) / 10F);
                p.bD = Mth.lerp(plante, -2.6F * leve, -0.9F) * (1 - retour) + -0.35F * retour;
                p.cD = -0.3F;
                p.bG = Mth.lerp(leve, p.bG, -1.0F) * (1 - retour);
                p.bGZ = 0.18F + 0.8F * leve * (1 - retour);
                p.tT = -0.3F * leve * (1 - plante) + 0.25F * plante * (1 - retour);
                p.penche = 0.08F + 0.35F * plante * (1 - retour);
                p.orbe = 0.6F + 0.4F * leve;
            }
            case Liche.RAYON -> {
                float r = lisse(a / 8F) * (1 - lisse((a - Liche.DUREE_RAYON + 8) / 8F));
                p.bD = Mth.lerp(r, p.bD, -1.6F);
                p.cD = Mth.lerp(r, p.cD, 0F);
                p.bG = Mth.lerp(r, p.bG, -1.5F);
                p.cG = -0.1F;
                p.baton = 1.1F * r;
                p.orbe = 0.6F + 0.4F * r + (a > Liche.CHARGE_RAYON ? 0.4F : 0);
                p.tT = 0.1F * r;
            }
            case Liche.ONDE -> {
                float leve = lisse(a / 10F), frappe = lisse((a - 10) / 3F), retour = lisse((a - 16) / 8F);
                p.bG = Mth.lerp(frappe, -2.6F * leve, -0.6F) * (1 - retour);
                p.bGZ = 0.18F + 1.0F * frappe * (1 - retour);
                p.bD = Mth.lerp(frappe, -2.0F * leve, -0.6F) * (1 - retour) - 0.35F * retour;
                p.bDZ = 0.12F + 0.9F * frappe * (1 - retour);
                p.tT = -0.5F * leve * (1 - frappe);
            }
            case Liche.RAGE, Liche.INVOCATION -> {
                float r = lisse(a / 10F) * (1 - lisse((a - 36) / 10F));
                p.bG = Mth.lerp(r, p.bG, -2.6F);
                p.bGZ = Mth.lerp(r, p.bGZ, 0.6F);
                p.bD = Mth.lerp(r, p.bD, -2.8F);
                p.tT = -0.6F * r;
                p.orbe = 0.6F + 0.4F * r;
            }
            default -> {
            }
        }
        if (o.deathTime > 0) {
            float k = lisse(o.deathTime / 50F);
            p.chute = k;
            p.yeux = 1 - k;
            p.orbe = 0.6F * (1 - k);
        }
        Formes.debut(buffers, lumiere, OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0)));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        dessiner(ps, buffers, p, o.enrage(), temps);
        ps.popPose();
    }

    private static void dessiner(PoseStack ps, MultiBufferSource buffers, P p, boolean rage, float temps) {
        float pulse = 0.7F + 0.3F * Mth.sin(temps * 0.2F);
        int gemme = Formes.melange(0x8A20C8, 0xE070FF, pulse);
        ps.pushPose();
        ps.translate(0, p.flotte - p.chute * 0.5F, 0);
        if (p.chute > 0) {
            // il s'effondre sur lui-même
            ps.mulPose(Axis.XP.rotation(p.chute * 0.4F));
            ps.scale(1, 1 - p.chute * 0.45F, 1);
        }
        if (p.dissous > 0) {
            ps.scale(1 - p.dissous * 0.6F, 1 + p.dissous * 0.3F, 1 - p.dissous * 0.6F);
        }
        // la robe : elle tombe en lambeaux qui ondulent, sans pieds (assis en tailleur, elle se replie)
        float hautRobe = 2.6F - p.tailleur * 1.2F;
        ps.pushPose();
        ps.translate(0, 2.7F - hautRobe, 0);
        pave(ps, -0.7F, 0.6F, -0.5F, 0.7F, hautRobe, 0.5F, ROBE);
        for (int k = 0; k < 8; k++) {
            float ang = k * Mth.PI / 4;
            float x = Mth.cos(ang) * 0.75F, z = Mth.sin(ang) * 0.6F;
            float onde = Mth.sin(temps * 0.12F + k * 1.3F) * 0.12F;
            float l = 0.9F + (k % 3) * 0.25F - p.tailleur * 0.4F;
            ps.pushPose();
            tourner(ps, x, 0.8F, z, onde + (z > 0 ? 0.15F : -0.15F) * (1 - p.tailleur), 0, -onde * Mth.sign(x));
            pave(ps, -0.22F, -l, -0.08F, 0.22F, 0.2F, 0.08F, k % 2 == 0 ? ROBE_S : ROBE);
            ps.popPose();
        }
        if (p.tailleur > 0.05F) {
            // les genoux d'os, croisés devant lui
            for (int s : new int[] {-1, 1}) {
                ps.pushPose();
                tourner(ps, s * 0.3F, 0.7F, 0.3F, 0, s * 0.6F * p.tailleur, 0);
                pave(ps, -0.13F, -0.13F, 0, 0.13F, 0.13F, 1.0F * p.tailleur, ROBE_S);
                ps.popPose();
            }
        }
        ps.popPose();
        // le torse, maigre : les côtes sous la robe ouverte
        ps.pushPose();
        tourner(ps, 0, 2.7F, 0, p.penche, 0, 0);
        pave(ps, -0.25F, 0, -0.2F, 0.25F, 1.5F, 0.1F, OS_S);
        for (int k = 0; k < 4; k++) {
            float y = 0.5F + k * 0.25F, w = 0.55F - Math.abs(k - 2) * 0.06F;
            pave(ps, -w, y, -0.35F, w, y + 0.1F, 0.3F, OS);
        }
        // les pans de la robe de chaque côté, et le col d'os
        pave(ps, -0.85F, 0, -0.45F, -0.4F, 1.6F, 0.4F, ROBE);
        pave(ps, 0.4F, 0, -0.45F, 0.85F, 1.6F, 0.4F, ROBE);
        pave(ps, -0.86F, 0, 0.3F, -0.62F, 1.6F, 0.42F, OR);
        pave(ps, 0.62F, 0, 0.3F, 0.86F, 1.6F, 0.42F, OR);
        pave(ps, -0.9F, 1.4F, -0.5F, 0.9F, 1.75F, 0.45F, ROBE_C);
        for (int k = -3; k <= 3; k++) {
            ps.pushPose();
            tourner(ps, k * 0.26F, 1.7F, -0.35F, -0.5F, 0, k * 0.12F);
            pave(ps, -0.07F, 0, -0.07F, 0.07F, 0.7F - Math.abs(k) * 0.08F, 0.07F, OS);
            ps.popPose();
        }
        // la cape
        pave(ps, -0.85F, -1.2F, -0.6F, 0.85F, 1.7F, -0.45F, ROBE_S);
        // le crâne et sa couronne
        ps.pushPose();
        tourner(ps, 0, 1.72F, 0.05F, p.tT, p.tL, 0);
        crane(ps, buffers, p, gemme, rage);
        ps.popPose();
        bras(ps, false, p.bG, p.bGZ, p.cG, p, buffers, temps);
        bras(ps, true, p.bD, p.bDZ, p.cD, p, buffers, temps);
        ps.popPose();
        ps.popPose();
    }

    private static void crane(PoseStack ps, MultiBufferSource buffers, P p, int gemme, boolean rage) {
        pave(ps, -0.38F, 0.1F, -0.38F, 0.38F, 0.85F, 0.36F, OS);
        pave(ps, -0.3F, -0.12F, 0.0F, 0.3F, 0.15F, 0.38F, OS_S);
        // les orbites, noires, où brûlent deux flammes
        pave(ps, -0.3F, 0.38F, 0.34F, -0.06F, 0.58F, 0.4F, 0x0A0610);
        pave(ps, 0.06F, 0.38F, 0.34F, 0.3F, 0.58F, 0.4F, 0x0A0610);
        if (p.yeux > 0.05F) {
            int c = rage ? 0xFF60FF : 0x7FF0FF;
            pave(ps, -0.24F, 0.42F, 0.4F, -0.12F, 0.53F, 0.42F, c, true);
            pave(ps, 0.12F, 0.42F, 0.4F, 0.24F, 0.53F, 0.42F, c, true);
            Effets.sphere(ps, buffers, new Vec3(-0.18, 0.48, 0.44), 0.09 * p.yeux, rage ? 1F : 0.5F, rage ? 0.4F : 0.95F, 1F, 0.8F * p.yeux);
            Effets.sphere(ps, buffers, new Vec3(0.18, 0.48, 0.44), 0.09 * p.yeux, rage ? 1F : 0.5F, rage ? 0.4F : 0.95F, 1F, 0.8F * p.yeux);
            Formes.reprendre();
        }
        // les dents
        for (int k = -2; k <= 2; k++) {
            pave(ps, k * 0.1F - 0.035F, -0.02F, 0.36F, k * 0.1F + 0.035F, 0.12F, 0.4F, 0xF4EEDC);
        }
        // la couronne d'or, ses pointes, ses gemmes
        pave(ps, -0.42F, 0.78F, -0.42F, 0.42F, 0.95F, 0.4F, OR);
        for (int k = 0; k < 5; k++) {
            float x = -0.36F + k * 0.18F;
            float h = k == 2 ? 0.55F : k % 2 == 0 ? 0.38F : 0.28F;
            pave(ps, x - 0.05F, 0.95F, 0.3F, x + 0.05F, 0.95F + h, 0.4F, OR);
            pave(ps, x - 0.05F, 0.95F, -0.42F, x + 0.05F, 0.95F + h * 0.7F, -0.32F, OR);
        }
        pave(ps, -0.08F, 0.82F, 0.4F, 0.08F, 0.94F, 0.44F, gemme, true);
        pave(ps, -0.32F, 0.82F, 0.4F, -0.22F, 0.92F, 0.43F, gemme, true);
        pave(ps, 0.22F, 0.82F, 0.4F, 0.32F, 0.92F, 0.43F, gemme, true);
    }

    private static void bras(PoseStack ps, boolean droit, float bras, float brasZ, float coude, P p, MultiBufferSource buffers, float temps) {
        float s = droit ? 1 : -1;
        ps.pushPose();
        tourner(ps, s * 0.75F, 1.45F, 0, bras, 0, s * -brasZ);
        // l'épaulière d'os
        pave(ps, -0.3F, -0.1F, -0.3F, 0.3F, 0.3F, 0.3F, OS_S);
        pave(ps, s > 0 ? 0.05F : -0.4F, 0.15F, -0.25F, s > 0 ? 0.4F : -0.05F, 0.45F, 0.25F, OS);
        // la manche, large, puis l'avant-bras d'os
        pave(ps, -0.22F, -1.3F, -0.22F, 0.22F, 0.05F, 0.22F, ROBE);
        tourner(ps, 0, -1.25F, 0, coude, 0, 0);
        pave(ps, -0.3F, -0.5F, -0.3F, 0.3F, 0.05F, 0.3F, ROBE_C);
        pave(ps, -0.07F, -1.2F, -0.07F, 0.07F, -0.4F, 0.07F, OS);
        // la main, de longs doigts
        pave(ps, -0.13F, -1.42F, -0.1F, 0.13F, -1.18F, 0.12F, OS);
        for (int k = -1; k <= 1; k++) {
            pave(ps, k * 0.08F - 0.025F, -1.75F, 0.02F, k * 0.08F + 0.025F, -1.4F, 0.08F, OS_S);
        }
        if (droit) {
            baton(ps, buffers, p, temps);
        }
        ps.popPose();
    }

    /** Le bâton : un bois noir tordu, une couronne de griffes d'os, et l'orbe d'âme qui brûle au milieu. */
    private static void baton(PoseStack ps, MultiBufferSource buffers, P p, float temps) {
        ps.pushPose();
        tourner(ps, 0, -1.3F, 0.05F, 1.57F + p.baton, 0, 0);
        pave(ps, -0.07F, -0.07F, -1.6F, 0.07F, 0.07F, 2.2F, BOIS);
        pave(ps, -0.1F, -0.1F, 0.4F, 0.1F, 0.1F, 0.55F, OR);
        // en haut (vers +z ici) : les griffes et l'orbe
        for (int k = 0; k < 4; k++) {
            ps.pushPose();
            tourner(ps, 0, 0, 2.2F, 0, k * Mth.HALF_PI, 0);
            tourner(ps, 0.12F, 0, 0, 0, 0, 0);
            ps.mulPose(Axis.YP.rotation(0.5F));
            pave(ps, -0.04F, -0.04F, 0, 0.04F, 0.04F, 0.55F, OS);
            ps.popPose();
        }
        float r = 0.2F + 0.08F * p.orbe + 0.03F * Mth.sin(temps * 0.4F);
        Effets.sphere(ps, buffers, new Vec3(0, 0, 2.45), r, 0.75F, 0.4F, 1F, 0.95F);
        Effets.sphere(ps, buffers, new Vec3(0, 0, 2.45), r * 1.9F, 0.55F, 0.3F, 1F, 0.35F * p.orbe);
        Formes.reprendre();
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Liche o) {
        return Formes.GRAIN;
    }
}
