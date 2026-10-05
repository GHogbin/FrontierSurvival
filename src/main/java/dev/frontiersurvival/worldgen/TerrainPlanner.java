package dev.frontiersurvival.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import net.minecraft.resources.ResourceLocation;

/** Plans against a single immutable snapshot of natural ground, never against placed structures. */
public final class TerrainPlanner {
    public static final int MAX_SUPPORT = 12;
    public static final int MAX_FOOTPRINT_RELIEF = 3;
    public static final int MAX_SETTLEMENT_RELIEF = 12;
    public static final int MAX_PATH_ADJUSTMENT = 3;
    public static final int ABSENT = Integer.MIN_VALUE;

    public record Plot(ResourceLocation template, int x, int z, int groundX, int groundZ,
                       int groundWidth, int groundDepth, List<Cell> entrances) {
        public static final Codec<Plot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("template").forGetter(Plot::template),
                Codec.intRange(0, 38).fieldOf("x").forGetter(Plot::x),
                Codec.intRange(0, 38).fieldOf("z").forGetter(Plot::z),
                Codec.intRange(0, 38).fieldOf("ground_x").forGetter(Plot::groundX),
                Codec.intRange(0, 38).fieldOf("ground_z").forGetter(Plot::groundZ),
                Codec.intRange(1, 39).fieldOf("ground_width").forGetter(Plot::groundWidth),
                Codec.intRange(1, 39).fieldOf("ground_depth").forGetter(Plot::groundDepth),
                Cell.CODEC.listOf().optionalFieldOf("entrances", List.of()).forGetter(Plot::entrances)
        ).apply(instance, Plot::new));

        public Plot { entrances = List.copyOf(entrances); }

        public Plot(ResourceLocation template, int x, int z, int groundX, int groundZ, int groundWidth, int groundDepth) {
            this(template, x, z, groundX, groundZ, groundWidth, groundDepth, List.of());
        }

        public int minX() { return x + groundX; }
        public int minZ() { return z + groundZ; }
    }

    public record Cell(int x, int z) {
        public static final Codec<Cell> CODEC = Codec.INT.listOf().comapFlatMap(values ->
                values.size() == 2
                        ? DataResult.success(new Cell(values.get(0), values.get(1)))
                        : DataResult.error(() -> "A path coordinate must be [x,z]"),
                cell -> List.of(cell.x, cell.z));
    }

    public record Walls(int min, int max, int gateStart, int gateEnd, boolean northGate) {
        public static final Codec<Walls> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 38).fieldOf("min").forGetter(Walls::min),
                Codec.intRange(0, 38).fieldOf("max").forGetter(Walls::max),
                Codec.intRange(0, 38).fieldOf("gate_start").forGetter(Walls::gateStart),
                Codec.intRange(0, 38).fieldOf("gate_end").forGetter(Walls::gateEnd),
                Codec.BOOL.fieldOf("north_gate").forGetter(Walls::northGate)
        ).apply(instance, Walls::new));

        public boolean perimeter(int x, int z) {
            return x >= min && x <= max && z >= min && z <= max
                    && (x == min || x == max || z == min || z == max);
        }

        public boolean gate(int x, int z) {
            return x >= gateStart && x <= gateEnd && (z == max || northGate && z == min);
        }
    }

    public record Sample(int groundY, boolean wet, boolean solid) {
        public Sample(int groundY, boolean wet) { this(groundY, wet, true); }
    }

    @FunctionalInterface
    public interface Sampler {
        Sample sample(int x, int z);
    }

    public record Building(Plot plot, int floorY) {}

    public static final class Plan {
        private final int width;
        private final List<Building> buildings;
        private final Optional<Walls> walls;
        private final int[] natural;
        private final int[] occupancy;
        private final int[] paths;
        private final int[] wallHeights;

        private Plan(int width, List<Building> buildings, Optional<Walls> walls, int[] natural,
                     int[] occupancy, int[] paths, int[] wallHeights) {
            this.width = width;
            this.buildings = List.copyOf(buildings);
            this.walls = walls;
            this.natural = natural.clone();
            this.occupancy = occupancy.clone();
            this.paths = paths.clone();
            this.wallHeights = wallHeights.clone();
        }

        public int width() { return width; }
        public List<Building> buildings() { return buildings; }
        public Optional<Walls> walls() { return walls; }
        public int ground(int x, int z) { return natural[z * width + x]; }
        public int occupant(int x, int z) { return occupancy[z * width + x]; }
        public int path(int x, int z) { return paths[z * width + x]; }
        public int wall(int x, int z) { return wallHeights[z * width + x]; }
        public int[] groundHeights() { return natural.clone(); }
        public int[] occupancy() { return occupancy.clone(); }
        public int[] pathHeights() { return paths.clone(); }
        public int[] wallHeights() { return wallHeights.clone(); }
    }

    private record QueueEntry(int index, int height) {}

    private TerrainPlanner() {}

    static boolean suitable(Sample sample, int minBuildY, int maxBuildY) {
        return sample.solid && !sample.wet && sample.groundY > minBuildY && sample.groundY < maxBuildY - 4;
    }

    public static boolean validLayout(int width, List<Plot> plots, List<Cell> paths, Optional<Walls> walls) {
        if (width < 13 || width > 39 || plots.isEmpty() || plots.size() > width * width
                || paths.size() > width * width) return false;
        boolean[] occupied = new boolean[width * width];
        for (Plot plot : plots) {
            if (plot.x < 0 || plot.z < 0 || plot.groundX < 0 || plot.groundZ < 0
                    || plot.x >= width || plot.z >= width || plot.groundX >= width || plot.groundZ >= width
                    || plot.groundWidth < 1 || plot.groundDepth < 1
                    || plot.groundWidth > width || plot.groundDepth > width
                    || plot.minX() + plot.groundWidth > width || plot.minZ() + plot.groundDepth > width) return false;
            if (plot.entrances.size() > plot.groundWidth * plot.groundDepth) return false;
            for (Cell entrance : plot.entrances) {
                if (entrance.x < plot.groundX || entrance.z < plot.groundZ
                        || entrance.x >= plot.groundX + plot.groundWidth
                        || entrance.z >= plot.groundZ + plot.groundDepth) return false;
            }
            for (int z = plot.minZ(); z < plot.minZ() + plot.groundDepth; z++) {
                for (int x = plot.minX(); x < plot.minX() + plot.groundWidth; x++) {
                    int i = z * width + x;
                    if (occupied[i]) return false;
                    occupied[i] = true;
                }
            }
        }
        for (Cell cell : paths) {
            if (cell.x < 0 || cell.z < 0 || cell.x >= width || cell.z >= width) return false;
        }
        if (walls.isPresent()) {
            Walls wall = walls.get();
            if (wall.min < 0 || wall.max >= width || wall.min >= wall.max
                    || wall.gateStart <= wall.min || wall.gateEnd >= wall.max
                    || wall.gateEnd - wall.gateStart < 2) return false;
            for (int z = wall.min; z <= wall.max; z++) {
                for (int x = wall.min; x <= wall.max; x++) {
                    if (wall.perimeter(x, z) && occupied[z * width + x]) return false;
                }
            }
        }
        return true;
    }

    public static Optional<Plan> plan(int width, List<Plot> plots, List<Cell> pathCells, Optional<Walls> walls,
                                      Sampler sampler, int minBuildY, int maxBuildY) {
        if (!validLayout(width, plots, pathCells, walls)) return Optional.empty();
        int size = width * width;
        int[] natural = new int[size];
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                Sample sample = sampler.sample(x, z);
                if (!suitable(sample, minBuildY, maxBuildY)) return Optional.empty();
                natural[z * width + x] = sample.groundY;
                lowest = Math.min(lowest, sample.groundY);
                highest = Math.max(highest, sample.groundY);
            }
        }
        if (highest - lowest > MAX_SETTLEMENT_RELIEF) return Optional.empty();

        List<Building> buildings = new ArrayList<>();
        int[] occupancy = new int[size];
        for (Plot plot : plots) {
            int[] heights = new int[plot.groundWidth * plot.groundDepth];
            int n = 0;
            for (int z = plot.minZ(); z < plot.minZ() + plot.groundDepth; z++) {
                for (int x = plot.minX(); x < plot.minX() + plot.groundWidth; x++) {
                    heights[n++] = natural[z * width + x];
                    occupancy[z * width + x] = buildings.size() + 1;
                }
            }
            Arrays.sort(heights);
            if (heights[heights.length - 1] - heights[0] > MAX_FOOTPRINT_RELIEF) return Optional.empty();
            int floor = heights[heights.length / 2];
            if (floor - heights[0] > MAX_SUPPORT) return Optional.empty();
            buildings.add(new Building(plot, floor));
        }

        boolean[] pathNodes = new boolean[size];
        for (Cell cell : pathCells) pathNodes[cell.z * width + cell.x] = true;
        walls.ifPresent(wall -> {
            for (int x = wall.gateStart; x <= wall.gateEnd; x++) {
                pathNodes[wall.max * width + x] = true;
                if (wall.northGate) pathNodes[wall.min * width + x] = true;
            }
        });
        int[] anchors = absent(size);
        for (int i = 0; i < size; i++) {
            if (!pathNodes[i]) continue;
            int[] nearby = neighbors(i, width);
            int owner = occupancy[i];
            if (owner != 0) anchors[i] = buildings.get(owner - 1).floorY;
            for (int neighbor : nearby) {
                owner = occupancy[neighbor];
                if (owner == 0) continue;
                Building building = buildings.get(owner - 1);
                Plot plot = building.plot;
                if (!plot.entrances.contains(new Cell(neighbor % width - plot.x, neighbor / width - plot.z))) continue;
                int floor = building.floorY;
                if (anchors[i] != ABSENT && anchors[i] != floor) return Optional.empty();
                anchors[i] = floor;
            }
        }
        Optional<int[]> gradedPaths = grade(width, pathNodes, natural, anchors, minBuildY, maxBuildY);
        if (gradedPaths.isEmpty()) return Optional.empty();
        int[] paths = gradedPaths.get();
        int[] wallHeights = absent(size);
        if (walls.isPresent()) {
            Walls wall = walls.get();
            boolean[] wallNodes = new boolean[size];
            int[] wallAnchors = absent(size);
            for (int z = wall.min; z <= wall.max; z++) {
                for (int x = wall.min; x <= wall.max; x++) {
                    if (!wall.perimeter(x, z)) continue;
                    int i = z * width + x;
                    wallNodes[i] = true;
                    if (pathNodes[i]) wallAnchors[i] = paths[i];
                    // A wall base adjacent to a graded approach follows that approach.
                    for (int neighbor : neighbors(i, width)) {
                        if (paths[neighbor] == ABSENT || wallAnchors[i] != ABSENT) continue;
                        wallAnchors[i] = paths[neighbor];
                    }
                }
            }
            Optional<int[]> gradedWalls = grade(width, wallNodes, natural, wallAnchors, minBuildY, maxBuildY);
            if (gradedWalls.isEmpty()) return Optional.empty();
            wallHeights = gradedWalls.get();
        }
        return Optional.of(new Plan(width, buildings, walls, natural, occupancy, paths, wallHeights));
    }

    private static Optional<int[]> grade(int width, boolean[] nodes, int[] natural, int[] anchors,
                                         int minBuildY, int maxBuildY) {
        int[] lower = absent(nodes.length);
        int[] upper = absent(nodes.length);
        for (int i = 0; i < nodes.length; i++) {
            if (!nodes[i]) continue;
            lower[i] = Math.max(minBuildY + 1, natural[i] - MAX_PATH_ADJUSTMENT);
            upper[i] = Math.min(maxBuildY - 4, natural[i] + MAX_PATH_ADJUSTMENT);
            if (anchors[i] != ABSENT) {
                if (anchors[i] < lower[i] || anchors[i] > upper[i]) return Optional.empty();
                lower[i] = anchors[i];
                upper[i] = anchors[i];
            }
        }
        // These distance envelopes enforce every fixed anchor and every bounded earthwork constraint.
        envelope(width, nodes, lower, false);
        envelope(width, nodes, upper, true);
        int[] preferred = absent(nodes.length);
        for (int i = 0; i < nodes.length; i++) {
            if (!nodes[i]) continue;
            if (lower[i] > upper[i]) return Optional.empty();
            preferred[i] = Math.max(lower[i], Math.min(upper[i], natural[i]));
        }
        // The minimum envelope of values inside feasible envelopes remains inside both and is 1-Lipschitz.
        envelope(width, nodes, preferred, true);
        return Optional.of(preferred);
    }

    private static void envelope(int width, boolean[] nodes, int[] values, boolean minimum) {
        Comparator<QueueEntry> order = Comparator.comparingInt(QueueEntry::height);
        if (!minimum) order = order.reversed();
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(order.thenComparingInt(QueueEntry::index));
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i]) queue.add(new QueueEntry(i, values[i]));
        }
        while (!queue.isEmpty()) {
            QueueEntry entry = queue.remove();
            if (entry.height != values[entry.index]) continue;
            int next = entry.height + (minimum ? 1 : -1);
            for (int neighbor : neighbors(entry.index, width)) {
                if (!nodes[neighbor] || (minimum ? next >= values[neighbor] : next <= values[neighbor])) continue;
                values[neighbor] = next;
                queue.add(new QueueEntry(neighbor, next));
            }
        }
    }

    private static int[] neighbors(int index, int width) {
        int x = index % width;
        int z = index / width;
        int[] result = new int[4];
        int n = 0;
        if (x > 0) result[n++] = index - 1;
        if (x + 1 < width) result[n++] = index + 1;
        if (z > 0) result[n++] = index - width;
        if (z + 1 < width) result[n++] = index + width;
        return Arrays.copyOf(result, n);
    }

    private static int[] absent(int size) {
        int[] values = new int[size];
        Arrays.fill(values, ABSENT);
        return values;
    }
}
