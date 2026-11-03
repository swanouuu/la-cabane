package fr.cabane.client.lutte;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.cabane.Cabane;
import fr.cabane.client.pouvoir.Clavier;
import fr.cabane.lutte.Choregraphie;
import fr.cabane.lutte.Choregraphie.Geste;
import fr.cabane.lutte.Choregraphie.Pose;
import fr.cabane.lutte.Equipement;
import fr.cabane.lutte.Lutte;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Le catch, côté client : les gestes en cours (chacun bouge son propre joueur, et on pose le corps de tous ceux qu'on
 * voit), le clic droit masqué (la prise, ou accroupi la provoc), le haut de l'écran pendant un match (les noms, les
 * cœurs, le chrono qui monte jusqu'au KO), le public qui se lève sur une prise, et le bras gonflé à la première personne.
 */
@EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
public final class ClientLutte {
    /** Un geste en cours pour une entité : A ou B, le repère, la distance de B, d'où il part, et le tick du début. */
    private record Jeu(Geste geste, boolean b, Vec3 origine, float lacet, float dB, float[] debut, long debutTick) {
    }

    private static final Map<Integer, Jeu> JEUX = new HashMap<>();
    /** Le public se lève et crie jusqu'à ce tick. */
    private static long excitation;

    /** Un match vu par ce client : le dernier état reçu et quand. */
    private record Vu(Lutte.EtatMatch etat, long recu) {
    }

    private static final Map<String, Vu> MATCHS = new HashMap<>();
    /** Quand ma prise et ma provoc sont de nouveau prêtes (System.nanoTime). */
    private static long pretPrise, pretProvoc;

    private static long maintenant() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    // ------------------------------------------------------------------ les paquets

    public static void anim(Lutte.Anim p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Geste g = Geste.values()[p.geste()];
        long t = maintenant();
        JEUX.put(p.a(), new Jeu(g, false, p.origine(), p.lacet(), p.dB(), g.prise() ? new float[] {p.aF(), p.aR()} : null, t));
        // ma propre prise, ma propre provoc : leur recharge s'affiche dans le panneau
        if (mc.player != null && p.a() == mc.player.getId()) {
            long fin = System.nanoTime() + fr.cabane.lutte.Combat.RECHARGE_PRISE * 50_000_000L;
            if (g.prise()) {
                pretPrise = fin;
            } else if (g.provoc()) {
                pretProvoc = fin;
            }
        }
        if (p.b() >= 0) {
            JEUX.put(p.b(), new Jeu(g, true, p.origine(), p.lacet(), p.dB(), new float[] {p.bF(), p.bR()}, t));
        }
        if (g.prise()) {
            for (int id : new int[] {p.a(), p.b()}) {
                Entity e = id >= 0 ? mc.level.getEntity(id) : null;
                if (e != null) {
                    Lutte.PASSE_CORDES.add(e.getUUID());
                }
            }
        }
        if (g == Geste.KO) {
            excitation = t + 80;
        }
    }

    public static void match(Lutte.EtatMatch p) {
        if ("-".equals(p.gagnant())) {
            MATCHS.remove(p.id());
        } else {
            MATCHS.put(p.id(), new Vu(p, maintenant()));
        }
    }

    // ------------------------------------------------------------------ les poses

    /** La pose de ce joueur maintenant (avec la fraction de tick), ou null s'il n'est dans aucun geste. */
    public static Pose pose(Entity e, float partiel) {
        Jeu j = JEUX.get(e.getId());
        if (j == null) {
            return null;
        }
        float t = (maintenant() - j.debutTick() + partiel) / Choregraphie.RALENTI;
        if (t > j.geste().duree) {
            return null;
        }
        return Choregraphie.pose(j.geste(), j.b(), t, j.dB(), j.debut());
    }

    /** Le public : 0 au calme, 1 quand il est debout et crie (après une prise ou un KO). */
    public static float excitation(float partiel) {
        float reste = excitation - (maintenant() + partiel);
        return Mth.clamp(reste / 20F, 0, 1);
    }

    /** Ceux dont on a poussé la pose (pour la retirer après leur rendu). */
    private static final Set<Integer> POSES = new HashSet<>();

    @SubscribeEvent
    public static void avantJoueur(RenderPlayerEvent.Pre event) {
        Player j = event.getEntity();
        float partiel = event.getPartialTick();
        CorpsCatcheur.partiel = partiel;
        Jeu jeu = JEUX.get(j.getId());
        Pose p = jeu == null ? null : pose(j, partiel);
        if (p == null) {
            return;
        }
        // tout le corps : à la hanche voulue, tourné, penché, roulé (le rendu le tourne ensuite de son lacet)
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        POSES.add(j.getId());
        Vec3 hanche = Choregraphie.hanche(jeu.origine(), jeu.lacet(), p);
        Vec3 ici = j.getPosition(partiel);
        float lacet = Choregraphie.lacetCorps(jeu.lacet(), p);
        j.yBodyRot = j.yBodyRotO = lacet;
        j.yHeadRot = j.yHeadRotO = lacet;
        ps.translate(hanche.x - ici.x, hanche.y - ici.y, hanche.z - ici.z);
        ps.mulPose(Axis.YP.rotationDegrees(180 - lacet));
        ps.mulPose(Axis.XP.rotationDegrees(-p.tangage));
        ps.mulPose(Axis.ZP.rotationDegrees(-p.roulis));
        ps.mulPose(Axis.YP.rotationDegrees(-(180 - lacet)));
        ps.translate(0, -Choregraphie.HANCHE, 0);
    }

    @SubscribeEvent
    public static void apresJoueur(RenderPlayerEvent.Post event) {
        if (POSES.remove(event.getEntity().getId())) {
            event.getPoseStack().popPose();
        }
    }

    /** Chacun déplace son propre joueur pendant un geste (le serveur, lui, déplace les créatures). */
    @SubscribeEvent
    public static void tickJoueur(PlayerTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntity() != mc.player || mc.player == null) {
            return;
        }
        Jeu j = JEUX.get(mc.player.getId());
        if (j == null) {
            return;
        }
        float t = (maintenant() - j.debutTick() + 1) / (float) Choregraphie.RALENTI;
        if (t > j.geste().duree) {
            return;
        }
        Pose p = Choregraphie.pose(j.geste(), j.b(), t, j.dB(), j.debut());
        if (p == null) {
            return;
        }
        Vec3 pieds = Choregraphie.pieds(j.origine(), j.lacet(), p);
        mc.player.setPos(pieds.x, pieds.y, pieds.z);
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.fallDistance = 0;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            JEUX.clear();
            MATCHS.clear();
            return;
        }
        long t = maintenant();
        for (Iterator<Map.Entry<Integer, Jeu>> it = JEUX.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Jeu> e = it.next();
            Jeu j = e.getValue();
            long dt = t - j.debutTick();
            if (j.geste().impact >= 0 && dt == (long) j.geste().impact * Choregraphie.RALENTI && !j.b()) {
                excitation = t + 60;
            }
            if (dt > (long) (j.geste().duree + 1) * Choregraphie.RALENTI) {
                Entity ent = mc.level.getEntity(e.getKey());
                if (ent != null) {
                    Lutte.PASSE_CORDES.remove(ent.getUUID());
                }
                it.remove();
            }
        }
        MATCHS.values().removeIf(v -> t - v.recu() > (v.etat().fini() ? 150 : 40));
        // les textures des catcheurs en tenue qu'on voit
        for (Player p : mc.level.players()) {
            int tenue = Equipement.tenue(p);
            if (tenue >= 0 && p instanceof AbstractClientPlayer c) {
                CorpsCatcheur.preparer(c, tenue);
            }
        }
    }

    // ------------------------------------------------------------------ le clic droit masqué

    /** Dans un ring (debout sur le tapis) ou à la salle. */
    static boolean zone(Player j) {
        return j.level().dimension() == Lutte.DIMENSION || j.level().getBlockState(j.blockPosition().below()).is(Lutte.TAPIS.get());
    }

    @SubscribeEvent
    public static void clic(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || mc.player == null || Equipement.masque(mc.player) < 0 || !zone(mc.player) || JEUX.containsKey(mc.player.getId())) {
            return;
        }
        if (mc.player.isShiftKeyDown()) {
            PacketDistributor.sendToServer(new Lutte.DemandeProvoc());
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        if (mc.hitResult instanceof EntityHitResult vise && vise.getEntity() instanceof LivingEntity cible && cible.distanceTo(mc.player) <= 4.5F) {
            PacketDistributor.sendToServer(new Lutte.DemandePrise(cible.getId()));
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    // ------------------------------------------------------------------ le bras à la première personne

    @SubscribeEvent
    public static void bras(RenderArmEvent event) {
        if (CorpsCatcheur.bras(event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), event.getPlayer(), event.getArm())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ le haut de l'écran pendant un match

    /** Le match à montrer : celui de la salle si on y est, sinon le plus proche à moins de 40 blocs. */
    static Lutte.EtatMatch matchVu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        Lutte.EtatMatch mieux = null;
        double d = 40;
        for (Vu v : MATCHS.values()) {
            Lutte.EtatMatch e = v.etat();
            if (e.salle() && mc.player.level().dimension() == Lutte.DIMENSION) {
                return e;
            }
            double ici = e.centre().distanceTo(mc.player.position());
            if (ici < d) {
                d = ici;
                mieux = e;
            }
        }
        return mieux;
    }

    /** Le chrono (en ticks) d'un match : il monte jusqu'au KO, puis reste figé. */
    static int chrono(Lutte.EtatMatch e) {
        Vu v = MATCHS.get(e.id());
        return e.fini() || v == null ? e.ecoule() : e.ecoule() + (int) (maintenant() - v.recu());
    }

    static String temps(int ticks) {
        int s = Math.max(0, ticks / 20);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    private static final ResourceLocation COEUR = ResourceLocation.withDefaultNamespace("hud/heart/full"),
            DEMI = ResourceLocation.withDefaultNamespace("hud/heart/half"), VIDE = ResourceLocation.withDefaultNamespace("hud/heart/container");

    private static void coeurs(GuiGraphics g, int x, int y, float pv, float max, boolean droite) {
        int n = Math.max(1, Mth.ceil(max / 2));
        for (int i = 0; i < Math.min(n, 10); i++) {
            int cx = droite ? x - 9 - i * 8 : x + i * 8;
            g.blitSprite(VIDE, cx, y, 9, 9);
            float reste = pv - i * 2;
            if (reste >= 2) {
                g.blitSprite(COEUR, cx, y, 9, 9);
            } else if (reste >= 1) {
                g.blitSprite(DEMI, cx, y, 9, 9);
            }
        }
    }

    private static void dessinerMatch(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Lutte.EtatMatch e = matchVu();
        if (e == null || mc.options.hideGui || e.noms().isEmpty()) {
            return;
        }
        Font f = mc.font;
        int w = g.guiWidth(), x0 = w / 2 - 150, x1 = w / 2 + 150;
        g.fill(x0, 3, x1, 34, 0xB0100C18);
        g.fill(x0, 3, x1, 4, 0xFFFFD25A);
        String a = e.noms().get(0), b = e.noms().size() > 1 ? e.noms().get(1) : "";
        g.drawString(f, a, x0 + 6, 7, 0xFFFFD25A);
        g.drawString(f, b, x1 - 6 - f.width(b), 7, 0xFFFFD25A);
        coeurs(g, x0 + 6, 20, e.pv().get(0), e.pvMax().get(0), false);
        if (e.pv().size() > 1) {
            coeurs(g, x1 - 6, 20, e.pv().get(1), e.pvMax().get(1), true);
        }
        String temps = temps(chrono(e));
        g.pose().pushPose();
        g.pose().translate(w / 2F, 9, 0);
        g.pose().scale(2.2F, 2.2F, 1);
        g.drawString(f, temps, -f.width(temps) / 2, 0, e.fini() ? 0xFFFFD25A : 0xFFFFFFFF);
        g.pose().popPose();
        if (e.fini() && !e.gagnant().isEmpty()) {
            String s = e.gagnant().toUpperCase() + " GAGNE PAR KO EN " + temps(e.ecoule());
            g.fill(w / 2 - f.width(s) / 2 - 8, 38, w / 2 + f.width(s) / 2 + 8, 52, 0xC0100C18);
            g.drawString(f, s, w / 2 - f.width(s) / 2, 41, 0xFFFFD25A);
        }
    }

    // ------------------------------------------------------------------ le panneau des touches, à gauche

    private static final String[] NOMS = {"Mystério", "Vipère", "Champion", "Démon", "Taureau"};
    private static final String[] PRISES = {"Le 619 (il est dans les cordes)", "Le RKO", "Le AA", "Le chokeslam", "Le spear"};

    /** Avec la tenue ou un masque : ses touches à lui (voir Clavier), et la recharge des clics droits. */
    private static void dessinerTouches(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }
        int tenue = Equipement.tenue(mc.player), masque = Equipement.masque(mc.player);
        if (tenue < 0 && masque < 0) {
            return;
        }
        long maintenant = System.nanoTime();
        double prise = Math.max(0, (pretPrise - maintenant) / 1e9), provoc = Math.max(0, (pretProvoc - maintenant) / 1e9);
        String gauche = Clavier.gauche(), droite = Clavier.droite(), accroupi = Clavier.accroupi();
        java.util.List<String[]> lignes = new java.util.ArrayList<>();
        java.util.List<Boolean> gris = new java.util.ArrayList<>();
        if (tenue >= 0) {
            lignes.add(new String[] {gauche, "Coup de poing"});
            lignes.add(new String[] {Clavier.saut() + " + " + gauche, "Coup de pied sauté"});
            lignes.add(new String[] {"Courir + " + gauche, "Corde à linge"});
            gris.add(false);
            gris.add(false);
            gris.add(false);
        }
        if (masque >= 0) {
            lignes.add(new String[] {droite + " sur lui", PRISES[masque] + (prise > 0 ? "  " + (int) Math.ceil(prise) + " s" : "")});
            lignes.add(new String[] {accroupi + " + " + droite, "Provoc" + (provoc > 0 ? "  " + (int) Math.ceil(provoc) + " s" : "")});
            gris.add(prise > 0);
            gris.add(provoc > 0);
        }
        lignes.add(new String[] {accroupi, "Passer entre les cordes"});
        gris.add(false);
        boolean ici = zone(mc.player);
        String titre = masque >= 0 ? "Catch  ·  " + NOMS[masque] : "Catch";
        Font font = mc.font;
        int teinte = 0xFFFFD25A, largeurTouche = 0, largeurAction = font.width(titre) - 10;
        for (String[] l : lignes) {
            largeurTouche = Math.max(largeurTouche, font.width(l[0]));
            largeurAction = Math.max(largeurAction, font.width(l[1]));
        }
        String note = "Dans un ring ou à la salle";
        largeurAction = Math.max(largeurAction, ici ? 0 : font.width(note) - largeurTouche - 10);
        int pw = largeurTouche + largeurAction + 26, ph = 22 + lignes.size() * 13 + (ici ? 0 : 13);
        int x = 6, y = g.guiHeight() / 2 - ph / 2;
        g.fill(x, y, x + pw, y + ph, 0xA0000000);
        g.fill(x, y, x + 2, y + ph, teinte);
        g.drawString(font, titre, x + 8, y + 6, teinte, true);
        for (int i = 0; i < lignes.size(); i++) {
            int ly = y + 21 + i * 13;
            g.fill(x + 7, ly - 2, x + 11 + font.width(lignes.get(i)[0]), ly + 9, 0x40FFFFFF);
            g.drawString(font, lignes.get(i)[0], x + 9, ly, 0xFFFFFFFF, false);
            g.drawString(font, lignes.get(i)[1], x + 18 + largeurTouche, ly, gris.get(i) || !ici ? 0xFF7A7A7A : 0xFFEDEDED, true);
        }
        if (!ici) {
            g.drawString(font, note, x + 9, y + 21 + lignes.size() * 13, 0xFF9A9A9A, true);
        }
    }

    @EventBusSubscriber(modid = Cabane.MODID, value = Dist.CLIENT)
    public static final class Enregistrement {
        @SubscribeEvent
        public static void couches(RegisterGuiLayersEvent event) {
            event.registerAboveAll(Cabane.id("catch_match"), (g, delta) -> dessinerMatch(g));
            event.registerAboveAll(Cabane.id("catch_touches"), (g, delta) -> dessinerTouches(g));
        }

        @SubscribeEvent
        public static void rendus(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(Lutte.BE_PUBLIC.get(), RenduPublic::new);
            event.registerBlockEntityRenderer(Lutte.BE_ECRAN.get(), RenduEcran::new);
        }

        /** Le corps gonflé et le masque se posent sur tous les joueurs (bras larges comme fins). */
        @SubscribeEvent
        public static void corps(EntityRenderersEvent.AddLayers event) {
            for (PlayerSkin.Model modele : event.getSkins()) {
                if (event.getSkin(modele) instanceof PlayerRenderer rendu) {
                    rendu.addLayer(new CorpsCatcheur.Couche(rendu));
                }
            }
        }

        private Enregistrement() {
        }
    }

    /** Le match du ring de la salle, s'il y en a un (pour l'écran géant). */
    static Lutte.EtatMatch matchSalle() {
        for (Vu v : MATCHS.values()) {
            if (v.etat().salle()) {
                return v.etat();
            }
        }
        return null;
    }

    private ClientLutte() {
    }
}
