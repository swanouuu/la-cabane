package fr.cabane.client.donjon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.donjon.Donjon;
import fr.cabane.donjon.Ogre;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Ugrak en jeu : la pose de son modèle selon ce qu'il est en train de faire (dormir, rugir, frapper, sauter, charger…). */
public class RenduOgre extends EntityRenderer<Ogre> {
    public RenduOgre(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 2.7F;
        shadowStrength = 0.9F;
    }

    private static float lisse(float u) {
        u = Mth.clamp(u, 0, 1);
        return u * u * (3 - 2 * u);
    }

    @Override
    public void render(Ogre o, float lacet, float partiel, PoseStack ps, MultiBufferSource buffers, int lumiere) {
        float corps = Mth.rotLerp(partiel, o.yBodyRotO, o.yBodyRot);
        float teteL = Mth.clamp(Mth.wrapDegrees(Mth.rotLerp(partiel, o.yHeadRotO, o.yHeadRot) - corps), -50F, 50F) * Mth.DEG_TO_RAD;
        float temps = o.tickCount + partiel;
        float a = o.tickCount - o.debutClient + partiel;
        float amp = Math.min(1F, o.walkAnimation.speed(partiel) * 1.4F);
        float pas = o.walkAnimation.position(partiel) * 0.42F;
        float sw = Mth.sin(pas);

        // la marche : de lourdes enjambées, les bras qui balancent, le dos qui respire
        float tL = teteL, tT = 0.05F, abaisse = 0, penche = 0.22F + Mth.sin(temps * 0.07F) * 0.02F;
        float bD = -sw * 0.35F * amp, bDZ = 0.12F, cD = -0.25F, bG = sw * 0.35F * amp, bGZ = 0.12F, cG = -0.3F;
        float cuD = sw * 0.5F * amp, tiD = Math.max(0, -sw) * 0.7F * amp, cuG = -sw * 0.5F * amp, tiG = Math.max(0, sw) * 0.7F * amp;
        float mach = 0.05F + Math.max(0, Mth.sin(temps * 0.05F)) * 0.06F, arme = 0.45F, chute = 0;

        switch (o.etat()) {
            case Ogre.SOMMEIL -> {
                // agenouillé devant le trône, la tête basse, le couperet planté devant lui
                abaisse = 1.0F;
                cuG = -1.45F;
                tiG = 1.45F;
                cuD = 0.1F;
                tiD = 1.55F;
                penche = 0.45F;
                tT = 0.55F;
                tL = 0;
                bD = -0.55F;
                cD = -0.2F;
                arme = 1.25F;
                bG = -0.95F;
                cG = -0.6F;
                mach = 0.02F;
            }
            case Ogre.REVEIL -> {
                float k = lisse(a / 34F);
                abaisse = 1.0F * (1 - k);
                cuG = -1.45F * (1 - k);
                tiG = 1.45F * (1 - k);
                cuD = 0.1F * (1 - k);
                tiD = 1.55F * (1 - k);
                penche = Mth.lerp(k, 0.45F, 0.2F);
                tT = Mth.lerp(k, 0.55F, 0F);
                tL = 0;
                bD = Mth.lerp(k, -0.55F, -0.2F);
                arme = Mth.lerp(k, 1.25F, 0.5F);
                bG = Mth.lerp(k, -0.95F, 0F);
                if (a > 30) {
                    // le rugissement : la tête en arrière, les bras grands ouverts
                    float r = lisse((a - 30) / 8F) * (1 - lisse((a - 62) / 8F));
                    tT = -0.65F * r;
                    mach = 0.05F + 0.75F * r;
                    penche = Mth.lerp(r, 0.2F, -0.12F);
                    bDZ = 0.12F + 0.85F * r;
                    bGZ = 0.12F + 0.85F * r;
                    bD = -0.2F - 0.4F * r;
                    bG = -0.4F * r;
                }
            }
            case Ogre.FRAPPE -> {
                // le couperet monte au-dessus de la tête, puis s'abat devant lui
                float leve = lisse(a / 12F), abat = lisse((a - 12) / 3F), retour = lisse((a - 19) / 7F);
                bD = Mth.lerp(abat, Mth.lerp(leve, bD, -2.9F), -0.75F);
                bD = Mth.lerp(retour, bD, -0.2F);
                cD = Mth.lerp(leve, -0.25F, -0.6F) * (1 - abat) + -0.05F * abat;
                bG = Mth.lerp(abat, Mth.lerp(leve, 0, -2.4F), -0.6F) * (1 - retour);
                penche = Mth.lerp(abat, Mth.lerp(leve, 0.22F, -0.08F), 0.5F) * (1 - retour) + 0.22F * retour;
                arme = Mth.lerp(abat, 0.2F, 0.75F);
                mach = 0.1F + 0.5F * abat * (1 - retour);
                cuD = 0.25F * abat;
                cuG = -0.35F * abat;
                tiG = 0.3F * abat;
            }
            case Ogre.SAUT -> {
                if (a < 12) {
                    float k = lisse(a / 8F);
                    abaisse = 0.6F * k;
                    cuD = -0.7F * k;
                    tiD = 1.3F * k;
                    cuG = -0.7F * k;
                    tiG = 1.3F * k;
                    bD = 0.7F * k;
                    bG = 0.7F * k;
                    penche = 0.22F + 0.35F * k;
                } else if (!o.onGround() || a < 18) {
                    bD = -2.8F;
                    bG = -2.6F;
                    cD = -0.4F;
                    arme = 0.1F;
                    cuD = -0.6F;
                    tiD = 0.9F;
                    cuG = -0.3F;
                    tiG = 1.1F;
                    penche = 0.05F;
                    mach = 0.6F;
                } else {
                    // l'atterrissage : tout s'écrase vers le sol
                    float k = 1 - lisse((a - 18) / 16F);
                    abaisse = 0.7F * k;
                    cuD = -0.8F * k;
                    tiD = 1.4F * k;
                    cuG = -0.8F * k;
                    tiG = 1.4F * k;
                    bD = -0.5F;
                    bG = -0.4F;
                    arme = 0.9F;
                    penche = 0.22F + 0.45F * k;
                    mach = 0.4F * k;
                }
            }
            case Ogre.CHARGE -> {
                if (a < Ogre.PREPA_CHARGE) {
                    float r = lisse(a / 6F);
                    tT = -0.55F * r;
                    mach = 0.8F * r;
                    bDZ = 0.12F + 0.7F * r;
                    bGZ = 0.12F + 0.7F * r;
                    penche = 0.22F - 0.3F * r;
                    // il gratte le sol du pied
                    cuD = Mth.sin(a * 0.9F) * 0.4F;
                } else {
                    float ph = a * 0.75F;
                    float s = Mth.sin(ph);
                    penche = 0.7F;
                    tT = -0.45F;
                    bG = -1.3F;
                    cG = -0.2F;
                    bD = 0.7F;
                    arme = 0.2F;
                    cuD = s * 0.8F;
                    tiD = Math.max(0, -s) * 1.0F;
                    cuG = -s * 0.8F;
                    tiG = Math.max(0, s) * 1.0F;
                    mach = 0.3F;
                }
            }
            case Ogre.ETOURDI -> {
                tL = Mth.sin(a * 0.25F) * 0.5F;
                tT = 0.35F + Mth.sin(a * 0.4F) * 0.1F;
                penche = 0.55F;
                abaisse = 0.25F;
                cuD = -0.3F;
                tiD = 0.5F;
                cuG = -0.3F;
                tiG = 0.5F;
                bD = 0.15F;
                bG = 0.2F;
                bDZ = 0.25F;
                bGZ = 0.25F;
                arme = 1.2F;
                mach = 0.45F;
            }
            case Ogre.RAGE -> {
                float r = lisse(a / 10F) * (1 - lisse((a - 48) / 10F));
                tT = -0.7F * r;
                mach = 0.85F * r;
                penche = 0.22F - 0.35F * r;
                bD = -1.0F * r;
                bG = -1.0F * r;
                bDZ = 0.12F + 1.0F * r;
                bGZ = 0.12F + 1.0F * r;
                cD = -0.6F * r;
                cG = -0.6F * r;
            }
            case Ogre.ERUPTION -> {
                float leve = lisse(a / 14F), abat = lisse((a - 30) / 3F), retour = lisse((a - 36) / 8F);
                bD = Mth.lerp(abat, -3.0F * leve, -0.4F) * (1 - retour);
                bG = Mth.lerp(abat, -3.0F * leve, -0.4F) * (1 - retour);
                arme = 0.2F;
                penche = Mth.lerp(abat, 0.22F - 0.25F * leve, 0.7F) * (1 - retour) + 0.22F * retour;
                tT = Mth.lerp(abat, -0.4F * leve, 0.3F);
                mach = 0.6F * leve;
                abaisse = 0.4F * abat * (1 - retour);
            }
            default -> {
            }
        }
        if (o.deathTime > 0) {
            float k = lisse(o.deathTime / 34F);
            chute = k * k;
            mach = 0.7F;
        }
        ModeleOgre.Pose p = new ModeleOgre.Pose(pas, amp, tL, tT, abaisse, penche, bD, bDZ, cD, bG, bGZ, cG, cuD, tiD, cuG, tiG, mach, arme, chute);
        int overlay = OverlayTexture.pack(OverlayTexture.u(0F), OverlayTexture.v(o.hurtTime > 0));
        ps.pushPose();
        ps.mulPose(Axis.YP.rotationDegrees(-corps));
        // une fois et demie la taille du modèle : une dizaine de blocs, cornes comprises
        ps.scale(ModeleOgre.ECHELLE, ModeleOgre.ECHELLE, ModeleOgre.ECHELLE);
        ModeleOgre.dessiner(ps, buffers, p, lumiere, overlay, o.enrage(), temps);
        ps.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(Ogre o) {
        return Cabane.id("textures/entity/cheveux.png");
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(Donjon.OGRE.get(), RenduOgre::new);
        }
    }
}
