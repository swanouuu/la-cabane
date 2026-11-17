package fr.cabane.muscu;

import fr.cabane.CabaneBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Une machine de muscu : un bloc à poser (le grand modèle déborde sur les cases voisines, qui sont des parties
 * invisibles comme pour les tables du casino). Clic droit : on monte dessus. Le rack, lui, donne l'haltère du poids
 * qu'on vise, et reprend celui qu'on tient.
 */
public class MachineBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final VoxelShape FORME = Block.box(0, 0, 0, 16, 14, 16);
    public final Machine machine;

    public MachineBlock(Properties properties, Machine machine) {
        super(properties);
        this.machine = machine;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(p -> new MachineBlock(p, machine));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState etat = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        for (BlockPos p : parties(context.getClickedPos(), etat)) {
            if (!context.getLevel().getBlockState(p).canBeReplaced(context) || !context.getLevel().isInWorldBounds(p)) {
                if (context.getPlayer() != null && context.getLevel().isClientSide) {
                    context.getPlayer().displayClientMessage(Component.literal("Pas assez de place pour cette machine.").withStyle(ChatFormatting.RED), true);
                }
                return null;
            }
        }
        return etat;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide) {
            for (BlockPos p : parties(pos, state)) {
                level.setBlock(p, CabaneBlocks.PARTIE_TABLE.get().defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState nouvel, boolean deplace) {
        if (!state.is(nouvel.getBlock())) {
            for (BlockPos p : parties(pos, state)) {
                if (level.getBlockState(p).is(CabaneBlocks.PARTIE_TABLE.get())) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 35);
                }
            }
            if (!level.isClientSide) {
                for (SiegeMuscu s : level.getEntitiesOfClass(SiegeMuscu.class, new AABB(pos).inflate(1))) {
                    s.ejectPassengers();
                    s.discard();
                }
            }
        }
        super.onRemove(state, level, pos, nouvel, deplace);
    }

    /** Les cases en plus d'une machine posée en pos (même logique que les grandes tables du casino). */
    public static List<BlockPos> parties(BlockPos pos, BlockState etat) {
        if (!(etat.getBlock() instanceof MachineBlock bloc)) {
            return List.of();
        }
        Direction face = etat.getValue(FACING);
        Direction droite = face.getCounterClockWise();
        Direction arriere = face.getOpposite();
        List<BlockPos> l = new ArrayList<>();
        for (int[] c : bloc.machine.cases()) {
            l.add(pos.relative(droite, c[0]).above(c[1]).relative(arriere, c[2]));
        }
        return l;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return FORME;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player joueur, BlockHitResult hit) {
        if (machine == Machine.RACK) {
            if (!level.isClientSide) {
                int kg = Machine.KILOS[colonne(state, pos, hit.getLocation())];
                ItemStack h = new ItemStack(Muscu.HALTERES.get(kg).get());
                if (!joueur.getInventory().add(h)) {
                    joueur.drop(h, false);
                }
                level.playSound(null, pos, SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.BLOCKS, 0.8F, 0.8F);
                joueur.displayClientMessage(Component.literal("Haltère de " + kg + " kg. ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Clic droit avec pour faire une série.").withStyle(ChatFormatting.GRAY)), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (joueur.isPassenger()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            for (SiegeMuscu s : level.getEntitiesOfClass(SiegeMuscu.class, new AABB(pos).inflate(1.5))) {
                if (pos.equals(s.machinePos())) {
                    if (s.isVehicle()) {
                        joueur.displayClientMessage(Component.literal("Quelqu'un est déjà dessus.").withStyle(ChatFormatting.RED), true);
                        return InteractionResult.CONSUME;
                    }
                    s.discard();
                }
            }
            SiegeMuscu siege = SiegeMuscu.creer(level, pos);
            level.addFreshEntity(siege);
            joueur.startRiding(siege, true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack tenu, BlockState state, Level level, BlockPos pos, Player joueur, InteractionHand main, BlockHitResult hit) {
        if (machine == Machine.RACK && tenu.getItem() instanceof HaltereItem h) {
            // on repose l'haltère sur le rack
            if (!level.isClientSide) {
                tenu.shrink(1);
                level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.25F, 1.6F);
                joueur.displayClientMessage(Component.literal("Haltère de " + h.kg + " kg reposé.").withStyle(ChatFormatting.GRAY), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** La colonne du rack visée (0 = 5 kg à gauche de celui qui regarde le rack, 5 = 30 kg). */
    static int colonne(BlockState state, BlockPos pos, Vec3 visee) {
        Direction face = state.getValue(FACING);
        double dx = visee.x - (pos.getX() + 0.5), dz = visee.z - (pos.getZ() + 0.5);
        double a = Machine.angle(face), c = Math.cos(a), s = Math.sin(a);
        // du monde vers le repère de la machine (rotation inverse)
        double x = dx * c - dz * s;
        int i = (int) Math.floor((1.5 - x) / 0.5);
        return Math.max(0, Math.min(Machine.KILOS.length - 1, i));
    }
}
