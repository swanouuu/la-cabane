package fr.cabane.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.boutique.Forge;
import fr.cabane.client.ui.Ios;
import fr.cabane.client.ui.Ui;
import fr.cabane.economie.Economie;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * La forge de Brokk côté client : l'écran (les pièces, ce qu'il faut rapporter, le prix, le temps de forge) et le
 * spectacle sur l'enclume monumentale : le marteau qui se lève et retombe, la pièce rougie, les étincelles, la trempe
 * dans la vapeur, puis la pièce finie qui s'élève. Celui qui a commandé voit en haut de l'écran où en est sa pièce.
 */
public final class ClientForge {
    private static final RandomSource HASARD = RandomSource.create();
    private static final String[] PAROLES = {
            "Brokk chauffe le métal au rouge…",
            "« Le métal parle. Brokk écoute. »",
            "« Toi regarde. Toi touche pas l'enclume. »",
            "« Celui-là, il est têtu. Brokk plus têtu. »",
            "« Grosh ! Plus de charbon ! … Grosh. Pose ce charbon. »",
            "« Encore un coup. Toujours encore un coup. »",
    };

    /** Ce qui est sur l'enclume (null : rien), et depuis quand (temps du monde côté client) ; pour l'envol, depuis sa fin. */
    private static Forge.Chantier chantier;
    private static long debut;
    private static long dernierTick = -1;

    public static void ouvrir(Forge.Ouvrir p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof Ecran e) {
            if (p.fermer()) {
                e.onClose();
                return;
            }
            e.recettes = p.recettes();
            e.statut = p.statut();
            if (!p.onglet().isEmpty()) {
                e.onglet = p.onglet();
            }
            e.rafraichir();
        } else if (!p.fermer()) {
            Ecran e = new Ecran(p.recettes(), p.statut());
            e.onglet = p.onglet().isEmpty() ? null : p.onglet();
            mc.setScreen(e);
        }
    }

    public static void chantier(Forge.Chantier p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long maintenant = mc.level.getGameTime();
        if (p.etat() == 2) {
            chantier = p;
            debut = maintenant;
            Vec3 e = new Vec3(p.enclume());
            son(e, SoundEvents.ANVIL_LAND, 0.9F, 1.3F);
            son(e, SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F, 0.8F);
            if (p.toi()) {
                son(e, SoundEvents.PLAYER_LEVELUP, 0.7F, 0.9F);
            }
            for (int i = 0; i < 30; i++) {
                mc.level.addParticle(ParticleTypes.END_ROD, e.x, e.y + 0.3, e.z, HASARD.nextGaussian() * 0.12, 0.08 + HASARD.nextDouble() * 0.12, HASARD.nextGaussian() * 0.12);
            }
            mc.level.addParticle(ParticleTypes.FLASH, e.x, e.y + 0.5, e.z, 0, 0, 0);
            return;
        }
        boolean nouveau = chantier == null || chantier.numero() != p.numero() || chantier.etat() != p.etat();
        if (nouveau || Math.abs(maintenant - debut - p.ecoule()) > 4) {
            debut = maintenant - p.ecoule();
        }
        chantier = p;
        if (nouveau && p.toi() && mc.screen instanceof Ecran ecran) {
            ecran.onClose(); // on regarde Brokk travailler
        }
    }

    private static void son(Vec3 p, SoundEvent son, float volume, float hauteur) {
        Minecraft.getInstance().level.playLocalSound(p.x, p.y, p.z, son, SoundSource.BLOCKS, volume, hauteur, false);
    }

    // ================================================================ le spectacle sur l'enclume

    /** Le temps (en ticks) depuis que la pièce est sur l'enclume, ou depuis qu'elle est finie (l'envol). */
    private static float ecoule(float partiel) {
        return Minecraft.getInstance().level.getGameTime() - debut + partiel;
    }

    /** Où on en est dans le cycle du marteau : 0 au moment du coup. */
    private static float cycle(float t) {
        return ((t - Forge.FRAPPE) % Forge.CYCLE + Forge.CYCLE) % Forge.CYCLE;
    }

    /**
     * L'angle du marteau (0 : dressé à la verticale ; frappe : posé sur la pièce) : il rebondit après le coup, se relève
     * lentement par-dessus l'épaule, et retombe d'un coup. Pendant la trempe, il attend au-dessus de l'enclume.
     */
    private static float angle(float t, float frappe, float leve, boolean trempe) {
        if (trempe) {
            return frappe - 0.7F;
        }
        float c = cycle(t);
        if (c < 3) {
            return frappe - 0.18F * Mth.sin(c / 3F * Mth.PI);
        }
        if (c < 14) {
            float k = (c - 3) / 11F;
            return Mth.lerp(k * k * (3 - 2 * k), frappe, leve);
        }
        float k = (c - 14) / 6F;
        return Mth.lerp(k * k * k, leve, frappe);
    }

    private static int melange(int a, int b, float k) {
        k = Mth.clamp(k, 0, 1);
        int r = (int) Mth.lerp(k, (a >> 16) & 0xFF, (b >> 16) & 0xFF);
        int v = (int) Mth.lerp(k, (a >> 8) & 0xFF, (b >> 8) & 0xFF);
        int bl = (int) Mth.lerp(k, a & 0xFF, b & 0xFF);
        return 0xFF000000 | r << 16 | v << 8 | bl;
    }

    private static void dessiner(PoseStack ps, MultiBufferSource buffers, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Forge.Chantier c = chantier;
        Vec3 enclume = new Vec3(c.enclume());
        float t = ecoule(partiel);
        if (c.etat() == 2) {
            // la pièce finie s'élève au-dessus de l'enclume en tournant, et luit
            float k = Mth.clamp(t / 25F, 0, 1);
            ps.pushPose();
            ps.translate(enclume.x - cam.x, enclume.y + 0.35 + 1.1 * (1 - (1 - k) * (1 - k)) - cam.y, enclume.z - cam.z);
            ps.mulPose(Axis.YP.rotationDegrees(t * 9));
            ps.scale(1.1F, 1.1F, 1.1F);
            mc.getItemRenderer().renderStatic(c.objet(), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ps, buffers, mc.level, 0);
            ps.popPose();
            return;
        }
        Vec3 epaule = new Vec3(c.epaule());
        Vec3 d = enclume.subtract(epaule);
        float horizontal = (float) Math.sqrt(d.x * d.x + d.z * d.z);
        float tete = 0.36F;
        float chute = (float) -d.y - 0.1F; // de l'épaule au-dessus de la pièce
        float rayon = Mth.sqrt(horizontal * horizontal + chute * chute);
        float manche = Mth.sqrt(Math.max(0.6F, rayon * rayon - tete * tete));
        // l'angle où la face avant du marteau touche la pièce, et celui où il est levé, par-dessus l'épaule
        float frappe = (float) (Math.atan2(manche, tete) - Math.atan2(-chute, horizontal));
        float leve = frappe - 2.45F;
        boolean trempe = t >= c.duree() * Forge.MARTELAGE;
        int lumiere = LevelRenderer.getLightColor(mc.level, BlockPos.containing(epaule));

        ps.pushPose();
        ps.translate(epaule.x - cam.x, epaule.y - cam.y, epaule.z - cam.z);
        ps.mulPose(Axis.YP.rotation((float) -Math.atan2(d.z, d.x))); // x : vers l'enclume
        ClientBorne.Pinceau p = new ClientBorne.Pinceau(buffers, ps, lumiere);

        // la pièce sur l'enclume : blanche au moment du coup, orange entre deux, puis grise une fois trempée
        float x = horizontal;
        float y = (float) d.y;
        int couleur;
        boolean chaude;
        if (!trempe) {
            couleur = melange(0xFFFFF0B0, 0xFFFF7A1A, cycle(t) / 7F);
            chaude = true;
        } else {
            float k = (t - c.duree() * Forge.MARTELAGE) / (c.duree() * (1 - Forge.MARTELAGE));
            couleur = melange(0xFFFF7A1A, 0xFF50545C, k * 1.3F);
            chaude = k < 0.55F;
        }
        p.pave(couleur, x - 0.12F, y, -0.32F, x + 0.12F, y + 0.07F, 0.32F, chaude);
        p.pave(melange(couleur, 0xFF000000, 0.25F), x - 0.08F, y + 0.07F, -0.26F, x + 0.08F, y + 0.1F, 0.26F, chaude);

        // le marteau de Brokk : manche de chêne sombre, poignée de cuir, tête de fer aux faces polies, cerclage doré
        ps.mulPose(Axis.ZP.rotation(-angle(t, frappe, leve, trempe)));
        p.pave(0xFF6B4423, -0.055F, -0.35F, -0.055F, 0.055F, manche, 0.055F);
        p.pave(0xFF3B2516, -0.07F, -0.35F, -0.07F, 0.07F, 0.25F, 0.07F);
        p.pave(0xFF2B1A10, -0.075F, -0.42F, -0.075F, 0.075F, -0.35F, 0.075F);
        p.pave(0xFF4E5259, -tete + 0.05F, manche - 0.19F, -0.2F, tete - 0.05F, manche + 0.19F, 0.2F);
        p.pave(0xFF9AA0AA, tete - 0.06F, manche - 0.21F, -0.22F, tete, manche + 0.21F, 0.22F);
        p.pave(0xFF9AA0AA, -tete, manche - 0.21F, -0.22F, -tete + 0.06F, manche + 0.21F, 0.22F);
        p.pave(0xFFB8862B, -0.05F, manche - 0.2F, -0.215F, 0.05F, manche + 0.2F, 0.215F);
        ps.popPose();
    }

    /** Les coups (étincelles, son de l'enclume), la vapeur de la trempe, les éclats de l'envol : une fois par tick. */
    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            chantier = null;
            return;
        }
        long maintenant = mc.level.getGameTime();
        if (chantier == null || maintenant == dernierTick) {
            return;
        }
        dernierTick = maintenant;
        Forge.Chantier c = chantier;
        Vec3 e = new Vec3(c.enclume());
        long t = maintenant - debut;
        if (mc.player.position().distanceToSqr(e) > 96 * 96 || (c.etat() == 1 && t > c.duree() + 60)) {
            chantier = null; // trop loin, ou le serveur n'a plus rien dit
            return;
        }
        if (c.etat() == 2) {
            if (t >= Forge.FINAL) {
                chantier = null;
            } else if (t % 2 == 0) {
                double a = t * 0.5;
                mc.level.addParticle(ParticleTypes.END_ROD, e.x + Math.cos(a) * 0.5, e.y + 0.6 + t * 0.02, e.z + Math.sin(a) * 0.5, 0, 0.02, 0);
            }
            return;
        }
        long trempe = (long) Math.ceil(c.duree() * Forge.MARTELAGE);
        if (t < trempe) {
            if (t % Forge.CYCLE == Forge.FRAPPE) {
                // CLANG : des étincelles qui jaillissent, quelques gouttes de métal en fusion
                son(e, SoundEvents.ANVIL_USE, 0.9F, 0.8F + HASARD.nextFloat() * 0.35F);
                if (HASARD.nextInt(3) == 0) {
                    son(e, SoundEvents.ANVIL_LAND, 0.25F, 1.6F);
                }
                for (int i = 0; i < 14; i++) {
                    mc.level.addParticle(ParticleTypes.CRIT, e.x, e.y + 0.1, e.z,
                            HASARD.nextGaussian() * 0.35, 0.2 + HASARD.nextDouble() * 0.35, HASARD.nextGaussian() * 0.35);
                }
                for (int i = 0; i < 5; i++) {
                    mc.level.addParticle(ParticleTypes.SMALL_FLAME, e.x + HASARD.nextGaussian() * 0.15, e.y + 0.1, e.z + HASARD.nextGaussian() * 0.15,
                            HASARD.nextGaussian() * 0.04, 0.05 + HASARD.nextDouble() * 0.05, HASARD.nextGaussian() * 0.04);
                }
                if (HASARD.nextBoolean()) {
                    mc.level.addParticle(ParticleTypes.LAVA, e.x, e.y + 0.1, e.z, 0, 0, 0);
                }
                mc.level.addParticle(ParticleTypes.SMOKE, e.x, e.y + 0.15, e.z, 0, 0.04, 0);
            } else if (HASARD.nextInt(6) == 0) {
                mc.level.addParticle(ParticleTypes.SMALL_FLAME, e.x + HASARD.nextGaussian() * 0.12, e.y + 0.1, e.z + HASARD.nextGaussian() * 0.12, 0, 0.01, 0);
            }
            return;
        }
        // la trempe : un grand PSSHHH, puis la pièce fume en refroidissant
        if (t == trempe) {
            son(e, SoundEvents.LAVA_EXTINGUISH, 1.2F, 0.7F);
            son(e, SoundEvents.FIRE_EXTINGUISH, 0.8F, 0.6F);
        } else if ((t - trempe) % 25 == 0) {
            son(e, SoundEvents.FIRE_EXTINGUISH, 0.35F, 0.8F + HASARD.nextFloat() * 0.3F);
        }
        float k = (float) (t - trempe) / Math.max(1, c.duree() - trempe);
        if (HASARD.nextFloat() < 1.2F - k) {
            mc.level.addParticle(ParticleTypes.CLOUD, e.x + HASARD.nextGaussian() * 0.15, e.y + 0.15, e.z + HASARD.nextGaussian() * 0.2,
                    HASARD.nextGaussian() * 0.01, 0.04 + HASARD.nextDouble() * 0.04, HASARD.nextGaussian() * 0.01);
        }
    }

    /** En haut de l'écran, pour celui qui a commandé : la pièce, une barre, le temps qu'il reste et ce que dit Brokk. */
    private static void bandeau(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Forge.Chantier c = chantier;
        if (c == null || !c.toi() || mc.level == null || mc.options.hideGui) {
            return;
        }
        float t = ecoule(mc.getTimer().getGameTimeDeltaPartialTick(false));
        int w = 236;
        int x = (g.guiWidth() - w) / 2;
        int y = 6;
        Ios.rond(g, x - 2, y - 2, w + 4, 44, 8, 0xFFE86A1E);
        Ios.rond(g, x, y, w, 40, 7, 0xF0181A1F);
        g.renderItem(c.objet(), x + 8, y + 8);
        String titre = c.etat() == 2 ? "Fini : " : "Brokk forge : ";
        g.drawString(mc.font, Component.literal(titre).withStyle(ChatFormatting.GOLD).append(c.objet().getHoverName().copy().withStyle(ChatFormatting.WHITE)),
                x + 30, y + 6, 0xFFFFFFFF, false);
        float k = c.etat() == 2 ? 1 : Mth.clamp(t / c.duree(), 0, 1);
        int bx = x + 30;
        int bw = w - 78;
        g.fill(bx, y + 18, bx + bw, y + 23, 0xFF2E2620);
        for (int i = 0; i < (int) (bw * k); i++) {
            g.fill(bx + i, y + 18, bx + i + 1, y + 23, melange(0xFFB8321A, 0xFFFFC34D, i / (float) bw));
        }
        String reste = c.etat() == 2 ? "✔" : Math.max(0, (int) Math.ceil((c.duree() - t) / 20F)) + " s";
        g.drawString(mc.font, reste, x + w - 8 - mc.font.width(reste), y + 16, c.etat() == 2 ? Ui.VERT : Ui.OR, false);
        String parole;
        if (c.etat() == 2) {
            parole = "« Voilà. Bon travail. Brokk content. »";
        } else if (t >= c.duree() * Forge.MARTELAGE) {
            parole = "Brokk trempe la pièce… PSSSHHH.";
        } else {
            parole = PAROLES[(int) (t / 120) % PAROLES.length];
        }
        g.drawString(mc.font, parole, x + 30, y + 28, Ui.GRIS, false);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ClientForge.tick();
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (chantier == null || mc.level == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            ClientForge.dessiner(event.getPoseStack(), buffers, event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
            buffers.endBatch();
        }

        private Evenements() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("forge"), (g, delta) -> bandeau(g));
        }

        private Couches() {
        }
    }

    // ================================================================ l'écran de la forge

    static final class Ecran extends Screen {
        private static final int W = 340;
        private static final int H = 256;
        private static final int PAR_PAGE = 4;
        List<Forge.Vue> recettes;
        String statut;
        String onglet;
        private int page;
        private int left;
        private int top;

        Ecran(List<Forge.Vue> recettes, String statut) {
            super(Component.literal("La forge de Brokk"));
            this.recettes = recettes;
            this.statut = statut;
        }

        void rafraichir() {
            rebuildWidgets();
        }

        private List<String> onglets() {
            List<String> l = new ArrayList<>();
            for (Forge.Vue v : recettes) {
                if (!l.contains(v.categorie())) {
                    l.add(v.categorie());
                }
            }
            return l;
        }

        private static String nomOnglet(String categorie) {
            return switch (categorie) {
                case "arme" -> "Armes";
                case "armure" -> "Armures";
                case "relique" -> "Reliques des boss";
                default -> categorie.substring(0, 1).toUpperCase() + categorie.substring(1);
            };
        }

        private List<Forge.Vue> visibles() {
            return recettes.stream().filter(v -> v.categorie().equals(onglet)).toList();
        }

        @Override
        protected void init() {
            left = (width - W) / 2;
            top = (height - H) / 2;
            List<String> onglets = onglets();
            if (onglet == null || !onglets.contains(onglet)) {
                onglet = onglets.isEmpty() ? "" : onglets.get(0);
            }
            int ox = left + 10;
            for (String o : onglets) {
                int w = font.width(nomOnglet(o)) + 16;
                addRenderableWidget(Ios.Bouton.plein(ox, top + 26, w, 14, nomOnglet(o), o.equals(onglet) ? 0xFFB8862B : 0xFF3A2A1E, () -> {
                    onglet = o;
                    page = 0;
                    rebuildWidgets();
                }));
                ox += w + 4;
            }
            List<Forge.Vue> liste = visibles();
            int pages = Math.max(1, (liste.size() + PAR_PAGE - 1) / PAR_PAGE);
            page = Math.min(page, pages - 1);
            for (int k = 0; k < PAR_PAGE; k++) {
                int i = page * PAR_PAGE + k;
                if (i >= liste.size()) {
                    break;
                }
                Forge.Vue r = liste.get(i);
                addRenderableWidget(Ios.Bouton.plein(left + W - 78, top + 46 + k * 46 + 12, 66, 18, "Forger", 0xFFB8862B,
                        () -> PacketDistributor.sendToServer(new Forge.Forger(r.id()))));
            }
            if (pages > 1) {
                addRenderableWidget(Ios.Bouton.plein(left + 12, top + H - 22, 24, 16, "‹", 0xFF3A2A1E, () -> {
                    page = Math.floorMod(page - 1, pages);
                    rebuildWidgets();
                }));
                addRenderableWidget(Ios.Bouton.plein(left + 40, top + H - 22, 24, 16, "›", 0xFF3A2A1E, () -> {
                    page = Math.floorMod(page + 1, pages);
                    rebuildWidgets();
                }));
            }
        }

        private int possede(ItemStack ing) {
            int n = 0;
            var joueur = Minecraft.getInstance().player;
            if (joueur != null) {
                for (ItemStack s : joueur.getInventory().items) {
                    if (s.is(ing.getItem())) {
                        n += s.getCount();
                    }
                }
            }
            return n;
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderBackground(g, mouseX, mouseY, partialTick);
            // fer sombre, liseré de braise
            Ios.rond(g, left - 4, top - 4, W + 8, H + 8, 10, 0xFFE86A1E);
            for (int dy = 0; dy < H; dy++) {
                int c = fr.cabane.client.casino.CasinoUi.melange(0xFF2B2E33, 0xFF15161A, dy / (float) H);
                g.fill(left, top + dy, left + W, top + dy + 1, c);
            }
            Ios.texteCentre(g, font, "⚒ LA FORGE DE BROKK ⚒", left + W / 2F, top + 10, 1.2F, 0xFFFFB347, true);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            Ui.solde(g, font, left + W - 10, top + 10);
            List<Forge.Vue> liste = visibles();
            if (liste.isEmpty()) {
                g.drawCenteredString(font, "Brokk n'a rien à forger pour l'instant.", left + W / 2, top + 110, Ui.GRIS);
            }
            List<Component> bulle = null;
            ItemStack survol = ItemStack.EMPTY;
            for (int k = 0; k < PAR_PAGE; k++) {
                int i = page * PAR_PAGE + k;
                if (i >= liste.size()) {
                    break;
                }
                Forge.Vue r = liste.get(i);
                int y = top + 46 + k * 46;
                Ios.rond(g, left + 8, y, W - 16, 42, 6, 0x60000000);
                g.renderItem(r.resultat(), left + 14, y + 4);
                g.drawString(font, r.resultat().getHoverName(), left + 34, y + 4, 0xFFFFE0B0, false);
                String prix = Economie.format(r.prix());
                g.drawString(font, prix, left + 34, y + 15, Ui.OR, false);
                g.drawString(font, "⏱ " + r.temps() + " s", left + 42 + font.width(prix), y + 15, Ui.GRIS, false);
                int x = left + 14;
                for (ItemStack ing : r.ingredients()) {
                    g.renderItem(ing, x, y + 24);
                    boolean ok = possede(ing) >= ing.getCount();
                    g.drawString(font, "×" + ing.getCount(), x + 16, y + 30, ok ? Ui.VERT : Ui.ROUGE, true);
                    if (mouseX >= x && mouseX < x + 16 && mouseY >= y + 24 && mouseY < y + 40) {
                        survol = ing;
                    }
                    x += 40;
                }
                if (mouseX >= left + 14 && mouseX < left + 30 && mouseY >= y + 4 && mouseY < y + 20) {
                    bulle = new ArrayList<>(getTooltipFromItem(minecraft, r.resultat()));
                    if (!r.indice().isEmpty()) {
                        bulle.add(Component.literal(r.indice()).withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
                    }
                }
            }
            int pages = Math.max(1, (liste.size() + PAR_PAGE - 1) / PAR_PAGE);
            if (pages > 1) {
                g.drawString(font, (page + 1) + "/" + pages, left + 70, top + H - 18, Ui.GRIS, false);
            }
            if (!statut.isEmpty()) {
                Ios.texteCentre(g, font, "⚒ " + statut, left + W / 2F + 34, top + H - 18, 1F, 0xFFFFB347, false);
            } else {
                Ios.texteCentre(g, font, "Survole une pièce pour en savoir plus.", left + W / 2F + 34, top + H - 18, 1F, Ui.GRIS, false);
            }
            if (bulle != null) {
                g.renderComponentTooltip(font, bulle, mouseX, mouseY);
            } else if (!survol.isEmpty()) {
                g.renderTooltip(font, survol, mouseX, mouseY);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private ClientForge() {
    }
}
