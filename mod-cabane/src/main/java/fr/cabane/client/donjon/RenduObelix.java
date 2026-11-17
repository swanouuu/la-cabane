package fr.cabane.client.donjon;

import static fr.cabane.client.donjon.Formes.lisse;
import static fr.cabane.client.donjon.Formes.pave;
import static fr.cabane.client.donjon.Formes.tourner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.donjon.Obelix;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Obélix, en pavés : un ventre énorme rayé bleu et blanc (le pantalon), la ceinture verte, les épaules nues, la tête
 * aux tresses rousses et à la moustache orange, des bracelets dorés, et le menhir sur le dos. Il dort assis contre ses
 * menhirs ; il soulève son menhir et le lance (on le voit voler jusqu'au point d'impact), fonce le ventre en avant,
 * fait tourner quelqu'un au bout du bras, et mange un sanglier rôti.
 */
public class RenduObelix extends EntityRenderer<Obelix> {
    private static final int BLEU = 0x2F6FD6, BLANC = 0xF4F4F4, VERT = 0x3A9A2A, PEAU = 0xF1C7A0, ROUX = 0xD8462A, MOUSTACHE = 0xE0662A, CUIR = 0x6B4A2B,
            OR = 0xE8C04A, MENHIR = 0x9AA0A6, MENHIR_S = 0x7D838A, ROTI = 0x8A4A24;

    public RenduObelix(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 2.4F;
    }

    private static final class P {
        float jG, jD, bG, bD, bGz, bDz, tete, penche, abaisse;
        /** Le menhir : 0 sur le dos, 1 au-dessus de la tête ; vol : le menhir est parti. */
        float leve;
        boolean vol, roti;
    }

    @Override
    public boolean shouldRender(Obelix o, net.minecraft.client.renderer.culling.Frustum f, double x, double y, double z) {
        return o.distanceToSqr(x, y, z) < 160 * 160;
    }

    @Override
    public void render(Obelix o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float a = o.tickCount - o.debutClient + partiel;
        float amp = Math.min(1F, o.walkAnimation.speed(partiel) * 1.8F), pas = o.walkAnimation.position(partiel) * 0.42F;
        float sw = Mth.sin(pas) * amp;
        P p = new P();
        p.jG = sw * 0.45F;
        p.jD = -sw * 0.45F;
        p.bG = -sw * 0.35F;
        p.bD = sw * 0.35F;
        switch (o.etat()) {
            case Obelix.SOMMEIL -> assis(p, 1);
            case Obelix.REVEIL -> {
                float k = lisse((a - 20) / 10F);
                assis(p, 1 - k);
                if (a > 32) {
                    float r = lisse((a - 32) / 6F) * (1 - lisse((a - 44) / 6F));
                    p.bG = -2.9F * r;
                    p.bD = -2.9F * r;
                    p.tete = -0.4F * r;
                }
            }
            case Obelix.MENHIR -> {
                if (a < Obelix.LANCER) {
                    p.leve = lisse(a / 22F);
                    p.bG = -3.0F * p.leve;
                    p.bD = -3.0F * p.leve;
                    p.penche = -0.15F * p.leve;
                } else {
                    p.vol = true;
                    float k = lisse((a - Obelix.LANCER) / 5F) * (1 - lisse((a - 40) / 8F));
                    p.bG = Mth.lerp(k, -3.0F, -0.6F) * (a > 40 ? 1 - lisse((a - 40) / 8F) : 1);
                    p.bD = p.bG;
                    p.penche = 0.25F * k;
                }
            }
            case Obelix.CHARGE -> {
                float k = a < 10 ? lisse(a / 10F) : 1;
                p.penche = 0.35F * k;
                p.bG = 0.7F * k;
                p.bD = 0.7F * k;
                if (a >= 10) {
                    float s2 = Mth.sin(a * 1.2F);
                    p.jG = s2 * 0.75F;
                    p.jD = -s2 * 0.75F;
                } else {
                    p.abaisse = 0.3F * k;
                }
            }
            case Obelix.ATTRAPE -> {
                float k = lisse(a / 6F);
                p.bD = -1.5F * k;
                p.bDz = 0.9F * k;
                p.bG = -0.3F * k;
                p.penche = -0.1F * k;
            }
            case Obelix.SANGLIER -> {
                float k = lisse(a / 6F) * (1 - lisse((a - 62) / 6F));
                float mache = Mth.sin(a * 0.8F) * 0.25F;
                p.bG = (-1.6F + mache) * k;
                p.bD = (-1.6F + mache) * k;
                p.bGz = -0.35F * k;
                p.bDz = 0.35F * k;
                p.tete = 0.15F * k;
                p.roti = k > 0.3F && a < 58;
            }
            case Obelix.RAGE -> {
                float r = lisse(a / 8F) * (1 - lisse((a - 40) / 8F));
                p.bG = -2.9F * r;
                p.bD = -2.9F * r;
                p.bGz = -0.5F * r;
                p.bDz = 0.5F * r;
                p.tete = -0.4F * r;
                // il tape du pied
                float pied = Math.max(lisse((a - 14) / 5F) * (1 - lisse((a - 20) / 2F)), lisse((a - 28) / 5F) * (1 - lisse((a - 34) / 2F)));
                p.jD = -1.0F * pied;
            }
            case Obelix.INVOCATION -> {
                float k = lisse(a / 6F) * (1 - lisse((a - 30) / 6F));
                p.bD = -2.8F * k;
                p.bG = -2.8F * k;
            }
            default -> {
            }
        }
        Formes.debut(buffers, lumiere, OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0)));
        // le menhir en vol, en coordonnées du monde (avant de tourner le corps)
        if (o.etat() == Obelix.MENHIR && a >= Obelix.LANCER && a <= Obelix.IMPACT) {
            double ex = Mth.lerp(partiel, o.xo, o.getX()), ey = Mth.lerp(partiel, o.yo, o.getY()), ez = Mth.lerp(partiel, o.zo, o.getZ());
            Vec3 c = o.cibleMenhir();
            float k = (a - Obelix.LANCER) / (Obelix.IMPACT - Obelix.LANCER);
            double x = Mth.lerp(k, ex, c.x) - ex, z = Mth.lerp(k, ez, c.z) - ez;
            double y = Mth.lerp(k, ey + 9.5, c.y + 1.2) + Math.sin(Math.PI * k) * 9 - ey;
            ps.pushPose();
            ps.translate(x, y, z);
            ps.mulPose(Axis.YP.rotationDegrees(-corps));
            ps.mulPose(Axis.XP.rotation(k * 7F));
            menhir(ps);
            ps.popPose();
        }
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        if (o.deathTime > 0) {
            ps.mulPose(Axis.XP.rotation(lisse(o.deathTime / 24F) * 1.45F));
        }
        dessiner(ps, p);
        ps.popPose();
        super.render(o, lacet, partiel, ps, buffers, lumiere);
    }

    /** Assis par terre, les jambes tendues, adossé à ses menhirs, la tête qui pique du nez. */
    private static void assis(P p, float k) {
        p.abaisse = 1.6F * k;
        p.jG = Mth.lerp(k, p.jG, -1.5F);
        p.jD = Mth.lerp(k, p.jD, -1.4F);
        p.bG = Mth.lerp(k, p.bG, -0.3F);
        p.bD = Mth.lerp(k, p.bD, -0.3F);
        p.tete = Mth.lerp(k, p.tete, 0.4F);
        p.penche = Mth.lerp(k, p.penche, -0.2F);
    }

    /** Un menhir : une grande pierre grise, plus étroite en haut. */
    private static void menhir(PoseStack ps) {
        pave(ps, -0.6F, -2.1F, -0.5F, 0.6F, 1.4F, 0.5F, MENHIR);
        pave(ps, -0.45F, 1.4F, -0.38F, 0.45F, 2.1F, 0.38F, MENHIR_S);
        pave(ps, -0.62F, -0.4F, -0.52F, 0.62F, -0.2F, 0.52F, 0x6B7178);
    }

    private static void dessiner(PoseStack ps, P p) {
        ps.pushPose();
        ps.translate(0, -p.abaisse, 0);
        // les jambes : le pantalon rayé, les sandales
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 0.75F, 1.9F, 0, s < 0 ? p.jG : p.jD, 0, 0);
            for (int i = 0; i < 4; i++) {
                float x = -0.48F + i * 0.24F;
                pave(ps, x, -1.7F, -0.47F, x + 0.24F, 0, 0.47F, (i & 1) == 0 ? BLEU : BLANC);
            }
            pave(ps, -0.52F, -1.92F, -0.5F, 0.52F, -1.6F, 0.9F, CUIR);
            ps.popPose();
        }
        ps.pushPose();
        tourner(ps, 0, 1.9F, 0, p.penche, 0, 0);
        // le ventre : des tranches de plus en plus larges puis plus étroites, rayées bleu et blanc
        float[][] tranches = {{-0.3F, 0.5F, 1.5F}, {0.4F, 1.3F, 2.0F}, {1.2F, 2.2F, 2.25F}, {2.1F, 2.8F, 2.1F}};
        for (float[] t : tranches) {
            float w = t[2], d = w * 0.9F;
            int n = 10;
            for (int i = 0; i < n; i++) {
                float x0 = -w + i * 2 * w / n;
                pave(ps, x0, t[0], -d, x0 + 2 * w / n, t[1], d, (i & 1) == 0 ? BLEU : BLANC);
            }
        }
        // la ceinture verte, le torse et les épaules
        pave(ps, -2.15F, 2.05F, -1.95F, 2.15F, 2.4F, 1.95F, VERT);
        pave(ps, -1.8F, 2.4F, -0.95F, 1.8F, 3.5F, 0.95F, PEAU);
        pave(ps, -0.9F, 2.6F, 0.92F, 0.9F, 3.3F, 1.0F, 0xE8B890);
        // la tête : le gros nez, la moustache orange, les tresses rousses, les cheveux
        ps.pushPose();
        tourner(ps, 0, 3.45F, 0.1F, p.tete, 0, 0);
        pave(ps, -0.65F, 0, -0.65F, 0.65F, 1.3F, 0.65F, PEAU);
        pave(ps, -0.25F, 0.2F, 0.6F, 0.25F, 0.7F, 1.05F, 0xF0A888);
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 0.5F, 0.25F, 0.72F, 0, 0, s * 0.45F);
            pave(ps, -0.4F, -0.11F, -0.1F, 0.4F, 0.11F, 0.1F, MOUSTACHE);
            ps.popPose();
            pave(ps, s * 0.28F - 0.06F, 0.69F, 0.64F, s * 0.28F + 0.06F, 0.81F, 0.67F, 0x111111);
            pave(ps, s * 0.7F - 0.11F, -0.95F, -0.31F, s * 0.7F + 0.11F, 0.55F, -0.09F, ROUX);
            pave(ps, s * 0.7F - 0.13F, -1.05F, -0.33F, s * 0.7F + 0.13F, -0.9F, -0.07F, VERT);
        }
        pave(ps, -0.68F, 1.25F, -0.7F, 0.68F, 1.55F, 0.5F, ROUX);
        pave(ps, -0.68F, 0.65F, -0.72F, 0.68F, 1.55F, -0.6F, ROUX);
        ps.popPose();
        // les bras, les bracelets dorés, et ce qu'il tient
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 2.25F, 3.3F, 0, s < 0 ? p.bG : p.bD, 0, s < 0 ? p.bGz : p.bDz);
            pave(ps, -0.33F, -1.25F, -0.33F, 0.33F, 0.05F, 0.33F, PEAU);
            pave(ps, -0.35F, -1.2F, -0.35F, 0.35F, -1.02F, 0.35F, OR);
            pave(ps, -0.3F, -1.9F, -0.25F, 0.3F, -1.1F, 0.35F, PEAU);
            pave(ps, -0.36F, -2.35F, -0.31F, 0.36F, -1.75F, 0.41F, PEAU);
            ps.popPose();
        }
        // le sanglier rôti, tenu à deux mains devant la bouche
        if (p.roti) {
            pave(ps, -1.0F, 4.05F, 1.3F, 1.0F, 4.85F, 2.1F, ROTI);
            pave(ps, 0.95F, 4.15F, 1.4F, 1.35F, 4.65F, 2.0F, ROTI);
            for (int s = -1; s <= 1; s += 2) {
                pave(ps, s * 0.6F - 0.1F, 3.75F, 1.4F, s * 0.6F + 0.1F, 4.1F, 1.6F, 0x5A2A14);
                pave(ps, s * 0.6F - 0.1F, 3.75F, 1.8F, s * 0.6F + 0.1F, 4.1F, 2.0F, 0x5A2A14);
            }
        }
        // le menhir : sur le dos, ou au-dessus de la tête (couché en travers) quand il va le lancer
        if (!p.vol) {
            ps.pushPose();
            if (p.leve > 0) {
                tourner(ps, 0, Mth.lerp(p.leve, 2.6F, 6.8F), Mth.lerp(p.leve, -1.6F, 0.3F), 0.2F * (1 - p.leve), 0, Mth.HALF_PI * p.leve);
            } else {
                tourner(ps, 0, 2.6F, -1.6F, 0.2F, 0, 0);
            }
            menhir(ps);
            ps.popPose();
        }
        ps.popPose();
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Obelix o) {
        return Formes.GRAIN;
    }
}
