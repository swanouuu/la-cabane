package fr.cabane.labo;

import fr.cabane.Cabane;
import fr.cabane.CabaneEntities;
import fr.cabane.hachibi3.Hachibi3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Le sceau de la salle des rituels : un bouton rouge sur l'estrade, au pied de l'autel. Quand on appuie, la grotte gronde
 * et tremble, puis son mur est explose : derrière, dans le vide, le Hachibi géant est enchaîné sur son rocher, et il
 * rugit. C'est lui qu'on scellera dans quelqu'un sur l'autel (pour le RP). Il n'attaque personne. Rappuyer le fait
 * seulement rugir ; si le labo a été reconstruit (le mur est revenu), le bouton recasse le mur sans en faire un second.
 *
 * <p>Coordonnées relatives au centre du labo, comme dans LaboBuilder.
 */
@EventBusSubscriber(modid = Cabane.MODID)
public final class Scellement {
    /** Le bouton, sur l'estrade, au pied de l'autel. */
    static final BlockPos BOUTON = new BlockPos(LaboBuilder.RX - 1, 3, LaboBuilder.RZ);
    /** Le trou dans le mur est de la grotte : il va de x = MUR_X0 au bord (MUR_X1), centré en (RZ, TROU_Y). */
    private static final int MUR_X0 = 43, MUR_X1 = 50, TROU_Y = 5;
    private static final double TROU_DZ = 8.5, TROU_DY = 6.8;
    /** Le rocher du Hachibi, dans le vide à l'est : son centre, son dessus (sous le sol du labo), son rayon. */
    private static final int ILE_X = 130, ILE_Y = -31, ILE_R = 46;

    /** Où en est la libération (en ticks depuis l'appui), -1 si rien n'est en cours. */
    private static int etape = -1;

    /** Le mur est-il déjà cassé ? (alors la salle n'est plus plongée dans le noir) */
    static boolean ouvert(ServerLevel level) {
        BlockPos p = LaboBuilder.abs(MUR_X1, TROU_Y, LaboBuilder.RZ);
        return level.isLoaded(p) && level.getBlockState(p).isAir();
    }

    @SubscribeEvent
    public static void onClic(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(Labo.DIMENSION) && event.getHand() == InteractionHand.MAIN_HAND
                && event.getPos().equals(LaboBuilder.abs(BOUTON.getX(), BOUTON.getY(), BOUTON.getZ()))) {
            liberer(level);
        }
    }

    /** L'appui sur le bouton (ou /hachibi3 sceau) : la libération, ou juste un cri s'il est déjà là, visible. */
    public static void liberer(ServerLevel level) {
        if (etape >= 0) {
            return;
        }
        Hachibi3 bete = bete(level);
        if (bete != null && ouvert(level)) {
            bete.crier();
            return;
        }
        etape = 0;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (etape < 0) {
            return;
        }
        ServerLevel level = Labo.niveau(event.getServer());
        if (level == null) {
            etape = -1;
            return;
        }
        etape++;
        BlockPos mur = LaboBuilder.abs(MUR_X1 - 2, TROU_Y, LaboBuilder.RZ);
        RandomSource r = level.random;
        if (etape == 1) {
            // il est déjà là, derrière le mur ; la grotte se met à gronder
            preparer(level);
            level.playSound(null, mur, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 4F, 0.5F);
            level.playSound(null, mur, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 4F, 0.6F);
        }
        if (etape < 50 && etape % 4 == 0) {
            // la poussière tombe de la voûte, le mur se fissure
            for (int k = 0; k < 6; k++) {
                double x = LaboBuilder.RX + r.nextGaussian() * 8, z = LaboBuilder.RZ + r.nextGaussian() * 7;
                BlockPos voute = LaboBuilder.abs(Mth.floor(x), 9, Mth.floor(z));
                level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.TUFF.defaultBlockState()), x, voute.getY(), z, 3, 0.5, 0.2,
                        0.5, 0);
            }
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState()), mur.getX() + 0.5, mur.getY(),
                    mur.getZ() + 0.5, 12, 0.6, 3, 5, 0.1);
            level.playSound(null, mur, SoundEvents.DEEPSLATE_BREAK, SoundSource.BLOCKS, 2F, 0.5F + r.nextFloat() * 0.3F);
        }
        if (etape == 30) {
            level.playSound(null, mur, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 2F, 0.4F);
        }
        if (etape == 50) {
            casser(level);
        }
        if (etape == 62) {
            Hachibi3 bete = bete(level);
            if (bete != null) {
                bete.crier();
            }
        }
        if (etape >= 80) {
            etape = -1;
        }
    }

    /** Le Hachibi enchaîné sur son rocher : on construit le rocher et on le pose, s'il n'y est pas déjà. */
    private static void preparer(ServerLevel level) {
        if (bete(level) != null) {
            return;
        }
        int cx = ILE_X, cz = LaboBuilder.RZ, haut = LaboBuilder.SOL + ILE_Y;
        for (int x = (cx - ILE_R - 6) >> 4; x <= (cx + ILE_R + 6) >> 4; x++) {
            for (int z = (cz - ILE_R - 6) >> 4; z <= (cz + ILE_R + 6) >> 4; z++) {
                level.getChunk(x, z);
            }
        }
        RandomSource r = RandomSource.create(0x8B1L);
        int flags = Block.UPDATE_CLIENTS;
        for (int dx = -ILE_R - 5; dx <= ILE_R + 5; dx++) {
            for (int dz = -ILE_R - 5; dz <= ILE_R + 5; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz), bord = ILE_R + (bruit(dx, dz) - 0.5) * 9;
                if (d > bord) {
                    continue;
                }
                double f = 1 - d / bord;
                int fond = haut - (int) (2 + 15 * Math.pow(f, 0.8) + bruit(dx * 5, dz * 5) * 3);
                for (int y = fond; y <= haut; y++) {
                    BlockState etat = y == haut ? dessus(r, dx, dz, d) : y > haut - 3 ? (r.nextFloat() < 0.5F ? Blocks.BLACKSTONE : Blocks.DEEPSLATE).defaultBlockState()
                            : (r.nextFloat() < 0.4F ? Blocks.DEEPSLATE : r.nextFloat() < 0.5F ? Blocks.TUFF : Blocks.BLACKSTONE).defaultBlockState();
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), etat, flags);
                }
            }
        }
        Hachibi3 bete = CabaneEntities.HACHIBI3.get().create(level);
        if (bete != null) {
            // il regarde vers le labo (vers l'ouest)
            bete.moveTo(cx + 0.5, haut + 1, cz + 0.5, 90, 0);
            level.addFreshEntity(bete);
        }
    }

    /** Le dessus du rocher : de la roche noire, des plaques de nylium cramoisi, du magma qui luit, du sang séché sous lui. */
    private static BlockState dessus(RandomSource r, int dx, int dz, double d) {
        double b = bruit(dx * 3 + 7, dz * 3 - 5);
        if (r.nextFloat() < 0.035F) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (d < 16 && r.nextFloat() < 0.04F) {
            return Blocks.SHROOMLIGHT.defaultBlockState();
        }
        if (b > 0.72) {
            return Blocks.CRIMSON_NYLIUM.defaultBlockState();
        }
        if (d < 22 && b < 0.25) {
            return Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState();
        }
        return (r.nextFloat() < 0.5F ? Blocks.BLACKSTONE : r.nextFloat() < 0.5F ? Blocks.COBBLED_DEEPSLATE : Blocks.BASALT).defaultBlockState();
    }

    /** Le mur explose : un grand trou rond vers le vide, des blocs projetés dehors, une barrière invisible au bord. */
    private static void casser(ServerLevel level) {
        RandomSource r = level.random;
        int rz = LaboBuilder.RZ;
        int compte = 0;
        for (int x = MUR_X0; x <= MUR_X1; x++) {
            for (int z = rz - (int) TROU_DZ - 1; z <= rz + (int) TROU_DZ + 1; z++) {
                for (int y = 1; y <= TROU_Y + (int) TROU_DY + 1; y++) {
                    double dz = (z - rz) / TROU_DZ, dy = (y - TROU_Y) / TROU_DY;
                    if (dz * dz + dy * dy > 1 + (bruit(x * 13 + y, z * 7) - 0.5) * 0.35) {
                        continue;
                    }
                    BlockPos p = LaboBuilder.abs(x, y, z);
                    BlockState etat = level.getBlockState(p);
                    if (etat.isAir()) {
                        continue;
                    }
                    if (etat.isCollisionShapeFullBlock(level, p) && compte++ % 3 == 0) {
                        // un bloc sur trois est projeté dehors, vers le vide
                        FallingBlockEntity bloc = FallingBlockEntity.fall(level, p, etat);
                        // en retombant, il disparaît (sans se reposer au bord du trou ni sur le rocher)
                        bloc.disableDrop();
                        bloc.dropItem = false;
                        bloc.setDeltaMovement(0.5 + r.nextDouble() * 0.9, 0.2 + r.nextDouble() * 0.5, (r.nextDouble() - 0.5) * 0.7);
                    } else {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, etat), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.1);
                    }
                }
            }
        }
        // personne ne tombe dans le vide : une barrière invisible au bord du trou
        for (int z = rz - (int) TROU_DZ - 1; z <= rz + (int) TROU_DZ + 1; z++) {
            for (int y = 1; y <= 4; y++) {
                BlockPos p = LaboBuilder.abs(MUR_X1 + 1, y, z);
                if (level.getBlockState(p).isAir()) {
                    level.setBlock(p, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        Vec3 centre = Vec3.atCenterOf(LaboBuilder.abs(MUR_X1 - 1, TROU_Y, rz));
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, centre.x, centre.y, centre.z, 2, 0.5, 2, 3, 0);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, centre.x, centre.y, centre.z, 40, 1, 3, 4, 0.05);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 6F, 0.6F);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 6F, 0.4F);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.BLOCKS, 4F, 0.5F);
        // le noir de la salle se lève : on doit le voir
        for (ServerPlayer joueur : level.players()) {
            joueur.removeEffect(MobEffects.DARKNESS);
        }
    }

    private static Hachibi3 bete(ServerLevel level) {
        BlockPos ile = new BlockPos(ILE_X, LaboBuilder.SOL + ILE_Y, LaboBuilder.RZ);
        if (!level.isLoaded(ile)) {
            level.getChunk(ile);
        }
        return level.getEntitiesOfClass(Hachibi3.class, new AABB(ile).inflate(90)).stream().findFirst().orElse(null);
    }

    /** Un bruit fixe entre 0 et 1, pour que le rocher et le trou aient toujours la même forme. */
    private static double bruit(int x, int z) {
        return ((Mth.getSeed(x, 0, z) >> 16) & 0xFFFF) / 65535.0;
    }

    private Scellement() {
    }
}
