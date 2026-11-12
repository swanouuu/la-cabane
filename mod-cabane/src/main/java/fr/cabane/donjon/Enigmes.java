package fr.cabane.donjon;

import fr.cabane.Cabane;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Les énigmes du donjon et ses deux portes.
 *
 * <ul>
 * <li>La salle des braseros (aile ouest) : quatre braseros, un bouton devant chacun. Il faut les allumer dans l'ordre
 * gravé au fond d'un cul-de-sac… de l'aile est (nord, sud, est, ouest). Une erreur : tout s'éteint, et des gobelins
 * sortent du sol.</li>
 * <li>La salle des leviers (aile est) : quatre leviers numérotés ; le code (I et III levés) est gravé au fond d'un
 * cul-de-sac… de l'aile ouest. Chaque levier levé allume son cristal.</li>
 * <li>Une énigme résolue réveille le sceau de sa salle. La porte des sceaux, au sud du carrefour, ne s'ouvre que si
 * quelqu'un se tient sur chacun des deux sceaux en même temps : il faut se séparer.</li>
 * <li>La grande porte de l'arène ne s'ouvre que quand tous les chasseurs du donjon sont dans l'antichambre ; elle se
 * referme derrière eux quand Ugrak se réveille, et se rouvre quand il est mort (ou rendormi).</li>
 * </ul>
 * L'état se remet à zéro avec chaque nouveau groupe (et au démarrage du serveur, si le donjon est vide).
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Enigmes {
    /** Un brasero : son nom, sa place par rapport au centre de la salle, l'orientation de son panneau (vers le centre). */
    public record Brasero(String nom, int dx, int dz, int versCentre) {
    }

    /** Dans l'ordre de la solution. */
    public static final List<Brasero> BRASEROS = List.of(new Brasero("NORD", 0, -6, 0), new Brasero("SUD", 0, 6, 8), new Brasero("EST", 6, 0, 4),
            new Brasero("OUEST", -6, 0, 12));
    /** Le code des leviers I à IV. */
    private static final boolean[] CODE = {true, false, true, false};
    /** Les sceaux sont à tant de blocs au sud du centre de leur salle. */
    public static final int DZ_SCEAU = 9;

    private static int allumes;
    private static boolean braserosResolus;
    private static boolean leviersResolus;
    private static boolean sceauxOuverts;
    private static boolean porteOuverte;
    private static boolean combat;
    private static final boolean[] BOUTONS = new boolean[4];

    private static int sol() {
        return DonjonBuilder.SOL;
    }

    private static BlockPos brasero(int i) {
        Brasero b = BRASEROS.get(i);
        return new BlockPos(DonjonBuilder.SALLE_BRASEROS[0] + b.dx(), sol() + 1, DonjonBuilder.SALLE_BRASEROS[1] + b.dz());
    }

    private static BlockPos bouton(int i) {
        Brasero b = BRASEROS.get(i);
        return new BlockPos(DonjonBuilder.SALLE_BRASEROS[0] + b.dx() * 2 / 3, sol() + 1, DonjonBuilder.SALLE_BRASEROS[1] + b.dz() * 2 / 3);
    }

    private static BlockPos levier(int k) {
        return new BlockPos(DonjonBuilder.SALLE_LEVIERS[0] - 3 + k * 2, sol() + 2, DonjonBuilder.SALLE_LEVIERS[1] + 5);
    }

    private static BlockPos cristal(int k) {
        return new BlockPos(DonjonBuilder.SALLE_LEVIERS[0] - 3 + k * 2, sol() + 3, DonjonBuilder.SALLE_LEVIERS[1] + 6);
    }

    private static Vec3 sceau(int[] salle) {
        return new Vec3(salle[0] + 0.5, sol() + 1, salle[1] + DZ_SCEAU + 0.5);
    }

    private static void pose(ServerLevel l, BlockPos p, BlockState s) {
        l.setBlock(p, s, Block.UPDATE_ALL);
    }

    // ---------------------------------------------------------------- l'état de départ

    /** Remet tout à zéro : portes fermées, braseros éteints, leviers baissés, cristaux éteints. */
    public static void reinitialiser(ServerLevel l) {
        allumes = 0;
        braserosResolus = false;
        leviersResolus = false;
        sceauxOuverts = false;
        porteOuverte = false;
        combat = false;
        for (int i = 0; i < 4; i++) {
            pose(l, brasero(i), Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
            pose(l, bouton(i), Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, AttachFace.FLOOR));
            BOUTONS[i] = false;
            pose(l, levier(i), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.NORTH)
                    .setValue(LeverBlock.POWERED, false));
            pose(l, cristal(i), Blocks.TINTED_GLASS.defaultBlockState());
        }
        porteDesSceaux(l, false);
        grandePorte(l, false);
    }

    /** La porte des sceaux : une grille de fer entre deux colonnes d'obsidienne pleureuse, ou rien. */
    private static void porteDesSceaux(ServerLevel l, boolean ouverte) {
        int z = DonjonBuilder.PORTE_SCEAUX_Z;
        for (int x = -3; x <= 3; x++) {
            for (int y = sol() + 1; y <= sol() + 8; y++) {
                BlockState s = ouverte ? Blocks.AIR.defaultBlockState()
                        : Math.abs(x) == 3 || y == sol() + 8 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState();
                pose(l, new BlockPos(x, y, z), s);
            }
        }
    }

    /** La grande porte de l'arène : deux battants de pierre noire, une couture d'obsidienne pleureuse au milieu, ou rien. */
    private static void grandePorte(ServerLevel l, boolean ouverte) {
        int z = DonjonBuilder.GRANDE_PORTE_Z;
        int s0 = DonjonBuilder.SOL_BAS;
        for (int x = -3; x <= 3; x++) {
            for (int y = s0 + 1; y <= s0 + 9; y++) {
                BlockState s = ouverte ? Blocks.AIR.defaultBlockState()
                        : x == 0 ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                        : (y - s0) % 4 == 0 ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                pose(l, new BlockPos(x, y, z), s);
            }
        }
    }

    /** Un panneau debout, gravé de quatre lignes qui luisent en bleu, que personne ne peut modifier. */
    static void panneau(ServerLevel l, BlockPos p, int rotation, String l1, String l2, String l3, String l4) {
        l.setBlock(p, Blocks.CRIMSON_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation), Block.UPDATE_CLIENTS);
        if (l.getBlockEntity(p) instanceof SignBlockEntity s) {
            SignText t = new SignText().setColor(DyeColor.LIGHT_BLUE).setHasGlowingText(true);
            String[] lignes = {l1, l2, l3, l4};
            for (int i = 0; i < 4; i++) {
                t = t.setMessage(i, Component.literal(lignes[i]));
            }
            s.setText(t, true);
            s.setWaxed(true);
            s.setChanged();
            l.sendBlockUpdated(p, s.getBlockState(), s.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Le coffre au bout du cul-de-sac de l'est : de quoi tenir face au boss. */
    static void coffre(ServerLevel l, BlockPos p) {
        l.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), Block.UPDATE_CLIENTS);
        if (l.getBlockEntity(p) instanceof ChestBlockEntity c) {
            c.clearContent();
            c.setItem(11, new ItemStack(Items.TOTEM_OF_UNDYING));
            c.setItem(13, new ItemStack(Items.GOLDEN_APPLE, 4));
            c.setItem(15, new ItemStack(Items.COOKED_BEEF, 12));
            c.setItem(22, new ItemStack(Items.ENDER_PEARL, 4));
            c.setChanged();
        }
    }

    // ---------------------------------------------------------------- en jeu

    private static List<ServerPlayer> chasseurs(ServerLevel l) {
        List<ServerPlayer> c = new ArrayList<>();
        for (ServerPlayer p : l.players()) {
            if (!p.isSpectator() && p.isAlive()) {
                c.add(p);
            }
        }
        return c;
    }

    private static boolean surSceau(ServerPlayer p, int[] salle) {
        Vec3 s = sceau(salle);
        return Math.abs(p.getX() - s.x) < 1.6 && Math.abs(p.getZ() - s.z) < 1.6 && Math.abs(p.getY() - s.y) < 1.5;
    }

    private static boolean dansAntichambre(ServerPlayer p) {
        return Math.abs(p.getX() - 0.5) <= 9 && p.getZ() >= 128 && p.getZ() < DonjonBuilder.GRANDE_PORTE_Z && p.getY() < DonjonBuilder.SOL_BAS + 14;
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
        braseros(l);
        leviers(l);
        sceaux(l);
        porte(l);
    }

    private static void braseros(ServerLevel l) {
        for (int i = 0; i < 4; i++) {
            BlockPos b = bouton(i);
            if (!l.isLoaded(b)) {
                return;
            }
            BlockState s = l.getBlockState(b);
            boolean appuye = s.hasProperty(ButtonBlock.POWERED) && s.getValue(ButtonBlock.POWERED);
            boolean nouveau = appuye && !BOUTONS[i];
            BOUTONS[i] = appuye;
            if (!nouveau || braserosResolus) {
                continue;
            }
            if (i == allumes) {
                allumes++;
                pose(l, brasero(i), Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                Vec3 c = Vec3.atCenterOf(brasero(i));
                l.playSound(null, c.x, c.y, c.z, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1F, 0.7F);
                l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.5, c.z, 20, 0.3, 0.4, 0.3, 0.03);
                if (allumes == 4) {
                    braserosResolus = true;
                    reveilSceau(l, DonjonBuilder.SALLE_BRASEROS, "Les quatre feux brûlent : le sceau de l'ouest s'éveille.");
                }
            } else {
                // mauvais ordre : tout s'éteint, et la salle se défend
                allumes = 0;
                for (int k = 0; k < 4; k++) {
                    pose(l, brasero(k), Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false));
                }
                Vec3 c = new Vec3(DonjonBuilder.SALLE_BRASEROS[0] + 0.5, sol() + 1, DonjonBuilder.SALLE_BRASEROS[1] + 0.5);
                l.playSound(null, c.x, c.y, c.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.5F, 0.6F);
                l.playSound(null, c.x, c.y, c.z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1F, 1.6F);
                for (int k = 0; k < 3; k++) {
                    double a = k * Math.PI * 2 / 3 + 0.5;
                    Boss.poserSbire(l, Donjon.GOBELIN.get(), c.x + Math.cos(a) * 4, c.y, c.z + Math.sin(a) * 4, c);
                }
                for (ServerPlayer p : l.players()) {
                    if (p.distanceToSqr(c) < 16 * 16) {
                        barre(p, "Mauvais ordre ! Les feux s'éteignent…", ChatFormatting.RED);
                    }
                }
            }
        }
    }

    private static void leviers(ServerLevel l) {
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
            bon &= leve == CODE[k];
        }
        if (bon && !leviersResolus) {
            leviersResolus = true;
            reveilSceau(l, DonjonBuilder.SALLE_LEVIERS, "Les bons cristaux brillent : le sceau de l'est s'éveille.");
        }
    }

    private static void reveilSceau(ServerLevel l, int[] salle, String texte) {
        Vec3 s = sceau(salle);
        l.playSound(null, s.x, s.y, s.z, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.5F, 0.8F);
        l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, s.x, s.y + 0.2, s.z, 40, 0.8, 0.2, 0.8, 0.05);
        Boss.annoncer(l, "SYSTÈME", new String[] {texte, braserosResolus && leviersResolus
                ? "Tenez les deux sceaux en même temps pour ouvrir la porte." : "Il reste l'autre énigme."}, "", 0);
    }

    private static void sceaux(ServerLevel l) {
        // les sceaux éveillés brillent
        if (l.getServer().getTickCount() % 10 == 0) {
            for (int[] salle : new int[][] {DonjonBuilder.SALLE_BRASEROS, DonjonBuilder.SALLE_LEVIERS}) {
                boolean eveille = salle == DonjonBuilder.SALLE_BRASEROS ? braserosResolus : leviersResolus;
                if (eveille && !sceauxOuverts) {
                    Vec3 s = sceau(salle);
                    l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, s.x, s.y + 0.1, s.z, 4, 0.6, 0.05, 0.6, 0.01);
                    l.sendParticles(ParticleTypes.END_ROD, s.x, s.y + 0.5, s.z, 1, 0.4, 0.4, 0.4, 0.01);
                }
            }
        }
        if (sceauxOuverts) {
            return;
        }
        boolean ouest = false, est = false;
        List<ServerPlayer> dessus = new ArrayList<>();
        for (ServerPlayer p : chasseurs(l)) {
            boolean o = surSceau(p, DonjonBuilder.SALLE_BRASEROS), e = surSceau(p, DonjonBuilder.SALLE_LEVIERS);
            ouest |= o;
            est |= e;
            if (o || e) {
                dessus.add(p);
            }
        }
        if (braserosResolus && leviersResolus && ouest && est) {
            sceauxOuverts = true;
            porteDesSceaux(l, true);
            int z = DonjonBuilder.PORTE_SCEAUX_Z;
            l.playSound(null, 0.5, sol() + 3, z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 2F, 0.5F);
            l.playSound(null, 0.5, sol() + 3, z, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.8F, 1.2F);
            l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, 0.5, sol() + 4, z + 0.5, 60, 2.5, 2.5, 0.3, 0.03);
            Boss.annoncer(l, "SYSTÈME", new String[] {"Les deux sceaux répondent.", "La porte des sceaux est ouverte, au sud du carrefour."}, "", 0);
            for (ServerPlayer p : l.players()) {
                fr.cabane.pnj.Missions.evenement(p, "sceaux");
            }
            return;
        }
        if (l.getServer().getTickCount() % 20 == 0) {
            for (ServerPlayer p : dessus) {
                boolean o = surSceau(p, DonjonBuilder.SALLE_BRASEROS);
                boolean eveille = o ? braserosResolus : leviersResolus;
                if (!eveille) {
                    barre(p, o ? "Ce sceau dort encore : allumez les braseros dans le bon ordre." : "Ce sceau dort encore : trouvez le code des leviers.",
                            ChatFormatting.GRAY);
                } else if (!(braserosResolus && leviersResolus)) {
                    barre(p, "Le sceau est éveillé. Il faut aussi éveiller l'autre.", ChatFormatting.AQUA);
                } else {
                    barre(p, "Il faut quelqu'un sur l'autre sceau, en même temps !", ChatFormatting.AQUA);
                }
            }
        }
    }

    private static void porte(ServerLevel l) {
        if (porteOuverte || combat || l.getServer().getTickCount() % 10 != 0) {
            return;
        }
        List<ServerPlayer> tous = chasseurs(l);
        List<ServerPlayer> devant = tous.stream().filter(Enigmes::dansAntichambre).toList();
        if (devant.isEmpty()) {
            return;
        }
        if (devant.size() >= tous.size()) {
            porteOuverte = true;
            grandePorte(l, true);
            int z = DonjonBuilder.GRANDE_PORTE_Z;
            l.playSound(null, 0.5, DonjonBuilder.SOL_BAS + 4, z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 3F, 0.4F);
            l.playSound(null, 0.5, DonjonBuilder.SOL_BAS + 4, z, SoundEvents.WARDEN_EMERGE, SoundSource.BLOCKS, 1.5F, 0.6F);
            l.sendParticles(ParticleTypes.LARGE_SMOKE, 0.5, DonjonBuilder.SOL_BAS + 4, z + 0.5, 60, 2.5, 3, 0.3, 0.03);
            Boss.annoncer(l, "SYSTÈME", new String[] {"Tous les chasseurs sont réunis.", "La grande porte s'ouvre…"}, "", 0);
            return;
        }
        for (ServerPlayer p : devant) {
            barre(p, "La grande porte attend tout le monde : " + devant.size() + " / " + tous.size() + " chasseurs", ChatFormatting.GOLD);
        }
    }

    /** Ugrak se réveille : la grande porte se referme ; ceux qui traînent dans l'antichambre sont poussés dans l'arène. */
    static void debutCombat(ServerLevel l) {
        combat = true;
        for (ServerPlayer p : chasseurs(l)) {
            if (dansAntichambre(p)) {
                p.teleportTo(l, 0.5, DonjonBuilder.SOL_BAS + 1, DonjonBuilder.GRANDE_PORTE_Z + 4.5, p.getYRot(), p.getXRot());
            }
        }
        if (porteOuverte) {
            porteOuverte = false;
            grandePorte(l, false);
            l.playSound(null, 0.5, DonjonBuilder.SOL_BAS + 4, DonjonBuilder.GRANDE_PORTE_Z, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 3F, 0.4F);
        }
    }

    /** Le combat est fini (Ugrak mort) : la grande porte se rouvre. */
    static void finCombat(ServerLevel l, boolean victoire) {
        combat = false;
        if (victoire) {
            porteOuverte = true;
            grandePorte(l, true);
        } else {
            // il s'est rendormi : il faudra de nouveau se réunir devant la porte
            porteOuverte = false;
            grandePorte(l, false);
        }
    }

    /** Pour les tests de l'hôte : les deux sceaux comme résolus, et la porte des sceaux ouverte. */
    static void toutOuvrir(ServerLevel l) {
        braserosResolus = true;
        leviersResolus = true;
        sceauxOuverts = true;
        porteDesSceaux(l, true);
    }

    private Enigmes() {
    }
}
