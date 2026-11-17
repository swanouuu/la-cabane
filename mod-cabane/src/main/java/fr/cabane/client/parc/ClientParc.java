package fr.cabane.client.parc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fr.cabane.Cabane;
import fr.cabane.parc.Maneges;
import fr.cabane.parc.Parc;
import fr.cabane.parc.Trajet;
import fr.cabane.parc.NacelleParc;
import fr.cabane.parc.Tournants;
import fr.cabane.parc.WagonParc;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Le parc chez le joueur : les rails des manèges (dessinés ici, parce qu'un looping ne se fait pas en blocs), les
 * anneaux de lumière du tunnel du dragon, et le manège vu de l'intérieur : la caméra suit le wagon (penchée dans les
 * virages, la tête en bas dans le looping) et on peut quand même tourner la tête ; le vent souffle d'autant plus fort
 * que ça va vite. Les autres passagers sont dessinés assis dans leur wagon, tournés comme lui.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientParc {
    /** Le sens du roulis de la caméra (à inverser si le monde penche du mauvais côté). */
    private static final float SENS_ROULIS = 1F;

    /** Comment un pavé des rails est éclairé : normalement, en arc-en-ciel (les anneaux), en néon, ou en eau qui coule. */
    private static final int NORMAL = -1, ANNEAU = 0, NEON = 1, EAU = 2;

    /** Un pavé des rails, précalculé : centre, trois axes, demi-côtés, couleur ; son effet et son rang (pour animer). */
    private record Piece(float x, float y, float z, float[] a, float[] b, float[] c, float ha, float hb, float hc, int rgb, int effet, int k) {
    }

    private static List<Piece> rails;
    private static float decalLacet;
    private static float dernierLacet;
    private static boolean dansWagon;
    /** Le manège dans lequel on était au tick d'avant (-1 : à pied). */
    private static byte manegeAvant = -1;

    private static boolean ici() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Parc.DIMENSION;
    }

    // ------------------------------------------------------------------ les rails

    private static List<Piece> rails() {
        if (rails != null) {
            return rails;
        }
        List<Piece> l = new ArrayList<>();
        dragon(l);
        simples(l, Maneges.trajet(Maneges.MINE), 0.55, 0.08F, 0x8A8F96, NORMAL, 0.9, 0.8F, 0x6B4A2B, 0);
        simples(l, Maneges.trajet(Maneges.COSMOS), 0.55, 0.08F, 0x00F0FF, NEON, 0, 0, 0, 0x2A2140);
        goulotte(l, Maneges.trajet(Maneges.SPLASH));
        rails = l;
        return l;
    }

    private static Piece pave(Trajet.Point a, double dh, double dg, double df, float hg, float hu, float hf, int rgb, int effet, int k) {
        float[] g = gauche(a);
        return new Piece((float) (a.x + a.ux * dh + g[0] * dg + a.fx * df), (float) (a.y + a.uy * dh + g[1] * dg + a.fy * df),
                (float) (a.z + a.uz * dh + g[2] * dg + a.fz * df), g, new float[]{(float) a.ux, (float) a.uy, (float) a.uz},
                new float[]{(float) a.fx, (float) a.fy, (float) a.fz}, hg, hu, hf, rgb, effet, k);
    }

    private static void dragon(List<Piece> l) {
        Trajet t = Maneges.trajet(Maneges.DRAGON);
        Trajet.Point a = new Trajet.Point(), b = new Trajet.Point();
        int n = (int) Math.ceil(t.total);
        for (int i = 0; i < n; i++) {
            t.a(i * t.total / n, a);
            t.a((i + 1) * t.total / n, b);
            float[] ga = gauche(a), gb = gauche(b);
            // les deux rails rouges et la poutre jaune dessous
            for (int s = -1; s <= 1; s += 2) {
                segment(l, a.x + ga[0] * s * 0.62, a.y + ga[1] * s * 0.62, a.z + ga[2] * s * 0.62,
                        b.x + gb[0] * s * 0.62, b.y + gb[1] * s * 0.62, b.z + gb[2] * s * 0.62, a, 0.1F, 0xD63031, NORMAL);
            }
            segment(l, a.x - a.ux * 0.55, a.y - a.uy * 0.55, a.z - a.uz * 0.55, b.x - b.ux * 0.55, b.y - b.uy * 0.55, b.z - b.uz * 0.55, a, 0.22F, 0xF1C40F, NORMAL);
        }
        // les traverses
        for (double s = 0; s < t.total; s += 1.1) {
            t.a(s, a);
            l.add(pave(a, -0.25, 0, 0, 0.78F, 0.07F, 0.14F, 0x7F8C8D, NORMAL, 0));
        }
        // les anneaux de lumière du tunnel (ils changent de couleur)
        int k = 0;
        for (double s = 0; s < t.total; s += 3) {
            t.a(s, a);
            if (!a.noir) {
                continue;
            }
            l.add(pave(a, 4.7, 0, 0, 2.35F, 0.13F, 0.13F, 0, ANNEAU, k));
            for (int sd = -1; sd <= 1; sd += 2) {
                l.add(pave(a, 2.3, sd * 2.3, 0, 0.13F, 2.45F, 0.13F, 0, ANNEAU, k));
            }
            k++;
        }
    }

    /** Deux rails (et des traverses, ou une poutre dessous) : la Mine et le Cosmos. */
    private static void simples(List<Piece> l, Trajet t, double ecart, float rayon, int couleur, int effet, double pas, float largeur, int bois, int poutre) {
        Trajet.Point a = new Trajet.Point(), b = new Trajet.Point();
        int n = (int) Math.ceil(t.total);
        for (int i = 0; i < n; i++) {
            t.a(i * t.total / n, a);
            t.a((i + 1) * t.total / n, b);
            float[] ga = gauche(a), gb = gauche(b);
            for (int s = -1; s <= 1; s += 2) {
                segment(l, a.x + ga[0] * s * ecart, a.y + ga[1] * s * ecart, a.z + ga[2] * s * ecart,
                        b.x + gb[0] * s * ecart, b.y + gb[1] * s * ecart, b.z + gb[2] * s * ecart, a, rayon, couleur, effet);
            }
            if (poutre != 0) {
                segment(l, a.x - a.ux * 0.4, a.y - a.uy * 0.4, a.z - a.uz * 0.4, b.x - b.ux * 0.4, b.y - b.uy * 0.4, b.z - b.uz * 0.4, a, 0.18F, poutre, NORMAL);
            }
        }
        if (pas > 0) {
            for (double s = 0; s < t.total; s += pas) {
                t.a(s, a);
                l.add(pave(a, -0.2, 0, 0, largeur, 0.06F, 0.13F, bois, NORMAL, 0));
            }
        }
    }

    /** La goulotte en bois de la Rivière Splash, et l'eau qui coule dedans quand elle est au-dessus du lac. */
    private static void goulotte(List<Piece> l, Trajet t) {
        Trajet.Point a = new Trajet.Point();
        int k = 0;
        for (double s = 0; s < t.total; s += 0.9) {
            t.a(s, a);
            l.add(pave(a, -0.55, 0, 0, 1.7F, 0.15F, 0.5F, 0x8A5A2B, NORMAL, 0));
            for (int sd = -1; sd <= 1; sd += 2) {
                l.add(pave(a, 0.1, sd * 1.6, 0, 0.12F, 0.65F, 0.5F, 0xA0703A, NORMAL, 0));
            }
            if (a.y > Maneges.SOL - 0.2) {
                l.add(pave(a, -0.36, 0, 0, 1.45F, 0.04F, 0.5F, 0x3F9BFF, EAU, k));
            }
            k++;
        }
    }

    private static float[] gauche(Trajet.Point p) {
        return new float[]{(float) (p.uy * p.fz - p.uz * p.fy), (float) (p.uz * p.fx - p.ux * p.fz), (float) (p.ux * p.fy - p.uy * p.fx)};
    }

    /** Un bout de rail droit entre deux points, de section carrée {@code demi}. */
    private static void segment(List<Piece> l, double ax, double ay, double az, double bx, double by, double bz, Trajet.Point p, float demi, int rgb, int effet) {
        Vector3f c = new Vector3f((float) (bx - ax), (float) (by - ay), (float) (bz - az));
        float len = c.length();
        c.normalize();
        Vector3f b = new Vector3f((float) p.ux, (float) p.uy, (float) p.uz);
        b.sub(new Vector3f(c).mul(c.dot(b))).normalize();
        Vector3f a = new Vector3f(b).cross(c);
        l.add(new Piece((float) ((ax + bx) / 2), (float) ((ay + by) / 2), (float) ((az + bz) / 2), new float[]{a.x, a.y, a.z}, new float[]{b.x, b.y, b.z},
                new float[]{c.x, c.y, c.z}, demi, demi, len / 2 + 0.04F, rgb, effet, 0));
    }

    @SubscribeEvent
    public static void dessiner(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !ici()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        VertexConsumer c = buf.getBuffer(Boites.type());
        double temps = (mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) / 20.0;
        PoseStack.Pose pose = ps.last();
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (Piece p : rails()) {
            double dx = p.x - cam.x, dy = p.y - cam.y, dz = p.z - cam.z;
            if (dx * dx + dy * dy + dz * dz > 220 * 220) {
                continue;
            }
            int rgb = p.rgb, lum;
            if (p.effet == ANNEAU) {
                rgb = Mth.hsvToRgb((float) ((temps * 0.6 + p.k * 0.08) % 1.0), 0.85F, 1F);
                lum = Boites.PLEIN;
            } else if (p.effet == NEON) {
                lum = Boites.PLEIN;
            } else {
                // (la goulotte du Splash trempe dans le lac : on prend la lumière au-dessus de l'eau)
                bp.set(Mth.floor(p.x), Math.max(Mth.floor(p.y), Maneges.SOL), Mth.floor(p.z));
                lum = LevelRenderer.getLightColor(mc.level, bp);
                if (p.effet == EAU) {
                    // des bandes plus claires qui descendent avec le courant
                    rgb = ((p.k - (int) Math.floor(temps * 3)) & 3) == 0 ? 0x9FD4FF : 0x3F9BFF;
                }
            }
            Boites.boite(c, pose, (float) dx, (float) dy, (float) dz, p.a[0], p.a[1], p.a[2], p.b[0], p.b[1], p.b[2], p.c[0], p.c[1], p.c[2],
                    p.ha, p.hb, p.hc, rgb, lum);
        }
        DecorParc.dessiner(c, pose, cam, temps);
        DecorTournants.dessiner(c, pose, cam, event.getPartialTick().getGameTimeDeltaPartialTick(false));
        DecorEvenement.dessiner(c, pose, cam, temps);
        float partiel = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        DecorFete.dessiner(c, pose, cam, temps, partiel);
        buf.endBatch(Boites.type());
        // la lumière (halos, projecteurs, rayons) par-dessus, sans rien cacher
        DecorFete.lueurs(buf.getBuffer(Boites.lumiere()), pose, cam, temps, partiel);
        buf.endBatch(Boites.lumiere());
        DecorParc.ecran(ps, cam, buf);
    }

    // ------------------------------------------------------------------ la caméra dans le wagon

    /** Le repère vu par les yeux du passager : celui du wagon, tourné de ce qu'il a tourné la tête. */
    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !manege(mc.player.getVehicle()) || !mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        float partiel = (float) event.getPartialTick();
        if (mc.getCameraEntity() != mc.player) {
            return;
        }
        net.minecraft.world.entity.Entity w = mc.player.getVehicle();
        Trajet.Point p = repere(w, partiel);
        float lacet = decalLacet + Mth.wrapDegrees(mc.player.getYRot() - dernierLacet);
        lacet = Mth.clamp(lacet, -150F, 150F);
        float tangage = Mth.clamp(mc.player.getXRot(), -80F, 80F);
        Quaternionf q = RenduWagonParc.orientation(p);
        q.rotateY((float) place(w, mc.player)[3]);
        q.rotateY((float) Math.toRadians(-lacet)).rotateX((float) Math.toRadians(tangage));
        Vector3f f = q.transform(new Vector3f(0, 0, 1)), u = q.transform(new Vector3f(0, 1, 0));
        // en angles de Minecraft : lacet, tangage (positif vers le bas), puis le roulis autour du regard
        float hx = f.x, hz = f.z;
        if (hx * hx + hz * hz < 1e-5F) {
            hx = -u.x * Math.signum(f.y);
            hz = -u.z * Math.signum(f.y);
        }
        float yaw = (float) Math.atan2(-hx, hz);
        float pitch = (float) Math.asin(Mth.clamp(-f.y, -1F, 1F));
        Vector3f r0 = new Vector3f((float) -Math.cos(yaw), 0, (float) -Math.sin(yaw));
        Vector3f u0 = new Vector3f(r0).cross(f);
        float roll = (float) Math.atan2(new Vector3f(u0).cross(u).dot(f), u0.dot(u));
        event.setYaw((float) Math.toDegrees(yaw));
        event.setPitch((float) Math.toDegrees(pitch));
        event.setRoll(SENS_ROULIS * (float) Math.toDegrees(roll));
    }

    /** Un wagon de grand huit ou une nacelle de manège qui tourne (la caméra et les passagers suivent son repère). */
    private static boolean manege(net.minecraft.world.entity.Entity v) {
        return v instanceof WagonParc || v instanceof NacelleParc;
    }

    /** Le repère du siège : celui du wagon (caisse tournée) ou de la nacelle. */
    private static Trajet.Point repere(net.minecraft.world.entity.Entity v, float partiel) {
        if (v instanceof WagonParc w) {
            return w.trajet().a(w.distance(partiel), new Trajet.Point()).caisse();
        }
        return ((NacelleParc) v).cadre(partiel, new Trajet.Point());
    }

    /** La place de ce passager dans son wagon : x, y, z et l'angle vers lequel il regarde. */
    private static double[] place(net.minecraft.world.entity.Entity w, net.minecraft.world.entity.Entity e) {
        double[][] l = w instanceof WagonParc wp ? Maneges.places(wp.manege()) : Tournants.places(((NacelleParc) w).manege());
        return l[Math.max(0, Math.min(w.getPassengers().indexOf(e), l.length - 1))];
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player j = mc.player;
        if (j == null) {
            return;
        }
        if (ici()) {
            passages(mc);
            // la musique de manège en boucle, tant qu'on est au parc (même en créatif, où le jeu joue la sienne)
            if (musique == null || musique.isStopped() || !mc.getSoundManager().isActive(musique)) {
                if (musique != null) {
                    mc.getSoundManager().stop(musique);
                }
                musique = new Musique();
                mc.getSoundManager().play(musique);
            }
        }
        // en descendant du tronc du Splash, la photo souvenir arrive
        byte manege = j.getVehicle() instanceof WagonParc wv ? wv.manege() : -1;
        if (manegeAvant == Maneges.SPLASH && manege != Maneges.SPLASH) {
            ClientPhoto.sortie();
        }
        manegeAvant = manege;
        boolean dedans = manege(j.getVehicle());
        if (dedans && !dansWagon) {
            // on monte : on regarde droit devant, et le vent se lève
            decalLacet = 0;
            dernierLacet = j.getYRot();
            j.setXRot(0);
            mc.getSoundManager().play(new Vent());
        }
        dansWagon = dedans;
        if (!dedans) {
            return;
        }
        float d = Mth.wrapDegrees(j.getYRot() - dernierLacet);
        dernierLacet = j.getYRot();
        decalLacet = Mth.clamp(decalLacet + d, -150F, 150F);
        // la tête revient doucement vers l'avant quand on ne bouge plus la souris
        if (Math.abs(d) < 0.05F) {
            decalLacet *= 0.95F;
            j.setXRot(j.getXRot() * 0.95F);
        }
    }

    /** Les wagons qui passent aux endroits où il se passe quelque chose : le splash, l'appareil photo, le squelette. */
    private static void passages(Minecraft mc) {
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof WagonParc w)) {
                continue;
            }
            double avant = w.distance(0F), apres = w.distance(1F);
            if (apres <= avant) {
                continue;
            }
            Trajet t = w.trajet();
            switch (w.manege()) {
                case Maneges.SPLASH -> {
                    if (passe(t, avant, apres, DecorParc.sSplash())) {
                        DecorParc.splash(mc);
                    }
                    if (passe(t, avant, apres, DecorParc.sPhoto())) {
                        DecorParc.flash();
                        if (mc.player.getVehicle() == w) {
                            ClientPhoto.declencher(w);
                        }
                    }
                }
                case Maneges.MINE -> {
                    if (passe(t, avant, apres, DecorParc.sSquelette() - 4)) {
                        DecorParc.squelette();
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Est-on passé par {@code cible} entre {@code a} et {@code b} (des distances qui ne font que croître) ? */
    private static boolean passe(Trajet t, double a, double b, double cible) {
        return Math.floor((b - cible) / t.total) > Math.floor((a - cible) / t.total);
    }

    private static Musique musique;

    /** La valse d'orgue du parc, partout dans le parc, en boucle. */
    private static final class Musique extends AbstractTickableSoundInstance {
        Musique() {
            super(Parc.MUSIQUE.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.55F;
            relative = true;
            attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            if (!ici()) {
                stop();
                return;
            }
            // elle se tait quand on approche des autos-tamponneuses, où passe un autre son
            volume = 0.55F * (1F - 0.92F * AmbianceParc.presAutos());
        }
    }

    /** Au parc, les autres musiques du jeu se taisent : on n'entend que celle du parc. */
    @SubscribeEvent
    public static void autreMusique(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        if (ici() && event.getSound() != null && event.getSound().getSource() == SoundSource.MUSIC) {
            event.setSound(null);
        }
    }

    /** Le vent dans les oreilles, plus fort quand ça va vite. */
    private static final class Vent extends AbstractTickableSoundInstance {
        Vent() {
            super(SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.01F;
            relative = true;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !manege(mc.player.getVehicle())) {
                stop();
                return;
            }
            float v = mc.player.getVehicle() instanceof WagonParc w ? w.vitesse() : ((NacelleParc) mc.player.getVehicle()).vitesse();
            volume = Mth.clamp((v / 30F) * (v / 30F), 0.01F, 1F) * 0.9F;
            pitch = 0.7F + Mth.clamp(v / 50F, 0, 0.8F);
        }
    }

    // ------------------------------------------------------------------ les autres passagers, tournés comme leur wagon

    @SubscribeEvent
    public static void passagerAvant(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity e = event.getEntity();
        if (!manege(e.getVehicle())) {
            return;
        }
        net.minecraft.world.entity.Entity w = e.getVehicle();
        float partiel = event.getPartialTick();
        Trajet.Point p = repere(w, partiel);
        // le corps est déjà tourné de son lacet par Minecraft : on ajoute ce qui manque pour être tourné comme le wagon
        float corps = Mth.rotLerp(partiel, e.yBodyRotO, e.yBodyRot) * Mth.DEG_TO_RAD;
        Quaternionf voulu = RenduWagonParc.orientation(p).rotateY((float) place(w, e)[3]);
        Quaternionf droit = new Quaternionf().rotateY(-corps);
        Quaternionf delta = voulu.mul(droit.conjugate(), new Quaternionf());
        float oeil = e.getEyeHeight();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(0, oeil, 0);
        ps.mulPose(delta);
        ps.translate(0, -oeil, 0);
    }

    @SubscribeEvent
    public static void passagerApres(RenderLivingEvent.Post<?, ?> event) {
        if (manege(event.getEntity().getVehicle())) {
            event.getPoseStack().popPose();
        }
    }

    private ClientParc() {
    }
}
