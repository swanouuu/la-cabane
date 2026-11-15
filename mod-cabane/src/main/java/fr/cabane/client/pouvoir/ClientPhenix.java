package fr.cabane.client.pouvoir;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.pouvoir.Phenix;
import fr.cabane.pouvoir.Pouvoirs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ce qu'on voit du phénix (voir {@link Phenix} et docs/fruits/planche-phenix*.png) : le plumage cyan qui brille, veiné
 * de flammes (l'avant-bras au niveau 1, la moitié droite au niveau 2, tout le corps au niveau 3), la petite aile de
 * l'avant-bras, les ailes de flammes dans le dos (qui battent en l'air), les serres, la crête ; et les coups : le jet,
 * les plumes, le grand phénix, le cocon, le piqué, le vol bleu, le brasier, le tourbillon, l'aube du phénix et la
 * renaissance (l'œuf de flammes, le phénix qui jaillit). Les modèles viennent du générateur des planches
 * (docs/fruits/generateur/phenix-jeu.mjs).
 */
public final class ClientPhenix {
    private static final RandomSource HASARD = RandomSource.create();
    private static final ResourceLocation BRAS = Cabane.id("textures/pouvoir/phenix_bras.png");
    private static final ResourceLocation MOITIE = Cabane.id("textures/pouvoir/phenix_moitie.png");
    private static final ResourceLocation PLUMES = Cabane.id("textures/pouvoir/phenix_plumes.png");
    private static final float R = 0.35F, G = 0.9F, B = 1F;
    private static final DustParticleOptions CYAN = new DustParticleOptions(new Vector3f(0.35F, 0.9F, 1F), 1.1F);
    private static final DustParticleOptions OR = new DustParticleOptions(new Vector3f(1F, 0.82F, 0.25F), 1F);

    static double secondes(long depuis) {
        return (System.nanoTime() - depuis) / 1e9;
    }

    static double lisse(double x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static Vec3 vec(Vector3f v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    // ================================================================ qui est éveillé

    static final class Etat {
        String pouvoir;
        int niveau;
        long debut1 = -1, debut2 = -1, debut3 = -1, fin = -1;
        int niveauFin;
        /** Le cocon (les ailes se referment devant lui) : quand il a commencé, et combien de temps. */
        long cocon = -1;
        double coconDuree;
    }

    private static final Map<UUID, Etat> ETATS = new HashMap<>();

    public static boolean eveille(UUID qui) {
        Etat e = ETATS.get(qui);
        return e != null && e.fin < 0 && e.niveau > 0;
    }

    public static void recevoir(Pouvoirs.Etat p) {
        long loin = System.nanoTime() - 60_000_000_000L;
        if (!p.eveille()) {
            Etat e = ETATS.get(p.joueur());
            if (e != null && e.fin < 0) {
                e.fin = System.nanoTime();
                e.niveauFin = e.niveau;
                e.niveau = 0;
            }
            return;
        }
        Etat e = ETATS.get(p.joueur());
        if (e == null || e.fin >= 0) {
            e = new Etat();
            ETATS.put(p.joueur(), e);
        }
        e.pouvoir = p.pouvoir();
        long maintenant = p.annonce() ? System.nanoTime() : loin;
        if (p.niveau() >= 1 && e.debut1 < 0) {
            e.debut1 = p.niveau() == 1 ? maintenant : loin;
        }
        if (p.niveau() >= 2 && e.debut2 < 0) {
            e.debut2 = p.niveau() == 2 ? maintenant : loin;
        }
        if (p.niveau() >= 3 && e.debut3 < 0) {
            e.debut3 = maintenant;
        }
        e.niveau = p.niveau();
        Minecraft mc = Minecraft.getInstance();
        if (p.annonce() && mc.player != null && p.joueur().equals(mc.player.getUUID()) && p.niveau() >= 3) {
            ultiFaite = false;
            renaissanceFaite = false;
            volReste = Phenix.VOL_MAX;
        }
        if (p.annonce() && mc.level != null && mc.level.getPlayerByUUID(p.joueur()) instanceof Player j) {
            mc.level.playLocalSound(j.getX(), j.getY(), j.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1F, 0.7F + 0.2F * p.niveau(), false);
        }
    }

    public static void oublier(UUID joueur) {
        ETATS.remove(joueur);
    }

    /** Le niveau du joueur local (0 : endormi, ou pas phénix). */
    public static int niveauLocal() {
        Etat e = moi();
        return e == null ? 0 : e.niveau;
    }

    // ================================================================ les coups (ce que le serveur annonce)

    private record Anim(UUID qui, int sorte, int phase, Vec3 pos, Vec3 dir, int n, long debut) {
        double age() {
            return secondes(debut);
        }
    }

    private static final List<Anim> ANIMS = new ArrayList<>();
    /** Les plumes qui volent (l'éventail) : leur numéro → leur départ, leur vitesse (par tick), leur durée en ticks. */
    private static final Map<Integer, Anim> PLUMES_VOL = new HashMap<>();
    /** Le grand phénix de chaque joueur : sa position d'avant, la dernière reçue, sa direction, quand. */
    private static final class Grand {
        Vec3 avant, pos, dir;
        long recu = System.nanoTime(), fin = -1;
    }

    private static final Map<UUID, Grand> GRANDS = new HashMap<>();

    /** La renaissance du joueur local : son ancienne caméra (rendue à la fin). */
    private static CameraType cameraAvant;
    private static long renaissanceMoi = -1;

    public static void effet(Phenix.Effet p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 pos = vec(p.pos()), dir = vec(p.dir());
        Anim a = new Anim(p.joueur(), p.sorte(), p.phase(), pos, dir, p.n(), System.nanoTime());
        boolean moi = mc.player != null && p.joueur().equals(mc.player.getUUID());
        switch (p.sorte()) {
            case Phenix.E_PLUME -> PLUMES_VOL.put(p.n(), a);
            case Phenix.E_PLUME_FIN -> PLUMES_VOL.remove(p.n());
            case Phenix.E_GRAND -> {
                Grand g = GRANDS.computeIfAbsent(p.joueur(), k -> new Grand());
                if (p.phase() == 2) {
                    GRANDS.remove(p.joueur());
                    ANIMS.add(a);
                } else {
                    g.avant = g.pos == null ? pos : g.pos;
                    g.pos = pos;
                    g.dir = dir;
                    g.recu = System.nanoTime();
                }
            }
            case Phenix.E_COCON -> {
                Etat e = ETATS.get(p.joueur());
                if (e != null) {
                    e.cocon = System.nanoTime();
                    e.coconDuree = p.n() / 20.0;
                }
                ANIMS.add(a);
            }
            case Phenix.E_RENAISSANCE -> {
                ANIMS.add(a);
                if (moi && p.phase() == 0) {
                    renaissanceFaite = true;
                    renaissanceMoi = System.nanoTime();
                    cameraAvant = mc.options.getCameraType();
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                }
                if (moi && p.phase() == 1) {
                    finirCamera();
                }
            }
            case Phenix.E_ULTI -> {
                ANIMS.add(a);
                if (p.phase() == 3) {
                    secouer(mc, pos, 1.2F, 30);
                }
            }
            case Phenix.E_PIQUE -> {
                ANIMS.add(a);
                if (p.phase() == 2) {
                    secouer(mc, pos, 0.6F, 16);
                }
            }
            default -> ANIMS.add(a);
        }
    }

    private static void finirCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (renaissanceMoi >= 0 && cameraAvant != null) {
            mc.options.setCameraType(cameraAvant);
        }
        renaissanceMoi = -1;
        cameraAvant = null;
    }

    private static void secouer(Minecraft mc, Vec3 p, float force, double portee) {
        if (mc.player == null || mc.player.position().distanceTo(p) > portee) {
            return;
        }
        float k = (float) (force * (1 - mc.player.position().distanceTo(p) / portee));
        mc.player.setXRot(mc.player.getXRot() + (HASARD.nextFloat() - 0.5F) * 6 * k);
        mc.player.setYRot(mc.player.getYRot() + (HASARD.nextFloat() - 0.5F) * 6 * k);
    }

    // ================================================================ sur le joueur

    /** Le dessin d'une pièce portée (un groupe par partie du joueur), sur les parties du modèle. */
    private static void piece(String nom, PoseStack ps, MultiBufferSource b, ModelPart[] parties, float alpha, int seule) {
        ModeleVent[] l = ModeleVent.lire("phenix", nom);
        for (int i = 0; i < l.length && i < parties.length; i++) {
            if (seule >= 0 && i != seule) {
                continue;
            }
            ps.pushPose();
            parties[i].translateAndRotate(ps);
            l[i].dessiner(b, ps.last(), LightTexture.FULL_BRIGHT, 1 / 16F, alpha);
            ps.popPose();
        }
    }

    /**
     * Une aile dans son dos (s = -1 : la droite), autour de son attache. {@code replie} : vers l'arrière (positif) ou
     * vers l'avant (le cocon, négatif) ; {@code leve} : le bout monte (positif) ; {@code taille} : elle pousse (0 → 1).
     */
    private static void aile(PoseStack ps, MultiBufferSource b, ModelPart corps, String nom, int s, float replie, float leve, float taille, float alpha) {
        ModeleVent[] l = ModeleVent.lire("phenix", nom);
        if (l.length == 0 || taille <= 0.02F) {
            return;
        }
        ps.pushPose();
        corps.translateAndRotate(ps);
        ps.translate(s * 1.4F / 16, 2.6F / 16, 2.5F / 16);
        ps.mulPose(Axis.YP.rotation(-s * replie));
        ps.mulPose(Axis.ZP.rotation(-s * leve));
        ps.scale(taille, taille, taille);
        l[0].dessiner(b, ps.last(), LightTexture.FULL_BRIGHT, 1 / 16F, alpha);
        ps.popPose();
    }

    public static final class Couche extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public Couche(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack ps, MultiBufferSource buffers, int lumiere, AbstractClientPlayer joueur, float marche, float vitesse,
                float partiel, float age, float tete, float tangage) {
            Etat e = ETATS.get(joueur.getUUID());
            if (e == null || joueur.isInvisible()) {
                return;
            }
            float efface = e.fin >= 0 ? (float) Math.max(0, 1 - secondes(e.fin) / 0.6) : 1;
            if (efface <= 0) {
                return;
            }
            int niveau = e.fin >= 0 ? e.niveauFin : e.niveau;
            double t = niveau >= 3 ? secondes(e.debut3) : niveau == 2 ? secondes(e.debut2) : secondes(e.debut1);
            // à chaque éveil, le plumage monte en 0,8 s, et les ailes poussent
            float monte = (float) lisse(t / 0.8) * efface;
            PlayerModel<AbstractClientPlayer> m = getParentModel();
            ModelPart[] parties = {m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg};
            boolean fin = joueur.getSkin().model() == PlayerSkin.Model.SLIM;
            ResourceLocation tex = niveau >= 3 ? PLUMES : niveau == 2 ? MOITIE : BRAS;
            VertexConsumer c = buffers.getBuffer(RenderType.entityTranslucentEmissive(tex));
            ClientHachibi.enveloppe(m, ps, c, fin, 0.93F, (Math.round(255 * monte) << 24) | 0xFFFFFF);
            // la lueur autour, qui vacille
            float vacille = 0.8F + 0.2F * Mth.sin(age * 0.5F);
            VertexConsumer v = buffers.getBuffer(RenderType.entityTranslucentEmissive(ClientHachibi.MANTEAU));
            if (niveau >= 2) {
                ClientHachibi.enveloppe(m, ps, v, fin, 1.08F, (Math.round(34 * monte * vacille) << 24) | 0x5AE6FF);
            }
            if (niveau == 1) {
                piece("bras1", ps, buffers, parties, monte, 2);
                return;
            }
            // les ailes : repliées au sol (elles ondulent), ouvertes et qui battent en l'air ; refermées devant lui (le cocon)
            boolean enLair = !joueur.onGround() && !joueur.isInWater();
            double h = System.nanoTime() / 1e9 + joueur.getId() * 0.37;
            float replie, leve;
            if (e.cocon >= 0 && secondes(e.cocon) < e.coconDuree) {
                double k = lisse(secondes(e.cocon) / 0.3) * (1 - lisse((secondes(e.cocon) - e.coconDuree + 0.3) / 0.3));
                replie = (float) Mth.lerp(k, 0.7, -1.35);
                leve = (float) Mth.lerp(k, 0, -0.2);
            } else if (enLair) {
                double vol = joueur.getAbilities().flying ? 5 : 8;
                replie = 0.12F;
                leve = (float) (0.15 + 0.45 * Math.sin(h * vol));
            } else {
                replie = (float) (0.9 + 0.08 * Math.sin(h * 1.6) - 0.25 * Math.min(1, vitesse));
                leve = (float) (-0.25 + 0.05 * Math.sin(h * 1.6));
            }
            float pousse = (float) lisse(t / 1.0);
            String n = niveau >= 3 ? "aile3" : "aile2";
            aile(ps, buffers, m.body, n + "_d", -1, replie, leve, pousse, efface);
            aile(ps, buffers, m.body, n + "_g", 1, replie, leve, pousse, efface);
            piece("serres", ps, buffers, parties, monte, niveau >= 3 ? -1 : 4);
            if (niveau >= 3) {
                piece("crete", ps, buffers, parties, monte, 0);
            }
        }
    }

    // ================================================================ dans le monde

    private static void monde(PoseStack ps, Vec3 cam, float partiel) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        MultiBufferSource.BufferSource b = mc.renderBuffers().bufferSource();
        for (Anim a : ANIMS) {
            dessiner(ps, b, cam, level, a, partiel);
        }
        // les plumes de l'éventail
        for (Anim a : PLUMES_VOL.values()) {
            double ticks = Math.min(a.phase(), a.age() * 20);
            Vec3 p = a.pos().add(a.dir().scale(ticks));
            DessinClair.modele(ps, b, cam, "phenix", "plume", p, DessinClair.vers(a.dir()), 1 / 16F, LightTexture.FULL_BRIGHT, 1);
        }
        // le grand phénix : il glisse entre les deux dernières positions reçues (toutes les 2 ticks)
        for (Grand g : GRANDS.values()) {
            if (g.pos == null) {
                continue;
            }
            double k = Mth.clamp(secondes(g.recu) / 0.1, 0, 1);
            Vec3 p = g.avant.lerp(g.pos, k);
            float bat = (float) Math.sin(System.nanoTime() / 1e9 * 9) * 0.08F;
            Quaternionf r = DessinClair.vers(g.dir).rotateX(bat);
            DessinClair.modele(ps, b, cam, "phenix", "oiseau", p, r, 2 / 16F, LightTexture.FULL_BRIGHT, 1);
            DessinClair.eclat(ps, b, cam, p, 1.6, R, G, B, 0.5F, 0);
        }
        b.endBatch();
    }

    private static float fondu(double t, double duree, double bord) {
        return (float) Math.min(lisse(t / bord), 1 - lisse((t - duree + bord) / bord));
    }

    private static void dessiner(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, float partiel) {
        double t = a.age();
        switch (a.sorte()) {
            case Phenix.E_JET -> {
                if (t < 0.4) {
                    float al = (float) (1 - t / 0.4);
                    DessinClair.rayon(ps, b, cam, a.pos(), a.dir(), 0.35, R, G, B, al);
                }
            }
            case Phenix.E_CROIX -> {
                if (t < 0.35) {
                    float al = (float) (1 - t / 0.35);
                    Vec3 c = a.pos(), d = a.dir().normalize(), cote = d.cross(new Vec3(0, 1, 0));
                    cote = cote.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : cote.normalize();
                    Vec3 u = cote.add(0, 1, 0).scale(0.9), w = cote.add(0, -1, 0).scale(0.9);
                    DessinClair.rayon(ps, b, cam, c.subtract(u), c.add(u), 0.22, R, G, B, al);
                    DessinClair.rayon(ps, b, cam, c.subtract(w), c.add(w), 0.22, 1F, 0.85F, 0.3F, al);
                }
            }
            case Phenix.E_PLUIE -> {
                double duree = a.n() / 20.0;
                if (t > duree + 0.8) {
                    return;
                }
                VertexConsumer c = DessinClair.lueur(b);
                DessinClair.anneauSol(ps, c, cam, a.pos().add(0, 0.06, 0), Phenix.RAYON_PLUIE, 0.3, R, G, B, 0.5F * fondu(t, duree + 0.8, 0.3));
                for (int k = 0; k < 16; k++) {
                    double cycle = 0.7, decal = hache(k, 1) * cycle, tt = t - decal;
                    if (tt < 0 || tt > duree) {
                        continue;
                    }
                    double u = (tt % cycle) / cycle;
                    double ang = hache(k, 2) * Mth.TWO_PI, rr = Math.sqrt(hache(k, 3)) * Phenix.RAYON_PLUIE;
                    Vec3 p = a.pos().add(Math.cos(ang) * rr, 11 * (1 - u), Math.sin(ang) * rr);
                    DessinClair.modele(ps, b, cam, "phenix", "plume", p, DessinClair.vers(new Vec3(0.15, -1, 0.1)), 1 / 16F, LightTexture.FULL_BRIGHT, 1);
                }
            }
            case Phenix.E_GRAND -> {
                // phase 2 : il explose
                DessinClair.explosion(ps, b, cam, a.pos(), t, 3, R, G, B);
                if (t < 0.8) {
                    VertexConsumer v = DessinClair.voile(b);
                    DessinClair.boule(ps, v, cam, a.pos(), Phenix.RAYON_GRAND * lisse(t / 0.25), (int) (110 * (1 - t / 0.8)) << 24 | 0x5AE6FF, LightTexture.FULL_BRIGHT);
                }
            }
            case Phenix.E_COCON -> {
                double duree = a.n() / 20.0;
                if (t > duree) {
                    return;
                }
                Player j = level.getPlayerByUUID(a.qui());
                Vec3 c = j == null ? a.pos().add(0, 1, 0) : j.getPosition(partiel).add(0, 1, 0);
                float al = fondu(t, duree, 0.25F);
                VertexConsumer v = DessinClair.voile(b);
                DessinClair.boule(ps, v, cam, c, 1.45, (int) (90 * al) << 24 | 0x5AE6FF, LightTexture.FULL_BRIGHT);
                DessinClair.eclat(ps, b, cam, c, 1.2, R, G, B, 0.45F * al, 0);
                if (t < 0.4) {
                    DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, c.add(0, -0.94, 0), 4 * lisse(t / 0.4), 0.4, R, G, B, (float) (1 - t / 0.4));
                }
            }
            case Phenix.E_PIQUE -> {
                if (a.phase() == 2) {
                    DessinClair.explosion(ps, b, cam, a.pos().add(0, 0.6, 0), t, 2.4, R, G, B);
                }
            }
            case Phenix.E_VOL_BLEU -> {
                // phase 1 : la traînée, du départ (dir) à l'arrivée (pos), qui s'efface
                if (a.phase() == 1 && t < 1.2) {
                    float al = (float) (1 - t / 1.2);
                    DessinClair.rayon(ps, b, cam, a.dir().add(0, 0.9, 0), a.pos().add(0, 0.9, 0), 0.7, R, G, B, al);
                }
            }
            case Phenix.E_BRASIER -> {
                double duree = a.n() / 20.0;
                if (t > duree) {
                    return;
                }
                float al = fondu(t, duree, 0.3);
                VertexConsumer c = DessinClair.lueur(b);
                DessinClair.anneauSol(ps, c, cam, a.pos().add(0, 0.06, 0), Phenix.RAYON_BRASIER, 0.9, R, G, B, 0.35F * al);
                DessinClair.anneauSol(ps, c, cam, a.pos().add(0, 0.07, 0), Phenix.RAYON_BRASIER, 0.2, 1, 1, 1, 0.6F * al);
            }
            case Phenix.E_TOURBILLON -> {
                double duree = a.n() / 20.0;
                if (t > duree) {
                    return;
                }
                Player j = level.getPlayerByUUID(a.qui());
                Vec3 c = j == null ? a.pos() : j.getPosition(partiel);
                float al = fondu(t, duree, 0.25);
                for (int k = 0; k < 12; k++) {
                    double ang = t * 7 + k * Mth.TWO_PI / 12, y = 0.3 + 1.6 * ((k * 0.37) % 1) + 0.2 * Math.sin(t * 5 + k);
                    double rr = Phenix.RAYON_TOURBILLON * (0.7 + 0.15 * Math.sin(k * 1.7));
                    Vec3 p = c.add(Math.cos(ang) * rr, y, Math.sin(ang) * rr);
                    Vec3 tangente = new Vec3(-Math.sin(ang), 0.1, Math.cos(ang));
                    DessinClair.modele(ps, b, cam, "phenix", "plume", p, DessinClair.vers(tangente), 1 / 16F, LightTexture.FULL_BRIGHT, al);
                }
            }
            case Phenix.E_ULTI -> ulti(ps, b, cam, level, a, t);
            case Phenix.E_RENAISSANCE -> renaissance(ps, b, cam, a, t);
            default -> { }
        }
    }

    private static double hache(int k, int n) {
        long h = k * 73856093L ^ n * 19349663L;
        h = (h ^ (h >>> 13)) * 0x5bd1e995L;
        return ((h ^ (h >>> 15)) & 0xFFFF) / 65535.0;
    }

    /**
     * L'aube du phénix : la phase 0 (le phénix géant monte au-dessus de lui pendant 5 s, puis plonge), les phases 1 et 2
     * (une vague de plumes qui part tout autour), la phase 3 (le dôme de flammes bleues éclate).
     */
    private static void ulti(PoseStack ps, MultiBufferSource b, Vec3 cam, Level level, Anim a, double t) {
        Vec3 c = a.pos();
        switch (a.phase()) {
            case 0 -> {
                double fin = Phenix.U_DOME / 20.0;
                if (t > fin + 0.3) {
                    return;
                }
                // il monte (2 s), plane en tournant, puis plonge sur le centre juste avant le dôme
                double monte = lisse(t / 2), plonge = lisse((t - fin + 0.6) / 0.6);
                double y = 3 + 13 * monte - 14 * plonge;
                double tourne = t * 0.6;
                Vec3 p = c.add(Math.cos(tourne) * 3 * (1 - plonge), y, Math.sin(tourne) * 3 * (1 - plonge));
                Vec3 d = plonge > 0 ? new Vec3(0, -1, 0.05) : new Vec3(-Math.sin(tourne), 0.25 - 0.25 * monte, Math.cos(tourne));
                float al = (float) Math.min(1, t / 0.4);
                DessinClair.modele(ps, b, cam, "phenix", "oiseau", p, DessinClair.vers(d), 5 / 16F, LightTexture.FULL_BRIGHT, al);
                DessinClair.eclat(ps, b, cam, p, 5, R, G, B, 0.35F * al, 0);
                DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, c.add(0, 0.06, 0), 6 + Math.sin(t * 3), 0.6, R, G, B, 0.3F * al);
            }
            case 1, 2 -> {
                if (t > 1.2) {
                    return;
                }
                double r = Phenix.RAYON_VAGUE * lisse(t / 1.1);
                float al = (float) (1 - lisse((t - 0.8) / 0.4));
                for (int k = 0; k < 32; k++) {
                    double ang = k * Mth.TWO_PI / 32 + (a.phase() == 2 ? 0.1 : 0);
                    Vec3 d = new Vec3(Math.cos(ang), 0, Math.sin(ang));
                    Vec3 p = c.add(d.scale(r)).add(0, 1.2 + 0.4 * Math.sin(k * 2.1), 0);
                    DessinClair.modele(ps, b, cam, "phenix", "plume", p, DessinClair.vers(d), 1.4F / 16, LightTexture.FULL_BRIGHT, al);
                }
                DessinClair.anneauSol(ps, DessinClair.lueur(b), cam, c.add(0, 0.08, 0), r, 1.2, R, G, B, 0.4F * al);
            }
            case 3 -> {
                if (t > 1.6) {
                    return;
                }
                double r = Phenix.RAYON_DOME * lisse(t / 0.5);
                float al = (float) (1 - lisse((t - 0.6) / 1));
                VertexConsumer v = DessinClair.voile(b);
                DessinClair.boule(ps, v, cam, c, r, (int) (120 * al) << 24 | 0x5AE6FF, LightTexture.FULL_BRIGHT);
                DessinClair.boule(ps, v, cam, c, r * 0.6, (int) (90 * al) << 24 | 0xE8FFFF, LightTexture.FULL_BRIGHT);
                DessinClair.explosion(ps, b, cam, c.add(0, 1, 0), t, 5, R, G, B);
            }
            default -> { }
        }
    }

    /**
     * La renaissance, sur 4 s (comme sur la planche) : il s'embrase (0), ses cendres et un cercle d'or (0,5 s), l'œuf de
     * flammes se forme (1,5 s), il se fêle et la lumière jaillit (2,5 s), un phénix jaillit et monte (3 s), il redescend
     * (3,5 s) ; puis l'onde (phase 1).
     */
    private static void renaissance(PoseStack ps, MultiBufferSource b, Vec3 cam, Anim a, double t) {
        Vec3 p = a.pos();
        if (a.phase() == 1) {
            DessinClair.explosion(ps, b, cam, p.add(0, 1, 0), t, 2.5, R, G, B);
            return;
        }
        if (t > 4.2) {
            return;
        }
        VertexConsumer c = DessinClair.lueur(b);
        if (t > 0.5 && t < 3.2) {
            float al = fondu(t - 0.5, 2.7, 0.3);
            DessinClair.anneauSol(ps, c, cam, p.add(0, 0.05, 0), 1.6 + 0.2 * Math.sin(t * 6), 0.25, 1F, 0.82F, 0.25F, 0.8F * al);
            DessinClair.anneauSol(ps, c, cam, p.add(0, 0.05, 0), 2.4, 0.6, 1F, 0.82F, 0.25F, 0.25F * al);
        }
        if (t > 1.5 && t < 3.1) {
            double k = lisse((t - 1.5) / 0.6);
            String nom = t > 2.5 ? "oeuf_fele" : "oeuf";
            Quaternionf r = new Quaternionf().rotateY((float) (t * 0.8));
            float e = (float) (2 / 16F * k);
            DessinClair.modele(ps, b, cam, "phenix", nom, p.add(0, 18 * e / 2 + 0.05, 0), r, e, LightTexture.FULL_BRIGHT, (float) Math.min(1, k * 1.5));
            DessinClair.eclat(ps, b, cam, p.add(0, 1.1, 0), 1.2 + (t > 2.5 ? (t - 2.5) * 2 : 0), R, G, B, 0.5F, 0);
            if (t > 2.5) {
                Vec3 centre = p.add(0, 1.1, 0);
                for (int q = 0; q < 10; q++) {
                    double ang = q * Mth.TWO_PI / 10 + t, el = (hache(q, 9) - 0.3) * 1.2;
                    Vec3 d = new Vec3(Math.cos(ang) * Math.cos(el), Math.sin(el) + 0.2, Math.sin(ang) * Math.cos(el)).normalize();
                    DessinClair.rayon(ps, b, cam, centre, centre.add(d.scale(2 + 4 * (t - 2.5))), 0.12, 1F, 0.95F, 0.6F, 0.8F);
                }
            }
        }
        if (t > 3 && t < 4.2) {
            // le phénix jaillit et monte, puis redescend sur lui, ailes ouvertes
            double u = t - 3;
            double y = u < 0.5 ? 1 + 7 * lisse(u / 0.5) : 8 - 7 * lisse((u - 0.5) / 0.6);
            float al = (float) (1 - lisse((u - 0.9) / 0.3));
            Vec3 d = u < 0.5 ? new Vec3(0, 1, 0.15) : new Vec3(0, -0.4, 1);
            Quaternionf r = DessinClair.vers(d).rotateZ((float) (u * 2));
            DessinClair.modele(ps, b, cam, "phenix", "oiseau", p.add(0, y, 0), r, 2.4F / 16, LightTexture.FULL_BRIGHT, al);
            DessinClair.eclat(ps, b, cam, p.add(0, y, 0), 2.5, R, G, B, 0.5F * al, 0);
        }
    }

    // ================================================================ les touches et le panneau

    private static long pretDroit, pretAccroupi, pretTech, pretGrand, pretPique, pretVol, pretBrasier, pretTourbillon;
    private static boolean ultiFaite, renaissanceFaite, accroupiAvant, accroupiAuSol, clicPendant, droitAvant, grandEnvoye, sautAvant;
    private static long accroupiDebut, droitDebut;
    private static int battements;
    /** La jauge de vol du niveau 3, comptée ici comme sur le serveur (pour le panneau). */
    private static int volReste = Phenix.VOL_MAX;

    private static long dans(int ticks) {
        return System.nanoTime() + ticks * 50_000_000L;
    }

    private static void envoyer(int action) {
        PacketDistributor.sendToServer(new Phenix.Touche(action));
    }

    private static String reste(long pret) {
        double s = (pret - System.nanoTime()) / 1e9;
        return s > 0 ? "  " + (int) Math.ceil(s) + " s" : "";
    }

    private static Etat moi() {
        Minecraft mc = Minecraft.getInstance();
        Etat e = mc.player == null ? null : ETATS.get(mc.player.getUUID());
        return e == null || e.fin >= 0 || e.niveau == 0 ? null : e;
    }

    private static boolean bloque() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || renaissanceMoi >= 0) {
            return true;
        }
        for (Anim a : ANIMS) {
            if (a.sorte() == Phenix.E_ULTI && a.phase() == 0 && a.qui().equals(mc.player.getUUID()) && a.age() < Phenix.U_FIN / 20.0) {
                return true;
            }
        }
        return false;
    }

    /** La touche technique (², lue dans ClientSharingan) : le cocon, au niveau 3. Rend vrai si le phénix la prend. */
    public static boolean technique() {
        Etat e = moi();
        if (e == null) {
            return false;
        }
        if (e.niveau >= 3 && !bloque() && System.nanoTime() >= pretTech) {
            pretTech = dans(Phenix.RECHARGE_COCON);
            envoyer(Phenix.TECHNIQUE);
        }
        return true;
    }

    /**
     * Les touches L, I, virgule et point (lues dans ClientSharingan) : le piqué, le vol bleu, le brasier, le tourbillon,
     * au niveau 3. Rend vrai si le phénix la prend (il est éveillé).
     */
    public static boolean touche(int action) {
        Etat e = moi();
        if (e == null) {
            return false;
        }
        if (e.niveau < 3 || bloque()) {
            return true;
        }
        long maintenant = System.nanoTime();
        switch (action) {
            case Phenix.PIQUE -> {
                if (maintenant >= pretPique) {
                    pretPique = dans(Phenix.RECHARGE_PIQUE);
                    envoyer(action);
                }
            }
            case Phenix.VOL_BLEU -> {
                if (maintenant >= pretVol) {
                    pretVol = dans(Phenix.RECHARGE_VOL);
                    envoyer(action);
                }
            }
            case Phenix.BRASIER -> {
                if (maintenant >= pretBrasier) {
                    pretBrasier = dans(Phenix.RECHARGE_BRASIER);
                    envoyer(action);
                }
            }
            case Phenix.TOURBILLON -> {
                if (maintenant >= pretTourbillon) {
                    pretTourbillon = dans(Phenix.RECHARGE_TOURBILLON);
                    envoyer(action);
                }
            }
            default -> { }
        }
        return true;
    }

    /** La touche d'ulti (J, lue dans ClientHachibi4) : l'aube du phénix, au niveau 3, une fois par éveil. */
    public static void toucheUlti() {
        Etat e = moi();
        if (e == null || e.niveau < 3 || bloque()) {
            return;
        }
        ultiFaite = true;
        envoyer(Phenix.ULTI);
    }

    private static void ecran(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Etat e = moi();
        if (e == null || mc.options.hideGui) {
            return;
        }
        int n = e.niveau;
        boolean deux = Phenix.NOM2.equals(e.pouvoir), trois = Phenix.NOM3.equals(e.pouvoir);
        String eveil = n == 1 && (deux || trois) ? "Niveau 2" : n == 2 && trois ? "Niveau 3" : "Rendormir";
        long now = System.nanoTime();
        List<String[]> lignes = new ArrayList<>();
        List<Boolean> gris = new ArrayList<>();
        if (n == 1) {
            lignes.add(new String[]{Clavier.gauche(), "Coup de flammes (brûle)"});
            gris.add(false);
            lignes.add(new String[]{Clavier.droite(), "Jet de flammes bleues" + reste(pretDroit)});
            gris.add(pretDroit > now);
            lignes.add(new String[]{Clavier.accroupi(), "Flammes de régénération" + reste(pretAccroupi)});
            gris.add(pretAccroupi > now);
        } else if (n == 2) {
            lignes.add(new String[]{Clavier.gauche(), "Coup de flammes (éclabousse)"});
            gris.add(false);
            lignes.add(new String[]{Clavier.droite(), "Coup de serre" + reste(pretDroit)});
            gris.add(pretDroit > now);
            lignes.add(new String[]{Clavier.accroupi(), "Pluie de plumes" + reste(pretAccroupi)});
            gris.add(pretAccroupi > now);
            lignes.add(new String[]{Clavier.saut() + " en l'air", "Battements d'ailes (" + Math.max(0, 3 - battements) + "), puis il plane"});
            gris.add(battements >= 3);
        } else {
            lignes.add(new String[]{Clavier.gauche(), "Serres (au 3e coup : en croix)"});
            gris.add(false);
            lignes.add(new String[]{Clavier.droite(), "Éventail de plumes" + reste(pretDroit)});
            gris.add(pretDroit > now);
            lignes.add(new String[]{Clavier.droite() + " maintenu", "Grand phénix" + reste(pretGrand)});
            gris.add(pretGrand > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.SUSANO), "Cocon" + reste(pretTech)});
            gris.add(pretTech > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.KAMUI), "Piqué" + reste(pretPique)});
            gris.add(pretPique > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.METEORITE), "Vol bleu" + reste(pretVol)});
            gris.add(pretVol > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.CORBEAU), "Brasier" + reste(pretBrasier)});
            gris.add(pretBrasier > now);
            lignes.add(new String[]{Clavier.nom(ClientSharingan.FRACTURE), "Tourbillon de plumes" + reste(pretTourbillon)});
            gris.add(pretTourbillon > now);
            lignes.add(new String[]{Clavier.nom(fr.cabane.client.hachibi4.ClientHachibi4.ULTI), ultiFaite ? "L'aube du phénix (déjà lancée)" : "L'aube du phénix"});
            gris.add(ultiFaite);
            lignes.add(new String[]{Clavier.saut() + " ×2", "Voler  " + (volReste + 19) / 20 + " s"});
            gris.add(volReste <= 0);
            lignes.add(new String[]{"", ""});
            gris.add(false);
            lignes.add(new String[]{"Passif", renaissanceFaite ? "Renaissance (usée)" : "Renaissance (prête)"});
            gris.add(renaissanceFaite);
        }
        lignes.add(new String[]{Clavier.nom(ClientSusano.EVEIL), eveil});
        gris.add(false);
        boolean[] g2 = new boolean[gris.size()];
        for (int i = 0; i < g2.length; i++) {
            g2[i] = gris.get(i);
        }
        PanneauPouvoir.dessiner(g, mc.font, "Phénix" + (n >= 2 ? "  ·  niv. " + n : ""), 0xFF7CE8FF, lignes.toArray(new String[0][]), g2);
    }

    // ================================================================ les flammes sur lui (à chaque tick)

    private static void ambiance(Minecraft mc, Player j, Etat e) {
        Level level = mc.level;
        double t = e.niveau >= 3 ? secondes(e.debut3) : e.niveau == 2 ? secondes(e.debut2) : secondes(e.debut1);
        Vec3 p = j.position();
        // l'éveil : les flammes montent des pieds
        if (t < 1.2) {
            for (int k = 0; k < 6; k++) {
                double a = HASARD.nextDouble() * Mth.TWO_PI, r = 0.5 + HASARD.nextDouble() * 0.5;
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, p.x + Math.cos(a) * r, p.y + t * 1.8 * HASARD.nextDouble(), p.z + Math.sin(a) * r, 0, 0.05, 0);
            }
        }
        // les perles d'or qui tournent autour de lui (niveau 3)
        if (e.niveau >= 3 && !j.isInvisible()) {
            double h = System.nanoTime() / 1e9;
            for (int k = 0; k < 2; k++) {
                double a = h * 3 + k * Math.PI, y = 0.2 + ((h * 0.5 + k * 0.5) % 1) * 1.8;
                level.addParticle(OR, p.x + Math.cos(a) * 0.8, p.y + y, p.z + Math.sin(a) * 0.8, 0, 0, 0);
            }
        }
        if (HASARD.nextInt(e.niveau >= 2 ? 2 : 4) == 0 && !j.isInvisible()) {
            level.addParticle(CYAN, p.x + HASARD.nextGaussian() * 0.35, p.y + 0.4 + HASARD.nextDouble() * 1.4, p.z + HASARD.nextGaussian() * 0.35, 0, 0.02, 0);
        }
    }

    private static void repas(Minecraft mc, Anim a) {
        Player j = mc.level.getPlayerByUUID(a.qui());
        double t = a.age(), duree = a.n() / 20.0;
        if (j == null || t > duree) {
            return;
        }
        double u = t / duree;
        for (int k = 0; k < 3; k++) {
            double ang = t * 7 + k * Mth.TWO_PI / 3, r = 1.1 - 0.4 * u;
            mc.level.addParticle(k == 0 ? OR : ParticleTypes.SOUL_FIRE_FLAME, j.getX() + Math.cos(ang) * r, j.getY() + 0.1 + u * 2.3, j.getZ() + Math.sin(ang) * r, 0, 0.02, 0);
        }
    }

    // ================================================================ les événements

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Couches {
        @SubscribeEvent
        public static void enregistrer(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("phenix"), (g, delta) -> ecran(g));
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
            LocalPlayer p = mc.player;
            if (mc.level == null || p == null) {
                ETATS.clear();
                ANIMS.clear();
                PLUMES_VOL.clear();
                GRANDS.clear();
                finirCamera();
                return;
            }
            Etat moi = moi();
            // la renaissance : la caméra sort de son corps et tourne autour de l'œuf
            if (renaissanceMoi >= 0) {
                if (secondes(renaissanceMoi) > 4.5) {
                    finirCamera();
                } else {
                    p.setYRot(p.getYRot() + 2.5F);
                    p.setXRot(Mth.lerp(0.1F, p.getXRot(), 22));
                }
            }
            boolean accroupi = mc.options.keyShift.isDown();
            // niveaux 1 et 2 : s'accroupir au sol (un appui court, sans clic) lance la régénération ou la pluie de plumes ;
            // en l'air, s'accroupir ne lance rien (il descend)
            if (moi != null && moi.niveau <= 2) {
                if (accroupi && !accroupiAvant) {
                    accroupiDebut = System.nanoTime();
                    clicPendant = false;
                    accroupiAuSol = p.onGround();
                }
                if (!accroupi && accroupiAvant && !clicPendant && accroupiAuSol && !bloque() && secondes(accroupiDebut) < 0.6
                        && System.nanoTime() >= pretAccroupi) {
                    pretAccroupi = dans(moi.niveau == 1 ? Phenix.RECHARGE_REGEN : Phenix.RECHARGE_PLUIE);
                    envoyer(Phenix.ACCROUPI);
                }
            }
            accroupiAvant = accroupi;
            // niveau 2 : sauter en l'air, 3 battements d'ailes, puis il plane (accroupi : il tombe)
            boolean saut = mc.options.keyJump.isDown();
            if (moi != null && moi.niveau == 2) {
                if (p.onGround() || p.isInWater() || p.onClimbable()) {
                    battements = 0;
                } else if (saut && !sautAvant && battements < 3 && !bloque() && !p.getAbilities().flying) {
                    battements++;
                    Vec3 d = p.getDeltaMovement();
                    p.setDeltaMovement(d.x * 1.1, 0.62, d.z * 1.1);
                    envoyer(Phenix.BATTEMENT);
                }
                Vec3 d = p.getDeltaMovement();
                if (battements > 0 && !p.onGround() && !accroupi && d.y < -0.11 && !p.isInWater() && !p.getAbilities().flying && !p.isPassenger()) {
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    if (p.input.forwardImpulse > 0) {
                        Vec3 regard = p.getLookAngle();
                        h = h.lerp(new Vec3(regard.x, 0, regard.z).normalize().scale(0.42), 0.08);
                    }
                    p.setDeltaMovement(h.x, -0.11, h.z);
                }
            } else {
                battements = 0;
            }
            sautAvant = saut;
            // niveau 3 : clic droit court, l'éventail ; maintenu, le grand phénix (relâché, il explose)
            boolean droit = mc.options.keyUse.isDown() && mc.screen == null;
            if (moi != null && moi.niveau >= 3) {
                if (droit && !droitAvant) {
                    droitDebut = System.nanoTime();
                    grandEnvoye = false;
                }
                if (droit && !grandEnvoye && secondes(droitDebut) > 0.3 && System.nanoTime() >= pretGrand && !bloque()) {
                    grandEnvoye = true;
                    pretGrand = dans(Phenix.RECHARGE_GRAND);
                    p.swing(InteractionHand.MAIN_HAND);
                    envoyer(Phenix.GRAND);
                }
                if (!droit && droitAvant) {
                    if (grandEnvoye) {
                        envoyer(Phenix.GRAND_LACHE);
                    } else if (System.nanoTime() >= pretDroit && !bloque()) {
                        pretDroit = dans(Phenix.RECHARGE_EVENTAIL);
                        p.swing(InteractionHand.MAIN_HAND);
                        envoyer(Phenix.DROIT);
                    }
                    grandEnvoye = false;
                }
                // la jauge de vol (comme sur le serveur)
                if (p.getAbilities().flying) {
                    volReste = Math.max(0, volReste - 1);
                } else if (p.onGround()) {
                    volReste = Math.min(Phenix.VOL_MAX, volReste + 2);
                }
            }
            droitAvant = droit;
            ETATS.values().removeIf(e -> e.fin >= 0 && secondes(e.fin) > 1);
            ANIMS.removeIf(a -> a.age() > (a.sorte() == Phenix.E_ULTI ? 8 : 6));
            PLUMES_VOL.values().removeIf(a -> a.age() > a.phase() / 20.0 + 0.5);
            GRANDS.values().removeIf(g -> secondes(g.recu) > 1);
            for (Map.Entry<UUID, Etat> en : ETATS.entrySet()) {
                Player j = mc.level.getPlayerByUUID(en.getKey());
                if (j != null && en.getValue().fin < 0 && en.getValue().niveau > 0 && j.distanceTo(p) < 64) {
                    ambiance(mc, j, en.getValue());
                }
            }
            for (Anim a : ANIMS) {
                if (a.sorte() == Phenix.E_REPAS) {
                    repas(mc, a);
                }
            }
        }

        /** Éveillé, le clic droit est au phénix (le jet, la serre ; au niveau 3, voir tick). */
        @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
        public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
            Minecraft mc = Minecraft.getInstance();
            Etat moi = moi();
            if (moi == null) {
                return;
            }
            if (event.isAttack() && mc.options.keyShift.isDown()) {
                clicPendant = true;
            }
            if (!event.isUseItem()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            clicPendant = true;
            if (moi.niveau >= 3 || bloque()) {
                return;
            }
            if (System.nanoTime() >= pretDroit) {
                pretDroit = dans(moi.niveau == 2 ? Phenix.RECHARGE_SERRE : Phenix.RECHARGE_JET);
                mc.player.swing(InteractionHand.MAIN_HAND);
                envoyer(Phenix.DROIT);
            }
        }

        /** Pendant sa renaissance, il ne bouge pas (il est en cendres). */
        @SubscribeEvent
        public static void immobile(MovementInputUpdateEvent event) {
            if (renaissanceMoi < 0) {
                return;
            }
            event.getInput().leftImpulse = 0;
            event.getInput().forwardImpulse = 0;
            event.getInput().jumping = false;
            event.getInput().shiftKeyDown = false;
            event.getInput().up = false;
            event.getInput().down = false;
            event.getInput().left = false;
            event.getInput().right = false;
        }

        @SubscribeEvent
        public static void monde(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || Minecraft.getInstance().level == null) {
                return;
            }
            if (ANIMS.isEmpty() && PLUMES_VOL.isEmpty() && GRANDS.isEmpty()) {
                return;
            }
            float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            ClientPhenix.monde(event.getPoseStack(), event.getCamera().getPosition(), partiel);
        }

        private Evenements() {
        }
    }

    private ClientPhenix() {
    }
}
