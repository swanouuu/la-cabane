package fr.cabane.client.cartel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.cartel.ArmeItem;
import fr.cabane.cartel.Cartel;
import fr.cabane.cartel.CartelPnj;
import fr.cabane.cartel.ProjectileArme;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

/**
 * Le cartel côté client : les gars (leur skin, leur pose, l'arme à deux mains), les projectiles qui volent dans le bon
 * sens, la façon de tenir les armes pour les joueurs, les traçantes et les flammes du canon, le compteur de balles,
 * la lunette du Barrett et la touche R qui recharge.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientCartel {
    /** R : recharger. */
    public static final KeyMapping RECHARGER = new KeyMapping("key.cabane.recharger", org.lwjgl.glfw.GLFW.GLFW_KEY_R, "key.categories.cabane");

    @SubscribeEvent
    public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Cartel.PNJ.get(), RenduPnj::new);
        event.registerEntityRenderer(Cartel.PROJECTILE.get(), RenduProjectile::new);
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(RECHARGER);
    }

    // ------------------------------------------------------------------ tenir l'arme

    /**
     * Après la pose habituelle du joueur : les fusils, le bazooka et le minigun se tiennent à deux mains (la main
     * droite sur la poignée, la gauche sur le garde-main, comme une arbalète chargée) ; un pistolet se tient bras tendu.
     */
    public static void poser(PlayerModel<?> model, Player joueur) {
        ItemStack tenu = joueur.getMainHandItem();
        if (!(tenu.getItem() instanceof ArmeItem arme) || arme.spec.prise() == ArmeItem.Prise.LANCER) {
            return;
        }
        boolean droitier = joueur.getMainArm() == HumanoidArm.RIGHT;
        if (arme.deuxMains()) {
            AnimationUtils.animateCrossbowHold(model.rightArm, model.leftArm, model.head, droitier);
        } else {
            ModelPart bras = droitier ? model.rightArm : model.leftArm;
            bras.xRot = -Mth.HALF_PI + model.head.xRot;
            bras.yRot = model.head.yRot + (droitier ? -0.1F : 0.1F);
            bras.zRot = 0;
        }
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
    }

    /** Le modèle des gars : celui du joueur, avec leurs poses (bras croisés, arme en travers, en joue). */
    public static final class ModelePnj extends PlayerModel<CartelPnj> {
        public ModelePnj(ModelPart racine) {
            super(racine, false);
        }

        @Override
        public void setupAnim(CartelPnj pnj, float marche, float amplitude, float age, float tete, float regard) {
            super.setupAnim(pnj, marche, amplitude, age, tete, regard);
            ItemStack tenu = pnj.getMainHandItem();
            boolean arme = tenu.getItem() instanceof ArmeItem a && a.spec.prise() != ArmeItem.Prise.LANCER;
            boolean deuxMains = tenu.getItem() instanceof ArmeItem a2 && a2.deuxMains();
            switch (pnj.pose()) {
                case CartelPnj.CROISE -> {
                    rightArm.xRot = -1.05F;
                    rightArm.yRot = -0.75F;
                    rightArm.zRot = 0;
                    leftArm.xRot = -1.05F;
                    leftArm.yRot = 0.75F;
                    leftArm.zRot = 0;
                }
                case CartelPnj.VISE -> {
                    if (deuxMains) {
                        AnimationUtils.animateCrossbowHold(rightArm, leftArm, head, true);
                    } else if (arme) {
                        rightArm.xRot = -Mth.HALF_PI + head.xRot;
                        rightArm.yRot = head.yRot - 0.1F;
                    }
                }
                case CartelPnj.PORTE -> {
                    if (deuxMains) {
                        // l'arme tenue un peu basse, canon vers le sol devant lui, la main gauche dessous
                        rightArm.xRot = -1.15F;
                        rightArm.yRot = -0.12F;
                        rightArm.zRot = 0;
                        leftArm.xRot = -1.3F;
                        leftArm.yRot = 0.6F;
                        leftArm.zRot = 0;
                    } else if (arme) {
                        rightArm.xRot = -0.35F;
                    }
                }
                default -> {
                }
            }
            rightSleeve.copyFrom(rightArm);
            leftSleeve.copyFrom(leftArm);
        }
    }

    public static final class RenduPnj extends HumanoidMobRenderer<CartelPnj, ModelePnj> {
        public RenduPnj(EntityRendererProvider.Context ctx) {
            super(ctx, new ModelePnj(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        }

        @Override
        public ResourceLocation getTextureLocation(CartelPnj pnj) {
            return Cabane.id("textures/entity/cartel/" + pnj.skin() + ".png");
        }
    }

    /** La roquette et l'obus pointent dans le sens du vol ; la grenade et le cocktail tournoient. */
    public static final class RenduProjectile extends EntityRenderer<ProjectileArme> {
        private final ItemRenderer objets;

        public RenduProjectile(EntityRendererProvider.Context ctx) {
            super(ctx);
            this.objets = ctx.getItemRenderer();
        }

        @Override
        public void render(ProjectileArme p, float yaw, float pt, PoseStack pose, MultiBufferSource tampons, int lumiere) {
            pose.pushPose();
            ArmeItem.Tir mode = p.mode();
            if (mode == ArmeItem.Tir.ROQUETTE || mode == ArmeItem.Tir.OBUS) {
                pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(pt, p.yRotO, p.getYRot()) - 90F));
                pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(pt, p.xRotO, p.getXRot())));
                pose.scale(0.9F, 0.9F, 0.9F);
            } else {
                pose.mulPose(Axis.YP.rotationDegrees(-Mth.lerp(pt, p.yRotO, p.getYRot())));
                pose.mulPose(Axis.XP.rotationDegrees((p.tickCount + pt) * 18F));
                pose.scale(0.55F, 0.55F, 0.55F);
            }
            objets.renderStatic(p.getItem(), ItemDisplayContext.NONE, lumiere, OverlayTexture.NO_OVERLAY, pose, tampons, p.level(), p.getId());
            pose.popPose();
            super.render(p, yaw, pt, pose, tampons, lumiere);
        }

        @Override
        public ResourceLocation getTextureLocation(ProjectileArme p) {
            return TextureAtlas.LOCATION_BLOCKS;
        }
    }

    // ------------------------------------------------------------------ les traçantes

    private static final DustParticleOptions TRACANTE = new DustParticleOptions(new Vector3f(1F, 0.85F, 0.4F), 0.7F);
    private static final DustParticleOptions GROSSE = new DustParticleOptions(new Vector3f(1F, 0.72F, 0.28F), 1.1F);
    private static final DustParticleOptions SANG = new DustParticleOptions(new Vector3f(0.55F, 0.02F, 0.02F), 1F);

    /** Une balle : la flamme et la fumée au bout du canon, la traînée dorée, puis l'impact. */
    public static void tracante(Cartel.Tracante p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Vec3 de = p.de(), a = p.a(), d = a.subtract(de);
        double L = d.length();
        Vec3 u = L < 1.0E-4 ? Vec3.ZERO : d.scale(1 / L);
        // une petite flamme et un filet de fumée au bout du canon (pas plus : vues de près, elles cachent tout) ; le
        // tireur lui-même, à la première personne, voit son arme en bas de l'écran : la flamme flotterait au milieu
        Minecraft mc = Minecraft.getInstance();
        boolean moi = mc.player != null && mc.options.getCameraType().isFirstPerson() && mc.player.getEyePosition().distanceToSqr(de) < 4;
        if (!moi) {
            level.addParticle(ParticleTypes.SMALL_FLAME, de.x, de.y, de.z, u.x * 0.04, u.y * 0.04, u.z * 0.04);
            if (Math.random() < 0.4) {
                level.addParticle(ParticleTypes.SMOKE, de.x, de.y, de.z, 0, 0.02, 0);
            }
        }
        double pas = p.calibre() > 0 ? 0.45 : 0.7;
        for (double s = moi ? 2.5 : 0.6; s < Math.min(L, 90); s += pas) {
            level.addParticle(p.calibre() > 0 ? GROSSE : TRACANTE, de.x + u.x * s, de.y + u.y * s, de.z + u.z * s, 0, 0, 0);
        }
        switch (p.impact()) {
            case 0 -> {
                for (int i = 0; i < 4; i++) {
                    level.addParticle(ParticleTypes.CRIT, a.x, a.y, a.z, -u.x * 0.2 + (Math.random() - 0.5) * 0.2, 0.1 + Math.random() * 0.1, -u.z * 0.2 + (Math.random() - 0.5) * 0.2);
                }
                level.addParticle(ParticleTypes.SMOKE, a.x, a.y, a.z, 0, 0.03, 0);
            }
            case 2 -> {
                for (int i = 0; i < 7; i++) {
                    level.addParticle(SANG, a.x + (Math.random() - 0.5) * 0.3, a.y + (Math.random() - 0.5) * 0.3, a.z + (Math.random() - 0.5) * 0.3, 0, 0, 0);
                }
            }
            case 1 -> level.addParticle(ParticleTypes.CRIT, a.x, a.y, a.z, 0, 0.1, 0);
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ jeu : touche R, compteur, lunette

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Jeu {
        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) {
            Player joueur = Minecraft.getInstance().player;
            while (RECHARGER.consumeClick()) {
                if (joueur != null && joueur.getMainHandItem().getItem() instanceof ArmeItem) {
                    PacketDistributor.sendToServer(new Cartel.Recharger());
                }
            }
        }

        private static boolean vise(Player joueur) {
            return joueur != null && joueur.isCrouching() && joueur.getMainHandItem().getItem() instanceof ArmeItem a && a.spec.lunette()
                    && Minecraft.getInstance().options.getCameraType().isFirstPerson();
        }

        /** Le Barrett, accroupi : on regarde dans la lunette. */
        @SubscribeEvent
        public static void onFov(ComputeFovModifierEvent event) {
            if (vise(event.getPlayer())) {
                event.setNewFovModifier(0.2F);
            }
        }

        /** Dans la lunette, on ne voit plus son arme. */
        @SubscribeEvent
        public static void onMain(net.neoforged.neoforge.client.event.RenderHandEvent event) {
            if (vise(Minecraft.getInstance().player)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void onGui(RenderGuiEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            Player joueur = mc.player;
            if (joueur == null || mc.options.hideGui || !(joueur.getMainHandItem().getItem() instanceof ArmeItem arme)) {
                return;
            }
            GuiGraphics g = event.getGuiGraphics();
            int w = g.guiWidth(), h = g.guiHeight();
            if (vise(joueur)) {
                // le noir autour de la lunette, et sa croix fine
                int r = (int) (Math.min(w, h) * 0.42), cx = w / 2, cy = h / 2;
                g.fill(0, 0, w, cy - r, 0xFF000000);
                g.fill(0, cy + r, w, h, 0xFF000000);
                g.fill(0, cy - r, cx - r, cy + r, 0xFF000000);
                g.fill(cx + r, cy - r, w, cy + r, 0xFF000000);
                g.fill(cx - r, cy, cx + r, cy + 1, 0xCC000000);
                g.fill(cx, cy - r, cx + 1, cy + r, 0xCC000000);
                g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFD02020);
            }
            if (arme.spec.chargeur() <= 0) {
                return;
            }
            ItemStack tenu = joueur.getMainHandItem();
            int balles = arme.munitions(tenu), x = w / 2 + 98, y = h - 20;
            if (joueur.getCooldowns().isOnCooldown(arme) && balles == arme.spec.chargeur() && arme.spec.chargeur() > 1) {
                g.drawString(mc.font, "Rechargement…", x, y, 0xBBBBBB, true);
                return;
            }
            int couleur = balles == 0 ? 0xFF5050 : balles <= arme.spec.chargeur() / 4 ? 0xFFB040 : 0xFFE070;
            g.drawString(mc.font, balles + " / " + arme.spec.chargeur(), x, y, couleur, true);
            if (balles <= arme.spec.chargeur() / 4) {
                g.drawString(mc.font, "R : recharger", x, y - 10, 0xAAAAAA, true);
            }
        }

        private Jeu() {
        }
    }

    private ClientCartel() {
    }
}
