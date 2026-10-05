package dev.frontiersurvival.block;

import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import org.jetbrains.annotations.Nullable;

/**
 * Trodden settlement turf. It looks and tints like grass but is deliberately not tagged as dirt, so worldgen
 * trees, flowers, bushes and boulders cannot root inside a settlement. Hoes and shovels still work on it.
 */
public final class SettlementGroundBlock extends SnowyDirtBlock {
    public SettlementGroundBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockState getToolModifiedState(BlockState state, UseOnContext context, ToolAction action,
                                                     boolean simulate) {
        boolean open = context.getLevel().getBlockState(context.getClickedPos().above()).isAir();
        if (action == ToolActions.HOE_TILL && open) return Blocks.FARMLAND.defaultBlockState();
        if (action == ToolActions.SHOVEL_FLATTEN && open) return Blocks.DIRT_PATH.defaultBlockState();
        return super.getToolModifiedState(state, context, action, simulate);
    }
}
