package fr.cabane.client.mission;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.mission.MissionGarde;
import fr.cabane.mission.Missions;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;

/**
 * Les missions, côté jeu : les gardes (leur skin, leurs poses, couchés quand ils sont K.O., et la bulle au-dessus de la tête :
 * « ? », « ! », « Zzz »), le chien du château, le panneau de la mission en haut à droite, et la brume du village de la Brume.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientMissions {
    private static String titre = "";
    private static List<String> lignes = List.of();
    private static boolean brume;

    public static void hud(Missions.Hud p) {
        titre = p.titre();
        lignes = p.lignes();
        brume = p.brume();
    }

    private static boolean enMission() {
        Minecraft mc = Minecraft.getInstance();
        return !titre.isEmpty() && mc.level != null && (mc.level.dimension() == Missions.JOUR || mc.level.dimension() == Missions.NUIT);
    }

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Missions.GARDE.get(), RenduGarde::new);
        event.registerEntityRenderer(Missions.CHIEN.get(), WolfRenderer::new);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("infiltration"), (g, delta) -> dessiner(g));
        }
    }

    // ------------------------------------------------------------------ le panneau en haut à droite

    private static void dessiner(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!enMission() || mc.options.hideGui) {
            return;
        }
        Font f = mc.font;
        int largeur = f.width(titre);
        for (String l : lignes) {
            largeur = Math.max(largeur, f.width(l));
        }
        int w = g.guiWidth(), x1 = w - 6, x0 = x1 - largeur - 12, y0 = 6, h = 16 + lignes.size() * 11;
        g.fill(x0, y0, x1, y0 + h, 0x90000000);
        g.fill(x0, y0, x0 + 2, y0 + h, 0xFFFFC83C);
        g.drawString(f, Component.literal(titre).withStyle(s -> s.withBold(true)), x0 + 7, y0 + 4, 0xFFFFD84A, true);
        int y = y0 + 16;
        for (String l : lignes) {
            g.drawString(f, l, x0 + 7, y, 0xFFFFFFFF, true);
            y += 11;
        }
    }

    // ------------------------------------------------------------------ la brume du village

    @SubscribeEvent
    public static void onBrume(ViewportEvent.RenderFog event) {
        if (brume && enMission()) {
            event.setNearPlaneDistance(2F);
            event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), 34F));
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCouleurBrume(ViewportEvent.ComputeFogColor event) {
        if (brume && enMission()) {
            event.setRed(0.16F);
            event.setGreen(0.19F);
            event.setBlue(0.26F);
        }
    }

    // ------------------------------------------------------------------ les gardes

    /** Le modèle du joueur, avec les poses des gardes (au repos, l'arme en main, les bras croisés, le bras tendu). */
    public static final class ModeleGarde extends PlayerModel<MissionGarde> {
        public ModeleGarde(ModelPart racine) {
            super(racine, false);
        }

        @Override
        public void setupAnim(MissionGarde g, float marche, float amplitude, float age, float tete, float regard) {
            super.setupAnim(g, marche, amplitude, age, tete, regard);
            int pose = g.pose();
            boolean bouge = amplitude > 0.15F;
            if (g.etat() == MissionGarde.ALERTE && pose == MissionGarde.CROISE) {
                pose = MissionGarde.PORTE;
            }
            if (attackTime <= 0) {
                switch (pose) {
                    case MissionGarde.CROISE -> {
                        if (!bouge) {
                            rightArm.xRot = -1.05F;
                            rightArm.yRot = -0.75F;
                            rightArm.zRot = 0;
                            leftArm.xRot = -1.05F;
                            leftArm.yRot = 0.75F;
                            leftArm.zRot = 0;
                        }
                    }
                    case MissionGarde.TEND -> {
                        rightArm.xRot = -1.35F + Mth.sin(age * 0.05F) * 0.04F;
                        rightArm.yRot = -0.1F;
                        if (g.type() == MissionGarde.MANNEQUIN) {
                            leftArm.xRot = -1.35F;
                            leftArm.yRot = 0.1F;
                        }
                    }
                    case MissionGarde.PORTE -> rightArm.xRot = rightArm.xRot * 0.5F - 0.45F;
                    default -> {
                    }
                }
            }
            rightSleeve.copyFrom(rightArm);
            leftSleeve.copyFrom(leftArm);
        }
    }

    public static final class RenduGarde extends HumanoidMobRenderer<MissionGarde, ModeleGarde> {
        public RenduGarde(EntityRendererProvider.Context ctx) {
            super(ctx, new ModeleGarde(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(MissionGarde g) {
            return Cabane.id("textures/entity/mission/" + g.skin().toLowerCase(java.util.Locale.ROOT) + ".png");
        }

        /** K.O. : couché sur le côté. */
        @Override
        protected void setupRotations(MissionGarde g, PoseStack pose, float bob, float corps, float pt, float echelle) {
            super.setupRotations(g, pose, bob, corps, pt, echelle);
            if (g.etat() == MissionGarde.KO) {
                pose.translate(0, 0.1, 0);
                pose.mulPose(Axis.ZP.rotationDegrees(90F));
            }
        }

        @Override
        public void render(MissionGarde g, float yaw, float pt, PoseStack pose, MultiBufferSource tampons, int lumiere) {
            super.render(g, yaw, pt, pose, tampons, lumiere);
            String bulle;
            int couleur;
            switch (g.etat()) {
                case MissionGarde.ALERTE -> {
                    bulle = "!";
                    couleur = 0xFFFF3030;
                }
                case MissionGarde.SOUPCON -> {
                    bulle = "?";
                    couleur = 0xFFFFD84A;
                }
                case MissionGarde.KO -> {
                    bulle = (g.tickCount / 10) % 3 == 0 ? "z" : (g.tickCount / 10) % 3 == 1 ? "zZ" : "zZz";
                    couleur = 0xFFB4D8FF;
                }
                default -> {
                    return;
                }
            }
            pose.pushPose();
            pose.translate(0, g.etat() == MissionGarde.KO ? 0.9 : g.getBbHeight() + 0.55, 0);
            pose.mulPose(entityRenderDispatcher.cameraOrientation());
            float k = g.etat() == MissionGarde.KO ? 0.03F : 0.055F;
            pose.scale(k, -k, k);
            Font f = getFont();
            Matrix4f m = pose.last().pose();
            float x = -f.width(bulle) / 2F;
            f.drawInBatch(bulle, x, 0, couleur, false, m, tampons, Font.DisplayMode.SEE_THROUGH, 0x50000000, 0xF000F0);
            f.drawInBatch(bulle, x, 0, couleur, false, m, tampons, Font.DisplayMode.NORMAL, 0, 0xF000F0);
            pose.popPose();
        }
    }

    private ClientMissions() {
    }
}
