package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Fruit;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Les fruits du démon chez chaque joueur (voir {@link Fruit}) : la main de lave (la gauche) ou de glace (la droite)
 * des éveillés, vue de l'extérieur comme à la première personne ; les jets, l'éruption et l'ère glaciaire (rien que du
 * dessin : rien ne reste au sol) ; le bloc de glace autour de ceux qui sont gelés ; l'animation quand on mange un fruit
 * (la spirale, la main qui change un instant, le bord de l'écran) ; les touches et le panneau. Au niveau 2 : la moitié
 * du corps (par-dessus l'armure s'il en porte), le grand poing, les lances, les météores et le faisan en vol.
 */
public final class ClientFruit {
    private static final ResourceLocation GANT_LAVE = Cabane.id("textures/entity/fruit/gant_lave.png");
    private static final ResourceLocation GANT_LAVE_LUEUR = Cabane.id("textures/entity/fruit/gant_lave_lueur.png");
    private static final ResourceLocation GANT_GLACE = Cabane.id("textures/entity/fruit/gant_glace.png");
    /** La moitié du corps (l'éveil 2), sur la disposition d'une peau de joueur : voir docs/fruits/generateur/objets.mjs. */
    private static final ResourceLocation MOITIE_LAVE = Cabane.id("textures/entity/fruit/moitie_lave.png");
    private static final ResourceLocation MOITIE_LAVE_LUEUR = Cabane.id("textures/entity/fruit/moitie_lave_lueur.png");
    private static final ResourceLocation MOITIE_GLACE = Cabane.id("textures/entity/fruit/moitie_glace.png");
    private static final ResourceLocation NEIGE_POUDREUSE = ResourceLocation.withDefaultNamespace("textures/misc/powder_snow_outline.png");
    private static final RandomSource HASARD = RandomSource.create();
    private static final double DUREE_JET = 0.4;
    private static final double DUREE_ONDE = 1.7;
    private static final double DUREE_REPAS = Fruit.DUREE_REPAS / 20.0;

    /** Les éveillés : le joueur → « lave » ou « glace ». */
    private static final Map<UUID, String> EVEILLES = new HashMap<>();

    /** Quelqu'un qui mange son fruit, depuis quand ; {@code avant} : le temps à l'image précédente (pour les sons). */
    private static final class Repas {
        final String sorte;
        final long debut = System.nanoTime();
        double avant = -1;

        Repas(String sorte) {
            this.sorte = sorte;
        }
    }

    private static final Map<UUID, Repas> REPAS = new HashMap<>();

    /** Un jet, de la main à ce qu'il touche ; {@code touche} : l'éclaboussure est faite. */
    private static final class Jet {
        final boolean glace;
        final Vec3 de;
        final Vec3 a;
        final long debut = System.nanoTime();
        boolean touche;

        Jet(boolean glace, Vec3 de, Vec3 a) {
            this.glace = glace;
            this.de = de;
            this.a = a;
        }
    }

    private static final List<Jet> JETS = new ArrayList<>();

    /** Une éruption ou une ère glaciaire, autour de ce point. */
    private record Onde(boolean glace, Vec3 centre, long debut, int graine) {
    }

    private static final List<Onde> ONDES = new ArrayList<>();
    /** Ce que l'ère glaciaire a pris dans la glace : l'entité → quand la glace se brise (System.nanoTime). */
    private static final Map<Integer, Long> GELES = new HashMap<>();
    /** Le niveau de chaque éveillé : 1 la main, 2 la moitié du corps. */
    private static final Map<UUID, Integer> NIVEAUX = new HashMap<>();

    /** Un tir de l'éveil 2 en vol (voir Fruit) : il avance ici comme sur le serveur, jusqu'à ce que le serveur l'arrête. */
    private static final class Vol {
        final int sorte;
        final Vec3 de;
        final Vec3 dir;
        final double vitesse;
        final double portee;
        final long debut;
        final int graine = HASARD.nextInt(1000);
        int id;

        Vol(int sorte, Vec3 de, Vec3 dir, double vitesse, double portee, long debut) {
            this.sorte = sorte;
            this.de = de;
            this.dir = dir.normalize();
            this.vitesse = vitesse;
            this.portee = portee;
            this.debut = debut;
        }

        double age(Minecraft mc, float partiel) {
            return mc.level.getGameTime() - debut + partiel;
        }

        Vec3 pos(Minecraft mc, float partiel) {
            return de.add(dir.scale(Math.min(portee, vitesse * age(mc, partiel))));
        }
    }

    private static final Map<Integer, Vol> VOLS = new HashMap<>();

    /** Le sillage gelé du faisan : des pics qui sortent du sol derrière lui, puis fondent. */
    private record Pic(Vec3 pos, long debut, double taille) {
    }

    private static final List<Pic> PICS = new ArrayList<>();

    /** La pluie de météores : la zone qui rougeoie au sol, et le nuage de cendres d'où tombent les roches. */
    private record Zone(Vec3 centre, long debut) {
    }

    private static final List<Zone> ZONES = new ArrayList<>();
    private static final double DUREE_ZONE = 3.8;

    /** Là où une météore (ou le grand poing) s'est écrasée : une flaque de magma qui refroidit, une onde de choc. */
    private record Cratere(Vec3 pos, long debut, double taille, int graine) {
    }

    private static final List<Cratere> CRATERES = new ArrayList<>();

    /** Le sillage du faisan : une bande de givre au sol, d'un point au suivant, qui s'efface. */
    private record Bande(Vec3 a, Vec3 b, Vec3 cote, long debut) {
    }

    private static final List<Bande> BANDES = new ArrayList<>();
    private static final Map<Integer, Vec3> DERNIER_SOL = new HashMap<>();

    /** Les éclats de glace quand le faisan se brise : ils volent, retombent, disparaissent. */
    private record Eclat(Vec3 pos, Vec3 vitesse, long debut) {
    }

    private static final List<Eclat> BRISURES = new ArrayList<>();
    /** L'écran tremble quand une météore ou le poing s'écrase tout près : depuis quand, et à quel point. */
    private static long secousse = -1;
    private static float forceSecousse;

    private static void secouer(Vec3 p, float force) {
        Minecraft mc = Minecraft.getInstance();
        double d = mc.player == null ? 99 : mc.player.position().distanceTo(p);
        if (d < 18) {
            secousse = System.nanoTime();
            forceSecousse = Math.max(forceSecousse * 0.5F, force * (float) (1 - d / 18));
        }
    }

    /** Le sol sous ce point (pour poser un cratère ou le sillage). */
    private static double sol(Minecraft mc, Vec3 p) {
        int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z));
        return Math.abs(y - p.y) < 6 ? y : p.y;
    }

    // ================================================================ ce que dit le serveur

    public static void recevoir(Pouvoirs.Etat p) {
        if (p.eveille()) {
            EVEILLES.put(p.joueur(), p.pouvoir());
            NIVEAUX.put(p.joueur(), p.niveau());
        } else {
            EVEILLES.remove(p.joueur());
            NIVEAUX.remove(p.joueur());
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && p.joueur().equals(mc.player.getUUID())) {
                pretJet = 0;
                pretChoc = 0;
            }
        }
    }

    public static void oublier(UUID joueur) {
        EVEILLES.remove(joueur);
        NIVEAUX.remove(joueur);
    }

    public static void vol(Fruit.Vol p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Vol v = new Vol(p.sorte(), vec(p.de()), vec(p.dir()), p.vitesse(), p.portee(), mc.level.getGameTime());
            v.id = p.id();
            VOLS.put(p.id(), v);
        }
    }

    /** Le tir s'arrête : le poing explose, la météore s'écrase, la lance se brise, le faisan éclate en neige. */
    public static void arret(Fruit.Arret p) {
        Vol v = VOLS.remove(p.id());
        Minecraft mc = Minecraft.getInstance();
        if (v == null || mc.level == null) {
            return;
        }
        Vec3 o = vec(p.pos());
        BlockParticleOption glace = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState());
        switch (v.sorte) {
            case Fruit.POING -> {
                for (int i = 0; i < 3; i++) {
                    mc.level.addParticle(ParticleTypes.EXPLOSION, o.x + aleat(0.8), o.y + aleat(0.6), o.z + aleat(0.8), 0, 0, 0);
                }
                for (int i = 0; i < 40; i++) {
                    mc.level.addParticle(i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, o.x, o.y, o.z, aleat(0.3), HASARD.nextDouble() * 0.3, aleat(0.3));
                }
                for (int i = 0; i < 10; i++) {
                    mc.level.addParticle(ParticleTypes.LARGE_SMOKE, o.x + aleat(1), o.y + HASARD.nextDouble(), o.z + aleat(1), 0, 0.05, 0);
                }
                CRATERES.add(new Cratere(new Vec3(o.x, sol(mc, o), o.z), System.nanoTime(), 2.2, HASARD.nextInt(100)));
                secouer(o, 1F);
            }
            case Fruit.METEORE -> {
                for (int i = 0; i < 24; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    mc.level.addParticle(i % 4 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, o.x, o.y + 0.2, o.z, Math.cos(a) * 0.2, 0.15 + HASARD.nextDouble() * 0.2, Math.sin(a) * 0.2);
                }
                mc.level.addParticle(ParticleTypes.EXPLOSION, o.x, o.y + 0.5, o.z, 0, 0, 0);
                for (int i = 0; i < 8; i++) {
                    mc.level.addParticle(ParticleTypes.LARGE_SMOKE, o.x + aleat(1.2), o.y + 0.3 + HASARD.nextDouble(), o.z + aleat(1.2), 0, 0.06, 0);
                }
                CRATERES.add(new Cratere(new Vec3(o.x, sol(mc, o), o.z), System.nanoTime(), 1.6 + HASARD.nextDouble() * 0.8, HASARD.nextInt(100)));
                secouer(o, 0.7F);
            }
            case Fruit.LANCE -> {
                for (int i = 0; i < 14; i++) {
                    mc.level.addParticle(i % 2 == 0 ? glace : ParticleTypes.SNOWFLAKE, o.x, o.y, o.z, aleat(0.15), HASARD.nextDouble() * 0.15, aleat(0.15));
                }
            }
            default -> {
                for (int i = 0; i < 90; i++) {
                    mc.level.addParticle(i % 3 == 0 ? glace : ParticleTypes.SNOWFLAKE, o.x + aleat(2.5), o.y + aleat(1.5), o.z + aleat(2.5), aleat(0.3), HASARD.nextDouble() * 0.3, aleat(0.3));
                }
                // le faisan se brise : une gerbe de grands éclats de glace
                long t0 = System.nanoTime();
                for (int i = 0; i < 40; i++) {
                    double a = HASARD.nextDouble() * Math.PI * 2;
                    BRISURES.add(new Eclat(o, new Vec3(Math.cos(a) * (3 + HASARD.nextDouble() * 6), 2 + HASARD.nextDouble() * 6, Math.sin(a) * (3 + HASARD.nextDouble() * 6)), t0));
                }
                DERNIER_SOL.remove(p.id());
                secouer(o, 0.6F);
            }
        }
    }

    private static int id(Vol v) {
        return v.id;
    }

    /** Son niveau, s'il est éveillé (0 sinon). */
    private static int niveau(UUID joueur) {
        return EVEILLES.containsKey(joueur) ? NIVEAUX.getOrDefault(joueur, 1) : 0;
    }

    public static void repas(Fruit.Repas p) {
        REPAS.put(p.joueur(), new Repas(p.sorte()));
    }

    public static void trait(Fruit.Trait p) {
        JETS.add(new Jet(Fruit.GLACE.equals(p.sorte()), vec(p.de()), vec(p.a())));
    }

    public static void onde(Fruit.Onde p) {
        if (Fruit.PLUIE.equals(p.sorte())) {
            ZONES.add(new Zone(vec(p.centre()), System.nanoTime()));
            return;
        }
        Onde o = new Onde(Fruit.GLACE.equals(p.sorte()), vec(p.centre()), System.nanoTime(), HASARD.nextInt());
        ONDES.add(o);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // le coup sur le sol : un anneau qui part tout autour
        for (int i = 0; i < 70; i++) {
            double a = HASARD.nextDouble() * Math.PI * 2, r = 0.5 + HASARD.nextDouble() * 4.5;
            double x = o.centre().x + Math.cos(a) * r, y = o.centre().y + 0.1, z = o.centre().z + Math.sin(a) * r;
            if (o.glace()) {
                mc.level.addParticle(i % 3 == 0 ? new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()) : ParticleTypes.SNOWFLAKE,
                        x, y, z, Math.cos(a) * 0.1, 0.12 + HASARD.nextDouble() * 0.15, Math.sin(a) * 0.1);
            } else {
                mc.level.addParticle(i % 4 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, x, y, z, Math.cos(a) * 0.05, 0.1 + HASARD.nextDouble() * 0.2, Math.sin(a) * 0.05);
            }
        }
    }

    public static void gel(Fruit.Gel p) {
        GELES.put(p.entite(), System.nanoTime() + p.ticks() * 50_000_000L);
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    /**
     * La main que l'on voit changer chez ce joueur : celle de son fruit s'il est éveillé ; pendant qu'il mange, elle
     * apparaît un instant (elle vacille, puis s'efface) ; sinon, rien.
     */
    private static String sorteVisible(UUID joueur) {
        String s = EVEILLES.get(joueur);
        if (s != null) {
            return s;
        }
        Repas r = REPAS.get(joueur);
        if (r == null) {
            return null;
        }
        double t = secondes(r.debut);
        return t > 1.2 && t < 2.7 && (t > 1.7 || Math.sin(t * 40) > 0) ? r.sorte : null;
    }

    private static boolean glace(String sorte) {
        return Fruit.GLACE.equals(sorte);
    }

    /** Où pend la main du fruit (gauche pour la lave, droite pour la glace), bras au repos. */
    private static Vec3 main(Player j, boolean glace, float partiel) {
        float lacet = Mth.lerp(partiel, j.yBodyRotO, j.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 droite = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet));
        return j.getPosition(partiel).add(droite.scale(glace ? 0.36 : -0.36)).add(0, j.isCrouching() ? 0.5 : 0.65, 0);
    }

    private static double aleat(double k) {
        return (HASARD.nextDouble() - 0.5) * 2 * k;
    }

    private static void son(Player j, SoundEvent son, float volume, float hauteur) {
        j.level().playLocalSound(j.getX(), j.getY() + 1, j.getZ(), son, SoundSource.PLAYERS, volume, hauteur, false);
    }

    // ================================================================ la main (le gant de lave ou de glace)

    /** Le gant, les veines qui remontent le bras et les éclats de glace : pour un bras large [0] ou fin [1]. */
    private static final ModelPart[] GANTS = new ModelPart[2];
    private static final ModelPart[] VEINES = new ModelPart[2];
    private static final ModelPart[] ECLATS = new ModelPart[2];

    /**
     * Les morceaux, dans le repère du bras (le bas du bras à y = 10, l'avant vers -z, l'extérieur du bras droit vers -x).
     * La texture fait 128×128 pour une taille déclarée de 32×32 : quatre pixels de texture par pixel du modèle.
     */
    private static void modeles() {
        if (GANTS[0] != null) {
            return;
        }
        for (int fin = 0; fin < 2; fin++) {
            float large = fin == 0 ? 4 : 3;
            // le gant couvre la main (les 5 derniers pixels du bras), un demi-pixel plus gros tout autour
            MeshDefinition g = new MeshDefinition();
            g.getRoot().addOrReplaceChild("g", CubeListBuilder.create().texOffs(0, fin * 10).addBox(-0.5F, 5.5F, -2.5F, large + 1, 5, 5), PartPose.ZERO);
            GANTS[fin] = LayerDefinition.create(g, 32, 32).bakeRoot().getChild("g");
            MeshDefinition v = new MeshDefinition();
            v.getRoot().addOrReplaceChild("v", CubeListBuilder.create().texOffs(fin * 16, 20).addBox(0, 1.5F, -2, large, 4, 4, new CubeDeformation(0.3F)),
                    PartPose.ZERO);
            VEINES[fin] = LayerDefinition.create(v, 32, 32).bakeRoot().getChild("v");
            // six éclats qui sortent du gant, vers l'extérieur, le haut, l'avant et l'arrière
            MeshDefinition e = new MeshDefinition();
            float[][] dirs = {{-0.8F, -0.6F, 0}, {-0.9F, 0.1F, 0.4F}, {-0.3F, -0.9F, -0.3F}, {-0.55F, 0.6F, -0.1F}, {-0.2F, -0.3F, -0.95F}, {-0.6F, -0.2F, 0.75F}};
            float[] longueurs = {3.4F, 2.6F, 3F, 2.2F, 2.4F, 2.6F};
            // (le bras va ici de x = 0 à x = large ; dessinerGant le remet à sa place)
            float cx = large / 2;
            for (int k = 0; k < dirs.length; k++) {
                Vector3f d = new Vector3f(dirs[k]).normalize();
                float a = (float) Math.asin(-d.z), c = (float) Math.atan2(d.x, -d.y);
                e.getRoot().addOrReplaceChild("e" + k, CubeListBuilder.create().texOffs(24, 0).addBox(-0.6F, -longueurs[k], -0.6F, 1.2F, longueurs[k], 1.2F),
                        PartPose.offsetAndRotation(cx + d.x * 2.2F, 8 + d.y * 2.2F, d.z * 2.2F, a, 0, c));
            }
            ECLATS[fin] = LayerDefinition.create(e, 32, 32).bakeRoot();
        }
    }

    /** Le gant dans le repère du bras (droit ou gauche : on le déplace pour qu'il tombe sur la main). */
    private static void dessinerGant(PoseStack ps, MultiBufferSource buffers, int lumiere, boolean glace, boolean fin, boolean droit, int dessous) {
        modeles();
        int f = fin ? 1 : 0;
        ps.pushPose();
        // les morceaux sont faits pour le bras droit fin ou large (x de -3 à 1, ou de -2 à 1) ; le gauche part de -1
        ps.translate((droit ? (fin ? -2 : -3) : -1) / 16F, 0, 0);
        if (glace) {
            VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucent(GANT_GLACE));
            VEINES[f].render(ps, c, lumiere, dessous, 0xC8FFFFFF);
            GANTS[f].render(ps, c, lumiere, dessous, 0xE6FFFFFF);
            ECLATS[f].render(ps, c, lumiere, dessous, 0xF0FFFFFF);
        } else {
            VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(GANT_LAVE));
            VEINES[f].render(ps, c, lumiere, dessous);
            GANTS[f].render(ps, c, lumiere, dessous);
            // les fissures brillent, de jour comme de nuit
            VertexConsumer l = buffers.getBuffer(RenderType.eyes(GANT_LAVE_LUEUR));
            VEINES[f].render(ps, l, LightTexture.FULL_BRIGHT, dessous);
            GANTS[f].render(ps, l, LightTexture.FULL_BRIGHT, dessous);
        }
        ps.popPose();
    }

    private static boolean fin(AbstractClientPlayer joueur) {
        return joueur.getSkin().model() == PlayerSkin.Model.SLIM;
    }

    /**
     * Les modèles de la moitié du corps : [bras fins][épais]. Épais (1,15 pixel), il passe par-dessus une armure ; mince
     * (0,3), il colle à la peau.
     */
    @SuppressWarnings("unchecked")
    private static final PlayerModel<AbstractClientPlayer>[][] MOITIES = new PlayerModel[2][2];

    private static PlayerModel<AbstractClientPlayer> modeleMoitie(boolean fin, boolean epais) {
        int f = fin ? 1 : 0, e = epais ? 1 : 0;
        if (MOITIES[f][e] == null) {
            MOITIES[f][e] = new PlayerModel<>(LayerDefinition.create(PlayerModel.createMesh(new CubeDeformation(epais ? 1.15F : 0.3F), fin), 64, 64)
                    .bakeRoot(), fin);
        }
        return MOITIES[f][e];
    }

    /** Ces morceaux, avec la matière de la moitié (la lave brille aussi dans le noir). */
    private static void rendreMoitie(PoseStack ps, MultiBufferSource buffers, int lumiere, boolean glace, int dessous, ModelPart... parties) {
        if (glace) {
            VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucent(MOITIE_GLACE));
            for (ModelPart p : parties) {
                p.render(ps, c, lumiere, dessous);
            }
            return;
        }
        VertexConsumer c = buffers.getBuffer(RenderType.entityCutoutNoCull(MOITIE_LAVE));
        for (ModelPart p : parties) {
            p.render(ps, c, lumiere, dessous);
        }
        VertexConsumer l = buffers.getBuffer(RenderType.eyes(MOITIE_LAVE_LUEUR));
        for (ModelPart p : parties) {
            p.render(ps, l, LightTexture.FULL_BRIGHT, dessous);
        }
    }

    /** L'éveil 2 : la moitié du corps, par-dessus l'armure là où il en porte (elle l'avale). */
    private static void moitie(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerModel<AbstractClientPlayer> parent,
            boolean glace, int dessous) {
        boolean fin = fin(joueur);
        PlayerModel<AbstractClientPlayer> mince = modeleMoitie(fin, false), epais = modeleMoitie(fin, true);
        parent.copyPropertiesTo(mince);
        parent.copyPropertiesTo(epais);
        PlayerModel<AbstractClientPlayer> tete = joueur.getItemBySlot(EquipmentSlot.HEAD).isEmpty() ? mince : epais;
        PlayerModel<AbstractClientPlayer> torse = joueur.getItemBySlot(EquipmentSlot.CHEST).isEmpty() ? mince : epais;
        PlayerModel<AbstractClientPlayer> jambes = joueur.getItemBySlot(EquipmentSlot.LEGS).isEmpty() && joueur.getItemBySlot(EquipmentSlot.FEET).isEmpty()
                ? mince : epais;
        rendreMoitie(ps, buffers, lumiere, glace, dessous, tete.head, torse.body, torse.rightArm, torse.leftArm, jambes.rightLeg, jambes.leftLeg);
    }

    /** Vu de l'extérieur : le gant sur la main du fruit ; au niveau 2, la moitié du corps. */
    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(PlayerRenderer parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            String sorte = sorteVisible(joueur.getUUID());
            if (sorte == null || joueur.isInvisible()) {
                return;
            }
            boolean glace = glace(sorte);
            int dessous = LivingEntityRenderer.getOverlayCoords(joueur, 0);
            if (niveau(joueur.getUUID()) >= 2) {
                moitie(ps, buffers, lumiere, joueur, getParentModel(), glace, dessous);
            }
            ModelPart bras = glace ? getParentModel().rightArm : getParentModel().leftArm;
            ps.pushPose();
            bras.translateAndRotate(ps);
            dessinerGant(ps, buffers, lumiere, glace, fin(joueur), glace, dessous);
            ps.popPose();
        }
    }

    /** À la première personne : le bras du fruit (rendu comme d'habitude), puis son gant par-dessus. */
    private static boolean dansBras;

    private static void dessinerBras(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, PlayerRenderer rendu,
            HumanoidArm bras, boolean glace) {
        dansBras = true;
        try {
            if (bras == HumanoidArm.RIGHT) {
                rendu.renderRightHand(ps, buffers, lumiere, joueur);
            } else {
                rendu.renderLeftHand(ps, buffers, lumiere, joueur);
            }
        } finally {
            dansBras = false;
        }
        ModelPart partie = bras == HumanoidArm.RIGHT ? rendu.getModel().rightArm : rendu.getModel().leftArm;
        if (niveau(joueur.getUUID()) >= 2) {
            // au niveau 2, tout le bras est de lave (ou de glace)
            PlayerModel<AbstractClientPlayer> mince = modeleMoitie(fin(joueur), false);
            ModelPart m = bras == HumanoidArm.RIGHT ? mince.rightArm : mince.leftArm;
            m.copyFrom(partie);
            rendreMoitie(ps, buffers, lumiere, glace, OverlayTexture.NO_OVERLAY, m);
        }
        ps.pushPose();
        partie.translateAndRotate(ps);
        dessinerGant(ps, buffers, lumiere, glace, fin(joueur), bras == HumanoidArm.RIGHT, OverlayTexture.NO_OVERLAY);
        ps.popPose();
    }

    /** Le bras de la main vide qu'on ne voit d'habitude pas (l'autre main), placé comme ItemInHandRenderer.renderPlayerArm. */
    private static void brasLibre(PoseStack ps, MultiBufferSource buffers, int lumiere, float equipe, float coup, HumanoidArm cote,
            AbstractClientPlayer joueur, PlayerRenderer rendu, boolean glace) {
        boolean droit = cote != HumanoidArm.LEFT;
        float f = droit ? 1 : -1;
        float racine = Mth.sqrt(coup);
        ps.pushPose();
        ps.translate(f * (-0.3F * Mth.sin(racine * Mth.PI) + 0.64F), 0.4F * Mth.sin(racine * Mth.TWO_PI) - 0.6F + equipe * -0.6F,
                -0.4F * Mth.sin(coup * Mth.PI) - 0.72F);
        ps.mulPose(Axis.YP.rotationDegrees(f * 45F));
        ps.mulPose(Axis.YP.rotationDegrees(f * Mth.sin(racine * Mth.PI) * 70F));
        ps.mulPose(Axis.ZP.rotationDegrees(f * Mth.sin(coup * coup * Mth.PI) * -20F));
        ps.translate(f * -1F, 3.6F, 3.5F);
        ps.mulPose(Axis.ZP.rotationDegrees(f * 120F));
        ps.mulPose(Axis.XP.rotationDegrees(200F));
        ps.mulPose(Axis.YP.rotationDegrees(f * -135F));
        ps.translate(f * 5.6F, 0, 0);
        dessinerBras(ps, buffers, lumiere, joueur, rendu, cote, glace);
        ps.popPose();
    }

    // ================================================================ les touches

    private static long pretJet, pretChoc;
    private static boolean accroupiAvant;

    /** La main du fruit (la gauche pour la lave) : la main principale ou l'autre, selon le bras du joueur. */
    private static InteractionHand mainDuFruit(Player joueur, boolean glace) {
        HumanoidArm bras = glace ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        return joueur.getMainArm() == bras ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    // ================================================================ le panneau, et le bord de l'écran quand on mange

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        Repas r = REPAS.get(mc.player.getUUID());
        if (r != null && secondes(r.debut) < DUREE_REPAS) {
            bord(g, glace(r.sorte), secondes(r.debut));
        }
        String sorte = EVEILLES.get(mc.player.getUUID());
        if (sorte != null) {
            panneau(g, mc.font, glace(sorte), niveau(mc.player.getUUID()));
        }
    }

    /** Le bord givré (la neige poudreuse de Minecraft) ou brûlant (un rougeoiement et des braises qui montent). */
    private static void bord(GuiGraphics g, boolean glace, double t) {
        float a = (float) Math.min(1, Math.min(t / 0.35, (DUREE_REPAS - t) / 0.6));
        int w = g.guiWidth(), h = g.guiHeight();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (glace) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            g.setColor(0.85F, 0.95F, 1F, a);
            g.blit(NEIGE_POUDREUSE, 0, 0, -90, 0, 0, w, h, w, h);
            g.setColor(1, 1, 1, 1);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        } else {
            int bande = w / 7;
            for (int i = 0; i < bande; i += 2) {
                float k = 1 - i / (float) bande;
                int c = ((int) (a * 170 * k * k) << 24) | 0xE8400C;
                g.fill(i, 0, i + 2, h, c);
                g.fill(w - i - 2, 0, w - i, h, c);
            }
            int haut = h / 6;
            g.fillGradient(0, 0, w, haut, ((int) (a * 150) << 24) | 0xE8400C, 0x00E8400C);
            g.fillGradient(0, h - haut, w, h, 0x00FF7A10, ((int) (a * 170) << 24) | 0xFF7A10);
            // des braises qui montent
            for (int k = 0; k < 40; k++) {
                double vitesse = 40 + (k * 37 % 50);
                int x = (int) ((k * 97 % 100) / 100.0 * w);
                int y = (int) (h - ((t * vitesse + k * 53) % (h * 0.7)));
                int couleur = ((int) (a * 230) << 24) | (k % 3 == 0 ? 0xFFD850 : 0xFF7A10);
                g.fill(x, y, x + 2, y + 2, couleur);
            }
        }
        RenderSystem.disableBlend();
    }

    private static void panneau(GuiGraphics g, Font font, boolean glace, int niveau) {
        long maintenant = System.nanoTime();
        double jet = Math.max(0, (pretJet - maintenant) / 1e9), choc = Math.max(0, (pretChoc - maintenant) / 1e9);
        boolean fort = niveau >= 2;
        String gauche = fort ? (glace ? "Arme +4 dégâts, le givre s'étend" : "Arme +4 dégâts, éclabousse")
                : glace ? "Arme +2 dégâts, ralentit" : "Arme +2 dégâts, enflamme";
        String droit = fort ? (glace ? "Lances de glace" : "Grand poing de magma") : glace ? "Jet de glace" : "Jet de lave";
        String shift = fort ? (glace ? "Faisan de glace" : "Pluie de météores") : glace ? "Ère glaciaire" : "Éruption";
        String[][] lignes = {
                {"Clic gauche", gauche},
                {"Clic droit", droit + (jet > 0 ? "  " + (int) Math.ceil(jet) + " s" : "")},
                {"Shift", shift + (choc > 0 ? "  " + (int) Math.ceil(choc) + " s" : "")},
        };
        boolean[] gris = {false, jet > 0, choc > 0};
        String titre = (glace ? "Hie Hie no Mi" : "Magu Magu no Mi") + (fort ? "  ·  niveau 2" : "");
        int teinte = glace ? 0xFF6FE4FF : 0xFFFF8A1E;
        int largeurTouche = 0, largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        int pw = largeurTouche + largeurAction + 26, ph = 22 + lignes.length * 13;
        int x = 6, y = g.guiHeight() / 2 - ph / 2;
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.length; i++) {
            int ly = y + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes[i][0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes[i][0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes[i][1], x + 18 + largeurTouche, ly, gris[i] ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
    }

    // ================================================================ dessins dans le monde

    private static final class Types extends RenderType {
        /** La glace, la lave, ses fissures et ses geysers : translucides, sans ombre (la lave brille d'elle-même). */
        static final RenderType VOILE = create("cabane_fruit_voile", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 65536,
                false, true, CompositeState.builder().setShaderState(POSITION_COLOR_SHADER).setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL).createCompositeState(false));

        private Types() {
            super("cabane_fruit_rien", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256, false, false, () -> { }, () -> { });
        }
    }

    private static void sommet(VertexConsumer c, Matrix4f m, Vec3 p, float r, float g, float b, float a) {
        c.addVertex(m, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }

    private static void quad(VertexConsumer c, Matrix4f m, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float r, float g, float b, float a) {
        sommet(c, m, p0, r, g, b, a);
        sommet(c, m, p1, r, g, b, a);
        sommet(c, m, p2, r, g, b, a);
        sommet(c, m, p3, r, g, b, a);
    }

    /** Un petit cube tourné sur lui-même (une goutte de lave, un bout du geyser). */
    private static void cube(VertexConsumer c, Matrix4f m, Vec3 o, double s, double angle, float r, float g, float b, float a) {
        Vec3 x = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(s), z = new Vec3(-Math.sin(angle), 0, Math.cos(angle)).scale(s);
        Vec3 y = new Vec3(0, s, 0);
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = o.add(x.scale((i & 1) == 0 ? -1 : 1)).add(y.scale((i & 2) == 0 ? -1 : 1)).add(z.scale((i & 4) == 0 ? -1 : 1));
        }
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        float[] k = {1F, 0.6F, 0.85F, 0.85F, 0.75F, 0.75F};
        for (int f = 0; f < 6; f++) {
            quad(c, m, p[faces[f][0]], p[faces[f][1]], p[faces[f][2]], p[faces[f][3]], r * k[f], g * k[f], b * k[f], a);
        }
    }

    /** Une pointe à base carrée, de {@code base} vers {@code dir} (un pic de glace, un éclat) ; plus claire au bout. */
    private static void pointe(VertexConsumer c, Matrix4f m, Vec3 base, Vec3 dir, double longueur, double largeur, float a) {
        Vec3 d = dir.normalize();
        Vec3 e1 = Math.abs(d.y) < 0.9 ? d.cross(new Vec3(0, 1, 0)).normalize() : d.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 e2 = d.cross(e1).normalize();
        Vec3 bout = base.add(d.scale(longueur));
        Vec3[] coins = {base.add(e1.add(e2).scale(largeur / 2)), base.add(e1.subtract(e2).scale(largeur / 2)),
                base.add(e1.scale(-1).subtract(e2).scale(largeur / 2)), base.add(e2.subtract(e1).scale(largeur / 2))};
        for (int i = 0; i < 4; i++) {
            float k = 0.82F + 0.18F * (i % 2);
            Vec3 p = coins[i], q = coins[(i + 1) % 4];
            sommet(c, m, p, 0.62F * k, 0.84F * k, 1F * k, a);
            sommet(c, m, q, 0.62F * k, 0.84F * k, 1F * k, a);
            sommet(c, m, bout, 0.96F, 0.99F, 1F, a);
            sommet(c, m, bout, 0.96F, 0.99F, 1F, a);
        }
    }

    /** Le bloc de glace autour de qui est gelé : des faces bleutées, et des arêtes blanches. */
    private static void blocDeGlace(VertexConsumer c, Matrix4f m, AABB b) {
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
        }
        int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 1, 3, 2}, {4, 5, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        for (int[] f : faces) {
            quad(c, m, p[f[0]], p[f[1]], p[f[2]], p[f[3]], 0.68F, 0.86F, 1F, 0.32F);
        }
        int[][] aretes = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] ar : aretes) {
            Vec3 a = p[ar[0]], z = p[ar[1]], milieu = a.add(z).scale(0.5);
            Vec3 d = z.subtract(a), e = Math.abs(d.normalize().y) > 0.9 ? new Vec3(0.04, 0, 0.04) : new Vec3(0, 0.04, 0);
            Vec3 v = milieu.subtract(b.getCenter()).normalize().scale(0.02);
            quad(c, m, a.add(e).add(v), z.add(e).add(v), z.subtract(e).add(v), a.subtract(e).add(v), 0.95F, 0.98F, 1F, 0.85F);
        }
    }

    private static void dessiner(PoseStack pose, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Matrix4f m = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lueur = buffers.getBuffer(Types.VOILE);
        // les jets de lave : un flot de magma qui grossit, qui file vers la cible puis s'éteint
        for (Jet j : JETS) {
            if (j.glace) {
                continue;
            }
            double t = secondes(j.debut), front = Math.min(1, t / DUREE_JET), fin = Math.max(0, (t - DUREE_JET) / 0.35);
            Vec3 d = j.a.subtract(j.de);
            int n = (int) Math.max(4, d.length() * 4);
            for (int i = 0; i <= n; i++) {
                double s = i / (double) n;
                if (s > front || s < fin) {
                    continue;
                }
                Vec3 p = j.de.add(d.scale(s)).add(Math.sin(i * 1.7 + t * 9) * 0.06 * s, Math.cos(i * 2.3 + t * 7) * 0.06 * s, 0).subtract(cam);
                float k = (float) s;
                cube(lueur, m, p, 0.1 + 0.2 * s, i * 0.7 + t * 4, 1F, 0.85F - 0.45F * k, 0.32F - 0.28F * k, 0.95F);
            }
        }
        // les éruptions : des fissures qui brillent au sol, et sept geysers de lave
        for (Onde o : ONDES) {
            if (o.glace()) {
                continue;
            }
            double t = secondes(o.debut());
            float fondu = (float) Math.max(0, 1 - t / DUREE_ONDE);
            for (int k = 0; k < 7; k++) {
                double angle = k * Math.PI * 2 / 7 + (o.graine() & 7) * 0.3;
                Vec3 avant = null;
                for (double r = 0.6; r <= 5; r += 0.4) {
                    double ecart = 0.3 * Math.sin(r * 2.1 + k * 1.3);
                    Vec3 p = o.centre().add(Math.cos(angle) * r - Math.sin(angle) * ecart, 0.04, Math.sin(angle) * r + Math.cos(angle) * ecart).subtract(cam);
                    if (avant != null) {
                        Vec3 cote = p.subtract(avant).cross(new Vec3(0, 1, 0)).normalize().scale(0.08);
                        quad(lueur, m, avant.add(cote), p.add(cote), p.subtract(cote), avant.subtract(cote), 1F, 0.62F, 0.12F, 0.95F * fondu);
                    }
                    avant = p;
                }
                double h = 3.4 * Math.min(1, t / 0.25) * (t > 0.9 ? Math.max(0, 1 - (t - 0.9) / 0.6) : 1);
                Vec3 base = o.centre().add(Math.cos(angle) * 5, 0, Math.sin(angle) * 5).subtract(cam);
                for (double y = 0; y < h; y += 0.3) {
                    double s = y / 3.4;
                    Vec3 p = base.add(Math.sin(y * 3 + t * 8 + k) * 0.12, y, Math.cos(y * 2.6 + t * 7 + k) * 0.12);
                    cube(lueur, m, p, 0.3 * (1 - 0.55 * s), y * 1.3 + t * 3, 1F, (float) (0.86 - 0.5 * s), (float) (0.3 - 0.25 * s), 1F);
                }
            }
        }
        VertexConsumer voile = lueur;
        // les jets de glace : des éclats qui filent tout droit
        for (Jet j : JETS) {
            if (!j.glace) {
                continue;
            }
            double t = secondes(j.debut), front = Math.min(1, t / DUREE_JET);
            float a = (float) Math.max(0, 1 - Math.max(0, t - DUREE_JET) / 0.35);
            Vec3 d = j.a.subtract(j.de), dir = d.normalize();
            Vec3 e1 = Math.abs(dir.y) < 0.9 ? dir.cross(new Vec3(0, 1, 0)).normalize() : new Vec3(1, 0, 0), e2 = dir.cross(e1);
            int n = (int) Math.max(4, d.length() * 2.5);
            for (int i = 0; i < n; i++) {
                double s = (i + 0.5) / n;
                if (s > front) {
                    continue;
                }
                double r = 0.05 + 0.3 * s, ang = i * 2.4;
                Vec3 p = j.de.add(d.scale(s)).add(e1.scale(Math.cos(ang) * r)).add(e2.scale(Math.sin(ang) * r)).subtract(cam);
                pointe(voile, m, p, dir.add(e1.scale(Math.cos(ang) * 0.12)).add(e2.scale(Math.sin(ang) * 0.12)), 0.45 + 0.35 * s, 0.1 + 0.12 * s, 0.85F * a);
            }
        }
        // les ères glaciaires : le sol qui gèle, et des pics de glace qui jaillissent tout autour
        for (Onde o : ONDES) {
            if (!o.glace()) {
                continue;
            }
            double t = secondes(o.debut());
            float fondu = (float) Math.max(0, Math.min(1, (DUREE_ONDE - t) / 0.5));
            double rayon = 5.2 * Math.min(1, t / 0.2);
            Vec3 c0 = o.centre().add(0, 0.03, 0).subtract(cam);
            for (int i = 0; i < 32; i++) {
                double a0 = i * Math.PI * 2 / 32, a1 = (i + 1) * Math.PI * 2 / 32;
                quad(voile, m, c0, c0, c0.add(Math.cos(a0) * rayon, 0, Math.sin(a0) * rayon), c0.add(Math.cos(a1) * rayon, 0, Math.sin(a1) * rayon),
                        0.86F, 0.95F, 1F, 0.55F * fondu);
            }
            RandomSource graine = RandomSource.create(o.graine());
            double monte = Math.min(1, t / 0.15) * fondu;
            for (int k = 0; k < 20; k++) {
                double angle = k * Math.PI * 2 / 20 + graine.nextDouble() * 0.3, r = 1.3 + graine.nextDouble() * 3.8;
                double h = (0.8 + graine.nextDouble() * 1.8) * (1 - Math.abs(r - 3.5) / 5) * monte;
                Vec3 base = o.centre().add(Math.cos(angle) * r, -0.1, Math.sin(angle) * r).subtract(cam);
                pointe(voile, m, base, new Vec3(Math.cos(angle) * 0.4, 1, Math.sin(angle) * 0.4), h, 0.35 + 0.3 * graine.nextDouble(), 0.8F);
            }
        }
        // les tirs de l'éveil 2
        for (Vol v : VOLS.values()) {
            Vec3 p = v.pos(mc, partiel).subtract(cam);
            double t = v.age(mc, partiel);
            switch (v.sorte) {
                case Fruit.POING -> poing(voile, m, p, v.dir, t);
                case Fruit.LANCE -> pointe(voile, m, p.subtract(v.dir.scale(0.9)), v.dir, 1.9, 0.26, 0.9F);
                case Fruit.METEORE -> meteore(voile, m, p, v.dir, t, v.graine);
                default -> faisan(voile, m, p, v.dir, t);
            }
        }
        // la pluie de météores : la zone qui rougeoie au sol, le nuage sombre au-dessus, rouge par-dessous
        for (Zone z : ZONES) {
            double t = secondes(z.debut());
            float a = (float) Math.max(0, Math.min(1, Math.min(t / 0.3, (DUREE_ZONE - t) / 0.5)));
            float pouls = (float) (0.75 + 0.25 * Math.sin(t * 9));
            Vec3 c0 = z.centre().add(0, 0.06, 0).subtract(cam);
            Vec3 ciel = z.centre().add(-6.5, 31, -3.5).subtract(cam);
            for (int i = 0; i < 48; i++) {
                double a0 = i * Math.PI * 2 / 48, a1 = (i + 1) * Math.PI * 2 / 48;
                Vec3 i0 = c0.add(Math.cos(a0) * 6.2, 0, Math.sin(a0) * 6.2), i1 = c0.add(Math.cos(a1) * 6.2, 0, Math.sin(a1) * 6.2);
                Vec3 o0 = c0.add(Math.cos(a0) * 6.7, 0, Math.sin(a0) * 6.7), o1 = c0.add(Math.cos(a1) * 6.7, 0, Math.sin(a1) * 6.7);
                quad(voile, m, i0, i1, o1, o0, 1F, 0.3F * pouls, 0.05F, 0.85F * a);
                quad(voile, m, c0, c0, i0, i1, 1F, 0.25F, 0.05F, 0.14F * a);
            }
            // le nuage : des volutes de fumée noire, en volume, et la lave qui rougeoie entre elles, par-dessous
            RandomSource g = RandomSource.create(z.debut());
            double gonfle = Math.min(1, t / 0.6);
            for (int k = 0; k < 40; k++) {
                double an = g.nextDouble() * Math.PI * 2, rr = Math.sqrt(g.nextDouble()) * 9.5;
                Vec3 q = ciel.add(Math.cos(an) * rr, (g.nextDouble() - 0.3) * 2.6 + Math.sin(t * 1.5 + k) * 0.15, Math.sin(an) * rr);
                float gris = 0.1F + 0.12F * g.nextFloat();
                cube(voile, m, q, (1 + g.nextDouble() * 1.7) * (0.4 + 0.6 * gonfle), an + t * 0.15, gris, gris * 0.85F, gris * 0.8F, 0.92F * a);
            }
            for (int k = 0; k < 16; k++) {
                double an = g.nextDouble() * Math.PI * 2, rr = Math.sqrt(g.nextDouble()) * 8;
                Vec3 q = ciel.add(Math.cos(an) * rr, -1.4 - g.nextDouble() * 0.6, Math.sin(an) * rr);
                cube(voile, m, q, 0.5 + g.nextDouble() * 0.6, an + t, 1F, (0.35F + 0.25F * g.nextFloat()) * pouls, 0.05F, 0.9F * a);
            }
        }
        // les cratères : une flaque de magma qui refroidit, et l'onde de choc qui part
        for (Cratere k : CRATERES) {
            double t = secondes(k.debut());
            float chaud = (float) Math.max(0, 1 - t / 2.6);
            Vec3 c0 = k.pos().add(0, 0.05, 0).subtract(cam);
            for (int i = 0; i < 16; i++) {
                double a0 = i * Math.PI * 2 / 16, a1 = (i + 1) * Math.PI * 2 / 16;
                double r0 = k.taille() * (0.75 + 0.3 * Math.sin(a0 * 3 + k.graine())), r1 = k.taille() * (0.75 + 0.3 * Math.sin(a1 * 3 + k.graine()));
                quad(voile, m, c0, c0, c0.add(Math.cos(a0) * r0, 0, Math.sin(a0) * r0), c0.add(Math.cos(a1) * r1, 0, Math.sin(a1) * r1),
                        1F, 0.3F + 0.55F * chaud, 0.04F + 0.25F * chaud, 0.92F * chaud);
            }
            if (t < 0.45) {
                double rr = 0.5 + t / 0.45 * 5;
                float al = (float) (0.6 * (1 - t / 0.45));
                for (int i = 0; i < 32; i++) {
                    double a0 = i * Math.PI * 2 / 32, a1 = (i + 1) * Math.PI * 2 / 32;
                    quad(voile, m, c0.add(Math.cos(a0) * rr, 0.1, Math.sin(a0) * rr), c0.add(Math.cos(a1) * rr, 0.1, Math.sin(a1) * rr),
                            c0.add(Math.cos(a1) * (rr + 0.6), 0.1, Math.sin(a1) * (rr + 0.6)), c0.add(Math.cos(a0) * (rr + 0.6), 0.1, Math.sin(a0) * (rr + 0.6)),
                            1F, 0.78F, 0.45F, al);
                }
            }
        }
        // le sillage du faisan : une large bande de givre
        for (Bande b : BANDES) {
            float a = (float) Math.max(0, Math.min(1, (2.6 - secondes(b.debut())) / 0.7));
            Vec3 l = b.cote().scale(1.6);
            quad(voile, m, b.a().add(l).subtract(cam), b.b().add(l).subtract(cam), b.b().subtract(l).subtract(cam), b.a().subtract(l).subtract(cam),
                    0.88F, 0.96F, 1F, 0.6F * a);
        }
        // les éclats du faisan qui se brise
        for (Eclat e : BRISURES) {
            double t = secondes(e.debut());
            Vec3 p = e.pos().add(e.vitesse().scale(t)).add(0, -9 * t * t, 0);
            Vec3 v = e.vitesse().add(0, -18 * t, 0);
            pointe(voile, m, p.subtract(cam), v, 0.9, 0.22, (float) Math.max(0, 0.9 - t));
        }
        // le sillage gelé du faisan : des pics qui jaillissent puis fondent
        for (Pic pic : PICS) {
            double age = secondes(pic.debut());
            double h = pic.taille() * Math.min(1, age / 0.15) * Math.max(0, Math.min(1, (2.2 - age) / 0.5));
            if (h > 0.02) {
                pointe(voile, m, pic.pos().subtract(cam), new Vec3(0, 1, 0), h, 0.3 + 0.12 * pic.taille(), 0.8F);
            }
        }
        // ceux qui sont pris dans la glace
        for (Map.Entry<Integer, Long> g : GELES.entrySet()) {
            Entity e = mc.level.getEntity(g.getKey());
            if (e == null) {
                continue;
            }
            AABB b = e.getBoundingBox().move(e.getPosition(partiel).subtract(e.position())).inflate(0.22, 0.12, 0.22).move(cam.scale(-1));
            blocDeGlace(voile, m, b);
        }
        buffers.endBatch(Types.VOILE);
    }

    /** Un repère qui regarde vers f : d sur le côté, h vers le haut. */
    private static Vec3[] repere(Vec3 dir) {
        Vec3 f = dir.normalize();
        Vec3 d = Math.abs(f.y) < 0.95 ? f.cross(new Vec3(0, 1, 0)).normalize() : new Vec3(1, 0, 0);
        return new Vec3[] {f, d, d.cross(f).normalize()};
    }

    /** Le grand poing de magma : un poing fermé, quatre phalanges devant, des plaques de croûte, des gouttes derrière. */
    private static void poing(VertexConsumer c, Matrix4f m, Vec3 p, Vec3 dir, double t) {
        Vec3[] r = repere(dir);
        Vec3 f = r[0], d = r[1], h = r[2];
        double angle = Math.atan2(f.z, f.x);
        cube(c, m, p, 0.62, angle, 1F, 0.42F, 0.08F, 1F);
        for (int k = 0; k < 4; k++) {
            cube(c, m, p.add(f.scale(0.62)).add(d.scale((k - 1.5) * 0.3)).add(h.scale(0.22)), 0.17, angle, 1F, 0.78F, 0.25F, 1F);
        }
        cube(c, m, p.add(f.scale(0.3)).add(d.scale(0.62)).add(h.scale(-0.2)), 0.2, angle, 1F, 0.6F, 0.15F, 1F);
        for (int k = 0; k < 7; k++) {
            Vec3 q = p.add(d.scale(Math.sin(k * 2.7) * 0.5)).add(h.scale(Math.cos(k * 1.9) * 0.5)).add(f.scale(Math.sin(k * 3.1) * 0.4));
            cube(c, m, q, 0.2, angle + k, 0.28F, 0.09F, 0.05F, 1F);
        }
        for (int k = 1; k <= 6; k++) {
            Vec3 q = p.subtract(f.scale(0.5 + k * 0.42)).add(d.scale(Math.sin(t * 0.8 + k) * 0.12)).add(h.scale(Math.cos(t * 0.7 + k) * 0.12));
            float u = k / 6F;
            cube(c, m, q, 0.34 - 0.035 * k, t * 0.3 + k, 1F, 0.85F - 0.5F * u, 0.3F - 0.25F * u, 1F);
        }
    }

    /** Une météore : une grosse roche de magma (un cœur qui brille sous des plaques de croûte), et sa longue traînée de feu. */
    private static void meteore(VertexConsumer c, Matrix4f m, Vec3 p, Vec3 dir, double t, int graine) {
        Vec3[] r = repere(dir);
        Vec3 f = r[0], d = r[1], h = r[2];
        double tourne = t * 0.25 + graine;
        cube(c, m, p, 0.85, tourne, 1F, 0.55F, 0.1F, 1F);
        for (int k = 0; k < 9; k++) {
            Vec3 q = p.add(d.scale(Math.sin(k * 2.1 + graine) * 0.62)).add(h.scale(Math.cos(k * 1.7 + graine) * 0.62)).add(f.scale(Math.sin(k * 2.9 + graine) * 0.5));
            cube(c, m, q, 0.42, tourne + k, 0.22F, 0.07F, 0.04F, 1F);
        }
        for (int k = 1; k <= 12; k++) {
            float u = k / 12F;
            Vec3 q = p.subtract(f.scale(0.6 + k * 0.7)).add(d.scale(Math.sin(t * 0.9 + k) * 0.15 * u)).add(h.scale(Math.cos(t * 0.8 + k) * 0.15 * u));
            cube(c, m, q, 0.75 - 0.05 * k, t * 0.4 + k, 1F, 0.95F - 0.6F * u, 0.45F - 0.4F * u, 1F - 0.5F * u);
        }
    }

    /**
     * Le faisan de glace, géant (près de 12 blocs d'envergure) : un corps de glace, une tête à crête et son bec, deux
     * grandes ailes à deux rangs de plumes qui battent, une longue queue.
     */
    private static void faisan(VertexConsumer c, Matrix4f m, Vec3 p, Vec3 dir, double t) {
        Vec3[] r = repere(dir);
        Vec3 f = r[0], d = r[1], h = r[2];
        double angle = Math.atan2(f.z, f.x), k0 = 1.8;
        for (int k = -1; k <= 1; k++) {
            cube(c, m, p.add(f.scale(k * 0.55 * k0)), (0.5 - 0.07 * Math.abs(k)) * k0, angle, 0.55F, 0.78F, 1F, 0.92F);
        }
        Vec3 tete = p.add(f.scale(1.15 * k0)).add(h.scale(0.35 * k0));
        cube(c, m, tete, 0.3 * k0, angle, 0.7F, 0.88F, 1F, 0.95F);
        pointe(c, m, tete.add(f.scale(0.25 * k0)), f.add(h.scale(-0.2)), 0.8 * k0, 0.22 * k0, 0.95F);
        for (int k = 0; k < 3; k++) {
            pointe(c, m, tete.add(h.scale(0.2 * k0)), h.add(f.scale(-0.8 - 0.3 * k)).add(d.scale((k - 1) * 0.3)), (0.7 + 0.15 * k) * k0, 0.12 * k0, 0.9F);
        }
        double bat = 0.45 * Math.sin(t * 0.55);
        for (int cote = -1; cote <= 1; cote += 2) {
            for (int couche = 0; couche < 2; couche++) {
                for (int k = 0; k < 8; k++) {
                    Vec3 base = p.add(d.scale(cote * 0.5 * k0)).add(f.scale((0.5 - k * 0.22) * k0)).add(h.scale((0.15 - couche * 0.1) * k0));
                    Vec3 vers = d.scale(cote).add(h.scale(0.25 + bat - 0.03 * k - couche * 0.12)).add(f.scale(-0.1 - 0.14 * k - couche * 0.1));
                    pointe(c, m, base, vers, (3.6 - 0.26 * k - couche * 0.8) * k0, (0.55 - 0.03 * k) * k0, couche == 0 ? 0.96F : 0.88F);
                }
            }
        }
        for (int k = -2; k <= 2; k++) {
            pointe(c, m, p.subtract(f.scale(0.8 * k0)).add(d.scale(k * 0.16 * k0)), f.scale(-1).add(h.scale(0.04 + 0.03 * Math.sin(t * 0.4 + k))).add(d.scale(k * 0.1)),
                    (3 + (2 - Math.abs(k)) * 0.7) * k0, 0.32 * k0, 0.95F);
        }
    }

    // ================================================================ les particules

    /** Au niveau 2 : des flammes et de la fumée sortent de l'épaule et de la tête (ou du givre flotte autour). */
    private static void particulesMoitie(Minecraft mc, Player j, boolean glace) {
        float lacet = j.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 cote = new Vec3(-Mth.cos(lacet), 0, -Mth.sin(lacet)).scale(glace ? 0.28 : -0.28);
        Vec3 p = j.position().add(cote).add(0, (j.isCrouching() ? 1.15 : 1.35) + HASARD.nextDouble() * 0.5, 0);
        if (glace) {
            if (HASARD.nextInt(2) == 0) {
                mc.level.addParticle(ParticleTypes.SNOWFLAKE, p.x + aleat(0.3), p.y + aleat(0.4), p.z + aleat(0.3), aleat(0.01), -0.01, aleat(0.01));
            }
        } else {
            mc.level.addParticle(ParticleTypes.FLAME, p.x + aleat(0.15), p.y, p.z + aleat(0.15), 0, 0.03, 0);
            if (HASARD.nextInt(5) == 0) {
                mc.level.addParticle(ParticleTypes.SMOKE, p.x, p.y + 0.5, p.z, 0, 0.04, 0);
            }
        }
    }

    /** Les tirs en vol : du feu et de la fumée derrière le poing et les météores, de la neige derrière les lances ; le faisan laisse son sillage gelé. */
    private static void particulesVols(Minecraft mc) {
        long maintenant = System.nanoTime();
        for (Vol v : VOLS.values()) {
            Vec3 p = v.pos(mc, 0);
            switch (v.sorte) {
                case Fruit.POING, Fruit.METEORE -> {
                    for (int i = 0; i < 3; i++) {
                        mc.level.addParticle(ParticleTypes.FLAME, p.x + aleat(0.4), p.y + aleat(0.4), p.z + aleat(0.4), 0, 0.02, 0);
                    }
                    mc.level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.4, p.z, 0, 0.04, 0);
                }
                case Fruit.LANCE -> mc.level.addParticle(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 0, 0, 0);
                default -> {
                    for (int i = 0; i < 10; i++) {
                        mc.level.addParticle(ParticleTypes.SNOWFLAKE, p.x + aleat(4), p.y + aleat(1.2), p.z + aleat(4), 0, -0.02, 0);
                    }
                    Vec3[] r = repere(v.dir);
                    double y = sol(mc, p);
                    for (int cote = -1; cote <= 1; cote += 2) {
                        for (int k = 0; k < 2; k++) {
                            Vec3 bord = p.add(r[1].scale(cote * (1.2 + HASARD.nextDouble() * 1.6)));
                            PICS.add(new Pic(new Vec3(bord.x, y - 0.2, bord.z), maintenant, 0.8 + HASARD.nextDouble() * 1.6));
                        }
                    }
                    Vec3 ici = new Vec3(p.x, y + 0.04, p.z);
                    Vec3 avant = DERNIER_SOL.put(id(v), ici);
                    if (avant != null && avant.distanceTo(ici) < 4) {
                        BANDES.add(new Bande(avant, ici, new Vec3(r[1].x, 0, r[1].z).normalize(), maintenant));
                    }
                    for (int i = 0; i < 4; i++) {
                        mc.level.addParticle(ParticleTypes.END_ROD, p.x + aleat(3), p.y + aleat(1), p.z + aleat(3), 0, -0.01, 0);
                    }
                }
            }
        }
        VOLS.values().removeIf(v -> v.age(mc, 0) > 400 || v.vitesse * v.age(mc, 0) > v.portee + 8);
        PICS.removeIf(pic -> secondes(pic.debut()) > 2.3);
        // le nuage de la pluie : de la fumée, des gouttes de lave qui tombent, des cendres dans la zone
        for (Zone z : ZONES) {
            Vec3 ciel = z.centre().add(-6.5, 31, -3.5);
            for (int i = 0; i < 3; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2, r = HASARD.nextDouble() * 9;
                mc.level.addParticle(ParticleTypes.LARGE_SMOKE, ciel.x + Math.cos(a) * r, ciel.y + 1, ciel.z + Math.sin(a) * r, 0, 0.05, 0);
                mc.level.addParticle(ParticleTypes.FALLING_LAVA, ciel.x + Math.cos(a) * r * 0.8, ciel.y - 0.3, ciel.z + Math.sin(a) * r * 0.8, 0, 0, 0);
            }
            for (int i = 0; i < 4; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2, r = HASARD.nextDouble() * 6.5;
                mc.level.addParticle(ParticleTypes.ASH, z.centre().x + Math.cos(a) * r, z.centre().y + 0.5 + HASARD.nextDouble() * 4, z.centre().z + Math.sin(a) * r, 0, 0, 0);
            }
        }
        ZONES.removeIf(z -> secondes(z.debut()) > DUREE_ZONE);
        CRATERES.removeIf(k -> secondes(k.debut()) > 2.7);
        BANDES.removeIf(b -> secondes(b.debut()) > 2.6);
        BRISURES.removeIf(e -> secondes(e.debut()) > 0.9);
    }

    /** Autour de la main du fruit : des flammes, des gouttes de lave ; ou des flocons et un peu de givre. */
    private static void particulesMain(Minecraft mc, Player j, boolean glace) {
        Vec3 p = main(j, glace, 1);
        if (glace) {
            if (HASARD.nextInt(2) == 0) {
                mc.level.addParticle(ParticleTypes.SNOWFLAKE, p.x + aleat(0.25), p.y + aleat(0.2), p.z + aleat(0.25), aleat(0.01), 0.005, aleat(0.01));
            }
            if (HASARD.nextInt(3) == 0) {
                mc.level.addParticle(Fruit.poussiere(true, 0.6F), p.x + aleat(0.18), p.y + aleat(0.15), p.z + aleat(0.18), 0, 0, 0);
            }
        } else {
            mc.level.addParticle(ParticleTypes.SMALL_FLAME, p.x + aleat(0.12), p.y + 0.05 + HASARD.nextDouble() * 0.15, p.z + aleat(0.12), 0, 0.02, 0);
            if (HASARD.nextInt(4) == 0) {
                mc.level.addParticle(ParticleTypes.FLAME, p.x + aleat(0.1), p.y + 0.12, p.z + aleat(0.1), 0, 0.03, 0);
            }
            if (HASARD.nextInt(12) == 0) {
                mc.level.addParticle(ParticleTypes.FALLING_LAVA, p.x + aleat(0.08), p.y - 0.18, p.z + aleat(0.08), 0, 0, 0);
            }
            if (HASARD.nextInt(14) == 0) {
                mc.level.addParticle(ParticleTypes.SMOKE, p.x, p.y + 0.3, p.z, 0, 0.03, 0);
            }
        }
    }

    /** Le pouvoir qui monte en lui (les 3 s du repas) : une spirale de givre ou de braises, du sol jusqu'au-dessus de sa tête. */
    private static void spirale(Minecraft mc, Player j, Repas r) {
        boolean glace = glace(r.sorte);
        double t = secondes(r.debut);
        if (t >= DUREE_REPAS) {
            return;
        }
        double u = t / DUREE_REPAS;
        for (int k = 0; k < 2; k++) {
            double a = t * 7 + k * Math.PI, rayon = 1.15 - 0.4 * u, y = 0.1 + u * 2.4;
            double x = j.getX() + Math.cos(a) * rayon, z = j.getZ() + Math.sin(a) * rayon;
            if (glace) {
                mc.level.addParticle(ParticleTypes.SNOWFLAKE, x, j.getY() + y, z, 0, 0.01, 0);
                mc.level.addParticle(Fruit.poussiere(true, 1.2F), x, j.getY() + y, z, 0, 0, 0);
            } else {
                mc.level.addParticle(ParticleTypes.FLAME, x, j.getY() + y, z, 0, 0.01, 0);
                mc.level.addParticle(Fruit.poussiere(false, 1.2F), x, j.getY() + y, z, 0, 0, 0);
            }
        }
        double avant = r.avant;
        r.avant = t;
        // le cœur qui bat, puis le pouvoir qui éclate en lui
        if (avant < 0.9 && t >= 0.9 || avant < 1.8 && t >= 1.8) {
            son(j, SoundEvents.WARDEN_HEARTBEAT, 1.6F, 0.9F);
        }
        if (avant < 2.6 && t >= 2.6) {
            son(j, glace ? SoundEvents.GLASS_BREAK : SoundEvents.FIRECHARGE_USE, 1.2F, 0.6F);
            son(j, SoundEvents.PLAYER_LEVELUP, 0.6F, glace ? 1.4F : 0.8F);
            for (int i = 0; i < 40; i++) {
                double a = HASARD.nextDouble() * Math.PI * 2;
                mc.level.addParticle(glace ? ParticleTypes.SNOWFLAKE : ParticleTypes.FLAME, j.getX(), j.getY() + 1, j.getZ(),
                        Math.cos(a) * 0.25, aleat(0.15), Math.sin(a) * 0.25);
            }
        }
    }

    /** Au bout de chaque jet : ce qui suit la tête du jet, puis l'éclaboussure sur la cible. */
    private static void particulesJet(Minecraft mc, Jet j) {
        double t = secondes(j.debut), front = Math.min(1, t / DUREE_JET);
        Vec3 p = j.de.add(j.a.subtract(j.de).scale(front));
        if (!j.touche) {
            for (int i = 0; i < 3; i++) {
                if (j.glace) {
                    mc.level.addParticle(ParticleTypes.SNOWFLAKE, p.x + aleat(0.2), p.y + aleat(0.2), p.z + aleat(0.2), 0, 0, 0);
                } else {
                    mc.level.addParticle(ParticleTypes.FLAME, p.x + aleat(0.2), p.y + aleat(0.2), p.z + aleat(0.2), 0, 0.02, 0);
                }
            }
            if (!j.glace && HASARD.nextInt(2) == 0) {
                mc.level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.4, p.z, 0, 0.03, 0);
            }
        }
        if (front >= 1 && !j.touche) {
            j.touche = true;
            for (int i = 0; i < 18; i++) {
                if (j.glace) {
                    mc.level.addParticle(i % 2 == 0 ? new BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()) : ParticleTypes.SNOWFLAKE,
                            j.a.x, j.a.y, j.a.z, aleat(0.2), HASARD.nextDouble() * 0.2, aleat(0.2));
                } else {
                    mc.level.addParticle(i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, j.a.x, j.a.y, j.a.z, aleat(0.12), HASARD.nextDouble() * 0.15, aleat(0.12));
                }
            }
        }
    }

    /** Les geysers crachent de la lave et fument ; l'ère glaciaire fait voler un peu de neige. */
    private static void particulesOnde(Minecraft mc, Onde o) {
        double t = secondes(o.debut());
        if (t > 1.1) {
            return;
        }
        for (int k = 0; k < 7; k++) {
            if (o.glace()) {
                if (HASARD.nextInt(3) == 0) {
                    double a = HASARD.nextDouble() * Math.PI * 2, r = 1 + HASARD.nextDouble() * 4;
                    mc.level.addParticle(ParticleTypes.SNOWFLAKE, o.centre().x + Math.cos(a) * r, o.centre().y + 0.3 + HASARD.nextDouble() * 1.5,
                            o.centre().z + Math.sin(a) * r, 0, 0.02, 0);
                }
                continue;
            }
            double angle = k * Math.PI * 2 / 7 + (o.graine() & 7) * 0.3;
            double x = o.centre().x + Math.cos(angle) * 5, z = o.centre().z + Math.sin(angle) * 5;
            mc.level.addParticle(ParticleTypes.FLAME, x + aleat(0.25), o.centre().y + HASARD.nextDouble() * 3, z + aleat(0.25), 0, 0.08, 0);
            if (HASARD.nextInt(3) == 0) {
                mc.level.addParticle(ParticleTypes.LAVA, x, o.centre().y + 3, z, 0, 0, 0);
            }
            if (HASARD.nextInt(3) == 0) {
                mc.level.addParticle(ParticleTypes.LARGE_SMOKE, x + aleat(0.3), o.centre().y + 3.6, z + aleat(0.3), 0, 0.05, 0);
            }
        }
    }

    // ================================================================ branchements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("fruit"), (g, delta) -> ecran(g));
        }

        @SubscribeEvent
        public static void couches(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new Couche(rendu));
                }
            }
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
                EVEILLES.clear();
                REPAS.clear();
                JETS.clear();
                ONDES.clear();
                GELES.clear();
                NIVEAUX.clear();
                VOLS.clear();
                PICS.clear();
                ZONES.clear();
                CRATERES.clear();
                BANDES.clear();
                BRISURES.clear();
                DERNIER_SOL.clear();
                return;
            }
            String moi = EVEILLES.get(mc.player.getUUID());
            long maintenant = System.nanoTime();
            // s'accroupir (l'appui, pas le maintien) : l'éruption ou l'ère glaciaire
            boolean accroupi = mc.options.keyShift.isDown();
            if (accroupi && !accroupiAvant && moi != null && mc.screen == null && maintenant >= pretChoc) {
                pretChoc = maintenant + (niveau(mc.player.getUUID()) >= 2 ? Fruit.RECHARGE_CHOC_2 : Fruit.RECHARGE_CHOC) * 50_000_000L;
                mc.player.swing(mainDuFruit(mc.player, glace(moi)));
                PacketDistributor.sendToServer(new Fruit.Choc());
            }
            accroupiAvant = accroupi;
            for (Map.Entry<UUID, String> e : EVEILLES.entrySet()) {
                Player j = mc.level.getPlayerByUUID(e.getKey());
                if (j != null && !j.isInvisible() && !(j == mc.player && mc.options.getCameraType().isFirstPerson())) {
                    particulesMain(mc, j, glace(e.getValue()));
                    if (niveau(e.getKey()) >= 2) {
                        particulesMoitie(mc, j, glace(e.getValue()));
                    }
                }
            }
            REPAS.entrySet().removeIf(e -> secondes(e.getValue().debut) > DUREE_REPAS + 0.5);
            for (Map.Entry<UUID, Repas> e : REPAS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(e.getKey());
                if (j != null) {
                    spirale(mc, j, e.getValue());
                }
            }
            JETS.removeIf(j -> secondes(j.debut) > DUREE_JET + 0.4);
            for (Jet j : JETS) {
                particulesJet(mc, j);
            }
            ONDES.removeIf(o -> secondes(o.debut()) > DUREE_ONDE);
            for (Onde o : ONDES) {
                particulesOnde(mc, o);
            }
            GELES.values().removeIf(fin -> fin < maintenant);
            particulesVols(mc);
        }

        /** Éveillé, le clic droit lance le jet (et rien d'autre) ; le clic gauche reste celui de l'arme, plus fort. */
        @SubscribeEvent
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            String moi = mc.player == null ? null : EVEILLES.get(mc.player.getUUID());
            if (moi == null || !event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (System.nanoTime() >= pretJet) {
                pretJet = System.nanoTime() + (niveau(mc.player.getUUID()) >= 2 ? Fruit.RECHARGE_JET_2 : Fruit.RECHARGE_JET) * 50_000_000L;
                mc.player.swing(mainDuFruit(mc.player, glace(moi)));
                PacketDistributor.sendToServer(new Fruit.Jet());
            }
        }

        /** Le bras du fruit, quand le jeu le dessine (main vide) : son gant par-dessus. */
        @SubscribeEvent
        public static void bras(RenderArmEvent event) {
            AbstractClientPlayer joueur = event.getPlayer();
            String sorte = dansBras ? null : sorteVisible(joueur.getUUID());
            if (sorte == null || event.getArm() != (glace(sorte) ? HumanoidArm.RIGHT : HumanoidArm.LEFT)
                    || !(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(joueur) instanceof PlayerRenderer rendu)) {
                return;
            }
            event.setCanceled(true);
            dessinerBras(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), joueur, rendu, event.getArm(), glace(sorte));
        }

        /** L'autre main, vide, n'est d'habitude pas dessinée : si c'est celle du fruit, on la montre, avec son gant. */
        @SubscribeEvent
        public static void autreMain(RenderHandEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (event.getHand() != InteractionHand.OFF_HAND || !event.getItemStack().isEmpty() || mc.player == null) {
                return;
            }
            String sorte = sorteVisible(mc.player.getUUID());
            HumanoidArm cote = mc.player.getMainArm().getOpposite();
            if (sorte == null || cote != (glace(sorte) ? HumanoidArm.RIGHT : HumanoidArm.LEFT)
                    || !(mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerRenderer rendu)) {
                return;
            }
            event.setCanceled(true);
            brasLibre(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), event.getEquipProgress(), event.getSwingProgress(),
                    cote, mc.player, rendu, glace(sorte));
        }

        /** Une météore ou le poing s'écrase tout près : l'écran tremble un peu. */
        @SubscribeEvent
        public static void tremblement(ViewportEvent.ComputeCameraAngles event) {
            if (secousse < 0) {
                return;
            }
            double apres = (System.nanoTime() - secousse) / 1e9;
            if (apres > 0.4) {
                secousse = -1;
                forceSecousse = 0;
                return;
            }
            float force = forceSecousse * (float) (1 - apres / 0.4);
            event.setRoll(event.getRoll() + (HASARD.nextFloat() - 0.5F) * 5 * force);
            event.setPitch(event.getPitch() + (HASARD.nextFloat() - 0.5F) * 3 * force);
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if ((JETS.isEmpty() && ONDES.isEmpty() && GELES.isEmpty() && VOLS.isEmpty() && PICS.isEmpty() && ZONES.isEmpty() && CRATERES.isEmpty()
                    && BANDES.isEmpty() && BRISURES.isEmpty()) || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                    || Minecraft.getInstance().level == null) {
                return;
            }
            dessiner(event.getPoseStack(), event.getCamera().getPosition(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }

        private Evenements() {
        }
    }

    private ClientFruit() {
    }
}
