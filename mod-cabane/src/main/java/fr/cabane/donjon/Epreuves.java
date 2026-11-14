package fr.cabane.donjon;

import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Les énigmes et les portes des donjons tracés par le mod ({@link PlanDonjon}) : chacun a les siennes, et leur
 * solution change à chaque nouveau groupe (les indices gravés au fond des salles de l'autre aile aussi).
 *
 * <ul>
 * <li>La salle de l'ordre : quatre feux (LUNE, SOLEIL, ÉTOILE, CRÂNE), un bouton devant chacun ; il faut les allumer
 * dans l'ordre gravé dans l'autre aile. Une erreur : tout s'éteint, et des gobelins sortent du sol.</li>
 * <li>La salle du code : quatre leviers I à IV ; seuls certains doivent être levés (le code est dans l'autre aile).</li>
 * <li>La porte des sceaux s'ouvre quand quelqu'un se tient sur chacun des deux sceaux en même temps.</li>
 * <li>La grande porte de l'arène attend que tous les chasseurs du donjon soient dans l'antichambre ; elle se referme
 * quand le boss se réveille.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Epreuves {
    public static final String[] FEUX = {"LUNE", "SOLEIL", "ÉTOILE", "CRÂNE"};
    /** Les feux, en arc au nord de la salle (le sceau est au sud). */
    private static final int[][] PLACES = {{-6, -3}, {-2, -7}, {2, -7}, {6, -3}};
    private static final String[] CHIFFRES = {"I", "II", "III", "IV"};

    private final TypeDonjon type;
    private final PlanDonjon plan;
    private final int[] ordreSolution = {0, 1, 2, 3};
    private final boolean[] code = new boolean[4];
    private final boolean[] boutons = new boolean[4];
    private int allumes;
    private boolean ordreResolu, codeResolu, sceauxOuverts, porteOuverte, combat;

    private static final Map<TypeDonjon, Epreuves> ETATS = new EnumMap<>(TypeDonjon.class);

    public static Epreuves de(TypeDonjon type) {
        return ETATS.computeIfAbsent(type, Epreuves::new);
    }

    private Epreuves(TypeDonjon type) {
        this.type = type;
        this.plan = PlanDonjon.de(type);
    }

    // ---------------------------------------------------------------- les places

    private BlockPos feu(int i) {
        return new BlockPos(plan.ordre.cx() + PLACES[i][0], PlanDonjon.SOL + 1, plan.ordre.cz() + PLACES[i][1]);
    }

    private BlockPos bouton(int i) {
        return new BlockPos(plan.ordre.cx() + PLACES[i][0] * 2 / 3, PlanDonjon.SOL + 1, plan.ordre.cz() + PLACES[i][1] * 2 / 3 + 1);
    }

    BlockPos levier(int k) {
        return new BlockPos(plan.code.cx() - 3 + k * 2, PlanDonjon.SOL + 2, plan.code.cz() - 5);
    }

    private BlockPos cristal(int k) {
        return new BlockPos(plan.code.cx() - 3 + k * 2, PlanDonjon.SOL + 3, plan.code.cz() - 6);
    }

    /** Le cœur du coffre, au fond de son cul-de-sac. */
    BlockPos coffre() {
        int s = plan.coffre.cx() < plan.x0 ? -1 : 1;
        return new BlockPos(plan.coffre.cx() + s * 3, PlanDonjon.SOL + 1, plan.coffre.cz());
    }

    private static String cote(PlanDonjon.Salle s, PlanDonjon p) {
        return s.cx() < p.x0 ? "L'OUEST" : "L'EST";
    }

    private static void pose(ServerLevel l, BlockPos p, BlockState s) {
        l.setBlock(p, s, Block.UPDATE_ALL);
    }

    /** La forme fixe des deux salles (posée à la construction) : les socles, les sceaux, les noms. */
    void construire(ServerLevel l, BlockState feuSocle, BlockState socle, BlockState sceauBord, BlockState sceauCoeur) {
        for (int i = 0; i < 4; i++) {
            pose(l, feu(i).below(), feuSocle);
            // le nom du feu, à côté de lui, lisible depuis le centre de la salle
            Enigmes.panneau(l, feu(i).east(), 0, "", FEUX[i], "", "");
        }
        for (int k = 0; k < 4; k++) {
            BlockPos lv = levier(k);
            pose(l, lv.below(), socle);
            pose(l, lv.below().north(), socle);
            pose(l, lv.north(), socle);
            Enigmes.panneau(l, new BlockPos(lv.getX(), PlanDonjon.SOL + 1, lv.getZ() + 2), 8, "", CHIFFRES[k], "", "");
        }
        for (PlanDonjon.Salle s : new PlanDonjon.Salle[] {plan.ordre, plan.code}) {
            Vec3 c = plan.sceau(s);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pose(l, BlockPos.containing(c.x + dx, PlanDonjon.SOL, c.z + dz), dx == 0 && dz == 0 ? sceauCoeur : sceauBord);
                }
            }
            Enigmes.panneau(l, BlockPos.containing(c.x + 2, PlanDonjon.SOL + 1, c.z), 0, "SCEAU DE", cote(s, plan), "", "");
        }
        int x0 = plan.x0;
        Enigmes.panneau(l, new BlockPos(x0 - 2, PlanDonjon.SOL + 1, plan.porteSceauxZ - 2), 8, "PORTE DES", "SCEAUX", "Tenez les deux", "en même temps.");
        Enigmes.panneau(l, new BlockPos(x0 + 2, PlanDonjon.SOL + 1, plan.porteSceauxZ - 2), 8, "Deux ailes,", "deux énigmes,", "deux sceaux.", "Séparez-vous.");
        Enigmes.panneau(l, new BlockPos(x0 - 3, PlanDonjon.SOL_BAS + 1, plan.grandePorteZ - 3), 8, "Elle ne s'ouvre", "que devant", "TOUS les", "chasseurs.");
        Enigmes.panneau(l, new BlockPos(x0 - 9, PlanDonjon.SOL + 1, plan.carrefour.cz() - 2), 4, "", "<- OUEST", "", "");
        Enigmes.panneau(l, new BlockPos(x0 + 9, PlanDonjon.SOL + 1, plan.carrefour.cz() - 2), 12, "", "EST ->", "", "");
    }

    // ---------------------------------------------------------------- l'état de départ

    /** Un nouveau groupe : nouvelles solutions, nouveaux indices, portes fermées, feux éteints, leviers baissés. */
    public void reinitialiser(ServerLevel l, boolean nouvellesSolutions) {
        if (nouvellesSolutions) {
            Random r = new Random();
            List<Integer> o = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(o, r);
            for (int i = 0; i < 4; i++) {
                ordreSolution[i] = o.get(i);
            }
            int n;
            do {
                n = 0;
                for (int k = 0; k < 4; k++) {
                    code[k] = r.nextBoolean();
                    n += code[k] ? 1 : 0;
                }
            } while (n == 0 || n == 4);
        }
        allumes = 0;
        ordreResolu = false;
        codeResolu = false;
        sceauxOuverts = false;
        porteOuverte = false;
        combat = false;
        for (int i = 0; i < 4; i++) {
            pose(l, feu(i), Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            pose(l, bouton(i), Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
            boutons[i] = false;
            pose(l, levier(i), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.SOUTH)
                    .setValue(LeverBlock.POWERED, false));
            pose(l, cristal(i), Blocks.TINTED_GLASS.defaultBlockState());
        }
        indices(l);
        porteDesSceaux(l, false);
        grandePorte(l, false);
        Enigmes.coffre(l, coffre());
    }

    /** Les indices, gravés au fond des salles de l'autre aile. */
    private void indices(ServerLevel l) {
        PlanDonjon.Salle io = plan.indiceOrdre, ic = plan.indiceCode;
        int so = io.cx() < plan.x0 ? -1 : 1, sc = ic.cx() < plan.x0 ? -1 : 1;
        // tournés vers l'entrée de la salle (le côté du carrefour)
        int rotO = so < 0 ? 12 : 4, rotC = sc < 0 ? 12 : 4;
        Enigmes.panneau(l, new BlockPos(io.cx() + so * 2, PlanDonjon.SOL + 1, io.cz() - 1), rotO, "FEUX DE " + cote(plan.ordre, plan),
                "1. " + FEUX[ordreSolution[0]], "2. " + FEUX[ordreSolution[1]], "3. " + FEUX[ordreSolution[2]]);
        Enigmes.panneau(l, new BlockPos(io.cx() + so * 2, PlanDonjon.SOL + 1, io.cz() + 1), rotO, "4. " + FEUX[ordreSolution[3]], "",
                "Dans cet ordre,", "sinon gare.");
        List<String> leves = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            if (code[k]) {
                leves.add(CHIFFRES[k]);
            }
        }
        Enigmes.panneau(l, new BlockPos(ic.cx() + sc * 2, PlanDonjon.SOL + 1, ic.cz() - 1), rotC, "LEVIERS DE", cote(plan.code, plan), "Levés :",
                String.join(", ", leves));
        Enigmes.panneau(l, new BlockPos(ic.cx() + sc * 2, PlanDonjon.SOL + 1, ic.cz() + 1), rotC, "Les autres", "dorment.", "", "");
    }

    private void porteDesSceaux(ServerLevel l, boolean ouverte) {
        int z = plan.porteSceauxZ, s = PlanDonjon.SOL;
        for (int x = -3; x <= 3; x++) {
            for (int y = s + 1; y <= s + 8; y++) {
                BlockState b = ouverte ? Blocks.AIR.defaultBlockState()
                        : Math.abs(x) == 3 || y == s + 8 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState();
                pose(l, new BlockPos(plan.x0 + x, y, z), b);
            }
        }
    }

    private void grandePorte(ServerLevel l, boolean ouverte) {
        int z = plan.grandePorteZ, s0 = PlanDonjon.SOL_BAS;
        for (int x = -3; x <= 3; x++) {
            for (int y = s0 + 1; y <= s0 + 9; y++) {
                BlockState b = ouverte ? Blocks.AIR.defaultBlockState()
                        : x == 0 ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                        : (y - s0) % 4 == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                pose(l, new BlockPos(plan.x0 + x, y, z), b);
            }
        }
    }

    // ---------------------------------------------------------------- en jeu

    private List<ServerPlayer> chasseurs(ServerLevel l) {
        List<ServerPlayer> c = new ArrayList<>();
        for (ServerPlayer p : Boss.joueurs(l, type)) {
            if (!p.isSpectator() && p.isAlive()) {
                c.add(p);
            }
        }
        return c;
    }

    private boolean surSceau(ServerPlayer p, PlanDonjon.Salle salle) {
        Vec3 s = plan.sceau(salle);
        return Math.abs(p.getX() - s.x) < 1.6 && Math.abs(p.getZ() - s.z) < 1.6 && Math.abs(p.getY() - s.y) < 1.5;
    }

    private static void barre(ServerPlayer p, String texte, ChatFormatting couleur) {
        p.displayClientMessage(Component.literal(texte).withStyle(couleur), true);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 2 != 0) {
            return;
        }
        ServerLevel l = Donjon.niveau(event.getServer());
        if (l == null || l.players().isEmpty()) {
            return;
        }
        for (TypeDonjon t : TypeDonjon.values()) {
            if (t.genere() && !Boss.joueurs(l, t).isEmpty()) {
                Epreuves e = de(t);
                e.ordre(l);
                e.code(l);
                e.sceaux(l);
                e.porte(l);
            }
        }
    }

    private void ordre(ServerLevel l) {
        for (int i = 0; i < 4; i++) {
            BlockPos b = bouton(i);
            if (!l.isLoaded(b)) {
                return;
            }
            BlockState s = l.getBlockState(b);
            boolean appuye = s.hasProperty(ButtonBlock.POWERED) && s.getValue(ButtonBlock.POWERED);
            boolean nouveau = appuye && !boutons[i];
            boutons[i] = appuye;
            if (!nouveau || ordreResolu) {
                continue;
            }
            if (i == ordreSolution[allumes]) {
                allumes++;
                pose(l, feu(i), Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                Vec3 c = Vec3.atCenterOf(feu(i));
                l.playSound(null, c.x, c.y, c.z, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1F, 0.7F + allumes * 0.1F);
                l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.5, c.z, 20, 0.3, 0.4, 0.3, 0.03);
                if (allumes == 4) {
                    ordreResolu = true;
                    reveilSceau(l, plan.ordre, "Les quatre feux brûlent : le sceau de " + cote(plan.ordre, plan).toLowerCase() + " s'éveille.");
                }
            } else {
                allumes = 0;
                for (int k = 0; k < 4; k++) {
                    pose(l, feu(k), Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
                }
                Vec3 c = plan.ordre.centre();
                l.playSound(null, c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.5F, 0.6F);
                l.playSound(null, c.x, c.y, c.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1F, 1.6F);
                for (int k = 0; k < 3; k++) {
                    double a = k * Math.PI * 2 / 3 + 0.5;
                    Boss.poserSbire(l, Donjon.GOBELIN.get(), c.x + Math.cos(a) * 4, c.y, c.z + Math.sin(a) * 4, c);
                }
                for (ServerPlayer p : chasseurs(l)) {
                    if (p.distanceToSqr(c) < 16 * 16) {
                        barre(p, "Mauvais ordre ! Les feux s'éteignent…", ChatFormatting.RED);
                    }
                }
            }
        }
    }

    private void code(ServerLevel l) {
        boolean bon = true;
        for (int k = 0; k < 4; k++) {
            BlockPos p = levier(k);
            if (!l.isLoaded(p)) {
                return;
            }
            BlockState s = l.getBlockState(p);
            boolean leve = s.hasProperty(LeverBlock.POWERED) && s.getValue(LeverBlock.POWERED);
            BlockState voulu = leve ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.TINTED_GLASS.defaultBlockState();
            if (l.getBlockState(cristal(k)) != voulu) {
                pose(l, cristal(k), voulu);
            }
            bon &= leve == code[k];
        }
        if (bon && !codeResolu) {
            codeResolu = true;
            reveilSceau(l, plan.code, "Les bons cristaux brillent : le sceau de " + cote(plan.code, plan).toLowerCase() + " s'éveille.");
        }
    }

    private void reveilSceau(ServerLevel l, PlanDonjon.Salle salle, String texte) {
        Vec3 s = plan.sceau(salle);
        l.playSound(null, s.x, s.y, s.z, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.5F, 0.8F);
        l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, s.x, s.y + 0.2, s.z, 40, 0.8, 0.2, 0.8, 0.05);
        Boss.annoncer(l, type, "SYSTÈME", new String[] {texte, ordreResolu && codeResolu
                ? "Tenez les deux sceaux en même temps pour ouvrir la porte." : "Il reste l'autre énigme, dans l'autre aile."}, "", 0);
    }

    private void sceaux(ServerLevel l) {
        if (l.getServer().getTickCount() % 10 == 0) {
            for (PlanDonjon.Salle salle : new PlanDonjon.Salle[] {plan.ordre, plan.code}) {
                boolean eveille = salle == plan.ordre ? ordreResolu : codeResolu;
                if (eveille && !sceauxOuverts) {
                    Vec3 s = plan.sceau(salle);
                    l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, s.x, s.y + 0.1, s.z, 4, 0.6, 0.05, 0.6, 0.01);
                    l.sendParticles(ParticleTypes.END_ROD, s.x, s.y + 0.5, s.z, 1, 0.4, 0.4, 0.4, 0.01);
                }
            }
        }
        if (sceauxOuverts) {
            return;
        }
        boolean surOrdre = false, surCode = false;
        List<ServerPlayer> dessus = new ArrayList<>();
        for (ServerPlayer p : chasseurs(l)) {
            boolean o = surSceau(p, plan.ordre), c = surSceau(p, plan.code);
            surOrdre |= o;
            surCode |= c;
            if (o || c) {
                dessus.add(p);
            }
        }
        if (ordreResolu && codeResolu && surOrdre && surCode) {
            sceauxOuverts = true;
            porteDesSceaux(l, true);
            double x = plan.x0 + 0.5;
            int z = plan.porteSceauxZ;
            l.playSound(null, x, PlanDonjon.SOL + 3, z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 2F, 0.5F);
            l.playSound(null, x, PlanDonjon.SOL + 3, z, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.8F, 1.2F);
            l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, PlanDonjon.SOL + 4, z + 0.5, 60, 2.5, 2.5, 0.3, 0.03);
            Boss.annoncer(l, type, "SYSTÈME", new String[] {"Les deux sceaux répondent.", "La porte des sceaux est ouverte, au sud du carrefour."}, "", 0);
            for (ServerPlayer p : chasseurs(l)) {
                fr.cabane.pnj.Missions.evenement(p, "sceaux");
            }
            return;
        }
        if (l.getServer().getTickCount() % 20 == 0) {
            for (ServerPlayer p : dessus) {
                boolean o = surSceau(p, plan.ordre);
                boolean eveille = o ? ordreResolu : codeResolu;
                if (!eveille) {
                    barre(p, o ? "Ce sceau dort encore : allumez les feux dans le bon ordre." : "Ce sceau dort encore : trouvez le code des leviers.",
                            ChatFormatting.GRAY);
                } else if (!(ordreResolu && codeResolu)) {
                    barre(p, "Le sceau est éveillé. Il faut aussi éveiller l'autre.", ChatFormatting.AQUA);
                } else {
                    barre(p, "Il faut quelqu'un sur l'autre sceau, en même temps !", ChatFormatting.AQUA);
                }
            }
        }
    }

    private void porte(ServerLevel l) {
        if (porteOuverte || combat || l.getServer().getTickCount() % 10 != 0) {
            return;
        }
        List<ServerPlayer> tous = chasseurs(l);
        List<ServerPlayer> devant = tous.stream().filter(p -> plan.dansAntichambre(p.position())).toList();
        if (devant.isEmpty()) {
            return;
        }
        if (devant.size() >= tous.size()) {
            porteOuverte = true;
            grandePorte(l, true);
            double x = plan.x0 + 0.5;
            int z = plan.grandePorteZ;
            l.playSound(null, x, PlanDonjon.SOL_BAS + 4, z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 3F, 0.4F);
            l.playSound(null, x, PlanDonjon.SOL_BAS + 4, z, SoundEvents.WARDEN_EMERGE, SoundSource.BLOCKS, 1.5F, 0.6F);
            l.sendParticles(ParticleTypes.LARGE_SMOKE, x, PlanDonjon.SOL_BAS + 4, z + 0.5, 60, 2.5, 3, 0.3, 0.03);
            Boss.annoncer(l, type, "SYSTÈME", new String[] {"Tous les chasseurs sont réunis.", "La grande porte s'ouvre…"}, "", 0);
            return;
        }
        for (ServerPlayer p : devant) {
            barre(p, "La grande porte attend tout le monde : " + devant.size() + " / " + tous.size() + " chasseurs", ChatFormatting.GOLD);
        }
    }

    /** Le boss se réveille : la grande porte se referme ; ceux qui traînent dans l'antichambre sont poussés dans l'arène. */
    void debutCombat(ServerLevel l) {
        combat = true;
        for (ServerPlayer p : chasseurs(l)) {
            if (plan.dansAntichambre(p.position())) {
                p.teleportTo(l, plan.x0 + 0.5, PlanDonjon.SOL_BAS + 1, plan.grandePorteZ + 4.5, p.getYRot(), p.getXRot());
            }
        }
        if (porteOuverte) {
            porteOuverte = false;
            grandePorte(l, false);
            l.playSound(null, plan.x0 + 0.5, PlanDonjon.SOL_BAS + 4, plan.grandePorteZ, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 3F, 0.4F);
        }
    }

    void finCombat(ServerLevel l, boolean victoire) {
        combat = false;
        porteOuverte = victoire;
        grandePorte(l, victoire);
    }

    /** Pour les tests de l'hôte : les deux énigmes résolues et la porte des sceaux ouverte. */
    void toutOuvrir(ServerLevel l) {
        ordreResolu = true;
        codeResolu = true;
        sceauxOuverts = true;
        porteDesSceaux(l, true);
    }
}
