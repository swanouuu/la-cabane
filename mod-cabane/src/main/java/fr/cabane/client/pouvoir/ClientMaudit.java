package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Maudit;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * La marque maudite, niveau 1, chez chaque joueur (voir {@link fr.cabane.pouvoir.Maudit}), d'après les maquettes
 * validées (docs/marque-maudite) : à l'éveil, il porte la tenue de Sasuke (t-shirt bleu nuit, short blanc cassé), le
 * sceau (trois virgules) apparaît en haut à gauche du torse, dans le col, puis des petites flammes rouges se répandent
 * depuis le sceau sur sa peau, sous les vêtements, et ressortent au col, sur la tête, les avant-bras et les mollets ; une
 * fois le corps couvert, elles noircissent à partir du sceau. Ses yeux rougissent. Une aura l'entoure, violette du début
 * à la fin. À l'endormissement, les marques se retirent vers le sceau. Les flammes sont dessinées dans un calque 4 fois
 * plus fin que la peau (256 × 256), collé par-dessus.
 * <p>
 * Le niveau 2 (« marque2 », docs/marque-forme2) : le noir gagne toute la peau, puis se dissout en une peau beige pâle,
 * bouche comprise ; les cheveux grisent et poussent en grosse touffe, une petite croix noire se dessine sur le nez, les
 * yeux deviennent noirs avec un seul pixel rouge. Le t-shirt se déchire (il garde le bas du skin de Sasuke, son haut
 * enlevé pendant à la taille), et deux mains géantes jaillissent de son dos ({@link ClientMarque2}). L'aura est forte
 * pendant la transformation, puis très légère.
 */
public final class ClientMaudit {
    /** L'éveil, en secondes : le sceau seul, la propagation en rouge, tout rouge, puis le noircissement. */
    private static final double SCEAU_SEUL = 0.4;
    private static final double PROPAGATION = 4;
    private static final double NOIRCI = SCEAU_SEUL + PROPAGATION + 0.6;
    private static final double NOIRCISSEMENT = 2.4;
    private static final double RETRAIT = 1.2;

    /** Le calque : R fois plus fin que la peau. */
    private static final int R = 4;
    private static final int T = 64 * R;
    private static final int ROUGE_PLEIN = abgr(226, 46, 20);
    private static final int ROUGE_BORD = abgr(96, 12, 8);
    private static final int NOIR = abgr(14, 9, 11);
    private static final int YEUX = abgr(205, 18, 22);
    /** Le sceau, en unités du modèle : les pieds à y = 0, l'avant vers +z, la gauche du personnage vers +x. */
    private static final double[] SCEAU = {2.6, 22.6, 2.1};

    /** L'aura : violette, du début à la fin. */
    private static final float[] AURA = {0.5F, 0.1F, 0.85F};
    private static final RandomSource HASARD = RandomSource.create();

    /**
     * Le passage au niveau 2, en secondes (les temps des maquettes) : le noir gagne toute la peau, puis se dissout dans
     * la peau claire ; les cheveux poussent, la croix se dessine ; le t-shirt se déchire et les ailes jaillissent ; l'aura,
     * forte jusque-là, redevient légère.
     */
    private static final double NOIR2 = 0.1;
    private static final double NOIR2_DUREE = 1.1;
    private static final double DISSOUS = 1.3;
    private static final double DISSOUS_DUREE = 0.9;
    private static final double CHEVEUX = 1.3;
    private static final double CHEVEUX_DUREE = 1.4;
    private static final double CROIX = 2.2;
    private static final double CROIX_DUREE = 0.4;
    static final double DECHIRE = 2.75;
    static final double AILES = 2.85;
    private static final double AILES_DUREE = 0.55;
    private static final double AURA_FORTE = 3.4;
    private static final double AURA_LEGERE = 4.3;

    // ================================================================ les marqués

    /** Un marqué : depuis quand (System.nanoTime), et quand il s'est rendormi (-1 s'il est éveillé). */
    private static final class Marque {
        long debut;
        long fin = -1;
        double tPrecedent = -1;
        int niveau = 1;
        /** Le passage au niveau 2 (-1 s'il n'y est pas). */
        long debut2 = -1;
        double t2Precedent = -1;
        /** Son dernier grand coup d'ailes (-1 : aucun). */
        long battement = -1;
        /** Le passage au niveau 3, les serpents (-1 s'il n'y est pas). */
        long debut3 = -1;
        double t3Precedent = -1;
        /** Sa liste blanche (« marque » ou « marque2 »). */
        String pouvoir = Maudit.NOM;
    }

    /** Son calque à lui (chacun en est à un moment différent de l'éveil). */
    private static final class Calque {
        DynamicTexture texture;
        ResourceLocation id;
        double avance = -1;
        double noirci = -1;
        double noir2 = -1;
        double dissous = -1;
        double croix = -1;
    }

    private static final Map<UUID, Marque> MARQUES = new HashMap<>();
    private static final Map<UUID, Calque> CALQUES = new HashMap<>();
    /** Pour lire la vraie peau d'un marqué, sans qu'on lui rende la nôtre. */
    private static boolean brut;
    private static boolean dansBras;

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    public static void recevoir(Pouvoirs.Etat p) {
        Minecraft mc = Minecraft.getInstance();
        long maintenant = System.nanoTime();
        Marque m = MARQUES.get(p.joueur());
        if (p.eveille()) {
            if (m == null || m.fin >= 0) {
                m = new Marque();
                m.debut = maintenant - (p.annonce() ? 0 : 20_000_000_000L);
                m.tPrecedent = p.annonce() ? -1 : 20;
                MARQUES.put(p.joueur(), m);
                // son calque d'un éveil précédent ne se montre plus tant qu'il n'est pas redessiné
                Calque c = CALQUES.get(p.joueur());
                if (c != null) {
                    c.avance = -1;
                    c.noir2 = -1;
                }
            }
            m.pouvoir = p.pouvoir();
            if (p.niveau() >= 2 && m.niveau < 2) {
                m.niveau = 2;
                // tout droit au niveau 3 (un joueur qui arrive) : le niveau 2 est passé depuis longtemps
                m.debut2 = maintenant - (p.annonce() && p.niveau() == 2 ? 0 : 20_000_000_000L);
                m.t2Precedent = m.debut2 == maintenant ? -1 : 20;
            } else if (p.niveau() < 2) {
                m.niveau = 1;
                m.debut2 = -1;
            }
            if (p.niveau() >= 3 && m.debut3 < 0) {
                m.niveau = 3;
                m.debut3 = maintenant - (p.annonce() ? 0 : 20_000_000_000L);
                m.t3Precedent = p.annonce() ? -1 : 20;
            } else if (p.niveau() < 3) {
                m.debut3 = -1;
            }
        } else if (m != null) {
            if (!p.annonce()) {
                MARQUES.remove(p.joueur());
                return;
            }
            if (m.fin < 0) {
                m.fin = maintenant;
                Player joueur = mc.level == null ? null : mc.level.getPlayerByUUID(p.joueur());
                if (joueur != null) {
                    son(joueur, SoundEvents.FIRE_EXTINGUISH, 0.6F, 0.6F);
                }
            }
        }
    }

    /** Un autre pouvoir s'éveille chez ce joueur : il n'est plus marqué. */
    public static void oublier(UUID joueur) {
        MARQUES.remove(joueur);
    }

    /** Le serveur : ce marqué vient de s'envoler d'un grand coup d'ailes (pour soi, l'envol se recharge). */
    public static void battement(Maudit.Battement p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(p.joueur())) {
            ClientMarque2.envole();
        }
        coupDAiles(p.joueur());
    }

    /** Un grand coup d'ailes : elles balaient vers l'avant, et la poussière vole autour de lui. */
    static void coupDAiles(UUID qui) {
        Marque m = MARQUES.get(qui);
        Minecraft mc = Minecraft.getInstance();
        if (m == null || mc.level == null) {
            return;
        }
        m.battement = System.nanoTime();
        if (mc.level.getPlayerByUUID(qui) instanceof Player joueur) {
            for (int i = 0; i < 24; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2;
                mc.level.addParticle(ParticleTypes.CLOUD, joueur.getX() + Math.cos(a) * 1.2, joueur.getY() + 0.1, joueur.getZ() + Math.sin(a) * 1.2,
                        Math.cos(a) * 0.25, 0.02, Math.sin(a) * 0.25);
            }
        }
    }

    /**
     * Où en est la forme 2 d'un joueur : t2 (secondes depuis le passage), les ailes et les cheveux (de 0 à 1, les ailes
     * dépassent un peu en jaillissant), s'il est torse nu, son dernier coup d'ailes. Null s'il n'est pas au niveau 2.
     */
    record Forme2(double t2, float ailes, float cheveux, boolean torseNu, long battement, double t3) {
    }

    static Forme2 forme2(UUID joueur) {
        Marque m = MARQUES.get(joueur);
        if (m == null || m.niveau < 2 || m.debut2 < 0) {
            return null;
        }
        double t2 = secondes(m.debut2);
        float retrait = m.fin < 0 ? 1 : 1 - (float) Mth.clamp(secondes(m.fin) / RETRAIT, 0, 1);
        double sortie = (t2 - AILES) / AILES_DUREE;
        float ailes = sortie <= 0 ? 0 : sortie >= 1 ? 1 : (float) (1 - Math.pow(1 - sortie, 3) + 0.08 * Math.sin(sortie * Math.PI));
        return new Forme2(t2, ailes * retrait, (float) lisse((t2 - CHEVEUX) / CHEVEUX_DUREE) * retrait, t2 >= DECHIRE, m.battement,
                m.debut3 >= 0 && m.fin < 0 ? secondes(m.debut3) : -1);
    }

    /**
     * Pour le panneau des touches : le niveau du joueur (0 s'il n'est pas marqué, ou pas encore visiblement), s'il peut
     * passer au niveau 2, au niveau 3, et s'il a fini de se transformer.
     */
    record Etat(int niveau, boolean deux, boolean trois, boolean pret) {
    }

    static Etat etat(UUID joueur) {
        Marque m = MARQUES.get(joueur);
        if (m == null || m.fin >= 0 || secondes(m.debut) < SCEAU_SEUL) {
            return null;
        }
        boolean pret = m.niveau < 2 || ailesSorties(joueur) && (m.debut3 < 0 || secondes(m.debut3) >= SERPENTS_PRETS);
        return new Etat(m.niveau, m.pouvoir.equals(Maudit.NOM2) || m.pouvoir.equals(Maudit.NOM3), m.pouvoir.equals(Maudit.NOM3), pret);
    }

    /** Les serpents ont fini de sortir (les temps de ClientMarque3) : il peut s'en servir. */
    static final double SERPENTS_PRETS = 3.3;

    /** Ce joueur est-il éveillé au niveau 3, serpents sortis ? */
    static boolean serpents(UUID joueur) {
        Marque m = MARQUES.get(joueur);
        return m != null && m.fin < 0 && m.debut3 >= 0 && secondes(m.debut3) >= SERPENTS_PRETS;
    }

    /** Ce joueur est-il au niveau 3 (les serpents sortent ou sont sortis) ? */
    static boolean niveau3(UUID joueur) {
        Marque m = MARQUES.get(joueur);
        return m != null && m.fin < 0 && m.debut3 >= 0;
    }

    /** Ce joueur est-il éveillé au niveau 2, ailes sorties (pour planer, s'envoler, lancer le Chidori) ? */
    static boolean ailesSorties(UUID joueur) {
        Marque m = MARQUES.get(joueur);
        return m != null && m.fin < 0 && m.niveau >= 2 && m.debut2 >= 0 && secondes(m.debut2) >= AILES + AILES_DUREE;
    }

    private static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Où en est l'éveil : {avance, noirci}, de 0 à 1 chacun. En se retirant, la marque recule vers le sceau. */
    private static double[] etape(Marque m) {
        double t = m.fin < 0 ? secondes(m.debut) : (m.fin - m.debut) / 1e9;
        double avance = Mth.clamp((t - SCEAU_SEUL) / PROPAGATION, 0, 1);
        double noirci = Mth.clamp((t - NOIRCI) / NOIRCISSEMENT, 0, 1);
        if (m.fin >= 0) {
            avance *= 1 - Mth.clamp(secondes(m.fin) / RETRAIT, 0, 1);
        }
        return new double[]{avance, noirci};
    }

    /**
     * La force de l'aura : elle monte dès que la marque se répand (jusqu'à 1), et s'éteint quand elle se retire. Au
     * niveau 2, elle est forte (1,6) pendant la transformation, puis très légère (0,25).
     */
    private static float aura(Marque m) {
        float force = (float) Mth.clamp(etape(m)[0] * 3, 0, 1);
        if (m.niveau >= 2 && m.debut2 >= 0) {
            double t2 = secondes(m.debut2);
            force = t2 < AURA_FORTE ? 1 + 0.6F * (float) lisse(t2 / 0.6) : (float) Mth.lerp(lisse((t2 - AURA_FORTE) / (AURA_LEGERE - AURA_FORTE)), 1.6, 0.25);
        }
        if (m.debut3 >= 0) {
            // le passage au niveau 3 : l'aura explose quand les serpents sortent, puis redevient légère
            double t3 = secondes(m.debut3);
            force = t3 < 3.4 ? 1 + 1.2F * (float) lisse(t3 / 1.7) : (float) Mth.lerp(lisse((t3 - 3.4) / 1.2), 2.2, 0.25);
        }
        if (m.fin >= 0) {
            force *= 1 - (float) Mth.clamp(secondes(m.fin) / RETRAIT, 0, 1);
        }
        return force;
    }

    private static void son(Player joueur, SoundEvent son, float volume, float hauteur) {
        joueur.level().playLocalSound(joueur.getX(), joueur.getY() + 1, joueur.getZ(), son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    // ================================================================ la peau : la tenue de Sasuke

    /**
     * La peau d'un joueur (par texture) : dans la tenue de Sasuke ; la même, peau claire et cheveux gris (le niveau 2
     * avant que le t-shirt se déchire) ; torse nu dans le bas du skin de Sasuke (le niveau 2) ; et ce qu'il faut pour y
     * poser les flammes.
     */
    private static final class Peau {
        /** La peau en tenue, ou null si on n'a pas pu lire la vraie. */
        ResourceLocation nue;
        ResourceLocation transition;
        ResourceLocation forme2;
        CompletableFuture<Geometrie> geometrie;
        PlayerSkin source;
        final Map<ResourceLocation, PlayerSkin> remplacees = new HashMap<>();
    }

    private static final Map<ResourceLocation, Peau> PEAUX = new HashMap<>();

    /** Appelé à chaque {@code getSkin()} : un marqué porte la tenue de Sasuke (pour son corps comme pour son bras). */
    public static PlayerSkin peau(AbstractClientPlayer joueur, PlayerSkin skin) {
        if (brut || MARQUES.isEmpty() || !MARQUES.containsKey(joueur.getUUID())) {
            return skin;
        }
        Peau p = PEAUX.get(skin.texture());
        if (p == null || p.nue == null) {
            return skin;
        }
        ResourceLocation choix = p.nue;
        Marque m = MARQUES.get(joueur.getUUID());
        if (m.niveau >= 2 && m.debut2 >= 0) {
            double t2 = secondes(m.debut2);
            ResourceLocation voulue = t2 >= DECHIRE ? p.forme2 : t2 >= DISSOUS ? p.transition : p.nue;
            choix = voulue == null ? p.nue : voulue;
        }
        if (p.source != skin) {
            p.source = skin;
            p.remplacees.clear();
        }
        ResourceLocation c = choix;
        return p.remplacees.computeIfAbsent(c, id -> new PlayerSkin(id, skin.textureUrl(), skin.capeTexture(), skin.elytraTexture(), skin.model(), skin.secure()));
    }

    private static PlayerSkin vraiePeau(AbstractClientPlayer joueur) {
        brut = true;
        try {
            return joueur.getSkin();
        } finally {
            brut = false;
        }
    }

    /** Lit la peau du joueur sur la carte graphique, lui met la tenue de Sasuke, et lance le calcul des flammes. */
    private static Peau preparer(PlayerSkin skin) {
        Peau p = PEAUX.get(skin.texture());
        if (p != null) {
            return p;
        }
        p = new Peau();
        PEAUX.put(skin.texture(), p);
        int[] base = lire(skin.texture());
        if (base == null) {
            return p;
        }
        boolean fin = skin.model() == PlayerSkin.Model.SLIM;
        int[] nue = transformer(base, fin);
        p.nue = enregistrer(nue, "peau_" + PEAUX.size());
        double[] peau = teint(base);
        p.transition = enregistrer(forme2(nue.clone(), fin, peau), "transition_" + PEAUX.size());
        int[] bas = basSasuke();
        if (bas != null) {
            p.forme2 = enregistrer(forme2(torseNu(base, bas, fin, peau), fin, peau), "forme2_" + PEAUX.size());
        }
        p.geometrie = CompletableFuture.supplyAsync(() -> geometrie(nue, fin), Util.backgroundExecutor());
        return p;
    }

    static ResourceLocation enregistrer(int[] px, String nom) {
        NativeImage image = new NativeImage(64, 64, true);
        for (int i = 0; i < px.length; i++) {
            image.setPixelRGBA(i % 64, i / 64, px[i]);
        }
        ResourceLocation id = Cabane.id("marque/" + nom);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        return id;
    }

    private static final ResourceLocation BAS_SASUKE = Cabane.id("textures/marque/bas_sasuke.png");
    private static int[] basSasukeLu;

    /** Le bas du skin de Sasuke (pantalon, sandales, ceinture de corde), seul sur une peau 64 × 64 (en ABGR), ou null. */
    static int[] basSasuke() {
        if (basSasukeLu != null) {
            return basSasukeLu;
        }
        try (var flux = Minecraft.getInstance().getResourceManager().open(BAS_SASUKE); NativeImage image = NativeImage.read(flux)) {
            int[] px = new int[64 * 64];
            for (int i = 0; i < px.length; i++) {
                px[i] = image.getPixelRGBA(i % 64, i / 64);
            }
            basSasukeLu = px;
        } catch (java.io.IOException | RuntimeException e) {
            Cabane.LOGGER.warn("Marque maudite : bas de Sasuke illisible", e);
        }
        return basSasukeLu;
    }

    /** La peau telle qu'elle est sur la carte graphique (64 × 64, en ABGR), ou null (les espionnes s'en servent aussi). */
    static int[] lire(ResourceLocation texture) {
        try {
            RenderSystem.bindTexture(Minecraft.getInstance().getTextureManager().getTexture(texture).getId());
            if (GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) != 64
                    || GlStateManager._getTexLevelParameter(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT) != 64) {
                return null;
            }
            try (NativeImage image = new NativeImage(64, 64, false)) {
                image.downloadTexture(0, false);
                int[] px = new int[64 * 64];
                for (int i = 0; i < px.length; i++) {
                    px[i] = image.getPixelRGBA(i % 64, i / 64);
                }
                return px;
            }
        } catch (RuntimeException e) {
            Cabane.LOGGER.warn("Marque maudite : peau illisible {}", texture, e);
            return null;
        }
    }

    private static int abgr(int r, int g, int b) {
        return 0xFF000000 | b << 16 | g << 8 | r;
    }

    private static int abgr(double[] c) {
        return abgr(octet(c[0]), octet(c[1]), octet(c[2]));
    }

    private static int octet(double v) {
        return (int) Mth.clamp(Math.round(v), 0, 255);
    }

    private static double[] rvb(int c) {
        return new double[]{c & 255, c >> 8 & 255, c >> 16 & 255};
    }

    private static double ecart(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double[] clair(double[] c, double k) {
        return new double[]{Math.min(255, c[0] * k), Math.min(255, c[1] * k), Math.min(255, c[2] * k)};
    }

    /** La couleur de peau : la plus fréquente sur le bas du visage (sous les yeux). */
    static double[] teint(int[] px) {
        Map<Integer, Integer> n = new LinkedHashMap<>();
        for (int y = 13; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                n.merge(px[y * 64 + x] & 0xFFFFFF, 1, Integer::sum);
            }
        }
        int meilleure = 0;
        int fois = -1;
        for (Map.Entry<Integer, Integer> e : n.entrySet()) {
            if (e.getValue() > fois) {
                meilleure = e.getKey();
                fois = e.getValue();
            }
        }
        return rvb(meilleure);
    }

    /** La tenue du marqué, comme celle de Sasuke : un t-shirt bleu nuit, un short blanc cassé. */
    private static final double[] TSHIRT = {32, 38, 74};
    private static final double[] SHORT = {226, 220, 204};

    /**
     * La peau du marqué, dans la tenue de Sasuke : un t-shirt bleu nuit à manches courtes, le col ouvert sur le sceau,
     * l'éventail des Uchiwa dans le dos, et un short blanc cassé jusqu'aux genoux (les chaussures restent). Le reste du
     * corps est à la couleur du visage, sans la deuxième couche. Les flammes ne se posent que sur la peau : elles passent
     * sous les vêtements et ressortent au col, sur les avant-bras et les mollets.
     */
    private static int[] transformer(int[] base, boolean fin) {
        int[] s = base.clone();
        double[] peau = teint(base);
        for (int partie = CORPS; partie < 6; partie++) {
            boolean jambe = partie >= 4;
            for (Texel t : texels(boite(partie, fin), UV[partie], 1)) {
                if (jambe && t.y < 2) {
                    continue;
                }
                double[] c = clair(peau, 1 + (((t.tx * 7 + t.ty * 13) % 5) - 2) * 0.015);
                if (partie == CORPS && !col(t)) {
                    c = tissu(TSHIRT, t, t.y < 13);
                } else if ((partie == BRAS_D || partie == BRAS_G) && (t.y > 20 || t.face == HAUT)) {
                    c = tissu(TSHIRT, t, t.y < 21);
                } else if (jambe && t.y > 6) {
                    c = tissu(SHORT, t, t.y < 7);
                }
                s[t.ty * 64 + t.tx] = abgr(c);
            }
            for (Texel t : texels(boite(partie, fin), UV2[partie], 1)) {
                if (!jambe || t.y >= 2) {
                    s[t.ty * 64 + t.tx] = 0;
                }
            }
        }
        // l'éventail des Uchiwa, en haut du dos : rouge dessus, blanc dessous, et son manche
        int[] rouge = {35, 36, 34, 35, 36, 37}, blanc = {34, 35, 36, 37, 35, 36};
        for (int k = 0; k < 6; k++) {
            s[(k < 2 ? 22 : 23) * 64 + rouge[k]] = abgr(190, 32, 30);
            s[(k < 4 ? 24 : 25) * 64 + blanc[k]] = abgr(232, 230, 226);
        }
        s[26 * 64 + 35] = s[26 * 64 + 36] = abgr(150, 148, 156);
        return s;
    }

    /** Le col du t-shirt, ouvert en V (un peu vers sa gauche, pour laisser voir le sceau). */
    private static boolean col(Texel t) {
        double i = t.x + 3.5, j = 23.5 - t.y;
        return t.face == 0 && j <= 3 && Math.abs(i - 4.5) <= 3.5 - 0.9 * j;
    }

    /** Une couleur de tissu, un peu irrégulière, plus sombre à l'ourlet. */
    private static double[] tissu(double[] couleur, Texel t, boolean ourlet) {
        return clair(couleur, (ourlet ? 0.78 : 1) * (1 + (hache(t.tx, t.ty, 7) - 0.5) * 0.08));
    }

    // ================================================================ la peau du niveau 2

    /** La peau du niveau 2 : un beige très pâle, qui tire sur le marron terre. */
    private static final double[] CLAIRE = {184, 156, 130};
    private static final double[] GRIS_CHEVEUX = {150, 160, 182};
    private static final int OEIL_NOIR = abgr(24, 18, 22);

    private static double lum(double[] c) {
        return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
    }

    /** Une teinte de peau : claire et chaude. */
    private static boolean tonPeau(double[] c) {
        return lum(c) > 110 && c[0] >= c[1] && c[0] - c[2] > 15;
    }

    /** Les yeux : dans les deux carrés de 2 × 2 sous la frange (lignes 12 et 13), ce qui n'est pas de la peau. */
    private static boolean oeil(int[] s, int x, int y) {
        return (x == 9 || x == 10 || x == 13 || x == 14) && (y == 12 || y == 13) && (s[y * 64 + x] >>> 24) > 0 && !tonPeau(rvb(s[y * 64 + x]));
    }

    /**
     * Torse nu : le corps, les bras et les jambes à la couleur du visage, sans la deuxième couche ; puis le bas du skin
     * de Sasuke par-dessus (ses deux couches).
     */
    static int[] torseNu(int[] base, int[] bas, boolean fin, double[] peau) {
        int[] s = base.clone();
        for (int partie = CORPS; partie < 6; partie++) {
            for (Texel t : texels(boite(partie, fin), UV[partie], 1)) {
                s[t.ty * 64 + t.tx] = abgr(clair(peau, 1 + (((t.tx * 7 + t.ty * 13) % 5) - 2) * 0.015));
            }
            for (Texel t : texels(boite(partie, fin), UV2[partie], 1)) {
                s[t.ty * 64 + t.tx] = 0;
            }
        }
        for (int i = 0; i < s.length; i++) {
            if ((bas[i] >>> 24) > 0) {
                s[i] = bas[i];
            }
        }
        return s;
    }

    /**
     * Le niveau 2 sur une peau : tout ce qui est peau devient clair (en gardant sa lumière), bouche comprise ; les cheveux
     * grisent ; les yeux deviennent noirs, avec un seul pixel rouge au coin intérieur du haut.
     */
    private static int[] forme2(int[] s, boolean fin, double[] peau) {
        return forme2(s, fin, peau, YEUX);
    }

    /** La même, avec un iris d une autre couleur (Kaguya niveau 3 : jaune). */
    static int[] forme2(int[] s, boolean fin, double[] peau, int iris) {
        int[] avant = s.clone();
        double lumPeau = Math.max(1, lum(peau));
        // la lumière moyenne des cheveux, pour garder leurs reflets
        List<Texel> tete = new ArrayList<>(texels(boite(TETE, fin), UV[TETE], 1));
        int base = tete.size();
        tete.addAll(texels(boite(TETE, fin), UV2[TETE], 1));
        double somme = 0;
        int n = 0;
        boolean[] cheveu = new boolean[tete.size()];
        for (int k = 0; k < tete.size(); k++) {
            Texel t = tete.get(k);
            int c = avant[t.ty * 64 + t.tx];
            boolean basVisage = k < base && t.face == 0 && t.ty >= 14;
            cheveu[k] = (c >>> 24) > 0 && !(k < base && oeil(avant, t.tx, t.ty)) && (k >= base || !basVisage && !tonPeau(rvb(c)));
            if (cheveu[k]) {
                somme += lum(rvb(c));
                n++;
            }
        }
        double moyenne = n == 0 ? 80 : somme / n;
        for (int k = 0; k < tete.size(); k++) {
            Texel t = tete.get(k);
            int c = avant[t.ty * 64 + t.tx];
            if ((c >>> 24) == 0 || k < base && oeil(avant, t.tx, t.ty)) {
                continue;
            }
            double[] v = rvb(c);
            double[] nouv = cheveu[k] ? clair(GRIS_CHEVEUX, Mth.clamp(1 + (lum(v) - moyenne) / (moyenne + 25) * 0.9, 0.72, 1.25))
                    : clair(CLAIRE, Mth.clamp(lum(v) / lumPeau, 0.5, 1.2));
            s[t.ty * 64 + t.tx] = (c & 0xFF000000) | (abgr(nouv) & 0xFFFFFF);
        }
        for (int partie = CORPS; partie < 6; partie++) {
            for (int[] uv : new int[][]{UV[partie], UV2[partie]}) {
                for (Texel t : texels(boite(partie, fin), uv, 1)) {
                    int c = avant[t.ty * 64 + t.tx];
                    if ((c >>> 24) > 0 && ecart(rvb(c), peau) <= 34) {
                        s[t.ty * 64 + t.tx] = abgr(clair(CLAIRE, Mth.clamp(lum(rvb(c)) / lumPeau, 0.5, 1.2)));
                    }
                }
            }
        }
        for (int y = 12; y <= 13; y++) {
            for (int x : new int[]{9, 10, 13, 14}) {
                if (oeil(avant, x, y)) {
                    boolean estIris = (x == 10 || x == 13) && (y == 12 || !oeil(avant, x, 12));
                    s[y * 64 + x] = estIris ? iris : OEIL_NOIR;
                }
            }
        }
        return s;
    }

    // ================================================================ le modèle du joueur, texel par texel

    private static final int TETE = 0;
    private static final int CORPS = 1;
    private static final int BRAS_D = 2;
    private static final int BRAS_G = 3;
    /** Les six parties (bras larges) : leur boîte, leur texture, celle de leur deuxième couche. */
    private static final double[][] BOITES = {{-4, 24, -4, 4, 32, 4}, {-4, 12, -2, 4, 24, 2}, {-8, 12, -2, -4, 24, 2}, {4, 12, -2, 8, 24, 2},
            {-4, 0, -2, 0, 12, 2}, {0, 0, -2, 4, 12, 2}};
    private static final int[][] UV = {{0, 0}, {16, 16}, {40, 16}, {32, 48}, {0, 16}, {16, 48}};
    private static final int[][] UV2 = {{32, 0}, {16, 32}, {40, 32}, {48, 48}, {0, 32}, {0, 48}};

    private static final int HAUT = 4;
    private static final int BAS = 5;

    /** Un texel : sa case dans la texture, son centre en 3D, la normale de sa face (0 devant, 1 dos, 2 droite, 3 gauche, 4 haut, 5 bas). */
    private record Texel(int tx, int ty, double x, double y, double z, double nx, double ny, double nz, int face) {
    }

    private static double[] boite(int partie, boolean fin) {
        double[] b = BOITES[partie].clone();
        if (fin && partie == BRAS_D) {
            b[0] = -7;
        }
        if (fin && partie == BRAS_G) {
            b[3] = 7;
        }
        return b;
    }

    /** Chaque texel d'une boîte, r fois plus fin que la peau. */
    private static List<Texel> texels(double[] b, int[] uv, int r) {
        double x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];
        int w = (int) Math.round((x1 - x0) * r), h = (int) Math.round((y1 - y0) * r), d = (int) Math.round((z1 - z0) * r);
        double sx = (x1 - x0) / w, sy = (y1 - y0) / h, sz = (z1 - z0) / d;
        int u = uv[0] * r, v = uv[1] * r;
        // pour chaque face : sa case (u, v, largeur, hauteur), son coin, ses deux axes, sa normale
        double[][] faces = {
                {u + d, v + d, w, h, x0, y1, z1, sx, 0, 0, 0, -sy, 0, 0, 0, 1},
                {u + d + w + d, v + d, w, h, x1, y1, z0, -sx, 0, 0, 0, -sy, 0, 0, 0, -1},
                {u, v + d, d, h, x0, y1, z0, 0, 0, sz, 0, -sy, 0, -1, 0, 0},
                {u + d + w, v + d, d, h, x1, y1, z1, 0, 0, -sz, 0, -sy, 0, 1, 0, 0},
                {u + d, v, w, d, x0, y1, z0, sx, 0, 0, 0, 0, sz, 0, 1, 0},
                {u + d + w, v, w, d, x0, y0, z1, sx, 0, 0, 0, 0, -sz, 0, -1, 0}};
        List<Texel> l = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            double[] f = faces[k];
            for (int j = 0; j < (int) f[3]; j++) {
                for (int i = 0; i < (int) f[2]; i++) {
                    double a = i + 0.5, c = j + 0.5;
                    l.add(new Texel((int) f[0] + i, (int) f[1] + j, f[4] + f[7] * a + f[10] * c, f[5] + f[8] * a + f[11] * c,
                            f[6] + f[9] * a + f[12] * c, f[13], f[14], f[15], k));
                }
            }
        }
        return l;
    }

    // ================================================================ les flammes

    /** Un hasard toujours le même pour les mêmes nombres (le même que les maquettes). */
    private static double hache(int i, int j, int k) {
        int h = i * 374761393 + j * 668265263 + k * 2147483647;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFFFFL) / 4294967296.0;
    }

    private static double loin(double x, double y, double z) {
        return Math.sqrt((x - SCEAU[0]) * (x - SCEAU[0]) + (y - SCEAU[1]) * (y - SCEAU[1]) * 0.81 + (z - SCEAU[2]) * (z - SCEAU[2]));
    }

    /** Une flamme : une tête ronde et une queue qui s'effile en s'enroulant, posée à plat sur la peau. */
    private static final class Flamme {
        double[] tete;
        double d;
        boolean sceau;
        double[] n;
        /** Ses 15 échantillons, de la tête au bout de la queue : position et rayon. */
        double[][] p = new double[15][];
        double[] r = new double[15];

        Flamme(double[] tete, double d, boolean sceau, double[] n, double r0, double longueur, double enroule, double[] dir) {
            this.tete = tete;
            this.d = d;
            this.sceau = sceau;
            this.n = n;
            double[] q = tete;
            for (int i = 0; i <= 14; i++) {
                double t = i / 14.0;
                p[i] = q;
                r[i] = r0 * Math.pow(1 - t, 1.15) + 0.04;
                dir = tourner(dir, n, enroule / 14);
                q = new double[]{q[0] + dir[0] * longueur / 14, q[1] + dir[1] * longueur / 14, q[2] + dir[2] * longueur / 14};
            }
        }
    }

    /** v tourné d'un angle autour de l'axe n (Rodrigues). */
    private static double[] tourner(double[] v, double[] n, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double[] k = croix(n, v);
        double d = n[0] * v[0] + n[1] * v[1] + n[2] * v[2];
        return new double[]{v[0] * c + k[0] * s + n[0] * d * (1 - c), v[1] * c + k[1] * s + n[1] * d * (1 - c), v[2] * c + k[2] * s + n[2] * d * (1 - c)};
    }

    private static double[] croix(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double distance(double[] a, double x, double y, double z) {
        return Math.sqrt((a[0] - x) * (a[0] - x) + (a[1] - y) * (a[1] - y) + (a[2] - z) * (a[2] - z));
    }

    private static boolean peauIci(int[] skin, double[] peau, int x, int y) {
        return ecart(rvb(skin[y * 64 + x]), peau) <= 34;
    }

    /** Les flammes (toujours les mêmes pour une peau) : posées sur la peau, plus serrées près du sceau, puis le sceau. */
    private static List<Flamme> flammes(int[] skin, double[] peau, boolean fin) {
        List<double[]> candidats = new ArrayList<>();
        List<Texel> texels = new ArrayList<>();
        for (int partie = 0; partie < 6; partie++) {
            for (Texel t : texels(boite(partie, fin), UV[partie], 2)) {
                if (partie == TETE && (t.face == HAUT || t.face == BAS || t.x < -0.5)) {
                    continue;
                }
                if (!peauIci(skin, peau, t.tx / 2, t.ty / 2)) {
                    continue;
                }
                candidats.add(new double[]{texels.size(), hache(t.tx, t.ty, 31)});
                texels.add(t);
            }
        }
        candidats.sort((a, b) -> Double.compare(a[1], b[1]));
        List<Flamme> l = new ArrayList<>();
        for (double[] c : candidats) {
            Texel t = texels.get((int) c[0]);
            double d = loin(t.x, t.y, t.z);
            double ecart = d < 5 ? 1.15 : 1.45;
            if (d < 1.6) {
                continue;
            }
            boolean pris = false;
            for (Flamme f : l) {
                if (distance(f.tete, t.x, t.y, t.z) < ecart) {
                    pris = true;
                    break;
                }
            }
            if (pris) {
                continue;
            }
            double[] h = new double[7];
            for (int k = 1; k < 7; k++) {
                h[k] = hache(t.tx * 7 + k, t.ty * 13 + k, 3);
            }
            double[] n = {t.nx, t.ny, t.nz};
            double[] haut = {-n[0] * n[1], 1 - n[1] * n[1], -n[2] * n[1]};
            double lh = Math.sqrt(haut[0] * haut[0] + haut[1] * haut[1] + haut[2] * haut[2]);
            if (lh < 0.2) {
                haut = croix(n, new double[]{0, 0, 1});
                lh = Math.sqrt(haut[0] * haut[0] + haut[1] * haut[1] + haut[2] * haut[2]);
            }
            double[] dir = tourner(new double[]{haut[0] / lh, haut[1] / lh, haut[2] / lh}, n, (h[1] - 0.5) * 1.6);
            double decale = (h[2] - 0.5) * 0.6;
            double[] tete = {t.x + decale * (Math.abs(n[0]) < 0.5 ? 1 : 0), t.y + decale, t.z + decale * (Math.abs(n[2]) < 0.5 ? 1 : 0)};
            l.add(new Flamme(tete, d, false, n, 0.3 + h[3] * 0.18, 0.7 + h[4] * 0.7, (h[5] < 0.5 ? -1 : 1) * (1.2 + h[6] * 1.3), dir));
        }
        // le sceau : trois virgules (tomoe) qui tournent autour d'un point
        double[] n = {0, 0, 1};
        for (int k = 0; k < 3; k++) {
            double a = k * 2 * Math.PI / 3;
            double[] tete = {SCEAU[0] + Math.cos(a) * 0.62, SCEAU[1] + Math.sin(a) * 0.62, 2};
            l.add(new Flamme(tete, 0, true, n, 0.3, 0.85, 1.6, new double[]{-Math.sin(a), Math.cos(a), 0}));
        }
        return l;
    }

    /**
     * Ce qu'il faut pour dessiner le calque à n'importe quel moment : pour chaque texel fin qui peut être marqué, les
     * flammes qui peuvent le couvrir (dans l'ordre), et à partir de quelle pousse de la flamme il est couvert.
     */
    private static final class Geometrie {
        int[] texels;
        int[] debut;
        int[] flamme;
        float[] seuil;
        double[] d;
        boolean[] sceau;
        int[] yeux;
        /** Pour le niveau 2 : tous les texels fins de peau, leur distance au sceau, et la ligne du bas des yeux. */
        int[] peau;
        float[] peauD;
        int basYeux;
    }

    private static Geometrie geometrie(int[] skin, boolean fin) {
        double[] peau = teint(skin);
        List<Flamme> fl = flammes(skin, peau, fin);
        Geometrie g = new Geometrie();
        g.d = new double[fl.size()];
        g.sceau = new boolean[fl.size()];
        for (int i = 0; i < fl.size(); i++) {
            g.d[i] = fl.get(i).d;
            g.sceau[i] = fl.get(i).sceau;
        }
        List<Integer> texels = new ArrayList<>();
        List<Integer> debut = new ArrayList<>();
        List<Integer> flamme = new ArrayList<>();
        List<Float> seuil = new ArrayList<>();
        List<Integer> tousPeau = new ArrayList<>();
        List<Float> tousD = new ArrayList<>();
        for (int partie = 0; partie < 6; partie++) {
            for (Texel t : texels(boite(partie, fin), UV[partie], R)) {
                if (!peauIci(skin, peau, t.tx / R, t.ty / R)) {
                    continue;
                }
                tousPeau.add(t.ty * T + t.tx);
                tousD.add((float) loin(t.x, t.y, t.z));
                int avant = flamme.size();
                for (int i = 0; i < fl.size(); i++) {
                    Flamme f = fl.get(i);
                    // une flamme ne couvre rien à plus de 2 de sa tête (sa queue fait au plus 1,4, et sa largeur 0,5)
                    if (distance(f.tete, t.x, t.y, t.z) > 2) {
                        continue;
                    }
                    // seulement sur la face où elle est posée, ou sur une face voisine (elle passe l'arête)
                    if (f.n[0] * t.nx + f.n[1] * t.ny + f.n[2] * t.nz < -0.5) {
                        continue;
                    }
                    // l'échantillon e couvre le texel dès que la pousse atteint e.t et que son rayon r × min(1, 0,45 + pousse) l'englobe
                    double s = 2;
                    for (int e = 0; e <= 14; e++) {
                        double ecart = distance(f.p[e], t.x, t.y, t.z);
                        if (ecart < f.r[e]) {
                            s = Math.min(s, Math.max(e / 14.0, ecart / f.r[e] - 0.45));
                        }
                    }
                    if (s <= 1) {
                        flamme.add(i);
                        seuil.add((float) Math.max(s, 1e-6));
                    }
                }
                if (flamme.size() > avant) {
                    texels.add(t.ty * T + t.tx);
                    debut.add(avant);
                }
            }
        }
        debut.add(flamme.size());
        g.texels = texels.stream().mapToInt(Integer::intValue).toArray();
        g.debut = debut.stream().mapToInt(Integer::intValue).toArray();
        g.flamme = flamme.stream().mapToInt(Integer::intValue).toArray();
        g.seuil = new float[seuil.size()];
        for (int i = 0; i < g.seuil.length; i++) {
            g.seuil[i] = seuil.get(i);
        }
        // les yeux : ce qui n'est ni blanc ni peau, sur la ligne des yeux
        List<Integer> yeux = new ArrayList<>();
        for (int x : new int[]{9, 10, 13, 14}) {
            double[] c = rvb(skin[12 * 64 + x]);
            if (!(c[0] > 200 && c[1] > 200 && c[2] > 200) && ecart(c, peau) > 40) {
                yeux.add(x);
            }
        }
        g.yeux = yeux.stream().mapToInt(Integer::intValue).toArray();
        g.peau = tousPeau.stream().mapToInt(Integer::intValue).toArray();
        g.peauD = new float[tousD.size()];
        for (int i = 0; i < g.peauD.length; i++) {
            g.peauD[i] = tousD.get(i);
        }
        g.basYeux = oeil(skin, 9, 13) || oeil(skin, 10, 13) || oeil(skin, 13, 13) || oeil(skin, 14, 13) ? 13 : 12;
        return g;
    }

    /**
     * Le calque du niveau 2 : les marques noires (celles du niveau 1, puis toute la peau à partir du sceau), qui se
     * dissolvent texel par texel dans la peau claire ; et la croix noire sur le nez, sous les yeux.
     */
    private static void dessiner2(NativeImage image, Geometrie g, double noir, double dissous, double croix) {
        image.fillRect(0, 0, T, T, 0);
        double rayon = noir * 34;
        for (int k = 0; k < g.texels.length; k++) {
            int i = g.texels[k];
            if (hache(i % T, i / T, 91) >= dissous) {
                image.setPixelRGBA(i % T, i / T, NOIR);
            }
        }
        for (int k = 0; k < g.peau.length; k++) {
            int i = g.peau[k];
            if (g.peauD[k] < rayon && hache(i % T, i / T, 91) >= dissous) {
                image.setPixelRGBA(i % T, i / T, NOIR);
            }
        }
        if (croix > 0) {
            // une étoile à quatre branches, plus large que haute
            double cx = 12 * R, cy = (g.basYeux + 1) * R, a = 7.5 * croix, b = 5.5 * croix;
            for (int y = (int) Math.floor(cy - b); y <= Math.ceil(cy + b); y++) {
                for (int x = (int) Math.floor(cx - a); x <= Math.ceil(cx + a); x++) {
                    double u = Math.abs(x + 0.5 - cx) / a, v = Math.abs(y + 0.5 - cy) / b;
                    if (Math.sqrt(u) + Math.sqrt(v) <= 1) {
                        image.setPixelRGBA(x, y, abgr(20, 14, 16));
                    }
                }
            }
        }
    }

    /** Le calque à une étape de l'éveil : les flammes rouges (un fin bord sombre) ou noires, et les yeux rouges. */
    private static void dessiner(NativeImage image, Geometrie g, double avance, double noirci) {
        byte[] marque = new byte[T * T];
        double rayon = avance * 34, rayonNoir = noirci * 40;
        for (int k = 0; k < g.texels.length; k++) {
            for (int e = g.debut[k]; e < g.debut[k + 1]; e++) {
                int f = g.flamme[e];
                double pousse = g.sceau[f] ? 1 : Mth.clamp((rayon - g.d[f]) / 2.5, 0, 1);
                if (pousse > 0 && pousse >= g.seuil[e]) {
                    marque[g.texels[k]] = (byte) (g.sceau[f] || g.d[f] < rayonNoir ? 2 : 1);
                    break;
                }
            }
        }
        image.fillRect(0, 0, T, T, 0);
        for (int i : g.texels) {
            if (marque[i] == 0) {
                continue;
            }
            int x = i % T, y = i / T;
            int c = NOIR;
            if (marque[i] == 1) {
                boolean bord = x == 0 || y == 0 || x == T - 1 || y == T - 1
                        || marque[i - 1] == 0 || marque[i + 1] == 0 || marque[i - T] == 0 || marque[i + T] == 0;
                c = bord ? ROUGE_BORD : ROUGE_PLEIN;
            }
            image.setPixelRGBA(x, y, c);
        }
        if (avance > 0.15) {
            for (int x : g.yeux) {
                image.fillRect(x * R, 12 * R, R, R, YEUX);
            }
        }
    }

    /** Le calque de ce joueur, prêt à dessiner, ou null s'il n'est pas marqué. */
    private static ResourceLocation calque(Player joueur) {
        Calque c = MARQUES.containsKey(joueur.getUUID()) ? CALQUES.get(joueur.getUUID()) : null;
        return c == null || c.avance < 0 ? null : c.id;
    }

    /** Tient le calque d'un marqué à jour (une fois par tick, comme les images des maquettes). */
    private static void mettreAJour(AbstractClientPlayer joueur, Marque m) {
        Peau p = preparer(vraiePeau(joueur));
        if (p.geometrie == null || !p.geometrie.isDone()) {
            return;
        }
        Geometrie g = p.geometrie.join();
        Calque c = CALQUES.computeIfAbsent(joueur.getUUID(), u -> {
            Calque n = new Calque();
            n.texture = new DynamicTexture(T, T, true);
            n.id = Cabane.id("marque/calque_" + u);
            Minecraft.getInstance().getTextureManager().register(n.id, n.texture);
            return n;
        });
        if (m.niveau >= 2 && m.debut2 >= 0) {
            double t2 = secondes(m.debut2);
            double noir = lisse((t2 - NOIR2) / NOIR2_DUREE), dissous = Mth.clamp((t2 - DISSOUS) / DISSOUS_DUREE, 0, 1);
            double croix = lisse((t2 - CROIX) / CROIX_DUREE);
            if (c.avance >= 0 && noir == c.noir2 && dissous == c.dissous && croix == c.croix) {
                return;
            }
            c.avance = 1;
            c.noir2 = noir;
            c.dissous = dissous;
            c.croix = croix;
            dessiner2(c.texture.getPixels(), g, noir, dissous, croix);
            c.texture.upload();
            return;
        }
        double[] e = etape(m);
        if (e[0] == c.avance && e[1] == c.noirci && c.noir2 < 0) {
            return;
        }
        c.avance = e[0];
        c.noirci = e[1];
        c.noir2 = -1;
        dessiner(c.texture.getPixels(), g, e[0], e[1]);
        c.texture.upload();
    }

    // ================================================================ le dessin

    /** Le calque collé sur le corps (sans la deuxième couche de la peau), puis le halo de l'aura qui l'épouse. */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            ResourceLocation calque = calque(joueur);
            if (calque == null || joueur.isInvisible()) {
                return;
            }
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            ModelPart[] couche2 = {m.hat, m.jacket, m.leftSleeve, m.rightSleeve, m.leftPants, m.rightPants};
            boolean[] visible = new boolean[couche2.length];
            for (int i = 0; i < couche2.length; i++) {
                visible[i] = couche2[i].visible;
                couche2[i].visible = false;
            }
            m.renderToBuffer(ps, buffers.getBuffer(RenderType.armorCutoutNoCull(calque)), lumiere, LivingEntityRenderer.getOverlayCoords(joueur, 0), -1);
            for (int i = 0; i < couche2.length; i++) {
                couche2[i].visible = visible[i];
            }
            float force = aura(MARQUES.get(joueur.getUUID()));
            if (force > 0.01F) {
                double t = secondes(MARQUES.get(joueur.getUUID()).debut);
                float vacille = 0.8F + 0.2F * Mth.sin((float) t * 9) * Mth.sin((float) t * 3.7F);
                int couleur = (int) (AURA[0] * 255) << 16 | (int) (AURA[1] * 255) << 8 | (int) (AURA[2] * 255);
                boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
                VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientHachibi.MANTEAU));
                ClientHachibi.enveloppe(m, ps, c, fin, 1.02F, Math.round(34 * force * vacille) << 24 | couleur);
                ClientHachibi.enveloppe(m, ps, c, fin, 1.16F, Math.round(22 * force * vacille) << 24 | couleur);
            }
        }
    }

    /**
     * Les flammes de l'aura, tout autour de lui : des langues qui montent, vacillent, et s'éteignent en pointe. Pas
     * pour soi en première personne (on serait dedans).
     */
    private static void flammesAura(MultiBufferSource buffers, Matrix4f mat, Vec3 camera, Player joueur, Marque m, float partiel) {
        float force = aura(m);
        if (force < 0.01F) {
            return;
        }
        double t = secondes(m.debut);
        Vec3 pied = joueur.getPosition(partiel).subtract(camera);
        float lacet = Mth.rotLerp(partiel, joueur.yBodyRotO, joueur.yBodyRot) * Mth.DEG_TO_RAD;
        float cos = Mth.cos(lacet), sin = Mth.sin(lacet);
        float hauteur = joueur.getBbHeight() / 1.8F;
        VertexConsumer voile = buffers.getBuffer(Types.VOILE);
        VertexConsumer lueur = buffers.getBuffer(Types.LUEUR);
        for (int i = 0; i < 22; i++) {
            double hasard = (i * 0.6180339887) % 1;
            double hasard2 = (i * 0.3819660113 + 0.13) % 1;
            double phase = (t * (1.1 + hasard * 0.8) + hasard * 3.7) % 1;
            float angle = i * 2.39996F;
            // tout autour du corps, sur une ellipse plus large que profonde
            float lx = Mth.sin(angle) * 0.42F, lz = Mth.cos(angle) * 0.27F;
            float ox = lx * cos - lz * sin, oz = lx * sin + lz * cos;
            float x = (float) pied.x + ox;
            float z = (float) pied.z + oz;
            // celles qui sont devant lui (côté caméra) s'effacent : l'aura dessine sa silhouette sans le cacher
            float lo = Mth.sqrt(ox * ox + oz * oz), lc = Mth.sqrt(x * x + z * z);
            float face = lo < 1e-4F || lc < 1e-4F ? 0 : Mth.clamp(-(ox * x + oz * z) / (lo * lc), 0, 1);
            float y0 = (float) pied.y + (0.05F + (float) hasard2 * 1.45F + (float) phase * 0.45F) * hauteur;
            float vie = Mth.sin((float) phase * Mth.PI);
            // plus forte que 1 (la transformation du niveau 2), elle monte plus haut et s'élargit
            float grande = Math.max(1, force);
            float haut = (0.32F + (float) hasard * 0.28F) * hauteur * (0.6F + 0.4F * vie) * grande;
            float large = 0.13F * hauteur * grande;
            // la langue fait toujours face à la caméra
            float dx = -z, dz = x;
            float l = Mth.sqrt(dx * dx + dz * dz);
            if (l < 1e-4F) {
                continue;
            }
            dx /= l;
            dz /= l;
            float a = force * vie * (1 - 0.9F * face * face);
            langue(voile, mat, x, y0, z, dx, dz, haut, large, (float) t * 9 + i * 1.7F, AURA, a * 0.42F);
            langue(lueur, mat, x, y0, z, dx, dz, haut * 0.8F, large * 0.45F, (float) t * 9 + i * 1.7F, AURA, a * 0.5F);
        }
    }

    /** Une langue de flamme : large en bas, qui ondule et s'effile en pointe, transparente vers le haut. */
    private static void langue(VertexConsumer c, Matrix4f m, float x, float y, float z, float dx, float dz, float haut, float large,
            float onde, float[] rgb, float a) {
        int n = 5;
        for (int s = 0; s < n; s++) {
            float v0 = (float) s / n, v1 = (float) (s + 1) / n;
            float l0 = large * forme(v0), l1 = large * forme(v1);
            float o0 = Mth.sin(onde + v0 * 3) * 0.05F * v0, o1 = Mth.sin(onde + v1 * 3) * 0.05F * v1;
            float a0 = a * (1 - v0 * 0.85F), a1 = a * (1 - v1 * 0.85F);
            float y0 = y + haut * v0, y1 = y + haut * v1;
            c.addVertex(m, x + dx * (o0 - l0), y0, z + dz * (o0 - l0)).setColor(rgb[0], rgb[1], rgb[2], a0);
            c.addVertex(m, x + dx * (o0 + l0), y0, z + dz * (o0 + l0)).setColor(rgb[0], rgb[1], rgb[2], a0);
            c.addVertex(m, x + dx * (o1 + l1), y1, z + dz * (o1 + l1)).setColor(rgb[0], rgb[1], rgb[2], a1);
            c.addVertex(m, x + dx * (o1 - l1), y1, z + dz * (o1 - l1)).setColor(rgb[0], rgb[1], rgb[2], a1);
        }
    }

    /** La largeur d'une langue de flamme, de sa base (0) à sa pointe (1). */
    private static float forme(float v) {
        return v < 0.25F ? 0.55F + 1.8F * v : (1 - v) / 0.75F;
    }

    private static final class Types extends RenderType {
        /** Le voile de l'aura : translucide, pour qu'il se voie aussi en plein jour. */
        static final RenderType VOILE = create("cabane_marque_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));
        /** Le cœur des flammes : de la lumière qui s'ajoute. */
        static final RenderType LUEUR = create("cabane_marque_lueur_aura", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(RENDERTYPE_LIGHTNING_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_marque_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Evenements {
        /** Le calque se pose sur tous les joueurs (bras larges comme fins). */
        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                MARQUES.clear();
                return;
            }
            MARQUES.entrySet().removeIf(e -> e.getValue().fin >= 0 && secondes(e.getValue().fin) > RETRAIT);
            for (Map.Entry<UUID, Marque> entree : MARQUES.entrySet()) {
                Marque m = entree.getValue();
                if (!(mc.level.getPlayerByUUID(entree.getKey()) instanceof AbstractClientPlayer joueur)) {
                    continue;
                }
                mettreAJour(joueur, m);
                if (m.fin >= 0) {
                    continue;
                }
                double t = secondes(m.debut);
                // le cœur qui cogne, le sceau qui brûle, puis la marque qui noircit
                if (m.tPrecedent < 0 && t >= 0) {
                    son(joueur, SoundEvents.WARDEN_HEARTBEAT, 1F, 0.85F);
                    for (int i = 0; i < 12; i++) {
                        mc.level.addParticle(new DustParticleOptions(new Vector3f(AURA[0], AURA[1], AURA[2]), 1F), joueur.getX() + aleat(0.3),
                                joueur.getY() + 1.3 + aleat(0.2), joueur.getZ() + aleat(0.3), 0, 0.02, 0);
                    }
                }
                if (m.tPrecedent < SCEAU_SEUL && t >= SCEAU_SEUL) {
                    son(joueur, SoundEvents.SOUL_ESCAPE.value(), 1.2F, 0.6F);
                }
                if (m.tPrecedent < NOIRCI && t >= NOIRCI) {
                    son(joueur, SoundEvents.FIRECHARGE_USE, 0.6F, 0.5F);
                }
                m.tPrecedent = t;
                if (m.niveau >= 2 && m.debut2 >= 0) {
                    niveau2(mc, joueur, m);
                }
                if (m.debut3 >= 0) {
                    niveau3(mc, joueur, m);
                }
                if (aura(m) > 0.1F && HASARD.nextInt(3) == 0) {
                    mc.level.addParticle(new DustParticleOptions(new Vector3f(AURA[0], AURA[1], AURA[2]), 0.9F), joueur.getX() + aleat(0.45),
                            joueur.getY() + HASARD.nextDouble() * 1.9, joueur.getZ() + aleat(0.45), 0, 0.04, 0);
                }
                if (t >= SCEAU_SEUL && HASARD.nextInt(5) == 0) {
                    mc.level.addParticle(ParticleTypes.REVERSE_PORTAL, joueur.getX() + aleat(0.4), joueur.getY() + HASARD.nextDouble() * 1.6,
                            joueur.getZ() + aleat(0.4), 0, 0.02, 0);
                }
            }
        }

        private static double aleat(double etendue) {
            return (HASARD.nextDouble() - 0.5) * 2 * etendue;
        }

        /** Les sons et les éclats du passage au niveau 2 : le cri, la peau qui pâlit, le t-shirt qui se déchire, les ailes. */
        private static void niveau2(Minecraft mc, AbstractClientPlayer joueur, Marque m) {
            double t2 = secondes(m.debut2), avant = m.t2Precedent;
            m.t2Precedent = t2;
            if (avant < 0 && t2 >= 0) {
                son(joueur, SoundEvents.WARDEN_HEARTBEAT, 1.2F, 0.7F);
                son(joueur, SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.5F, 0.6F);
            }
            if (avant < DISSOUS && t2 >= DISSOUS) {
                son(joueur, SoundEvents.SOUL_ESCAPE.value(), 1.2F, 0.5F);
            }
            if (avant < DECHIRE && t2 >= DECHIRE) {
                son(joueur, SoundEvents.WOOL_BREAK, 1.2F, 0.7F);
                son(joueur, SoundEvents.ARMOR_EQUIP_LEATHER.value(), 1F, 0.6F);
                // des lambeaux du t-shirt bleu nuit qui volent
                DustParticleOptions tissu = new DustParticleOptions(new Vector3f(0.13F, 0.15F, 0.3F), 1.6F);
                for (int i = 0; i < 40; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    mc.level.addParticle(tissu, joueur.getX() + Math.cos(a) * 0.3, joueur.getY() + 0.8 + HASARD.nextDouble() * 0.7,
                            joueur.getZ() + Math.sin(a) * 0.3, Math.cos(a) * 0.2, 0.05 + HASARD.nextDouble() * 0.1, Math.sin(a) * 0.2);
                }
            }
            if (avant < AILES && t2 >= AILES) {
                son(joueur, SoundEvents.ENDER_DRAGON_FLAP, 1.4F, 0.7F);
                son(joueur, SoundEvents.PHANTOM_FLAP, 1.2F, 0.5F);
                son(joueur, SoundEvents.EVOKER_PREPARE_SUMMON, 0.8F, 0.6F);
                DustParticleOptions violet = new DustParticleOptions(new Vector3f(AURA[0], AURA[1], AURA[2]), 2F);
                float lacet = joueur.yBodyRot * Mth.DEG_TO_RAD;
                for (int i = 0; i < 60; i++) {
                    double cote = HASARD.nextBoolean() ? 1 : -1, loin = HASARD.nextDouble() * 2.2;
                    double lx = cote * (0.4 + loin), lz = -0.5 - HASARD.nextDouble() * 0.6;
                    mc.level.addParticle(violet, joueur.getX() + lx * Mth.cos(lacet) - lz * Mth.sin(lacet), joueur.getY() + 1 + HASARD.nextDouble() * 1.8,
                            joueur.getZ() + lx * Mth.sin(lacet) + lz * Mth.cos(lacet), 0, 0.03, 0);
                }
            }
        }

        /**
         * Les sons et les éclats du passage au niveau 3 : l'aile se fissure (le cœur, les craquements), puis elle éclate en
         * serpents dans une explosion d'aura violette tout autour de lui, et ils sifflent.
         */
        private static void niveau3(Minecraft mc, AbstractClientPlayer joueur, Marque m) {
            double t3 = secondes(m.debut3), avant = m.t3Precedent;
            m.t3Precedent = t3;
            if (avant < 0 && t3 >= 0) {
                son(joueur, SoundEvents.WARDEN_HEARTBEAT, 1.4F, 0.6F);
            }
            if (avant < 0.6 && t3 >= 0.6) {
                son(joueur, SoundEvents.DEEPSLATE_BREAK, 1.2F, 0.6F);
                son(joueur, SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.5F, 0.5F);
            }
            if (avant < 1.2 && t3 >= 1.2) {
                son(joueur, SoundEvents.BASALT_BREAK, 1.2F, 0.5F);
            }
            if (avant < 1.7 && t3 >= 1.7) {
                son(joueur, SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 0.6F);
                son(joueur, SoundEvents.EVOKER_PREPARE_SUMMON, 1.2F, 0.5F);
                son(joueur, SoundEvents.CAT_HISS, 1.4F, 0.5F);
                // l'explosion d'aura : une sphère de chakra violet qui part dans tous les sens, et des éclats de portail
                DustParticleOptions gros = new DustParticleOptions(new Vector3f(AURA[0], AURA[1], AURA[2]), 3F);
                DustParticleOptions fin = new DustParticleOptions(new Vector3f(0.75F, 0.35F, 1F), 1.4F);
                for (int i = 0; i < 70; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2, b = Math.acos(HASARD.nextDouble() * 2 - 1), v = 0.25 + HASARD.nextDouble() * 0.55;
                    double dx = Math.sin(b) * Math.cos(a), dy = Math.cos(b), dz = Math.sin(b) * Math.sin(a);
                    mc.level.addParticle(i % 3 == 0 ? fin : gros, joueur.getX() + dx * 0.4, joueur.getY() + 1.2 + dy * 0.4, joueur.getZ() + dz * 0.4,
                            dx * v, dy * v, dz * v);
                }
                for (int i = 0; i < 16; i++) {
                    mc.level.addParticle(ParticleTypes.REVERSE_PORTAL, joueur.getX() + aleat(1.5), joueur.getY() + 0.3 + HASARD.nextDouble() * 2.4,
                            joueur.getZ() + aleat(1.5), aleat(0.4), 0.1, aleat(0.4));
                    mc.level.addParticle(ParticleTypes.WITCH, joueur.getX() + aleat(2), joueur.getY() + HASARD.nextDouble() * 2.8, joueur.getZ() + aleat(2), 0, 0.2, 0);
                }
            }
            if (avant < 3.2 && t3 >= 3.2) {
                son(joueur, SoundEvents.CAT_HISS, 1.2F, 0.7F);
            }
            // pendant le passage, l'aura déborde : des volutes violettes montent tout autour
            if (t3 > 0.6 && t3 < 3.6 && HASARD.nextInt(3) == 0) {
                for (int i = 0; i < 1; i++) {
                    mc.level.addParticle(new DustParticleOptions(new Vector3f(AURA[0], AURA[1], AURA[2]), 1.6F), joueur.getX() + aleat(1.2),
                            joueur.getY() + HASARD.nextDouble() * 2.4, joueur.getZ() + aleat(1.2), 0, 0.08, 0);
                }
            }
        }

        /** En première personne : les marques sur son bras aussi (le bras de la vraie main, puis le calque par-dessus). */
        @SubscribeEvent
        public static void bras(RenderArmEvent event) {
            AbstractClientPlayer joueur = event.getPlayer();
            ResourceLocation calque = dansBras ? null : calque(joueur);
            if (calque == null || !(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(joueur) instanceof PlayerRenderer rendu)) {
                return;
            }
            event.setCanceled(true);
            dansBras = true;
            try {
                if (event.getArm() == HumanoidArm.RIGHT) {
                    rendu.renderRightHand(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), joueur);
                } else {
                    rendu.renderLeftHand(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), joueur);
                }
            } finally {
                dansBras = false;
            }
            ModelPart bras = event.getArm() == HumanoidArm.RIGHT ? rendu.getModel().rightArm : rendu.getModel().leftArm;
            bras.render(event.getPoseStack(), event.getMultiBufferSource().getBuffer(RenderType.armorCutoutNoCull(calque)), event.getPackedLight(),
                    OverlayTexture.NO_OVERLAY);
        }

        @SubscribeEvent
        public static void dessiner(RenderLevelStageEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (MARQUES.isEmpty() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || mc.level == null) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            Vec3 camera = event.getCamera().getPosition();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            Matrix4f mat = event.getPoseStack().last().pose();
            for (Map.Entry<UUID, Marque> entree : MARQUES.entrySet()) {
                Player joueur = mc.level.getPlayerByUUID(entree.getKey());
                if (joueur == null || joueur.isInvisible() || joueur == mc.player && mc.options.getCameraType().isFirstPerson()) {
                    continue;
                }
                flammesAura(buffers, mat, camera, joueur, entree.getValue(), partiel);
            }
            buffers.endBatch(Types.VOILE);
            buffers.endBatch(Types.LUEUR);
        }

        private Evenements() {
        }
    }

    private ClientMaudit() {
    }
}
