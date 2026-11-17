package fr.cabane.muscu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Rien à retenir : il sert juste à dessiner les pièces qui bougent (barres, poignées, pédales, plaques). */
public class MachineBlockEntity extends BlockEntity {
    public MachineBlockEntity(BlockPos pos, BlockState state) {
        super(Muscu.MACHINE_BE.get(), pos, state);
    }

    public Machine machine() {
        return getBlockState().getBlock() instanceof MachineBlock b ? b.machine : Machine.RACK;
    }
}
