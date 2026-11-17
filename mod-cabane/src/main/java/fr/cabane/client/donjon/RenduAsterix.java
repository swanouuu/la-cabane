package fr.cabane.client.donjon;

import static fr.cabane.client.donjon.Formes.lisse;
import static fr.cabane.client.donjon.Formes.pave;
import static fr.cabane.client.donjon.Formes.tourner;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.donjon.Asterix;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Astérix, en pavés (comme dans la maquette, 1,9 fois plus grand qu'un joueur) : le pantalon rouge, la tunique noire
 * et la ceinture, l'épée et la gourde de potion, la grosse moustache blonde et les tresses, le casque noir à ailes
 * blanches. Il dort assis ; il bondit, frappe du poing vers le ciel, boit, et tourne comme une toupie, les bras
 * écartés. Quand la potion fait effet, une lueur dorée l'entoure.
 */
public class RenduAsterix extends EntityRenderer<Asterix> {
    private static final float ECHELLE = 1.9F;
    private static final int ROUGE = 0xD03A2A, NOIR = 0x1B1B1B, PEAU = 0xF1C7A0, BLOND = 0xF5C542, CUIR = 0x6B4A2B, OR = 0xB8892B, CASQUE = 0x2A2A2A,
            BLANC = 0xFFFFFF, ACIER = 0xC8CCD2, GOURDE = 0x8A5A2B;

    public RenduAsterix(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 1.2F;
    }

    /** La pose : angles en radians. */
    private static final class P {
        float jG, jD, bG, bD, bGz, bDz, tete, penche, abaisse, aura, gourde;
    }

    @Override
    public void render(Asterix o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float a = o.tickCount - o.debutClient + partiel;
        float amp = Math.min(1F, o.walkAnimation.speed(partiel) * 1.4F), pas = o.walkAnimation.position(partiel) * 0.9F;
        float sw = Mth.sin(pas) * amp;
        P p = new P();
        p.jG = sw * 1.0F;
        p.jD = -sw * 1.0F;
        p.bG = -sw * 0.8F;
        p.bD = sw * 0.8F;
        switch (o.etat()) {
            case Asterix.SOMMEIL -> assis(p, 1);
            case Asterix.REVEIL -> {
                float k = lisse((a - 14) / 8F);
                assis(p, 1 - k);
                if (a > 22) {
                    float r = lisse((a - 22) / 6F) * (1 - lisse((a - 32) / 6F));
                    p.bD = -2.8F * r;
                    p.bG = -2.8F * r;
                }
            }
            case Asterix.SAUT -> {
                float acc = a < 6 ? lisse(a / 6F) : 1 - lisse((a - 6) / 3F);
                p.abaisse = 0.25F * acc;
                if (a >= 6 && a < 20) {
                    p.jG = -1.2F;
                    p.jD = 0.4F;
                    p.bG = -2.6F;
                    p.bD = -2.6F;
                } else if (a >= 20) {
                    p.abaisse = 0.3F * (1 - lisse((a - 20) / 5F));
                    p.bG = 0.5F;
                    p.bD = 0.5F;
                }
            }
            case Asterix.UPPERCUT -> {
                p.bD = a < Asterix.IMPACT_UPPERCUT ? 0.8F * lisse(a / 4F) : Mth.lerp(lisse((a - 6) / 2F), 0.8F, -2.9F) * (1 - lisse((a - 12) / 4F));
                p.bG = 0.4F;
                p.jG = 0;
                p.jD = 0;
                p.penche = a < 6 ? 0.2F : -0.15F;
            }
            case Asterix.POTION -> {
                float k = a / Asterix.DUREE_POTION;
                float boit = lisse(a / 5F) * (1 - lisse((a - 18) / 4F));
                p.bD = -2.3F * boit;
                p.bDz = 0.6F * boit;
                p.gourde = boit;
                p.tete = -0.4F * boit;
                p.aura = lisse((a - 18) / 6F);
                if (k > 0.7F) {
                    p.bG = -1.4F;
                    p.bD = -1.4F;
                }
            }
            case Asterix.TOURBILLON -> {
                p.bGz = -1.5F;
                p.bDz = 1.5F;
                p.bG = 0;
                p.bD = 0;
                p.aura = 1;
                p.jG = Mth.sin(a * 1.4F) * 0.6F;
                p.jD = -p.jG;
            }
            case Asterix.RAGE -> {
                float boit = lisse(a / 5F) * (1 - lisse((a - 26) / 4F));
                p.bD = -2.6F * boit;
                p.bDz = 0.4F * boit;
                p.gourde = boit;
                p.tete = -0.6F * boit;
                p.aura = lisse((a - 26) / 4F);
                if (a > 30) {
                    float f = lisse((a - 30) / 4F);
                    p.bG = -2.9F * f;
                    p.bD = -2.9F * f;
                    p.bGz = -0.4F * f;
                    p.bDz = 0.4F * f;
                }
            }
            case Asterix.INVOCATION -> {
                // il siffle, deux doigts dans la bouche
                float k = lisse(a / 6F) * (1 - lisse((a - 30) / 6F));
                p.bD = -2.0F * k;
                p.bDz = 0.9F * k;
                p.tete = -0.3F * k;
            }
            default -> {
            }
        }
        if (o.enrage()) {
            p.aura = Math.max(p.aura, 0.6F);
        }
        Formes.debut(buffers, lumiere, OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0)));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        if (o.deathTime > 0) {
            ps.mulPose(Axis.XP.rotation(-lisse(o.deathTime / 20F) * 1.5F));
        }
        ps.scale(ECHELLE, ECHELLE, ECHELLE);
        dessiner(ps, p, o.tickCount + partiel);
        ps.popPose();
        super.render(o, lacet, partiel, ps, buffers, lumiere);
    }

    /** Assis par terre, adossé à un tonneau, la tête sur la poitrine. */
    private static void assis(P p, float k) {
        p.abaisse = 0.55F * k;
        p.jG = Mth.lerp(k, p.jG, -1.45F);
        p.jD = Mth.lerp(k, p.jD, -1.35F);
        p.bG = Mth.lerp(k, p.bG, -0.4F);
        p.bD = Mth.lerp(k, p.bD, -0.4F);
        p.tete = Mth.lerp(k, p.tete, 0.45F);
        p.penche = Mth.lerp(k, p.penche, -0.15F);
    }

    private static void dessiner(PoseStack ps, P p, float temps) {
        ps.pushPose();
        ps.translate(0, -p.abaisse, 0);
        // les jambes : le pantalon rouge, les chaussures de cuir
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 0.16F, 0.62F, 0, s < 0 ? p.jG : p.jD, 0, 0);
            pave(ps, -0.14F, -0.51F, -0.16F, 0.14F, 0.01F, 0.16F, ROUGE);
            pave(ps, -0.15F, -0.63F, -0.16F, 0.15F, -0.47F, 0.26F, CUIR);
            ps.popPose();
        }
        ps.pushPose();
        tourner(ps, 0, 0.62F, 0, p.penche, 0, 0);
        // le corps : la tunique noire, la ceinture, l'épée et la gourde
        pave(ps, -0.4F, 0.0F, -0.26F, 0.4F, 0.56F, 0.26F, NOIR);
        pave(ps, -0.42F, -0.02F, -0.28F, 0.42F, 0.1F, 0.28F, OR);
        pave(ps, -0.47F, -0.4F, -0.05F, -0.41F, 0.15F, 0.05F, ACIER);
        pave(ps, -0.5F, 0.1F, -0.08F, -0.38F, 0.18F, 0.08F, OR);
        if (p.gourde < 0.5F) {
            pave(ps, 0.36F, -0.12F, 0.06F, 0.52F, 0.12F, 0.18F, GOURDE);
        }
        // la tête : le gros nez, la moustache tombante, les tresses blondes, le casque ailé
        ps.pushPose();
        tourner(ps, 0, 0.56F, 0, p.tete, 0, 0);
        pave(ps, -0.32F, 0, -0.32F, 0.32F, 0.64F, 0.32F, PEAU);
        pave(ps, -0.11F, 0.12F, 0.3F, 0.11F, 0.32F, 0.51F, 0xF0A888);
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 0.2F, 0.17F, 0.34F, 0, 0, s * 0.5F);
            pave(ps, -0.17F, -0.045F, -0.04F, 0.17F, 0.045F, 0.04F, BLOND);
            ps.popPose();
            pave(ps, s * 0.14F - 0.04F, 0.36F, 0.32F, s * 0.14F + 0.04F, 0.44F, 0.34F, 0x111111);
            pave(ps, s * 0.34F - 0.04F, -0.1F, -0.12F, s * 0.34F + 0.04F, 0.32F, -0.04F, BLOND);
        }
        pave(ps, -0.35F, 0.6F, -0.35F, 0.35F, 0.84F, 0.35F, CASQUE);
        pave(ps, -0.25F, 0.84F, -0.25F, 0.25F, 0.94F, 0.25F, CASQUE);
        float bat = Mth.sin(temps * 0.15F) * 0.05F;
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 0.38F, 0.72F, -0.02F, 0, 0, -s * (0.35F + bat));
            pave(ps, -0.03F, 0, -0.17F, 0.03F, 0.5F, 0.17F, BLANC);
            tourner(ps, 0, 0.42F, -0.04F, 0, 0, -s * 0.2F);
            pave(ps, -0.025F, 0, -0.11F, 0.025F, 0.3F, 0.11F, 0xF0F0F0);
            ps.popPose();
        }
        ps.popPose();
        // les bras : la manche noire, l'avant-bras, le poing ; la gourde à la main quand il boit
        for (int s = -1; s <= 1; s += 2) {
            ps.pushPose();
            tourner(ps, s * 0.5F, 0.5F, 0, s < 0 ? p.bG : p.bD, 0, s < 0 ? p.bGz : p.bDz);
            pave(ps, -0.11F, -0.27F, -0.12F, 0.11F, 0.01F, 0.12F, NOIR);
            pave(ps, -0.1F, -0.58F, -0.1F, 0.1F, -0.26F, 0.1F, PEAU);
            pave(ps, -0.125F, -0.75F, -0.125F, 0.125F, -0.53F, 0.125F, PEAU);
            if (s > 0 && p.gourde >= 0.5F) {
                pave(ps, -0.08F, -0.95F, 0.0F, 0.08F, -0.69F, 0.16F, GOURDE);
            }
            ps.popPose();
        }
        // la lueur dorée de la potion
        if (p.aura > 0.02F) {
            float al = 0.18F * p.aura * (0.8F + 0.2F * Mth.sin(temps * 0.6F));
            Formes.verre(ps, -0.75F, -0.6F, -0.6F, 0.75F, 1.7F, 0.6F, 0xFFD23F, al, true);
        }
        ps.popPose();
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Asterix o) {
        return Formes.GRAIN;
    }
}
