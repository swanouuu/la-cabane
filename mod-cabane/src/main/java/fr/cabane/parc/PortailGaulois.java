package fr.cabane.parc;

import fr.cabane.donjon.Boss;
import fr.cabane.donjon.Donjon;
import fr.cabane.donjon.TypeDonjon;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * L'événement du parc (/parc event) : un grand portail doré s'ouvre au nord de la place, le ciel se fissure autour. On
 * entre en passant dedans, et on arrive dans la Forêt gauloise, un donjon de plus dans le système de Jin-woo (mêmes
 * règles : les deux ailes à énigmes, les sceaux, la grande porte qui attend tout le groupe) avec Astérix et Obélix au
 * fond. La sortie ramène devant le portail.
 *
 * <p>Le portail est « ouvert » tant qu'un bloc de lumière invisible est posé à {@link #DRAPEAU}, très haut au-dessus de
 * la place : ça tient même après un redémarrage, et chaque joueur sait ainsi s'il doit le dessiner.
 */
public final class PortailGaulois {
    /** Le centre du portail (il est debout, face au sud), et son rayon. */
    public static final double X = 0.5, Y = Maneges.SOL + 6.8, Z = -27.5, RAYON = 6.6;
    public static final BlockPos DRAPEAU = new BlockPos(0, Maneges.SOL + 70, -27);

    public static boolean ouvert(Level l) {
        return l.getBlockState(DRAPEAU).is(Blocks.LIGHT);
    }

    static void ouvrir(ServerLevel parc, boolean oui) {
        parc.getChunk(DRAPEAU);
        parc.setBlock(DRAPEAU, oui ? Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 1) : Blocks.AIR.defaultBlockState(), 3);
        if (!oui) {
            return;
        }
        Boss.Annonce a = new Boss.Annonce("ALERTE", List.of("Un portail gaulois s'est ouvert au-dessus de la place du parc !",
                "Derrière : une forêt, un village… et deux Gaulois très costauds."), "", 0);
        for (ServerPlayer j : parc.getServer().getPlayerList().getPlayers()) {
            if (Parc.dedans(j)) {
                PacketDistributor.sendToPlayer(j, a);
                parc.playSound(null, j.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.MASTER, 0.5F, 0.6F);
            }
            j.sendSystemMessage(Component.literal("[ÉVÉNEMENT] Un portail gaulois vient de s'ouvrir au parc ! /parc pour y aller, puis passe dans le portail.")
                    .withStyle(ChatFormatting.GOLD));
        }
        parc.playSound(null, X, Y, Z, SoundEvents.END_PORTAL_SPAWN, SoundSource.MASTER, 3F, 0.8F);
    }

    static void tick(ServerLevel parc) {
        if (parc.getGameTime() % 2 != 0 || !ouvert(parc)) {
            return;
        }
        if (parc.getGameTime() % 10 == 0) {
            parc.sendParticles(ParticleTypes.END_ROD, X, Y, Z, 6, RAYON * 0.6, RAYON * 0.6, 0.2, 0.02);
        }
        for (ServerPlayer j : List.copyOf(parc.players())) {
            if (j.isSpectator() || j.isPassenger()) {
                continue;
            }
            double dz = j.getZ() - Z, dx = j.getX() - X, dy = j.getY() + 1.0 - Y;
            if (Math.abs(dz) < 0.9 && dx * dx + dy * dy < RAYON * RAYON) {
                parc.playSound(null, j.getX(), j.getY(), j.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1F, 0.6F);
                Donjon.entrerEvenement(j, TypeDonjon.GAULOIS);
            }
        }
    }

    private PortailGaulois() {
    }
}
