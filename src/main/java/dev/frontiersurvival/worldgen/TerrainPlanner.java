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
    /** Moderate slopes are accepted; supports, excavation and blending absorb up to this much footprint relief. */
    public static final int MAX_FOOTPRINT_RELIEF = 5;
    public static final int MAX_SETTLEMENT_RELIEF = 16;
    public static final int MAX_PATH_ADJUSTMENT = 3;
    public static final int BLEND_RADIUS = 3;
    /** Blending never moves ground further than this; steeper natural drops or cave mouths are left as they are. */
    public static final int MAX_BLEND_CHANGE = MAX_FOOTPRINT_RELIEF;
    public static final int ABSENT = Integer.MIN_VALUE;
    private static final byte BUILDING = 1;
    private static final byte PATH = 2;
    private static final byte WALL = 4;

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
        private final int[] blend;

        private Plan(int width, List<Building> buildings, Optional<Walls> walls, int[] natural,
                     int[] occupancy, int[] paths, int[] wallHeights, int[] blend) {
            this.width = width;
            this.buildings = List.copyOf(buildings);
            this.walls = walls;
            this.natural = natural.clone();
            this.occupancy = occupancy.clone();
            this.paths = paths.clone();
            this.wallHeights = wallHeights.clone();
            this.blend = blend.clone();
        }

        public int width() { return width; }
        public List<Building> buildings() { return buildings; }
        public Optional<Walls> walls() { return walls; }
        public int ground(int x, int z) { return natural[z * width + x]; }
        public int occupant(int x, int z) { return occupancy[z * width + x]; }
        public int path(int x, int z) { return paths[z * width + x]; }
        public int wall(int x, int z) { return wallHeights[z * width + x]; }
        public int blend(int x, int z) { return blend[z * width + x]; }
        public int[] groundHeights() { return natural.clone(); }
        public int[] occupancy() { return occupancy.clone(); }
        public int[] pathHeights() { return paths.clone(); }
        public int[] wallHeights() { return wallHeights.clone(); }
        public int[] blendHeights() { return blend.clone(); }

        /** Total blocks of cut or fill under buildings, paths, walls and blended ground; lower is a better site. */
        public int earthworks() {
            int cost = 0;
            for (int i = 0; i < natural.length; i++) {
                if (occupancy[i] != 0) cost += Math.abs(buildings.get(occupancy[i] - 1).floorY - natural[i]);
                if (paths[i] != ABSENT) cost += Math.abs(paths[i] - natural[i]);
                if (wallHeights[i] != ABSENT) cost += Math.abs(wallHeights[i] - natural[i]);
                if (blend[i] != ABSENT) cost += Math.abs(blend[i] - natural[i]);
            }
            return cost;
        }
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
        return evaluate(width, plots, pathCells, walls, sampler, minBuildY, maxBuildY).plan();
    }

    /** A plan, or the first reason this site cannot hold the layout. */
    public record Evaluation(Optional<Plan> plan, String rejection) {
        private static Evaluation reject(String reason) { return new Evaluation(Optional.empty(), reason); }
    }

    public static Evaluation evaluate(int width, List<Plot> plots, List<Cell> pathCells, Optional<Walls> walls,
                                      Sampler sampler, int minBuildY, int maxBuildY) {
        if (!validLayout(width, plots, pathCells, walls)) return Evaluation.reject("layout");
        int size = width * width;
        byte[] used = usedCells(width, plots, pathCells, walls);
        int[] natural = new int[size];
        boolean[] untouchedUnsuitable = new boolean[size];
        int[] usedHeights = new int[size];
        int usedCount = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int i = z * width + x;
                Sample sample = sampler.sample(x, z);
                boolean suitable = suitable(sample, minBuildY, maxBuildY);
                // Water or cliffs in untouched courtyard corners are harmless; only built cells must be sound.
                if (used[i] == 0) {
                    untouchedUnsuitable[i] = !suitable;
                    natural[i] = sample.groundY;
                    continue;
                }
                if (!suitable) {
                    String part = (used[i] & BUILDING) != 0 ? "building" : (used[i] & WALL) != 0 ? "wall" : "path";
                    return Evaluation.reject((sample.wet ? "water:" : "ground:") + part);
                }
                natural[i] = sample.groundY;
                usedHeights[usedCount++] = sample.groundY;
                lowest = Math.min(lowest, sample.groundY);
                highest = Math.max(highest, sample.groundY);
            }
        }
        if (usedCount == 0 || highest - lowest > MAX_SETTLEMENT_RELIEF) return Evaluation.reject("settlement-relief");
        Arrays.sort(usedHeights, 0, usedCount);
        int typical = usedHeights[usedCount / 2];
        for (int i = 0; i < size; i++) {
            if (untouchedUnsuitable[i]) natural[i] = typical;
        }

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
            if (heights[heights.length - 1] - heights[0] > MAX_FOOTPRINT_RELIEF) {
                return Evaluation.reject("footprint-relief:" + plot.template.getPath().replaceAll(".*/", ""));
            }
            int floor = heights[heights.length / 2];
            if (floor - heights[0] > MAX_SUPPORT) return Evaluation.reject("support");
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
                if (anchors[i] != ABSENT && anchors[i] != floor) return Evaluation.reject("entrance-conflict");
                anchors[i] = floor;
            }
        }
        Optional<int[]> gradedPaths = grade(width, pathNodes, natural, anchors, minBuildY, maxBuildY);
        if (gradedPaths.isEmpty()) return Evaluation.reject("path-grading");
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
            if (gradedWalls.isEmpty()) return Evaluation.reject("wall-grading");
            wallHeights = gradedWalls.get();
        }
        int[] blend = blend(width, buildings, natural, occupancy, paths, wallHeights, untouchedUnsuitable);
        return new Evaluation(Optional.of(new Plan(width, buildings, walls, natural, occupancy, paths, wallHeights, blend)), "");
    }

    /**
     * Ramps open ground around each building toward its floor at one block per block, like a small vanilla
     * terrain beard. Conflicting neighbours, paths, walls, water and holes keep their own ground.
     */
    private static int[] blend(int width, List<Building> buildings, int[] natural, int[] occupancy, int[] paths,
                               int[] wallHeights, boolean[] unsuitable) {
        int size = width * width;
        int[] lower = new int[size];
        int[] upper = new int[size];
        Arrays.fill(lower, Integer.MIN_VALUE);
        Arrays.fill(upper, Integer.MAX_VALUE);
        boolean[] near = new boolean[size];
        for (Building building : buildings) {
            Plot plot = building.plot;
            int minX = plot.minX(), minZ = plot.minZ();
            int maxX = minX + plot.groundWidth - 1, maxZ = minZ + plot.groundDepth - 1;
            for (int z = Math.max(0, minZ - BLEND_RADIUS); z <= Math.min(width - 1, maxZ + BLEND_RADIUS); z++) {
                for (int x = Math.max(0, minX - BLEND_RADIUS); x <= Math.min(width - 1, maxX + BLEND_RADIUS); x++) {
                    int i = z * width + x;
                    if (occupancy[i] != 0) continue;
                    int distance = Math.max(Math.max(minX - x, x - maxX), Math.max(minZ - z, z - maxZ));
                    lower[i] = Math.max(lower[i], building.floorY - distance);
                    upper[i] = Math.min(upper[i], building.floorY + distance);
                    near[i] = true;
                }
            }
        }
        int[] targets = absent(size);
        for (int i = 0; i < size; i++) {
            if (!near[i] || unsuitable[i] || paths[i] != ABSENT || wallHeights[i] != ABSENT || lower[i] > upper[i]) continue;
            int target = Math.max(lower[i], Math.min(upper[i], natural[i]));
            if (target != natural[i] && Math.abs(target - natural[i]) <= MAX_BLEND_CHANGE) targets[i] = target;
        }
        return targets;
    }

    private static byte[] usedCells(int width, List<Plot> plots, List<Cell> pathCells, Optional<Walls> walls) {
        byte[] used = new byte[width * width];
        for (Plot plot : plots) {
            for (int z = plot.minZ(); z < plot.minZ() + plot.groundDepth; z++) {
                for (int x = plot.minX(); x < plot.minX() + plot.groundWidth; x++) used[z * width + x] |= BUILDING;
            }
        }
        for (Cell cell : pathCells) used[cell.z * width + cell.x] |= PATH;
        walls.ifPresent(wall -> {
            for (int z = wall.min; z <= wall.max; z++) {
                for (int x = wall.min; x <= wall.max; x++) {
                    if (wall.perimeter(x, z)) used[z * width + x] |= WALL;
                }
            }
        });
        return used;
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
