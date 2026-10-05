package dev.frontiersurvival.settlement;

import dev.frontiersurvival.FrontierSurvival;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class SettlementBoardBlockEntity extends BlockEntity {
    private boolean outpost;
    private boolean registered;

    public SettlementBoardBlockEntity(BlockPos position, BlockState state) {
        super(FrontierSurvival.BOARD_ENTITY.get(), position, state);
    }

    public void register(ServerLevel level) {
        if (level.dimension().equals(Level.OVERWORLD)) {
            SettlementState.get(level).register(worldPosition, outpost);
            registered = true;
        }
    }

    public static void tick(Level level, BlockPos position, BlockState state, SettlementBoardBlockEntity board) {
        if (!board.registered && level instanceof ServerLevel server) board.register(server);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        outpost = tag.getBoolean("Outpost");
        registered = false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean("Outpost", outpost);
    }
}
