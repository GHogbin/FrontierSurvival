package dev.frontiersurvival.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public final class TerrainSettlementStructure extends Structure {
    public static final Codec<TerrainSettlementStructure> CODEC =
            RecordCodecBuilder.<TerrainSettlementStructure>mapCodec(instance -> instance.group(
                    settingsCodec(instance),
                    Codec.intRange(13, 39).fieldOf("width").forGetter(TerrainSettlementStructure::width),
                    TerrainPlanner.Plot.CODEC.listOf().fieldOf("plots").forGetter(TerrainSettlementStructure::plots),
                    TerrainPlanner.Cell.CODEC.listOf().fieldOf("paths").forGetter(TerrainSettlementStructure::paths),
                    TerrainPlanner.Walls.CODEC.optionalFieldOf("walls").forGetter(TerrainSettlementStructure::walls)
            ).apply(instance, TerrainSettlementStructure::new)).flatXmap(
                    TerrainSettlementStructure::validate, TerrainSettlementStructure::validate).codec();

    private final int width;
    private final List<TerrainPlanner.Plot> plots;
    private final List<TerrainPlanner.Cell> paths;
    private final Optional<TerrainPlanner.Walls> walls;

    public TerrainSettlementStructure(StructureSettings settings, int width, List<TerrainPlanner.Plot> plots,
                                      List<TerrainPlanner.Cell> paths, Optional<TerrainPlanner.Walls> walls) {
        super(settings);
        this.width = width;
        this.plots = List.copyOf(plots);
        this.paths = List.copyOf(paths);
        this.walls = walls;
    }

    public int width() { return width; }
    public List<TerrainPlanner.Plot> plots() { return plots; }
    public List<TerrainPlanner.Cell> paths() { return paths; }
    public Optional<TerrainPlanner.Walls> walls() { return walls; }

    private static DataResult<TerrainSettlementStructure> validate(TerrainSettlementStructure structure) {
        return TerrainPlanner.validLayout(structure.width, structure.plots, structure.paths, structure.walls)
                ? DataResult.success(structure)
                : DataResult.error(() -> "Invalid or overlapping terrain settlement footprint");
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        int originX = context.chunkPos().getMinBlockX() + 8 - width / 2;
        int originZ = context.chunkPos().getMinBlockZ() + 8 - width / 2;
        TerrainPlanner.Sample[] samples = new TerrainPlanner.Sample[width * width];
        TerrainPlanner.Sampler sampler = (x, z) -> {
            int index = z * width + x;
            if (samples[index] == null) samples[index] = sample(context, originX + x, originZ + z);
            return samples[index];
        };
        if (!preflight(context, sampler)) return Optional.empty();
        Optional<TerrainPlanner.Plan> candidate = TerrainPlanner.plan(width, plots, paths, walls,
                sampler,
                context.heightAccessor().getMinBuildHeight(), context.heightAccessor().getMaxBuildHeight());
        if (candidate.isEmpty()) return Optional.empty();
        TerrainPlanner.Plan plan = candidate.get();
        for (TerrainPlanner.Building building : plan.buildings()) {
            TerrainPlanner.Plot plot = building.plot();
            Optional<StructureTemplate> found = context.structureTemplateManager().get(plot.template());
            if (found.isEmpty()) throw new IllegalStateException("Missing terrain component " + plot.template());
            var size = found.get().getSize();
            if (size.getY() < 2 || plot.x() + size.getX() > width || plot.z() + size.getZ() > width
                    || plot.groundX() + plot.groundWidth() > size.getX()
                    || plot.groundZ() + plot.groundDepth() > size.getZ()
                    || building.floorY() + size.getY() > context.heightAccessor().getMaxBuildHeight()) {
                return Optional.empty();
            }
        }
        if (!geometryFits(plan, context.structureTemplateManager())) return Optional.empty();
        BlockPos origin = new BlockPos(originX, 0, originZ);
        BlockPos center = new BlockPos(originX + width / 2, plan.ground(width / 2, width / 2), originZ + width / 2);
        return Optional.of(new GenerationStub(center, builder -> {
            builder.addPiece(new TerrainGroundPiece(origin, plan));
            for (TerrainPlanner.Building building : plan.buildings()) {
                builder.addPiece(new TerrainTemplatePiece(context.structureTemplateManager(), origin, plan, building));
            }
        }));
    }

    public boolean geometryFits(TerrainPlanner.Plan plan, StructureTemplateManager manager) {
        Map<BlockPos, Block> solid = new HashMap<>();
        Set<BlockPos> clearing = new HashSet<>();
        for (TerrainPlanner.Building building : plan.buildings()) {
            TerrainPlanner.Plot plot = building.plot();
            StructureTemplate template = manager.get(plot.template()).orElseThrow(
                    () -> new IllegalStateException("Missing terrain component " + plot.template()));
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block == Blocks.STRUCTURE_VOID) continue;
                var settings = new StructurePlaceSettings().setRandom(RandomSource.create(0));
                for (var info : template.filterBlocks(BlockPos.ZERO, settings, block)) {
                    BlockPos position = info.pos().offset(plot.x(), building.floorY(), plot.z());
                    if (block == Blocks.AIR) {
                        if (solid.containsKey(position)) return false;
                        clearing.add(position);
                    } else {
                        if (clearing.contains(position)) return false;
                        Block previous = solid.putIfAbsent(position, block);
                        if (previous != null && previous != block) return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean preflight(GenerationContext context, TerrainPlanner.Sampler sampler) {
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int[] point : new int[][]{{0,0},{width-1,0},{0,width-1},{width-1,width-1},{width/2,width/2}}) {
            TerrainPlanner.Sample sample = sampler.sample(point[0], point[1]);
            if (!TerrainPlanner.suitable(sample, context.heightAccessor().getMinBuildHeight(),
                    context.heightAccessor().getMaxBuildHeight())) return false;
            lowest = Math.min(lowest, sample.groundY());
            highest = Math.max(highest, sample.groundY());
        }
        if (highest - lowest > TerrainPlanner.MAX_SETTLEMENT_RELIEF) return false;
        for (TerrainPlanner.Plot plot : plots) {
            int x = plot.minX(), z = plot.minZ();
            int maxX = x + plot.groundWidth() - 1, maxZ = z + plot.groundDepth() - 1;
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int[] point : new int[][]{{x,z},{maxX,z},{x,maxZ},{maxX,maxZ},{(x+maxX)/2,(z+maxZ)/2}}) {
                TerrainPlanner.Sample sample = sampler.sample(point[0], point[1]);
                if (!TerrainPlanner.suitable(sample, context.heightAccessor().getMinBuildHeight(),
                        context.heightAccessor().getMaxBuildHeight())) return false;
                low = Math.min(low, sample.groundY());
                high = Math.max(high, sample.groundY());
            }
            if (high - low > TerrainPlanner.MAX_FOOTPRINT_RELIEF) return false;
        }
        return true;
    }

    private static TerrainPlanner.Sample sample(GenerationContext context, int x, int z) {
        int minY = context.heightAccessor().getMinBuildHeight();
        NoiseColumn column = context.chunkGenerator().getBaseColumn(x, z, context.heightAccessor(), context.randomState());
        boolean wet = false;
        for (int y = context.heightAccessor().getMaxBuildHeight() - 1; y >= minY; y--) {
            BlockState state = column.getBlock(y);
            if (state.is(BlockTags.LEAVES)) continue;
            wet |= !state.getFluidState().isEmpty();
            if (state.isSolid() && state.getFluidState().isEmpty()) return new TerrainPlanner.Sample(y, wet);
        }
        return new TerrainPlanner.Sample(minY, wet, false);
    }

    @Override
    public StructureType<?> type() {
        return TerrainTypes.TERRAIN_SETTLEMENT.get();
    }
}
