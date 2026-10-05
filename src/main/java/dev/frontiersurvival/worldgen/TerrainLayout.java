package dev.frontiersurvival.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;

/**
 * A settlement layout turned a whole number of quarter turns about its square grid. Plots, doorway entrances,
 * graded paths and gated palisades all turn together, exactly as {@code StructureTemplate} turns each building.
 */
public record TerrainLayout(int width, List<TerrainPlanner.Plot> plots, List<TerrainPlanner.Cell> paths,
                            Optional<TerrainPlanner.Walls> walls, Rotation rotation) {

    /** Turns an authored (unrotated) layout; {@code sizes} gives each template's unrotated size. */
    public static TerrainLayout rotate(int width, List<TerrainPlanner.Plot> plots, List<TerrainPlanner.Cell> paths,
                                       Optional<TerrainPlanner.Walls> walls, Rotation rotation,
                                       Function<ResourceLocation, Vec3i> sizes) {
        if (rotation == Rotation.NONE) return new TerrainLayout(width, List.copyOf(plots), List.copyOf(paths), walls, rotation);
        List<TerrainPlanner.Plot> turnedPlots = new ArrayList<>(plots.size());
        for (TerrainPlanner.Plot plot : plots) turnedPlots.add(rotate(plot, width, rotation, sizes.apply(plot.template())));
        List<TerrainPlanner.Cell> turnedPaths = new ArrayList<>(paths.size());
        for (TerrainPlanner.Cell cell : paths) turnedPaths.add(rotate(cell, width, rotation));
        return new TerrainLayout(width, List.copyOf(turnedPlots), List.copyOf(turnedPaths),
                walls.map(wall -> wall.rotate(rotation)), rotation);
    }

    /** The same quarter turn {@code StructureTemplate} applies about its origin, re-seated inside the grid. */
    public static TerrainPlanner.Cell rotate(TerrainPlanner.Cell cell, int width, Rotation rotation) {
        int last = width - 1;
        return switch (rotation) {
            case NONE -> cell;
            case CLOCKWISE_90 -> new TerrainPlanner.Cell(last - cell.z(), cell.x());
            case CLOCKWISE_180 -> new TerrainPlanner.Cell(last - cell.x(), last - cell.z());
            case COUNTERCLOCKWISE_90 -> new TerrainPlanner.Cell(cell.z(), last - cell.x());
        };
    }

    /**
     * After turning, a plot's x/z is the min corner of the turned template box and its ground footprint and
     * entrances are given relative to that corner along world axes.
     */
    public static TerrainPlanner.Plot rotate(TerrainPlanner.Plot plot, int width, Rotation rotation, Vec3i size) {
        if (rotation == Rotation.NONE) return plot;
        TerrainPlanner.Cell a = rotate(new TerrainPlanner.Cell(plot.x(), plot.z()), width, rotation);
        TerrainPlanner.Cell b = rotate(new TerrainPlanner.Cell(plot.x() + size.getX() - 1, plot.z() + size.getZ() - 1),
                width, rotation);
        int x = Math.min(a.x(), b.x());
        int z = Math.min(a.z(), b.z());
        TerrainPlanner.Cell g0 = rotate(new TerrainPlanner.Cell(plot.minX(), plot.minZ()), width, rotation);
        TerrainPlanner.Cell g1 = rotate(new TerrainPlanner.Cell(plot.minX() + plot.groundWidth() - 1,
                plot.minZ() + plot.groundDepth() - 1), width, rotation);
        List<TerrainPlanner.Cell> entrances = new ArrayList<>(plot.entrances().size());
        for (TerrainPlanner.Cell entrance : plot.entrances()) {
            TerrainPlanner.Cell turned = rotate(new TerrainPlanner.Cell(plot.x() + entrance.x(), plot.z() + entrance.z()),
                    width, rotation);
            entrances.add(new TerrainPlanner.Cell(turned.x() - x, turned.z() - z));
        }
        return new TerrainPlanner.Plot(plot.template(), x, z, Math.min(g0.x(), g1.x()) - x, Math.min(g0.z(), g1.z()) - z,
                Math.abs(g0.x() - g1.x()) + 1, Math.abs(g0.z() - g1.z()) + 1, entrances);
    }
}
