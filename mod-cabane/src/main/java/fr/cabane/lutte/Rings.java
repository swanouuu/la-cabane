package fr.cabane.lutte;

import fr.cabane.lutte.LutteData.Ring;
import fr.cabane.lutte.RingBlocs.Couleur;
import fr.cabane.lutte.RingBlocs.Lettre;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Poser, retrouver et ranger les rings (ils sont tous retenus dans {@link LutteData}). */
public final class Rings {
    public static final int MIN = 5, MAX = 40;
    private static final String MOT = "CATCH";

    /** Le ring où se trouve ce point, ou null. */
    public static Ring trouver(Level level, Vec3 pos) {
        if (level.getServer() == null) {
            return null;
        }
        for (Ring r : LutteData.get(level.getServer()).rings()) {
            if (r.dim().equals(level.dimension().location()) && r.dedans(pos)) {
                return r;
            }
        }
        return null;
    }

    /** Le ring dont ce bloc fait partie, ou null. */
    public static Ring duBloc(Level level, BlockPos pos) {
        if (level.getServer() == null) {
            return null;
        }
        for (Ring r : LutteData.get(level.getServer()).rings()) {
            if (r.dim().equals(level.dimension().location()) && r.contient(pos)) {
                return r;
            }
        }
        return null;
    }

    /** Les blocs au-dessus du sol sont-ils libres (de quoi poser le ring, ses cordes et marcher dessus) ? */
    public static boolean place(ServerLevel level, int x0, int y0, int z0, int l, int p) {
        for (int x = x0; x < x0 + l; x++) {
            for (int z = z0; z < z0 + p; z++) {
                for (int y = y0; y < y0 + 4; y++) {
                    BlockState s = level.getBlockState(new BlockPos(x, y, z));
                    if (!s.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Pose un ring (le plancher, les lettres, les cordes, les poteaux, deux marches) et le retient. */
    public static Ring construire(ServerLevel level, int x0, int y0, int z0, int l, int p, Couleur couleur, boolean salle) {
        Block tablier = Lutte.TABLIER.get(), tapis = Lutte.TAPIS.get();
        for (int x = x0; x < x0 + l; x++) {
            for (int z = z0; z < z0 + p; z++) {
                Lettre lettre = lettre(x - x0, z - z0, l, p);
                level.setBlock(new BlockPos(x, y0, z), tablier.defaultBlockState().setValue(RingBlocs.LETTRE, lettre), 2);
                level.setBlock(new BlockPos(x, y0 + 1, z), tapis.defaultBlockState().setValue(RingBlocs.LETTRE, lettre), 2);
                for (int y = y0 + 2; y < y0 + 4; y++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        int y = y0 + 2, x1 = x0 + l - 1, z1 = z0 + p - 1;
        BlockState corde = Lutte.CORDE.get().defaultBlockState().setValue(RingBlocs.COULEUR, couleur);
        for (int x = x0 + 1; x < x1; x++) {
            level.setBlock(new BlockPos(x, y, z0), corde.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 2);
            level.setBlock(new BlockPos(x, y, z1), corde.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 2);
        }
        for (int z = z0 + 1; z < z1; z++) {
            level.setBlock(new BlockPos(x0, y, z), corde.setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 2);
            level.setBlock(new BlockPos(x1, y, z), corde.setValue(HorizontalDirectionalBlock.FACING, Direction.EAST), 2);
        }
        BlockState poteau = Lutte.POTEAU.get().defaultBlockState().setValue(RingBlocs.COULEUR, couleur);
        level.setBlock(new BlockPos(x0, y, z0), poteau.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 2);
        level.setBlock(new BlockPos(x1, y, z0), poteau.setValue(HorizontalDirectionalBlock.FACING, Direction.EAST), 2);
        level.setBlock(new BlockPos(x1, y, z1), poteau.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 2);
        level.setBlock(new BlockPos(x0, y, z1), poteau.setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 2);
        // deux marches d'acier contre le ring, au sud-est et au nord-ouest (si la place est libre)
        marche(level, new BlockPos(x1 - 1, y0, z1 + 1), Direction.NORTH);
        marche(level, new BlockPos(x0 + 1, y0, z0 - 1), Direction.SOUTH);
        Ring r = new Ring(level.dimension().location(), x0, y0, z0, l, p, couleur, salle);
        LutteData.get(level.getServer()).ajouter(r);
        return r;
    }

    private static void marche(ServerLevel level, BlockPos pos, Direction vers) {
        if (level.getBlockState(pos).canBeReplaced()) {
            level.setBlock(pos, Lutte.MARCHES.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, vers), 2);
        }
    }

    /** CATCH au milieu de chaque côté (lu de l'extérieur), une lettre par colonne, si le côté est assez long. */
    private static Lettre lettre(int i, int k, int l, int p) {
        // côté nord (k = 0) : lu de l'extérieur, la première lettre est à l'est ; côté sud : à l'ouest ; est : au sud ; ouest : au nord
        int n = MOT.length();
        if (k == 0 && l >= n + 2) {
            return lettreA(l - 1 - i - (l - n) / 2);
        }
        if (k == p - 1 && l >= n + 2) {
            return lettreA(i - (l - n) / 2);
        }
        if (i == l - 1 && p >= n + 2) {
            return lettreA(p - 1 - k - (p - n) / 2);
        }
        if (i == 0 && p >= n + 2) {
            return lettreA(k - (p - n) / 2);
        }
        return Lettre.VIDE;
    }

    private static Lettre lettreA(int j) {
        if (j < 0 || j >= MOT.length()) {
            return Lettre.VIDE;
        }
        return Lettre.valueOf(String.valueOf(MOT.charAt(j)));
    }

    /** Enlève un ring (tous ses blocs) et l'oublie. */
    public static void demonter(ServerLevel level, Ring r) {
        for (int x = r.x0() - 1; x <= r.x0() + r.l(); x++) {
            for (int z = r.z0() - 1; z <= r.z0() + r.p(); z++) {
                for (int y = r.y0(); y <= r.y0() + 2; y++) {
                    BlockPos b = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(b);
                    if (s.is(Lutte.TAPIS.get()) || s.is(Lutte.TABLIER.get()) || s.is(Lutte.CORDE.get()) || s.is(Lutte.POTEAU.get()) || s.is(Lutte.MARCHES.get())) {
                        level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        LutteData.get(level.getServer()).enlever(r);
    }

    /** Un bloc du ring cassé : le ring se range dans un kit (pas celui de la salle, voir Lutte.onCasse). */
    static void ranger(ServerLevel level, BlockPos poteau, ServerPlayer joueur) {
        Ring r = duBloc(level, poteau);
        if (r == null) {
            return;
        }
        if (r.salle()) {
            joueur.displayClientMessage(Component.literal("Le ring de la salle reste là.").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        demonter(level, r);
        ItemStack kit = new ItemStack(Lutte.KIT.get());
        kit.set(Lutte.KIT_COULEUR.get(), r.couleur().ordinal());
        if (!joueur.getInventory().add(kit)) {
            joueur.drop(kit, false);
        }
        level.playSound(null, poteau, SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.BLOCKS, 1F, 0.8F);
        joueur.displayClientMessage(Component.literal("Le ring est rangé dans le kit.").withStyle(ChatFormatting.GOLD), true);
    }

    /** Dans un ring, ou à la salle : c'est là que le catch compte (prises, armure de la tenue, KO au lieu de mourir). */
    public static boolean zone(LivingEntity e) {
        return e.level().dimension() == Lutte.DIMENSION || trouver(e.level(), e.position()) != null;
    }

    private Rings() {
    }
}
