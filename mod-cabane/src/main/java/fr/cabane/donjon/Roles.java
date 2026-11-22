package fr.cabane.donjon;

import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Les rôles des grands groupes (2026-11-22 : « que ça s'adapte au nombre qu'on est… tout le monde a un petit rôle à
 * jouer », de 1 à 7). La porte des sceaux de chaque donjon demande autant de sceaux qu'il y a de chasseurs (au moins
 * deux : ceux des deux énigmes), chacun avec sa propre épreuve, puis qu'on les tienne tous en même temps :
 * <ol>
 * <li>seul : les deux énigmes, puis un seul sceau suffit (personne pour tenir l'autre) ;</li>
 * <li>à deux : les deux énigmes, un sur chaque sceau (comme avant) ;</li>
 * <li>dès 3 : le <b>sceau du sang</b> (le cul-de-sac de l'embuscade) — on monte dessus, une vague surgit, il faut tout
 * tuer ;</li>
 * <li>dès 4 : le <b>sceau des cristaux</b> (le cul-de-sac du coffre) — trois pierres d'écho cachées dans cette aile, à
 * frapper ;</li>
 * <li>dès 5 : le <b>sceau des cloches</b> (le carrefour) — sonner les deux cloches des salles des énigmes à moins de
 * 3 s d'écart ;</li>
 * <li>dès 6 : le <b>sceau de la course</b> (la salle d'arrivée) — un bouton, puis courir jusqu'à la pierre d'arrivée
 * (au fond de l'autre salle des indices) avant la fin du temps ;</li>
 * <li>dès 7 : le <b>sceau du gardien</b> (une salle des indices) — on monte dessus, le gardien du sceau surgit, il faut
 * le battre.</li>
 * </ol>
 * Tout est posé une fois pour toutes (les sceaux en trop dorment, leur panneau dit à partir de combien ils servent) et
 * remis à neuf à chaque nouveau groupe. La taille du groupe, c'est le plus grand nombre de chasseurs vus dans le donjon
 * avant que la porte des sceaux s'ouvre (ceux qui arrivent après comptent aussi).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Roles {
    /** Les épreuves des grands groupes, et à partir de combien de chasseurs chacune compte. */
    public enum Sorte {
        SANG(3, "DU SANG"), CRISTAUX(4, "DES CRISTAUX"), CLOCHES(5, "DES CLOCHES"), COURSE(6, "DE LA COURSE"), GARDIEN(7, "DU GARDIEN");

        public final int des;
        public final String nom;

        Sorte(int des, String nom) {
            this.des = des;
            this.nom = nom;
        }
    }

    /** Les deux cloches doivent sonner à moins de tant de ticks d'écart. */
    private static final int ECART_CLOCHES = 60;
    /** La course : tant de secondes de marge, plus une seconde par tant de blocs de chemin. */
    private static final double COURSE_MARGE = 8, COURSE_VITESSE = 4.8;

    /**
     * Où tout se trouve dans un donjon : le centre (au sol) de chaque sceau en plus et son panneau, les deux cloches, le
     * bouton de départ et la pierre d'arrivée de la course, les trois pierres d'écho, les deux panneaux devant la porte
     * des sceaux, et le carrefour (pour mesurer la course).
     */
    record Lieux(Map<Sorte, BlockPos> sceaux, Map<Sorte, BlockPos> panneaux, BlockPos[] cloches, BlockPos depart, BlockPos arrivee,
                 BlockPos[] pierres, BlockPos[] panneauxPorte, BlockPos carrefour, String aileCoffre) {
    }

    private final TypeDonjon type;
    private final Lieux lieux;
    private int taille;
    private boolean annonce;
    private int calme;
    private final Map<Sorte, Boolean> eveille = new EnumMap<>(Sorte.class);
    /** La vague du sang et le gardien : ceux qu'il faut tuer. */
    private final List<UUID> vague = new ArrayList<>();
    private boolean vagueLancee;
    private UUID gardien;
    private boolean gardienLance;
    private final boolean[] pierres = new boolean[3];
    private final boolean[] boutonsPierres = new boolean[3];
    private final int[] cloches = {-9999, -9999};
    private UUID coureur;
    private int finCourse;
    private boolean boutonDepart;

    private static final Map<TypeDonjon, Roles> ROLES = new EnumMap<>(TypeDonjon.class);

    public static Roles de(TypeDonjon type) {
        return ROLES.computeIfAbsent(type, Roles::new);
    }

    private Roles(TypeDonjon type) {
        this.type = type;
        this.lieux = type.genere() ? lieuxGeneres(PlanDonjon.de(type)) : lieuxUgrak();
    }

    // ---------------------------------------------------------------- les places

    private static BlockPos p(int x, int z) {
        return new BlockPos(x, DonjonBuilder.SOL, z);
    }

    /** La grotte d'Ugrak, faite à la main (DonjonBuilder) : l'embuscade à l'ouest, le coffre à l'est. */
    private static Lieux lieuxUgrak() {
        Map<Sorte, BlockPos> s = new EnumMap<>(Sorte.class), pa = new EnumMap<>(Sorte.class);
        s.put(Sorte.SANG, p(-56, 73));
        pa.put(Sorte.SANG, p(-56, 71));
        s.put(Sorte.CRISTAUX, p(59, 58));
        pa.put(Sorte.CRISTAUX, p(61, 58));
        s.put(Sorte.CLOCHES, p(-5, 45));
        pa.put(Sorte.CLOCHES, p(-3, 45));
        s.put(Sorte.COURSE, p(-6, 6));
        pa.put(Sorte.COURSE, p(-4, 6));
        s.put(Sorte.GARDIEN, p(-53, 8));
        pa.put(Sorte.GARDIEN, p(-51, 8));
        return new Lieux(s, pa, new BlockPos[] {p(-40, 78), p(40, 70)}, p(6, 6), p(51, 19),
                new BlockPos[] {p(36, 25), p(50, 42), p(36, 55)}, new BlockPos[] {p(-2, DonjonBuilder.PORTE_SCEAUX_Z - 2), p(2, DonjonBuilder.PORTE_SCEAUX_Z - 2)},
                p(0, DonjonBuilder.CARREFOUR[1]), "L'EST");
    }

    /** Un donjon tracé par {@link PlanDonjon} : tout se déduit de son plan. */
    private static Lieux lieuxGeneres(PlanDonjon plan) {
        int x0 = plan.x0, cz = plan.carrefour.cz();
        Map<Sorte, BlockPos> s = new EnumMap<>(Sorte.class), pa = new EnumMap<>(Sorte.class);
        PlanDonjon.Salle emb = plan.embuscade, cof = plan.coffre;
        int se = emb.cx() < x0 ? -1 : 1, sc = cof.cx() < x0 ? -1 : 1;
        s.put(Sorte.SANG, p(emb.cx(), emb.cz()));
        pa.put(Sorte.SANG, p(emb.cx(), emb.cz() - 3));
        s.put(Sorte.CRISTAUX, p(cof.cx() - sc, cof.cz()));
        pa.put(Sorte.CRISTAUX, p(cof.cx() - sc, cof.cz() - 3));
        s.put(Sorte.CLOCHES, p(x0 - 5, cz + 5));
        pa.put(Sorte.CLOCHES, p(x0 - 3, cz + 5));
        s.put(Sorte.COURSE, p(x0 - 6, 6));
        pa.put(Sorte.COURSE, p(x0 - 4, 6));
        // les salles des indices : celle de l'aile de l'embuscade garde le gardien, celle de l'aile du coffre l'arrivée
        PlanDonjon.Salle[] indices = {plan.indiceOrdre, plan.indiceCode};
        PlanDonjon.Salle ie = indices[0].cx() < x0 == se < 0 ? indices[0] : indices[1];
        PlanDonjon.Salle ic = ie == indices[0] ? indices[1] : indices[0];
        s.put(Sorte.GARDIEN, p(ie.cx() - se * 2, ie.cz()));
        pa.put(Sorte.GARDIEN, p(ie.cx() - se * 2, ie.cz() + 3));
        BlockPos arrivee = p(ic.cx() - sc * 2, ic.cz());
        // les cloches, sur le côté des deux salles des énigmes
        BlockPos[] cloches = {p(plan.ordre.cx() - 7, plan.ordre.cz() + 3), p(plan.code.cx() - 7, plan.code.cz() + 3)};
        // les pierres d'écho, au milieu des couloirs de l'aile du coffre
        PlanDonjon.Salle en = plan.ordre.cx() < x0 == sc < 0 ? plan.ordre : plan.code;
        int ex = en.cx();
        BlockPos[] pierres = {
                p((x0 + sc * 10 + ex) / 2, cz + 2),
                p(ex + 2, (ic.cz() + cz) / 2),
                p((ex + sc * 9 + cof.cx() - sc * 3) / 2, en.cz() - 2)};
        return new Lieux(s, pa, cloches, p(x0 + 6, 6), arrivee, pierres,
                new BlockPos[] {p(x0 - 2, plan.porteSceauxZ - 2), p(x0 + 2, plan.porteSceauxZ - 2)}, p(x0, cz), sc < 0 ? "L'OUEST" : "L'EST");
    }

    // ---------------------------------------------------------------- la pose

    private static void pose(ServerLevel l, BlockPos p, BlockState s) {
        l.setBlock(p, s, Block.UPDATE_ALL);
    }

    private static void vider(ServerLevel l, BlockPos sol, int rayon) {
        for (int dx = -rayon; dx <= rayon; dx++) {
            for (int dz = -rayon; dz <= rayon; dz++) {
                for (int y = 1; y <= 3; y++) {
                    BlockPos q = sol.offset(dx, y, dz);
                    if (!l.getBlockState(q).isAir()) {
                        pose(l, q, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    private static BlockState bouton() {
        return Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR);
    }

    /**
     * Un nouveau groupe : tout est (re)posé à sa place — les sceaux en plus, les cloches, le départ et l'arrivée de la
     * course, les pierres d'écho — et toutes les épreuves sont à refaire. Les sceaux en plus prennent les pierres des
     * deux sceaux des énigmes ({@code bord}, {@code coeur}).
     */
    void installer(ServerLevel l, BlockState bord, BlockState coeur) {
        taille = 0;
        annonce = false;
        calme = 0;
        eveille.clear();
        retirerMonstres(l);
        vagueLancee = false;
        gardienLance = false;
        coureur = null;
        finCourse = 0;
        boutonDepart = false;
        cloches[0] = cloches[1] = -9999;
        for (Sorte s : Sorte.values()) {
            BlockPos c = lieux.sceaux().get(s);
            vider(l, c, 1);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    pose(l, c.offset(dx, 0, dz), dx == 0 && dz == 0 ? coeur : bord);
                }
            }
            BlockPos pa = lieux.panneaux().get(s).above();
            pose(l, pa, Blocks.AIR.defaultBlockState());
            Enigmes.panneau(l, pa, 8, "SCEAU", s.nom, consigne(s), "Dès " + s.des + " chasseurs");
        }
        for (BlockPos c : lieux.cloches()) {
            vider(l, c, 0);
            pose(l, c.above(), Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR).setValue(BellBlock.FACING, Direction.NORTH));
        }
        vider(l, lieux.depart(), 0);
        pose(l, lieux.depart().above(), Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
        pose(l, lieux.depart().above(2), bouton());
        Enigmes.panneau(l, lieux.depart().offset(0, 1, -1), 8, "DÉPART", "DE LA COURSE", "Arrivée : indice", "de " + lieux.aileCoffre());
        vider(l, lieux.arrivee(), 0);
        pose(l, lieux.arrivee().above(), Blocks.LODESTONE.defaultBlockState());
        pose(l, lieux.arrivee().above(2), Blocks.AIR.defaultBlockState());
        Enigmes.panneau(l, lieux.arrivee().offset(0, 1, -1), 8, "", "ARRIVÉE", "DE LA COURSE", "");
        for (int k = 0; k < 3; k++) {
            BlockPos c = lieux.pierres()[k];
            vider(l, c, 0);
            pose(l, c.above(), Blocks.CRYING_OBSIDIAN.defaultBlockState());
            pose(l, c.above(2), bouton());
            pierres[k] = false;
            boutonsPierres[k] = false;
        }
        Enigmes.panneau(l, lieux.panneauxPorte()[1].above(), 8, "PORTE DES", "SCEAUX", "Un sceau par", "chasseur.");
        Enigmes.panneau(l, lieux.panneauxPorte()[0].above(), 8, "Éveillez-les,", "puis tenez-les", "TOUS en même", "temps.");
    }

    /** Pareil, avec les pierres du sceau de l'énigme des feux de ce donjon. */
    void installer(ServerLevel l) {
        BlockPos c;
        if (type.genere()) {
            PlanDonjon plan = PlanDonjon.de(type);
            c = new BlockPos(plan.ordre.cx(), PlanDonjon.SOL, plan.ordre.cz() + PlanDonjon.DZ_SCEAU);
        } else {
            c = new BlockPos(DonjonBuilder.SALLE_BRASEROS[0], DonjonBuilder.SOL, DonjonBuilder.SALLE_BRASEROS[1] + Enigmes.DZ_SCEAU);
        }
        BlockState coeur = l.getBlockState(c), bord = l.getBlockState(c.east());
        if (coeur.isAir() || bord.isAir()) {
            coeur = Blocks.CRYING_OBSIDIAN.defaultBlockState();
            bord = Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState();
        }
        installer(l, bord, coeur);
    }

    private static String consigne(Sorte s) {
        return switch (s) {
            case SANG -> "Une vague surgit";
            case CRISTAUX -> "3 pierres d'écho";
            case CLOCHES -> "2 cloches ensemble";
            case COURSE -> "Course chrono";
            case GARDIEN -> "Battez le gardien";
        };
    }

    private void retirerMonstres(ServerLevel l) {
        List<UUID> tous = new ArrayList<>(vague);
        if (gardien != null) {
            tous.add(gardien);
        }
        for (UUID u : tous) {
            Entity e = l.getEntity(u);
            if (e != null) {
                e.discard();
            }
        }
        vague.clear();
        gardien = null;
    }

    // ---------------------------------------------------------------- les comptes

    /** Pour les tests de l'hôte (/donjon groupe) : le groupe compte au moins tant de chasseurs. */
    void forcer(int n) {
        taille = n;
        annonce = false;
        calme = 0;
    }

    /** Combien de chasseurs compte ce groupe (au moins un). */
    public int taille() {
        return Math.max(1, taille);
    }

    public boolean actif(Sorte s) {
        return taille() >= s.des;
    }

    /** Les épreuves de ce groupe en plus des deux énigmes. */
    public List<Sorte> actives() {
        List<Sorte> l = new ArrayList<>();
        for (Sorte s : Sorte.values()) {
            if (actif(s)) {
                l.add(s);
            }
        }
        return l;
    }

    private boolean fait(Sorte s) {
        return eveille.getOrDefault(s, false);
    }

    /** Combien de sceaux en tout (les deux des énigmes compris). */
    public int total() {
        return 2 + actives().size();
    }

    /** Ce qu'il reste à faire, après qu'un sceau s'est éveillé. */
    public String suite(boolean ordre, boolean code) {
        int reste = (ordre ? 0 : 1) + (code ? 0 : 1);
        for (Sorte s : actives()) {
            reste += fait(s) ? 0 : 1;
        }
        if (reste == 0) {
            int t = Math.min(total(), taille());
            return t <= 1 ? "Montez sur un sceau pour ouvrir la porte." : "Tenez " + t + " sceaux en même temps pour ouvrir la porte.";
        }
        return reste == 1 ? "Il reste un sceau à éveiller." : "Il reste " + reste + " sceaux à éveiller.";
    }

    private Vec3 centre(Sorte s) {
        BlockPos c = lieux.sceaux().get(s);
        return new Vec3(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
    }

    static boolean sur(ServerPlayer p, Vec3 s) {
        return Math.abs(p.getX() - s.x) < 1.6 && Math.abs(p.getZ() - s.z) < 1.6 && Math.abs(p.getY() - s.y) < 1.5;
    }

    private static void barre(ServerPlayer p, String texte, ChatFormatting couleur) {
        p.displayClientMessage(Component.literal(texte).withStyle(couleur), true);
    }

    // ---------------------------------------------------------------- en jeu

    /** Toutes les 2 ticks, tant qu'il y a des chasseurs dans ce donjon. */
    void tick(ServerLevel l, List<ServerPlayer> chasseurs, boolean porteOuverte) {
        int now = l.getServer().getTickCount();
        if (!porteOuverte && chasseurs.size() > taille) {
            taille = chasseurs.size();
            calme = now + 60;
            annonce = false;
        }
        // on attend que tout le monde soit arrivé pour annoncer les sceaux
        if (!annonce && taille > 0 && now >= calme) {
            annonce = true;
            annoncer(l, chasseurs);
        }
        if (porteOuverte) {
            return;
        }
        if (actif(Sorte.SANG)) {
            sang(l, chasseurs, now);
        }
        if (actif(Sorte.CRISTAUX)) {
            cristaux(l);
        }
        if (actif(Sorte.CLOCHES)) {
            clochesTick(l);
        }
        if (actif(Sorte.COURSE)) {
            course(l, chasseurs, now);
        }
        if (actif(Sorte.GARDIEN)) {
            gardienTick(l, chasseurs);
        }
        if (now % 10 == 0) {
            for (Sorte s : actives()) {
                if (fait(s)) {
                    Vec3 c = centre(s);
                    l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.1, c.z, 4, 0.6, 0.05, 0.6, 0.01);
                    l.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.5, c.z, 1, 0.4, 0.4, 0.4, 0.01);
                }
            }
        }
    }

    private void annoncer(ServerLevel l, List<ServerPlayer> chasseurs) {
        int n = taille();
        List<Sorte> a = actives();
        String tenir = n <= 1 ? "Seul : un seul sceau à tenir à la fin." : "Puis tenez-en " + Math.min(total(), n) + " en même temps.";
        Boss.annoncer(l, type, "SYSTÈME", new String[] {n + (n > 1 ? " chasseurs" : " chasseur") + " : " + total() + " sceaux à éveiller.", tenir,
                a.isEmpty() ? "Les deux énigmes, une par aile." : "Les deux énigmes, et " + a.size() + " épreuve" + (a.size() > 1 ? "s" : "") + " en plus."}, "", 0);
        if (a.isEmpty()) {
            return;
        }
        // le détail dans le chat : chacun choisit son rôle
        List<Component> lignes = new ArrayList<>();
        lignes.add(Component.literal("[Système] Les rôles de ce donjon :").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lignes.add(Component.literal(" • Énigme des feux et énigme des leviers : une dans chaque aile (l'indice est dans l'autre).").withStyle(ChatFormatting.AQUA));
        for (Sorte s : a) {
            lignes.add(Component.literal(" • " + role(s)).withStyle(ChatFormatting.AQUA));
        }
        for (ServerPlayer p : chasseurs) {
            for (Component c : lignes) {
                p.sendSystemMessage(c);
            }
        }
    }

    private String role(Sorte s) {
        return switch (s) {
            case SANG -> "Sceau du sang (cul-de-sac de l'embuscade) : montez dessus, tuez toute la vague.";
            case CRISTAUX -> "Sceau des cristaux (cul-de-sac du coffre) : frappez les 3 pierres d'écho cachées dans l'aile de " + lieux.aileCoffre().toLowerCase() + ".";
            case CLOCHES -> "Sceau des cloches (carrefour) : sonnez les 2 cloches des salles des énigmes à moins de 3 s d'écart.";
            case COURSE -> "Sceau de la course (salle d'arrivée) : le bouton, puis foncez à la pierre d'arrivée (salle de l'indice de "
                    + lieux.aileCoffre().toLowerCase() + ").";
            case GARDIEN -> "Sceau du gardien (salle de l'indice, côté embuscade) : montez dessus et battez le gardien.";
        };
    }

    private void reveil(ServerLevel l, Sorte s, String texte, boolean ordre, boolean code) {
        if (fait(s)) {
            return;
        }
        eveille.put(s, true);
        Vec3 c = centre(s);
        l.playSound(null, c.x, c.y, c.z, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.5F, 0.8F);
        l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.2, c.z, 40, 0.8, 0.2, 0.8, 0.05);
        Boss.annoncer(l, type, "SYSTÈME", new String[] {texte, suite(ordre, code)}, "", 0);
    }

    /** Les deux énigmes sont-elles faites ? (pour le texte « il reste… ») — tenu à jour par les énigmes. */
    private boolean ordreFait, codeFait;

    void enigmes(boolean ordre, boolean code) {
        ordreFait = ordre;
        codeFait = code;
    }

    // ---- le sang : une vague

    private void sang(ServerLevel l, List<ServerPlayer> chasseurs, int now) {
        if (fait(Sorte.SANG)) {
            return;
        }
        Vec3 c = centre(Sorte.SANG);
        if (!vagueLancee) {
            if (chasseurs.stream().anyMatch(p -> sur(p, c))) {
                vagueLancee = true;
                int n = taille();
                int gobelins = 3 + n / 2, brutes = n >= 6 ? 2 : 1;
                l.playSound(null, c.x, c.y, c.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2F, 0.7F);
                for (int i = 0; i < gobelins + brutes; i++) {
                    double a = i * Math.PI * 2 / (gobelins + brutes);
                    Sbire s = Boss.poserSbire(l, i < brutes ? Donjon.BRUTE.get() : Donjon.GOBELIN.get(), c.x + Math.cos(a) * 4.5, c.y, c.z + Math.sin(a) * 4.5, c);
                    if (s != null) {
                        s.setPersistenceRequired();
                        vague.add(s.getUUID());
                    }
                }
                for (ServerPlayer p : chasseurs) {
                    if (p.distanceToSqr(c) < 30 * 30) {
                        barre(p, "Le sceau du sang réclame une vague : tuez-les tous !", ChatFormatting.RED);
                    }
                }
            }
            return;
        }
        if (!l.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(BlockPos.containing(c)))) {
            return;
        }
        vague.removeIf(u -> {
            Entity e = l.getEntity(u);
            return e == null || !e.isAlive();
        });
        if (vague.isEmpty()) {
            reveil(l, Sorte.SANG, "La vague est tombée : le sceau du sang s'éveille.", ordreFait, codeFait);
        } else if (now % 40 == 0) {
            for (ServerPlayer p : chasseurs) {
                if (p.distanceToSqr(c) < 25 * 25) {
                    barre(p, "Sceau du sang : encore " + vague.size() + " monstre" + (vague.size() > 1 ? "s" : "") + " à tuer", ChatFormatting.RED);
                }
            }
        }
    }

    // ---- les cristaux : trois pierres d'écho

    private void cristaux(ServerLevel l) {
        if (fait(Sorte.CRISTAUX)) {
            return;
        }
        int faites = 0;
        for (int k = 0; k < 3; k++) {
            BlockPos b = lieux.pierres()[k].above(2);
            if (!l.isLoaded(b)) {
                return;
            }
            if (!pierres[k]) {
                BlockState s = l.getBlockState(b);
                boolean appuye = s.hasProperty(ButtonBlock.POWERED) && s.getValue(ButtonBlock.POWERED);
                if (appuye && !boutonsPierres[k]) {
                    pierres[k] = true;
                    pose(l, b, Blocks.AIR.defaultBlockState());
                    pose(l, b.below(), Blocks.SEA_LANTERN.defaultBlockState());
                    Vec3 c = Vec3.atCenterOf(b);
                    l.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 2F, 1F);
                    l.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 20, 0.3, 0.5, 0.3, 0.05);
                    int n = 0;
                    for (boolean f : pierres) {
                        n += f ? 1 : 0;
                    }
                    for (ServerPlayer p : Boss.joueurs(l, type)) {
                        if (p.distanceToSqr(c) < 12 * 12) {
                            barre(p, "Pierre d'écho : " + n + " / 3", ChatFormatting.LIGHT_PURPLE);
                        }
                    }
                }
                boutonsPierres[k] = appuye;
                // une lueur qui aide à les trouver
                if (!pierres[k] && l.getServer().getTickCount() % 20 == 0) {
                    Vec3 c = Vec3.atCenterOf(b);
                    l.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.3, c.z, 1, 0.2, 0.3, 0.2, 0.01);
                }
            }
            faites += pierres[k] ? 1 : 0;
        }
        if (faites == 3) {
            reveil(l, Sorte.CRISTAUX, "Les trois pierres d'écho chantent : le sceau des cristaux s'éveille.", ordreFait, codeFait);
        }
    }

    // ---- les cloches : sonner les deux ensemble

    @SubscribeEvent
    public static void cloche(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel l) || !l.dimension().equals(Donjon.DIMENSION)) {
            return;
        }
        if (!l.getBlockState(event.getPos()).is(Blocks.BELL)) {
            return;
        }
        Roles r = de(TypeDonjon.de(event.getPos().getX()));
        for (int k = 0; k < 2; k++) {
            if (r.lieux.cloches()[k].above().equals(event.getPos())) {
                r.cloches[k] = l.getServer().getTickCount();
                if (r.actif(Sorte.CLOCHES) && !r.fait(Sorte.CLOCHES) && event.getEntity() instanceof ServerPlayer p) {
                    barre(p, "Ding ! L'autre cloche doit sonner dans les 3 secondes.", ChatFormatting.YELLOW);
                }
            }
        }
    }

    private void clochesTick(ServerLevel l) {
        if (fait(Sorte.CLOCHES)) {
            return;
        }
        if (cloches[0] > 0 && cloches[1] > 0 && Math.abs(cloches[0] - cloches[1]) <= ECART_CLOCHES) {
            reveil(l, Sorte.CLOCHES, "Les deux cloches ont sonné ensemble : le sceau des cloches s'éveille.", ordreFait, codeFait);
        }
    }

    // ---- la course

    private int dureeCourse() {
        BlockPos d = lieux.depart(), a = lieux.arrivee(), c = lieux.carrefour();
        double chemin = Math.abs(c.getZ() - d.getZ()) + Math.abs(a.getX() - c.getX()) + Math.abs(a.getZ() - c.getZ());
        return (int) Math.round((COURSE_MARGE + chemin / COURSE_VITESSE) * 20);
    }

    private void course(ServerLevel l, List<ServerPlayer> chasseurs, int now) {
        if (fait(Sorte.COURSE)) {
            return;
        }
        BlockPos b = lieux.depart().above(2);
        if (l.isLoaded(b)) {
            BlockState s = l.getBlockState(b);
            boolean appuye = s.hasProperty(ButtonBlock.POWERED) && s.getValue(ButtonBlock.POWERED);
            if (appuye && !boutonDepart && coureur == null) {
                ServerPlayer qui = null;
                double mieux = 36;
                for (ServerPlayer p : chasseurs) {
                    double d = p.distanceToSqr(Vec3.atCenterOf(b));
                    if (d < mieux) {
                        mieux = d;
                        qui = p;
                    }
                }
                if (qui != null) {
                    coureur = qui.getUUID();
                    finCourse = now + dureeCourse();
                    l.playSound(null, b.getX() + 0.5, b.getY(), b.getZ() + 0.5, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 2F, 1.5F);
                    qui.sendSystemMessage(Component.literal("[Système] Course ! La pierre d'arrivée est au fond de la salle de l'indice de "
                            + lieux.aileCoffre().toLowerCase() + ".").withStyle(ChatFormatting.GOLD));
                }
            }
            boutonDepart = appuye;
        }
        if (coureur == null) {
            return;
        }
        ServerPlayer p = l.getServer().getPlayerList().getPlayer(coureur);
        Vec3 arrivee = new Vec3(lieux.arrivee().getX() + 0.5, lieux.arrivee().getY() + 1, lieux.arrivee().getZ() + 0.5);
        if (now % 6 == 0) {
            l.sendParticles(ParticleTypes.END_ROD, arrivee.x, arrivee.y + 1.5, arrivee.z, 3, 0.1, 1.2, 0.1, 0.01);
        }
        if (p == null || !p.isAlive() || p.isSpectator()) {
            coureur = null;
            return;
        }
        double dx = p.getX() - arrivee.x, dz = p.getZ() - arrivee.z;
        if (dx * dx + dz * dz < 2.2 * 2.2 && Math.abs(p.getY() - arrivee.y) < 2.5) {
            coureur = null;
            reveil(l, Sorte.COURSE, p.getGameProfile().getName() + " a gagné la course : le sceau de la course s'éveille.", ordreFait, codeFait);
            return;
        }
        int reste = finCourse - now;
        if (reste <= 0) {
            coureur = null;
            barre(p, "Trop lent ! Retournez appuyer sur le bouton de départ.", ChatFormatting.RED);
            p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1F, 1F);
            return;
        }
        barre(p, "COURSE : " + (reste + 19) / 20 + " s", reste < 100 ? ChatFormatting.RED : ChatFormatting.GOLD);
    }

    // ---- le gardien

    private void gardienTick(ServerLevel l, List<ServerPlayer> chasseurs) {
        if (fait(Sorte.GARDIEN)) {
            return;
        }
        Vec3 c = centre(Sorte.GARDIEN);
        if (!gardienLance) {
            if (chasseurs.stream().anyMatch(p -> sur(p, c))) {
                gardienLance = true;
                Sbire g = Boss.poserSbire(l, Donjon.BRUTE.get(), c.x, c.y, c.z + 4, c);
                if (g != null) {
                    AttributeInstance vie = g.getAttribute(Attributes.MAX_HEALTH);
                    if (vie != null) {
                        vie.setBaseValue(vie.getBaseValue() * 3);
                        g.setHealth(g.getMaxHealth());
                    }
                    AttributeInstance force = g.getAttribute(Attributes.ATTACK_DAMAGE);
                    if (force != null) {
                        force.setBaseValue(force.getBaseValue() * 1.3);
                    }
                    g.setCustomName(Component.literal("Gardien du sceau").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                    g.setCustomNameVisible(true);
                    g.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 600, 0, false, false));
                    g.setPersistenceRequired();
                    gardien = g.getUUID();
                }
                l.playSound(null, c.x, c.y, c.z, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 2F, 0.8F);
                for (ServerPlayer p : chasseurs) {
                    if (p.distanceToSqr(c) < 30 * 30) {
                        barre(p, "Le gardien du sceau se réveille !", ChatFormatting.DARK_RED);
                    }
                }
            }
            return;
        }
        if (!l.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(BlockPos.containing(c)))) {
            return;
        }
        Entity e = gardien == null ? null : l.getEntity(gardien);
        if (e == null || !e.isAlive()) {
            gardien = null;
            reveil(l, Sorte.GARDIEN, "Le gardien est tombé : le sceau du gardien s'éveille.", ordreFait, codeFait);
        }
    }

    // ---------------------------------------------------------------- la porte des sceaux

    /**
     * La porte des sceaux : les deux sceaux des énigmes ({@code a}, {@code b}, éveillés ou non, et le message quand on
     * monte sur un sceau qui dort) plus ceux des rôles de ce groupe. Elle s'ouvre quand tous sont éveillés et qu'au moins
     * autant de sceaux que de chasseurs (au plus tous les sceaux) sont tenus en même temps. Dit à ceux qui sont dessus ce
     * qu'il manque.
     */
    boolean ouvrir(ServerLevel l, List<ServerPlayer> chasseurs, Vec3 a, boolean ea, String dortA, Vec3 b, boolean eb, String dortB) {
        enigmes(ea, eb);
        List<Vec3> sceaux = new ArrayList<>(List.of(a, b));
        List<Boolean> eveilles = new ArrayList<>(List.of(ea, eb));
        List<String> dorts = new ArrayList<>(List.of(dortA, dortB));
        for (Sorte s : actives()) {
            sceaux.add(centre(s));
            eveilles.add(fait(s));
            dorts.add(dort(s));
        }
        boolean tous = !eveilles.contains(false);
        int tenus = 0;
        for (int i = 0; i < sceaux.size(); i++) {
            Vec3 s = sceaux.get(i);
            if (chasseurs.stream().anyMatch(p -> sur(p, s))) {
                tenus++;
            }
        }
        int besoin = Math.max(1, Math.min(sceaux.size(), chasseurs.size()));
        if (tous && tenus >= besoin) {
            return true;
        }
        if (l.getServer().getTickCount() % 20 != 0) {
            return false;
        }
        int reste = (int) eveilles.stream().filter(e -> !e).count();
        for (ServerPlayer p : chasseurs) {
            for (int i = 0; i < sceaux.size(); i++) {
                if (!sur(p, sceaux.get(i))) {
                    continue;
                }
                if (!eveilles.get(i)) {
                    barre(p, dorts.get(i), ChatFormatting.GRAY);
                } else if (!tous) {
                    barre(p, "Ce sceau est éveillé. Encore " + reste + " sceau" + (reste > 1 ? "x" : "") + " à éveiller.", ChatFormatting.AQUA);
                } else {
                    barre(p, "Sceaux tenus : " + tenus + " / " + besoin + " — il en faut " + besoin + " en même temps !", ChatFormatting.AQUA);
                }
            }
            // un sceau en plus qui ne compte pas pour ce groupe
            for (Sorte s : Sorte.values()) {
                if (!actif(s) && sur(p, centre(s))) {
                    barre(p, "Ce sceau ne s'éveille que pour un groupe de " + s.des + " chasseurs ou plus.", ChatFormatting.DARK_GRAY);
                }
            }
        }
        return false;
    }

    private String dort(Sorte s) {
        return switch (s) {
            case SANG -> vagueLancee ? "Tuez toute la vague pour éveiller ce sceau." : "Restez dessus : une vague va surgir.";
            case CRISTAUX -> "Frappez les 3 pierres d'écho cachées dans l'aile de " + lieux.aileCoffre().toLowerCase() + ".";
            case CLOCHES -> "Sonnez les 2 cloches des salles des énigmes à moins de 3 s d'écart.";
            case COURSE -> "Le bouton de départ est de l'autre côté de cette salle : appuyez, puis foncez !";
            case GARDIEN -> gardienLance ? "Battez le gardien du sceau." : "Restez dessus : le gardien va se réveiller.";
        };
    }

    // ---------------------------------------------------------------- les groupes : monstres et boss

    /** Combien de chasseurs se battent dans ce donjon (au moins un). */
    static int chasseurs(ServerLevel l, TypeDonjon type) {
        int n = 0;
        for (ServerPlayer p : Boss.joueurs(l, type)) {
            if (p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /** Les monstres des salles : ×0,6 seul, ×1 à trois, jusqu'à ×1,8 à sept. */
    static int monstres(int base, int chasseurs) {
        return base <= 0 ? 0 : Math.max(1, (int) Math.round(base * (0.4 + 0.2 * Math.min(7, chasseurs))));
    }

    /**
     * Les coups sur un boss comptent plus quand on est peu : ×2,5 seul (il a donc 40 % de sa vie), ×1 à quatre,
     * ×0,75 à six et plus.
     */
    static float forceContreBoss(int chasseurs) {
        return Math.max(0.75F, Math.min(2.5F, 5F / (chasseurs + 1)));
    }
}
