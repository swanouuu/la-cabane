package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.cinema.Effets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import org.joml.Vector3f;

/**
 * Le mode Kurama de Minato 2 chez chaque joueur (voir docs/minato2) : sa peau (il garde ses cheveux, passés dans le
 * jaune du skin du mode Kurama, prend le visage de ce skin, ses yeux rouges, ses moustaches et une bouche de deux
 * pixels, et toute sa tenue, pixel pour pixel), la transformation (un anneau de lumière monte des pieds à la tête et
 * pose la tenue ; à la fin du mode, il redescend et l'enlève), et l'aura dorée du manteau, comme celle de Hachibi.
 */
public final class ClientKurama {
    /** L'anneau monte en 2,4 s, puis le flash ; à la fin du mode, il redescend en 1,5 s. */
    static final double MONTEE = 2.4, DESCENTE = 1.5;
    /** La tenue se pose par paliers de 3 pixels de hauteur (0 à 33) : douze peaux par joueur, faites une fois. */
    private static final int ETAPES = 12;
    private static final RandomSource HASARD = RandomSource.create();
    private static final DustParticleOptions OR = new DustParticleOptions(new Vector3f(1F, 0.8F, 0.25F), 1.1F);

    /** Ceux qui sont en mode Kurama, depuis quand l'anneau monte, depuis quand il redescend (System.nanoTime). */
    private static final Set<UUID> MODES = new HashSet<>();
    private static final Map<UUID, Long> MONTEES = new HashMap<>();
    private static final Map<UUID, Long> DESCENTES = new HashMap<>();
    /** Quand mon propre flash a eu lieu (l'écran blanchit un instant), -1 sinon. */
    private static long monFlash = -1;

    private static final ResourceLocation TENUE = Cabane.id("textures/minato/kurama_tenue.png");
    static final ResourceLocation AURA = Cabane.id("textures/minato/kurama_aura.png");
    private static int[] tenue;
    /** « texture de la vraie peau|palier » → la peau en mode Kurama jusqu'à ce palier (une texture à nous). */
    private static final Map<String, ResourceLocation> PEAUX = new HashMap<>();
    private static final Map<String, Long> RATEES = new HashMap<>();
    private static final Map<ResourceLocation, PlayerSkin> REMPLACEES = new HashMap<>();

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    // ================================================================ qui est en mode Kurama

    static void transformer(UUID joueur) {
        MODES.add(joueur);
        DESCENTES.remove(joueur);
        MONTEES.put(joueur, System.nanoTime());
    }

    /** Sans transformation (il était déjà en mode Kurama quand on est arrivé). */
    static void dejaLa(UUID joueur) {
        MODES.add(joueur);
        DESCENTES.remove(joueur);
    }

    static void detransformer(UUID joueur) {
        if (MODES.remove(joueur)) {
            MONTEES.remove(joueur);
            DESCENTES.put(joueur, System.nanoTime());
        }
    }

    static void oublier(UUID joueur) {
        MODES.remove(joueur);
        MONTEES.remove(joueur);
        DESCENTES.remove(joueur);
    }

    static boolean mode(UUID joueur) {
        return MODES.contains(joueur);
    }

    /** Jusqu'où la tenue est posée sur lui (en pixels de peau : 0 aux pieds, 33 au-dessus de la tête), -1 s'il n'y en a pas. */
    static double hauteur(UUID joueur) {
        Long d = DESCENTES.get(joueur);
        if (d != null) {
            double t = secondes(d);
            if (t < DESCENTE) {
                return 33 * (1 - t / DESCENTE);
            }
            DESCENTES.remove(joueur);
            return -1;
        }
        if (!MODES.contains(joueur)) {
            return -1;
        }
        Long m = MONTEES.get(joueur);
        return m == null ? 33 : Math.min(33, 33 * secondes(m) / MONTEE);
    }

    // ================================================================ la peau

    /** Appelé à chaque {@code getSkin()} (AbstractClientPlayerMixin) : la peau du mode Kurama, jusqu'où elle est posée. */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        if (MODES.isEmpty() && DESCENTES.isEmpty()) {
            return skin;
        }
        double h = hauteur(joueur.getUUID());
        int palier = h < 0 ? 0 : Mth.clamp((int) Math.round(h / 3), 0, ETAPES - 1);
        if (palier == 0) {
            return skin;
        }
        ResourceLocation id = texture(skin, palier);
        if (id == null) {
            return skin;
        }
        PlayerSkin s = REMPLACEES.get(id);
        if (s == null || s.model() != skin.model()) {
            // pas de cape sur le manteau de chakra
            s = new PlayerSkin(id, skin.textureUrl(), null, skin.elytraTexture(), skin.model(), skin.secure());
            REMPLACEES.put(id, s);
        }
        return s;
    }

    private static ResourceLocation texture(PlayerSkin vraie, int palier) {
        String cle = vraie.texture() + "|" + palier;
        ResourceLocation id = PEAUX.get(cle);
        if (id != null || System.nanoTime() < RATEES.getOrDefault(cle, 0L)) {
            return id;
        }
        int[] base = ClientMaudit.lire(vraie.texture());
        int[] kcm = tenue();
        if (base == null || kcm == null) {
            RATEES.put(cle, System.nanoTime() + 2_000_000_000L);
            return null;
        }
        int[] px = composer(base, kcm, palier * 3);
        NativeImage image = new NativeImage(64, 64, true);
        for (int i = 0; i < px.length; i++) {
            image.setPixelRGBA(i % 64, i / 64, px[i]);
        }
        id = Cabane.id("minato/kurama_" + PEAUX.size());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        PEAUX.put(cle, id);
        RATEES.remove(cle);
        return id;
    }

    /** Le skin du mode Kurama envoyé par l'utilisateur (64 × 64, en ABGR), lu une fois. */
    static int[] tenue() {
        if (tenue == null) {
            try (var flux = Minecraft.getInstance().getResourceManager().open(TENUE); NativeImage image = NativeImage.read(flux)) {
                int[] px = new int[64 * 64];
                for (int i = 0; i < px.length; i++) {
                    px[i] = image.getPixelRGBA(i % 64, i / 64);
                }
                tenue = px;
            } catch (java.io.IOException | RuntimeException e) {
                Cabane.LOGGER.warn("Mode Kurama : skin de la tenue illisible", e);
            }
        }
        return tenue;
    }

    // ---- où est chaque pixel d'une peau (les parties de PlayerModel, au repos)

    static final byte TETE = 0, CORPS = 1, BRAS = 2, JAMBE = 3;
    /** La hauteur du milieu de chaque pixel (en pixels de peau, 0 aux pieds), NaN s'il ne sert à rien. */
    static final float[] HAUTEUR = new float[64 * 64];
    static final byte[] PARTIE = new byte[64 * 64];
    private static final boolean[] COUCHE_1 = new boolean[64 * 64];

    static {
        Arrays.fill(HAUTEUR, Float.NaN);
        // partie, bas, haut, largeur, hauteur, profondeur, puis les coins de la première et de la seconde couche
        int[][] parties = {{TETE, 24, 32, 8, 8, 8, 0, 0, 32, 0}, {CORPS, 12, 24, 8, 12, 4, 16, 16, 16, 32},
                {BRAS, 12, 24, 4, 12, 4, 40, 16, 40, 32}, {BRAS, 12, 24, 4, 12, 4, 32, 48, 48, 48},
                {JAMBE, 0, 12, 4, 12, 4, 0, 16, 0, 32}, {JAMBE, 0, 12, 4, 12, 4, 16, 48, 0, 48}};
        for (int[] p : parties) {
            int w = p[3], h = p[4], d = p[5];
            for (int couche = 0; couche < 2; couche++) {
                int u = p[6 + 2 * couche], v = p[7 + 2 * couche];
                for (int j = 0; j < d; j++) {
                    for (int i = 0; i < w; i++) {
                        marquer(u + d + i, v + j, p[2], p[0], couche == 0);
                        marquer(u + d + w + i, v + j, p[1], p[0], couche == 0);
                    }
                }
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < 2 * d + 2 * w; i++) {
                        marquer(u + i, v + d + j, p[2] - j - 0.5F, p[0], couche == 0);
                    }
                }
            }
        }
    }

    private static void marquer(int x, int y, float hauteur, int partie, boolean premiere) {
        int i = y * 64 + x;
        HAUTEUR[i] = hauteur;
        PARTIE[i] = (byte) partie;
        COUCHE_1[i] = premiere;
    }

    // ---- les couleurs (ABGR, comme NativeImage)

    private static int r(int c) {
        return c & 255;
    }

    private static int g(int c) {
        return c >> 8 & 255;
    }

    private static int b(int c) {
        return c >> 16 & 255;
    }

    private static int a(int c) {
        return c >>> 24;
    }

    private static double lum(int c) {
        return 0.3 * r(c) + 0.59 * g(c) + 0.11 * b(c);
    }

    private static int abgr(double r, double g, double b) {
        return 0xFF000000 | (int) Mth.clamp(Math.round(b), 0, 255) << 16 | (int) Mth.clamp(Math.round(g), 0, 255) << 8 | (int) Mth.clamp(Math.round(r), 0, 255);
    }

    /** Le jaune du visage du skin. */
    private static final int VISAGE = abgr(255, 255, 153);
    private static final int NOIR = abgr(5, 5, 5);
    /** Les cheveux (et tout ce qui n'est pas la peau du visage), du plus sombre au plus clair : le jaune des cheveux du skin. */
    private static final double[][] CHEVEUX = {{0, 140, 104, 10}, {0.18, 222, 172, 22}, {0.42, 250, 212, 44}, {0.68, 255, 234, 86}, {1, 255, 248, 156}};

    private static int dore(int c) {
        double l = lum(c) / 255;
        for (int k = 1; k < CHEVEUX.length; k++) {
            if (l <= CHEVEUX[k][0] || k == CHEVEUX.length - 1) {
                double[] p = CHEVEUX[k - 1], q = CHEVEUX[k];
                double u = Mth.clamp((l - p[0]) / (q[0] - p[0]), 0, 1);
                return abgr(p[1] + (q[1] - p[1]) * u, p[2] + (q[2] - p[2]) * u, p[3] + (q[3] - p[3]) * u);
            }
        }
        return VISAGE;
    }

    /** La couleur de la peau du joueur : la médiane du bas de son visage (sous les yeux, hors bouche). */
    private static int teintePeau(int[] base) {
        int[][] ou = {{9, 13}, {10, 13}, {13, 13}, {14, 13}, {9, 14}, {10, 14}, {13, 14}, {14, 14}, {9, 15}, {14, 15}};
        int[] rs = new int[ou.length], gs = new int[ou.length], bs = new int[ou.length];
        int n = 0;
        for (int[] p : ou) {
            int c = base[p[1] * 64 + p[0]];
            if (a(c) > 128) {
                rs[n] = r(c);
                gs[n] = g(c);
                bs[n] = b(c);
                n++;
            }
        }
        if (n == 0) {
            return abgr(200, 150, 120);
        }
        Arrays.sort(rs, 0, n);
        Arrays.sort(gs, 0, n);
        Arrays.sort(bs, 0, n);
        return abgr(rs[n / 2], gs[n / 2], bs[n / 2]);
    }

    private static boolean estPeau(int c, int peau) {
        double dr = r(c) - r(peau), dg = g(c) - g(peau), db = b(c) - b(peau);
        return Math.sqrt(dr * dr + dg * dg + db * db) < 52;
    }

    /**
     * Ce que la tête du skin impose sur la première couche : ses yeux (blanc dehors, rouge dedans), ses moustaches (ses
     * traits noirs, qui passent sur les côtés de la tête), et une bouche de deux pixels noirs ; sinon 0.
     */
    private static int impose(int[] kcm, int x, int y) {
        int k = kcm[y * 64 + x];
        if (a(k) < 128) {
            return 0;
        }
        if ((y == 11 || y == 12) && (x == 9 || x == 10 || x == 13 || x == 14)) {
            return k;
        }
        if (y == 14 && (x == 11 || x == 12)) {
            return NOIR;
        }
        if (y == 14 && x == 13) {
            return 0;
        }
        return lum(k) < 70 ? k : 0;
    }

    /** La peau du mode Kurama jusqu'à la hauteur h (en pixels de peau) ; au-dessus, la peau du joueur telle quelle. */
    static int[] composer(int[] base, int[] kcm, int h) {
        int[] px = base.clone();
        int peau = teintePeau(base);
        for (int i = 0; i < px.length; i++) {
            float y0 = HAUTEUR[i];
            if (Float.isNaN(y0) || y0 > h) {
                continue;
            }
            int x = i % 64, y = i / 64;
            if (PARTIE[i] != TETE) {
                px[i] = a(kcm[i]) < 128 ? 0 : kcm[i] | 0xFF000000;
                continue;
            }
            int c = COUCHE_1[i] ? impose(kcm, x, y) : 0;
            if (c == 0) {
                if (a(base[i]) < 128) {
                    continue;
                }
                // le visage du skin (son jaune, sans ses traits) sur tout le bas du visage, et là où il a de la peau
                boolean visage = x >= 8 && x < 16 && y >= 11 && y < 16;
                if (COUCHE_1[i] && a(kcm[i]) > 128 && (visage || estPeau(base[i], peau))) {
                    c = lum(kcm[i]) < 70 ? VISAGE : kcm[i];
                } else {
                    c = dore(base[i]);
                }
            }
            px[i] = c | 0xFF000000;
        }
        return px;
    }

    // ================================================================ le dessin

    /** L'anneau de lumière autour de c (relatif à la caméra), horizontal, de rayon r, doré. */
    private static void anneau(PoseStack pose, MultiBufferSource buffers, Vec3 c, double r, double e, float alpha) {
        Vec3 prec = null;
        for (int i = 0; i <= 28; i++) {
            double q = i * Math.PI * 2 / 28;
            Vec3 p = c.add(Math.cos(q) * r, 0, Math.sin(q) * r);
            if (prec != null) {
                Effets.tube(pose, buffers, prec, p, e, 1F, 0.82F, 0.3F, alpha);
            }
            prec = p;
        }
    }

    private static void ecran(GuiGraphics g) {
        if (monFlash < 0) {
            return;
        }
        double t = secondes(monFlash);
        if (t > 0.6) {
            monFlash = -1;
            return;
        }
        int a = (int) (230 * (1 - t / 0.6));
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), a << 24 | 0xFFF6D8);
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("minato_kurama"), (g, delta) -> ecran(g));
        }

        private Couches() {
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                MODES.clear();
                MONTEES.clear();
                DESCENTES.clear();
                return;
            }
            for (UUID id : new HashSet<>(MONTEES.keySet())) {
                Player p = mc.level.getPlayerByUUID(id);
                double t = secondes(MONTEES.get(id));
                if (p == null) {
                    continue;
                }
                if (t < MONTEE) {
                    // des grains d'or et des étincelles autour de l'anneau qui monte
                    double y = p.getY() + 33 / 16.0 * 0.9375 * t / MONTEE;
                    for (int i = 0; i < 4; i++) {
                        double q = HASARD.nextDouble() * Math.PI * 2;
                        mc.level.addParticle(i % 2 == 0 ? OR : ParticleTypes.END_ROD, p.getX() + Math.cos(q) * 0.6, y, p.getZ() + Math.sin(q) * 0.6, 0, 0.02, 0);
                    }
                } else {
                    // le flash, et le rugissement de Kurama : la tenue est posée
                    MONTEES.remove(id);
                    mc.level.addParticle(ParticleTypes.FLASH, p.getX(), p.getY() + 1, p.getZ(), 0, 0, 0);
                    for (int i = 0; i < 40; i++) {
                        mc.level.addParticle(i % 3 == 0 ? ParticleTypes.END_ROD : OR, p.getX() + (HASARD.nextDouble() - 0.5) * 1.6, p.getY() + HASARD.nextDouble() * 2.2,
                                p.getZ() + (HASARD.nextDouble() - 0.5) * 1.6, (HASARD.nextDouble() - 0.5) * 0.3, HASARD.nextDouble() * 0.2, (HASARD.nextDouble() - 0.5) * 0.3);
                    }
                    mc.level.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1.4F, 0.7F, false);
                    if (p == mc.player) {
                        monFlash = System.nanoTime();
                    }
                }
            }
            // en mode Kurama : quelques grains d'or qui montent (pas devant ses propres yeux à la première personne)
            if (mc.level.getGameTime() % 3 == 0) {
                for (UUID id : MODES) {
                    Player p = mc.level.getPlayerByUUID(id);
                    boolean yeux = p == mc.player && mc.options.getCameraType().isFirstPerson();
                    if (p != null && !yeux && !p.isInvisible()) {
                        mc.level.addParticle(OR, p.getX() + (HASARD.nextDouble() - 0.5) * 0.9, p.getY() + 0.2 + HASARD.nextDouble() * 1.8,
                                p.getZ() + (HASARD.nextDouble() - 0.5) * 0.9, 0, 0.03, 0);
                    }
                }
            }
        }

        /** L'anneau de lumière, pendant qu'il monte (la transformation) ou qu'il redescend (la fin du mode). */
        @SubscribeEvent
        public static void anneaux(RenderLevelStageEvent event) {
            if ((MONTEES.isEmpty() && DESCENTES.isEmpty()) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = event.getCamera().getPosition();
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            for (UUID id : new HashSet<>(java.util.stream.Stream.concat(MONTEES.keySet().stream(), DESCENTES.keySet().stream()).toList())) {
                Player p = mc.level.getPlayerByUUID(id);
                double h = hauteur(id);
                if (p == null || h < 0 || p.isInvisible() || (MONTEES.containsKey(id) && secondes(MONTEES.get(id)) >= MONTEE)) {
                    continue;
                }
                if (p == mc.player && mc.options.getCameraType().isFirstPerson()) {
                    continue;
                }
                Vec3 c = p.getPosition(partiel).subtract(cam).add(0, h / 16 * 0.9375, 0);
                anneau(event.getPoseStack(), buffers, c, 0.62, 0.05, 0.95F);
                anneau(event.getPoseStack(), buffers, c, 0.8, 0.09, 0.35F);
            }
            buffers.endBatch();
        }

        /** L'aura dorée du manteau, sur lui (comme celle de Hachibi : chaque partie redessinée un peu plus grosse, qui brille). */
        @SubscribeEvent
        public static void aura(RenderPlayerEvent.Post event) {
            Player p = event.getEntity();
            if (!MODES.contains(p.getUUID()) || p.isInvisible() || ClientMinatoCombo.cache(p) || ClientBijuuDama.cache(p)) {
                return;
            }
            double h = hauteur(p.getUUID());
            float force = (float) Mth.clamp(h / 33, 0, 1);
            if (force <= 0.05F) {
                return;
            }
            PoseStack ps = event.getPoseStack();
            PlayerModel<AbstractClientPlayer> m = event.getRenderer().getModel();
            float partiel = event.getPartialTick();
            boolean fin = p instanceof AbstractClientPlayer c && c.getSkin().model() == PlayerSkin.Model.SLIM;
            float vacille = 0.85F + 0.15F * Mth.sin((p.tickCount + partiel) * 0.35F);
            ps.pushPose();
            ps.mulPose(Axis.YP.rotationDegrees(180 - Mth.rotLerp(partiel, p.yBodyRotO, p.yBodyRot)));
            ps.scale(-1, -1, 1);
            ps.scale(0.9375F, 0.9375F, 0.9375F);
            ps.translate(0, -1.501F, 0);
            VertexConsumer c = event.getMultiBufferSource().getBuffer(RenderType.entityTranslucentEmissive(AURA));
            ClientHachibi.enveloppe(m, ps, c, fin, 1F, Math.round(34 * force * vacille) << 24 | 0xFFC83C);
            ClientHachibi.enveloppe(m, ps, c, fin, 1.12F, Math.round(16 * force * vacille) << 24 | 0xFFE070);
            ps.popPose();
        }

        private Evenements() {
        }
    }

    private ClientKurama() {
    }
}
