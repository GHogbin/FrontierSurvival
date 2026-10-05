import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;

/**
 * Original Frontier Survival assets, with no Minecraft classes or external libraries.
 * From the project root: .tools\jdk-17.0.20.1+1\bin\java.exe tools\GenerateAssets.java
 * An optional first argument names the project root. All reported coordinates are template-local.
 */
public final class GenerateAssets {
    private static final String NS = "frontiersurvival:";
    private static final int DATA_VERSION = 3465;
    private static final int FLOOR = 5;
    private static final State AIR = state("air");
    private static final State SETTLEMENT_GRASS = state(NS + "settlement_grass", "snowy", "false");
    private static final State COBBLE = state("cobblestone");
    private static final State PLANKS = state("oak_planks");
    private static final State LOG = state("stripped_spruce_log", "axis", "y");
    private static final State FENCE = state("oak_fence",
        "north", "false", "south", "false", "east", "false", "west", "false", "waterlogged", "false");
    private static final Set<String> LOOT = Set.of(
        NS + "chests/hamlet_supplies", NS + "chests/tower_supplies", NS + "chests/bandit_cache");

    public static void main(String[] args) throws Exception {
        require(args.length <= 1, "Usage: GenerateAssets [project-root]");
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        Path data = root.resolve("src").resolve("main").resolve("resources").resolve("data")
            .resolve("frontiersurvival");
        for (String table : LOOT) {
            require(Files.isRegularFile(data.resolve("loot_tables").resolve("chests")
                .resolve(table.substring(table.lastIndexOf('/') + 1) + ".json")), "Missing loot table " + table);
        }

        List<Voxels> structures = List.of(hamlet(), watchtower(), camp(), hamletSmall(), hamletLarge(), campSmall(), campLarge(), watchtowerSmall(), watchtowerLarge());
        for (Voxels voxels : structures) {
            voxels.validate();
            Path file = data.resolve("structures").resolve(voxels.name + ".nbt");
            writeVerified(file, voxels.template());
            voxels.report(file);
            generateTerrain(data, voxels);
        }
        writeFrontierSites(data);
        Voxels arena = new Voxels("test/arena", 48, 24, 48);
        arena.fill(0, 0, 0, 47, 0, 47, state("grass_block", "snowy", "false"));
        require(arena.entities.isEmpty(), "Arena must be empty");
        for (Placed block : arena.blocks.values()) {
            require(block.pos.y == 0 ? block.state.name.equals("minecraft:grass_block")
                : block.state.equals(AIR), "Unexpected arena obstruction");
        }
        Path testStructures = root.resolve("src").resolve("gametest").resolve("resources").resolve("data")
            .resolve("frontiersurvival").resolve("structures").resolve("test");
        writeVerified(testStructures.resolve("arena.nbt"), arena.template());
        System.out.println("Arena test/arena: 48 x 24 x 48; grass y=0, air y=1..23, zero entities.");
        Voxels arenaLarge = new Voxels("test/arena_large", 64, 32, 64);
        arenaLarge.fill(0, 0, 0, 63, 0, 63, state("grass_block", "snowy", "false"));
        require(arenaLarge.entities.isEmpty(), "Large arena must be empty");
        writeVerified(testStructures.resolve("arena_large.nbt"), arenaLarge.template());
        System.out.println("Arena test/arena_large: 64 x 32 x 64; grass y=0, air y=1..31, zero entities.");

        Path skins = root.resolve("src").resolve("main").resolve("resources").resolve("assets")
            .resolve("frontiersurvival").resolve("textures").resolve("entity");
        Files.createDirectories(skins);
        for (int role = 0; role < 4; role++) {
            String name = List.of("guard", "bandit", "bandit_leader", "quartermaster").get(role);
            BufferedImage image = skin(role);
            Path file = skins.resolve(name + ".png");
            require(ImageIO.write(image, "PNG", file.toFile()), "PNG writer unavailable");
            BufferedImage read = ImageIO.read(file.toFile());
            require(read != null && read.getWidth() == 64 && read.getHeight() == 64, "Invalid skin " + name);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                require(image.getRGB(x, y) == read.getRGB(x, y), "Skin round-trip mismatch " + name);
            }
            System.out.println("Skin " + file + ": original 64 x 64, standard wide-arm humanoid UV.");
        }
        System.out.println("All geometry, populations, NBT round-trips and PNG round-trips passed.");
    }

    private record TerrainPlot(String name, int x, int z, int groundX, int groundZ,
                               int groundWidth, int groundDepth, Voxels component, List<Pos> entrances) {}

    private static void generateTerrain(Path data, Voxels original) throws IOException {
        List<TerrainPlot> plots = new ArrayList<>();
        if (original.name.equals("fortified_hamlet")) {
            for (int i = 0; i < 2; i++) {
                int x = i == 0 ? 3 : 29;
                plots.add(terrainPlot(original, "tower_" + i, x - 1, 2, 9, 9, x, 3, 7, 7,
                    v -> roofedTower(v, x, 3), List.of(new Pos(x + 3, 6, 9))));
            }
            for (int i = 0; i < 4; i++) {
                int x = i % 2 == 0 ? 4 : 26;
                int z = i < 2 ? 11 : 24;
                boolean south = i < 2;
                String color = List.of("blue", "cyan", "yellow", "green").get(i);
                String job = List.of("composter", "fletching_table", "smithing_table", "stonecutter").get(i);
                plots.add(terrainPlot(original, "cottage_" + i, x - 1, z - 1, 11, 9, x, z, 9, 7,
                    v -> cottage(v, x, z, south, color, job),
                    List.of(new Pos(x + 4, 6, south ? z + 6 : z))));
            }
            plots.add(terrainPlot(original, "market", 16, 16, 7, 7, 16, 16, 7, 7, v -> {
                v.fill(16, FLOOR, 16, 22, FLOOR, 22, state("stone_bricks"));
                board(v, 19, 18, false);
                v.set(19, 6, 21, COBBLE);
                v.set(19, 7, 21, state("bell", "attachment", "floor", "facing", "north", "powered", "false"));
            }, List.of()));
            for (int i = 0; i < 2; i++) {
                int x = i == 0 ? 14 : 22;
                plots.add(terrainPlot(original, "farm_" + i, x - 1, 24, 5, 11, x - 1, 24, 5, 11,
                    v -> farm(v, x, 25), List.of()));
            }
            int[][] lights = {{16,8},{22,8},{16,16},{22,22},{16,35},{22,35},{3,21},{35,21},{17,2},{21,36}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                if (insideGround(plots, x, z)) {
                    TerrainPlot owner = plots.stream().filter(plot -> contains(plot, x, z)).findFirst().orElseThrow();
                    // These two market lamps share the market's rigid floor instead of claiming overlapping plots.
                    owner.component.set(x - owner.x, 1, z - owner.z, FENCE);
                    owner.component.set(x - owner.x, 2, z - owner.z, FENCE);
                    owner.component.set(x - owner.x, 3, z - owner.z,
                        state("lantern", "hanging", "false", "waterlogged", "false"));
                } else {
                    plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1,
                        v -> lamp(v, x, z), List.of()));
                }
            }
            plots.add(terrainPlot(original, "guard_post_0", 18, 10, 1, 1, 18, 10, 1, 1, v -> {}, List.of()));
            plots.add(terrainPlot(original, "guard_post_1", 20, 32, 1, 1, 20, 32, 1, 1, v -> {}, List.of()));
        } else if (original.name.equals("watchtower")) {
            // One compact outpost; the terrain grid adds a margin so surrounding ground can blend into it.
            plots.add(terrainPlot(original, "tower_0", 0, 0, 13, 13, 0, 0, 13, 13, null,
                List.of(new Pos(5, 6, 12), new Pos(6, 6, 12), new Pos(7, 6, 12))));
        } else if (original.name.equals("bandit_camp")) {
            int[][] tents = {{3,4},{13,4},{13,13}};
            for (int i = 0; i < tents.length; i++) {
                int x = tents[i][0], z = tents[i][1];
                String cloth = List.of("brown", "light_gray", "gray").get(i);
                String stripe = i == 0 ? "orange" : "red";
                plots.add(terrainPlot(original, "tent_" + i, x, z, 7, 6, x, z, 7, 6,
                    v -> tent(v, x, z, cloth, stripe), List.of(new Pos(x + 3, 6, z + 5))));
            }
            plots.add(terrainPlot(original, "fire", 10, 11, 3, 3, 10, 11, 3, 3, v -> {
                for (int x = 10; x <= 12; x++) for (int z = 11; z <= 13; z++) {
                    Placed block = original.blocks.get(new Pos(x, 6, z));
                    v.set(x, 6, z, block.state, block.nbt);
                }
            }, List.of()));
            plots.add(terrainPlot(original, "guard_post_0", 6, 16, 1, 1, 6, 16, 1, 1, v -> {}, List.of()));
            int[][] lights = {{10,3},{12,3},{3,12},{19,12},{9,18},{13,20}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1, v -> {
                    v.fill(x, 6, z, x, 7, z, FENCE);
                    v.set(x, 8, z, state("torch"));
                }, List.of()));
            }
        } else if (original.name.equals("hamlet_small")) {
            plots.add(terrainPlot(original, "tower_0", 3, 3, 5, 5, 3, 3, 5, 5,
                null, List.of(new Pos(5, 6, 7))));
            plots.add(terrainPlot(original, "hut_0", 4, 18, 7, 5, 4, 18, 7, 5,
                null, List.of(new Pos(7, 6, 18))));
            plots.add(terrainPlot(original, "cottage_0", 19, 17, 9, 7, 19, 17, 9, 7,
                null, List.of(new Pos(23, 6, 17))));
            plots.add(terrainPlot(original, "farm_0", 21, 4, 5, 11, 21, 4, 5, 11, null, List.of()));
            plots.add(terrainPlot(original, "market_stall_0", 12, 11, 5, 4, 12, 11, 5, 4, null, List.of()));
            plots.add(terrainPlot(original, "charter", 15, 15, 1, 1, 15, 15, 1, 1, null, List.of()));
            plots.add(terrainPlot(original, "guard_post_0", 14, 25, 1, 1, 14, 25, 1, 1, v -> {}, List.of()));
            int[][] lights = {{15,2},{10,8},{20,8},{10,22},{20,22},{3,16},{27,16},{15,27}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                if (insideGround(plots, x, z)) continue;
                plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1, v -> lamp(v, x, z), List.of()));
            }
        } else if (original.name.equals("hamlet_large")) {
            int[][] towers = {{3,3,7},{41,3,5},{3,39,5},{39,39,7}};
            for (int i = 0; i < towers.length; i++) {
                int x = towers[i][0], z = towers[i][1], s = towers[i][2];
                plots.add(terrainPlot(original, "tower_" + i, x, z, s, s, x, z, s, s,
                    null, List.of(new Pos(x + s / 2, 6, z + s - 1))));
            }
            int[][] houses = {{5,17,11,7},{18,6,13,7},{33,17,9,7},{7,29,7,5},{34,30,9,7},
                {18,34,11,7},{32,6,9,7},{30,38,7,7}};
            String[] names = {"barracks","longhouse","cottage_0","hut_0","cottage_1","cottage_2","smithy","storehouse"};
            boolean[] south = {true,true,true,false,false,false,true,false};
            for (int i = 0; i < houses.length; i++) {
                int x = houses[i][0], z = houses[i][1], w = houses[i][2], d = houses[i][3];
                plots.add(terrainPlot(original, names[i], x, z, w, d, x, z, w, d,
                    null, List.of(new Pos(x + w / 2, 6, south[i] ? z + d - 1 : z))));
            }
            plots.add(terrainPlot(original, "well", 23, 28, 3, 3, 23, 28, 3, 3, null, List.of()));
            plots.add(terrainPlot(original, "pen", 10, 3, 8, 6, 10, 3, 8, 6, null, List.of(new Pos(13, 6, 8))));
            plots.add(terrainPlot(original, "farm_0", 29, 32, 4, 6, 29, 32, 4, 6, null, List.of()));
            plots.add(terrainPlot(original, "farm_1", 42, 8, 5, 11, 42, 8, 5, 11, null, List.of()));
            plots.add(terrainPlot(original, "market_stall_0", 18, 22, 5, 4, 18, 22, 5, 4, null, List.of()));
            plots.add(terrainPlot(original, "market_stall_1", 27, 22, 5, 4, 27, 22, 5, 4, null, List.of()));
            plots.add(terrainPlot(original, "charter", 23, 20, 4, 7, 23, 20, 4, 7, null, List.of()));
            plots.add(terrainPlot(original, "quartermaster_post_0", 19, 20, 1, 1, 19, 20, 1, 1, v -> {}, List.of()));
            plots.add(terrainPlot(original, "guard_post_0", 24, 43, 1, 1, 24, 43, 1, 1, v -> {}, List.of()));
            plots.add(terrainPlot(original, "villager_post_0", 33, 35, 1, 1, 33, 35, 1, 1, v -> {}, List.of()));
            int[][] lights = {{12,14},{36,14},{12,36},{36,36},{23,10},{25,40},{5,25},{43,25},{20,2},{28,46},{23,23},{27,27},{42,45}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                if (insideGround(plots, x, z)) continue;
                plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1, v -> lamp(v, x, z), List.of()));
            }
        } else if (original.name.equals("bandit_camp_small")) {
            plots.add(terrainPlot(original, "tent_0", 2, 5, 5, 5, 2, 5, 5, 5, null, List.of(new Pos(4, 6, 9))));
            plots.add(terrainPlot(original, "tent_1", 10, 5, 5, 5, 10, 5, 5, 5, null, List.of(new Pos(12, 6, 9))));
            plots.add(terrainPlot(original, "fire", 8, 10, 1, 1, 8, 10, 1, 1, null, List.of()));
            int[][] lights = {{4,4},{12,4},{4,12},{12,12}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1, null, List.of()));
            }
        } else if (original.name.equals("bandit_camp_large")) {
            int[][] tents = {{3,6,7,6},{21,6,7,6},{4,20,7,6},{18,19,9,7}};
            for (int i = 0; i < tents.length; i++) {
                int x = tents[i][0], z = tents[i][1], w = tents[i][2], d = tents[i][3];
                plots.add(terrainPlot(original, "tent_" + i, x, z, w, d, x, z, w, d,
                    null, List.of(new Pos(x + w / 2, 6, z + d - 1))));
            }
            plots.add(terrainPlot(original, "lookout", 13, 3, 5, 5, 13, 3, 5, 5,
                null, List.of(new Pos(15, 6, 7))));
            plots.add(terrainPlot(original, "fire", 15, 17, 1, 1, 15, 17, 1, 1, null, List.of()));
            plots.add(terrainPlot(original, "supplies", 23, 14, 3, 2, 23, 14, 3, 2, null, List.of()));
            int[][] lights = {{5,4},{25,4},{5,15},{25,15},{7,27},{23,27},{15,23}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                if (insideGround(plots, x, z)) continue;
                plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1, null, List.of()));
            }
        } else if (original.name.equals("watchtower_small")) {
            plots.add(terrainPlot(original, "tower_0", 0, 0, 9, 9, 0, 0, 9, 9, null,
                List.of(new Pos(3, 6, 8), new Pos(4, 6, 8), new Pos(5, 6, 8))));
        } else if (original.name.equals("watchtower_large")) {
            plots.add(terrainPlot(original, "tower_0", 3, 3, 7, 7, 3, 3, 7, 7, null, List.of(new Pos(6, 6, 9))));
            plots.add(terrainPlot(original, "tower_1", 19, 3, 7, 7, 19, 3, 7, 7, null, List.of(new Pos(22, 6, 9))));
            plots.add(terrainPlot(original, "barracks", 4, 18, 11, 7, 4, 18, 11, 7, null, List.of(new Pos(9, 6, 18))));
            plots.add(terrainPlot(original, "fletcher_house", 17, 18, 9, 7, 17, 18, 9, 7, null, List.of(new Pos(21, 6, 18))));
            plots.add(terrainPlot(original, "charter", 13, 11, 4, 5, 13, 11, 4, 5, null, List.of()));
            plots.add(terrainPlot(original, "quartermaster_post_0", 15, 16, 1, 1, 15, 16, 1, 1, v -> {}, List.of()));
            plots.add(terrainPlot(original, "well", 22, 12, 3, 3, 22, 12, 3, 3, null, List.of()));
            plots.add(terrainPlot(original, "market_stall_0", 5, 12, 5, 4, 5, 12, 5, 4, null, List.of()));
            plots.add(terrainPlot(original, "guard_post_0", 15, 24, 1, 1, 15, 24, 1, 1, v -> {}, List.of()));
            int[][] lights = {{9,10},{19,10},{5,25},{23,25},{14,4},{14,24},{3,15},{25,15}};
            for (int i = 0; i < lights.length; i++) {
                int x = lights[i][0], z = lights[i][1];
                if (insideGround(plots, x, z)) continue;
                plots.add(terrainPlot(original, "lamp_" + i, x, z, 1, 1, x, z, 1, 1, v -> lamp(v, x, z), List.of()));
            }
        } else {
            int inset = hasWalls(original.name) ? 2 : 0;
            plots.add(terrainPlot(original, "site_core", 0, 0, original.width, original.depth,
                inset, inset, original.width - inset * 2, original.depth - inset * 2, null, List.of()));
        }

        require(plots.stream().mapToInt(plot -> plot.component.entities.size()).sum() == original.entities.size(),
            original.name + ": terrain components must assign every original resident exactly once");
        for (Entity entity : original.entities) {
            require(plots.stream().filter(plot -> contains(plot, (int) entity.x, (int) entity.z)).count() == 1,
                original.name + ": missing or duplicated resident " + entity);
        }
        for (TerrainPlot plot : plots) {
            boolean exempt = original.name.equals("watchtower") || original.name.equals("watchtower_small");
            require(exempt || plot.groundWidth <= 15 && plot.groundDepth <= 15,
                original.name + "/" + plot.name + ": component footprint exceeds 15x15");
            require(original.name.equals("watchtower_small") || !plot.name.equals("site_core"),
                original.name + ": new layouts must be split into terrain components");
        }
        Set<Pos> occupied = new HashSet<>();
        for (TerrainPlot plot : plots) {
            for (int z = plot.z + plot.groundZ; z < plot.z + plot.groundZ + plot.groundDepth; z++) {
                for (int x = plot.x + plot.groundX; x < plot.x + plot.groundX + plot.groundWidth; x++) {
                    require(occupied.add(new Pos(x, 0, z)), original.name + ": overlapping terrain plot at " + x + "," + z);
                }
            }
        }
        Path componentRoot = data.resolve("structures").resolve("terrain").resolve(original.name);
        deleteTree(componentRoot);
        for (TerrainPlot plot : plots) {
            plot.component.connectBarriers();
            writeVerified(data.resolve("structures").resolve(plot.component.name + ".nbt"), plot.component.template());
        }
        Set<Pos> paths = new HashSet<>();
        for (Pos pos : original.paths) {
            if (original.accessible(pos)) paths.add(new Pos(pos.x, 0, pos.z));
        }
        for (TerrainPlot plot : plots) {
            for (Pos entrance : plot.entrances) paths.add(new Pos(plot.x + entrance.x, 0, plot.z + entrance.z));
        }
        int margin = original.name.equals("watchtower") || original.name.equals("watchtower_small") ? 3 : 0;
        if (margin > 0) {
            // Graded approach from the outpost gate across the blending margin.
            int center = original.width / 2;
            for (int z = original.depth; z < original.depth + margin; z++) {
                for (int x = center - 1; x <= center + 1; x++) paths.add(new Pos(x, 0, z));
            }
        }
        StringBuilder json = new StringBuilder("{\n  \"type\": \"frontiersurvival:terrain_settlement\",\n");
        json.append("  \"biomes\": \"#frontiersurvival:has_structure/").append(biomeTag(original.name)).append("\",\n")
            .append("  \"step\": \"surface_structures\",\n  \"terrain_adaptation\": \"none\",\n")
            .append("  \"spawn_overrides\": ").append(original.name.startsWith("bandit_camp") ? "{}" :
                "{\"monster\":{\"bounding_box\":\"piece\",\"spawns\":[]}}").append(",\n")
            .append("  \"width\": ").append(original.width + 2 * margin).append(",\n  \"plots\": [\n");
        for (int i = 0; i < plots.size(); i++) {
            TerrainPlot plot = plots.get(i);
            json.append("    {\"template\":\"").append(NS).append(plot.component.name).append("\",\"x\":")
                .append(plot.x + margin).append(",\"z\":").append(plot.z + margin).append(",\"ground_x\":").append(plot.groundX)
                .append(",\"ground_z\":").append(plot.groundZ).append(",\"ground_width\":").append(plot.groundWidth)
                .append(",\"ground_depth\":").append(plot.groundDepth).append(",\"entrances\":[");
            for (int e = 0; e < plot.entrances.size(); e++) {
                Pos entrance = plot.entrances.get(e);
                if (e > 0) json.append(',');
                json.append('[').append(entrance.x).append(',').append(entrance.z).append(']');
            }
            json.append("]}").append(i + 1 < plots.size() ? "," : "").append('\n');
        }
        json.append("  ],\n  \"paths\": [");
        List<Pos> ordered = paths.stream().sorted(Comparator.comparingInt(Pos::z).thenComparingInt(Pos::x)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) json.append(',');
            Pos pos = ordered.get(i);
            json.append('[').append(pos.x + margin).append(',').append(pos.z + margin).append(']');
        }
        json.append(']');
        if (hasWalls(original.name)) {
            int gateStart = original.width / 2 - 1;
            int gateEnd = original.width / 2 + 1;
            json.append(",\n  \"walls\":{\"min\":1,\"max\":").append(original.width - 2)
                .append(",\"gate_start\":").append(gateStart)
                .append(",\"gate_end\":").append(gateEnd)
                .append(",\"north_gate\":").append(hasNorthGate(original.name)).append('}');
        }
        json.append("\n}\n");
        Files.writeString(data.resolve("worldgen").resolve("structure").resolve("terrain_" + original.name + ".json"), json);
        System.out.println("Terrain " + original.name + ": " + plots.size() + " grounded plots, " + paths.size()
            + " graded path cells; legacy template preserved.");
    }

    private static boolean hasWalls(String name) {
        return name.equals("fortified_hamlet") || name.equals("bandit_camp") || name.equals("hamlet_small")
            || name.equals("hamlet_large") || name.equals("bandit_camp_large") || name.equals("watchtower_large");
    }

    private static boolean hasNorthGate(String name) {
        return name.equals("fortified_hamlet") || name.equals("hamlet_small") || name.equals("hamlet_large")
            || name.equals("watchtower_large");
    }

    private static boolean isCrop(String name) {
        return name.equals("wheat") || name.equals("carrots") || name.equals("potatoes") || name.equals("beetroots");
    }

    private static String biomeTag(String name) {
        if (name.startsWith("hamlet") || name.equals("fortified_hamlet")) return "fortified_hamlet";
        if (name.startsWith("bandit_camp")) return "bandit_camp";
        return "watchtower";
    }

    /**
     * One placement grid for every frontier site: each region holds at most one hamlet, camp or outpost, so they
     * cannot overlap; if the chosen kind does not fit the terrain, vanilla tries the others in weighted order.
     */
    private static void writeFrontierSites(Path data) throws IOException {
        Path sets = data.resolve("worldgen").resolve("structure_set");
        for (String old : List.of("fortified_hamlet", "watchtower", "bandit_camp")) {
            Files.deleteIfExists(sets.resolve(old + ".json"));
        }
        Files.writeString(sets.resolve("frontier_sites.json"), "{\n  \"structures\": [\n"
            + "    {\"structure\":\"" + NS + "terrain_hamlet_small\",\"weight\":2},\n"
            + "    {\"structure\":\"" + NS + "terrain_fortified_hamlet\",\"weight\":2},\n"
            + "    {\"structure\":\"" + NS + "terrain_hamlet_large\",\"weight\":1},\n"
            + "    {\"structure\":\"" + NS + "terrain_bandit_camp_small\",\"weight\":2},\n"
            + "    {\"structure\":\"" + NS + "terrain_bandit_camp\",\"weight\":1},\n"
            + "    {\"structure\":\"" + NS + "terrain_bandit_camp_large\",\"weight\":1},\n"
            + "    {\"structure\":\"" + NS + "terrain_watchtower_small\",\"weight\":2},\n"
            + "    {\"structure\":\"" + NS + "terrain_watchtower\",\"weight\":1},\n"
            + "    {\"structure\":\"" + NS + "terrain_watchtower_large\",\"weight\":1}\n  ],\n"
            + "  \"placement\": {\"type\":\"minecraft:random_spread\",\"spacing\":12,\"separation\":4,"
            + "\"spread_type\":\"linear\",\"salt\":1650973021,\n"
            + "    \"exclusion_zone\":{\"other_set\":\"minecraft:villages\",\"chunk_count\":7}}\n}\n");
        System.out.println("Frontier sites: one shared placement grid for all size variants.");
    }

    private static boolean insideGround(List<TerrainPlot> plots, int x, int z) {
        return plots.stream().anyMatch(plot -> contains(plot, x, z));
    }

    private static boolean contains(TerrainPlot plot, int x, int z) {
        return x >= plot.x + plot.groundX && x < plot.x + plot.groundX + plot.groundWidth
            && z >= plot.z + plot.groundZ && z < plot.z + plot.groundZ + plot.groundDepth;
    }

    private static TerrainPlot terrainPlot(Voxels original, String name, int x, int z, int width, int depth,
            int groundX, int groundZ, int groundWidth, int groundDepth, Consumer<Voxels> draw, List<Pos> entrances) {
        Voxels canvas;
        if (draw == null) canvas = original;
        else {
            canvas = new Voxels(name, original.width, original.height, original.depth);
            canvas.fill(groundX, FLOOR, groundZ, groundX + groundWidth - 1, FLOOR,
                groundZ + groundDepth - 1, SETTLEMENT_GRASS);
            draw.accept(canvas);
        }
        int top = Math.max(FLOOR + 2, canvas.blocks.values().stream()
            .filter(block -> block.pos.x >= x && block.pos.x < x + width && block.pos.z >= z && block.pos.z < z + depth
                && !block.state.equals(AIR)).mapToInt(block -> block.pos.y).max().orElse(FLOOR));
        List<Entity> residents = original.entities.stream().filter(entity ->
            entity.x >= groundX && entity.x < groundX + groundWidth && entity.z >= groundZ && entity.z < groundZ + groundDepth).toList();
        for (Entity resident : residents) top = Math.max(top, (int) Math.ceil(resident.y + 1.95));
        // Reserve room for the two market lamps added after component construction.
        if (name.equals("market")) top = Math.max(top, FLOOR + 3);
        Voxels part = new Voxels("terrain/" + original.name + "/" + name, width, top - FLOOR + 1, depth);
        part.blocks.clear();
        for (int zz = z; zz < z + depth; zz++) for (int xx = x; xx < x + width; xx++) {
            boolean core = xx >= groundX && xx < groundX + groundWidth && zz >= groundZ && zz < groundZ + groundDepth;
            for (int yy = FLOOR; yy <= top; yy++) {
                Placed source = canvas.blocks.get(new Pos(xx, yy, zz));
                if (!core && (yy == FLOOR || source.state.equals(AIR))) continue;
                if (name.startsWith("farm_") && yy == 6 && isCrop(source.state.name)) continue;
                part.set(xx - x, yy - FLOOR, zz - z, source.state, source.nbt);
            }
        }
        for (Entity resident : residents) {
            double px = resident.x - x, py = resident.y - FLOOR, pz = resident.z - z;
            Map<String, Tag> nbt = new LinkedHashMap<>(asCompound(resident.nbt));
            nbt.put("Pos", doubles(px, py, pz));
            part.entities.add(new Entity(resident.id, px, py, pz, new Tag((byte) 10, nbt)));
            require(part.accessible(new Pos((int) px, (int) py, (int) pz)), "Terrain resident lacks a usable floor");
        }
        return new TerrainPlot(name, x, z, groundX - x, groundZ - z, groundWidth, groundDepth, part,
            entrances.stream().map(pos -> new Pos(pos.x - x, 1, pos.z - z)).toList());
    }

    private static Voxels hamlet() {
        Voxels v = settlement("fortified_hamlet", 39, 19);
        palisade(v, 1, 37, 1, 37, 18, 20, true);
        path(v, 18, 0, 20, 38);
        path(v, 0, 18, 38, 20);
        v.fill(16, FLOOR, 16, 22, FLOOR, 22, state("stone_bricks"));

        roofedTower(v, 3, 3);
        roofedTower(v, 29, 3);
        cottage(v, 4, 11, true, "blue", "composter");
        cottage(v, 26, 11, true, "cyan", "fletching_table");
        cottage(v, 4, 24, false, "yellow", "smithing_table");
        cottage(v, 26, 24, false, "green", "stonecutter");
        farm(v, 14, 25);
        farm(v, 22, 25);
        for (int[] p : new int[][] {
            {16, 8}, {22, 8}, {16, 16}, {22, 22}, {16, 35}, {22, 35},
            {3, 21}, {35, 21}, {17, 2}, {21, 36}
        }) lamp(v, p[0], p[1]);

        board(v, 19, 18, false);
        v.set(19, 6, 21, COBBLE);
        v.set(19, 7, 21, state("bell", "attachment", "floor", "facing", "north", "powered", "false"));
        v.target(19, 6, 20);
        v.mob("guard", 18.5, 6, 10.5, 30, false);
        v.mob("guard", 20.5, 6, 32.5, 30, false);
        v.mob("guard", 7.5, 12, 6.5, 30, true);
        v.mob("quartermaster", 21.5, 6, 18.5, 24, false);
        v.villager(8.5, 6, 14.5, "farmer");
        v.villager(30.5, 6, 14.5, "fletcher");
        v.villager(8.5, 6, 27.5, "toolsmith");
        v.villager(30.5, 6, 27.5, "mason");
        v.expected = Map.of(NS + "guard", 3, NS + "quartermaster", 1, "minecraft:villager", 4);
        v.expectedBeds = 8;
        v.expectedChests = 4;
        v.expectedBoards = 1;
        v.expectedOutpost = 0;
        return v;
    }

    private static Voxels watchtower() {
        Voxels v = settlement("watchtower", 13, 19);
        roofedTower(v, 3, 2);
        for (int x = 1; x <= 11; x++) {
            v.set(x, 6, 1, FENCE);
            if (x < 5 || x > 7) v.set(x, 6, 11, FENCE);
        }
        for (int z = 1; z <= 11; z++) {
            v.set(1, 6, z, FENCE);
            v.set(11, 6, z, FENCE);
        }
        path(v, 5, 8, 7, 12);
        path(v, 8, 9, 10, 9);
        board(v, 6, 6, true);
        bed(v, 4, 4, "blue");
        chest(v, 8, 6, 4, "tower_supplies", "south");
        v.set(8, 6, 6, state("fletching_table"));
        v.target(7, 6, 6);
        v.fill(8, 6, 10, 10, 6, 10, state("spruce_planks"));
        v.fill(8, 10, 9, 10, 10, 11, state("green_wool"));
        v.fill(10, 7, 11, 10, 9, 11, LOG);
        v.fill(8, 7, 11, 8, 9, 11, LOG);
        chest(v, 10, 6, 9, "tower_supplies", "south");
        lamp(v, 3, 10);
        v.mob("guard", 6.5, 6, 10.5, 30, false);
        v.mob("guard", 7.5, 12, 5.5, 30, true);
        v.mob("quartermaster", 9.5, 6, 9.5, 24, false);
        v.villager(8.5, 6, 5.5, "fletcher");
        v.gate(5, 7, 11);
        v.expected = Map.of(NS + "guard", 2, NS + "quartermaster", 1, "minecraft:villager", 1);
        v.expectedBeds = 1;
        v.expectedChests = 2;
        v.expectedBoards = 1;
        v.expectedOutpost = 1;
        return v;
    }

    private static Voxels camp() {
        Voxels v = settlement("bandit_camp", 23, 14);
        palisade(v, 1, 21, 1, 21, 10, 12, false);
        path(v, 10, 0, 12, 22);
        path(v, 2, 11, 20, 12);
        tent(v, 3, 4, "brown", "orange");
        tent(v, 13, 4, "light_gray", "red");
        tent(v, 13, 13, "gray", "red");
        v.set(11, 6, 12, state("campfire",
            "facing", "north", "lit", "true", "signal_fire", "false", "waterlogged", "false"));
        State fireGuard = state("cobblestone_wall", "up", "true", "north", "none", "south", "none",
            "east", "none", "west", "none", "waterlogged", "false");
        for (int x = 10; x <= 12; x++) for (int z = 11; z <= 13; z++) {
            if (x != 11 || z != 12) v.set(x, 6, z, fireGuard);
        }
        for (int[] p : new int[][] {{10, 3}, {12, 3}, {3, 12}, {19, 12}, {9, 18}, {13, 20}}) {
            v.fill(p[0], 6, p[1], p[0], 7, p[1], FENCE);
            v.set(p[0], 8, p[1], state("torch"));
        }
        v.target(13, 6, 12);
        v.mob("bandit", 6.5, 6, 7.5, 24, false);
        v.mob("bandit", 16.5, 6, 7.5, 24, true);
        v.mob("bandit", 6.5, 6, 16.5, 24, false);
        v.mob("bandit_leader", 16.5, 6, 16.5, 48, false);
        v.expected = Map.of(NS + "bandit", 3, NS + "bandit_leader", 1);
        v.expectedBeds = 3;
        v.expectedChests = 3;
        v.expectedBoards = 0;
        return v;
    }


    private static void house(Voxels v, int x, int z, int w, int d, boolean south, String color,
                              int beds, String job, String loot) {
        v.fill(x, FLOOR, z, x + w - 1, FLOOR, z + d - 1, PLANKS);
        v.fill(x, 6, z, x + w - 1, 8, z, PLANKS);
        v.fill(x, 6, z + d - 1, x + w - 1, 8, z + d - 1, PLANKS);
        v.fill(x, 6, z, x, 8, z + d - 1, PLANKS);
        v.fill(x + w - 1, 6, z, x + w - 1, 8, z + d - 1, PLANKS);
        for (int xx : new int[] {x, x + w - 1}) for (int zz : new int[] {z, z + d - 1}) v.fill(xx, 6, zz, xx, 9, zz, LOG);
        int doorX = x + w / 2;
        int doorZ = south ? z + d - 1 : z;
        for (int y = 6; y <= 8; y++) v.set(doorX, y, doorZ, AIR);
        String facing = south ? "south" : "north";
        v.set(doorX, 6, doorZ, state("oak_door", "facing", facing, "half", "lower", "hinge", "left", "open", "false", "powered", "false"));
        v.set(doorX, 7, doorZ, state("oak_door", "facing", facing, "half", "upper", "hinge", "left", "open", "false", "powered", "false"));
        v.doors.add(new Pos(doorX, 6, doorZ));
        for (int dx = -1; dx <= w; dx++) {
            int y = 9 + Math.min(2, Math.min(Math.max(dx, 0), Math.max(w - 1 - dx, 0)));
            State roof = dx < w / 2 ? stairs("spruce", "east") : dx > w / 2 ? stairs("spruce", "west") : state("spruce_planks");
            for (int dz = -1; dz <= d; dz++) v.set(x + dx, y, z + dz, roof);
        }
        int bedColumns = Math.max(1, (w - 3) / 2);
        for (int i = 0; i < beds; i++) bed(v, x + 1 + 2 * (i % bedColumns), z + 1 + 2 * (i / bedColumns), color);
        if (job != null) {
            State station = state(job);
            if (job.equals("composter")) station = state(job, "level", "0");
            if (job.equals("stonecutter")) station = state(job, "facing", "north");
            v.set(x + w - 2, 6, z + d - 2, station);
        }
        if (loot != null) chest(v, x + w - 2, 6, z + 1, loot, "south");
        v.set(x + w - 2, 8, z + d / 2, state("wall_torch", "facing", "west"));
    }

    private static void customTower(Voxels v, int x, int z, int size, int wallTop) {
        int max = size - 1;
        v.fill(x, FLOOR, z, x + max, FLOOR, z + max, COBBLE);
        v.fill(x, 6, z, x + max, wallTop, z, state("spruce_planks"));
        v.fill(x, 6, z + max, x + max, wallTop, z + max, state("spruce_planks"));
        v.fill(x, 6, z, x, wallTop, z + max, state("spruce_planks"));
        v.fill(x + max, 6, z, x + max, wallTop, z + max, state("spruce_planks"));
        for (int xx : new int[] {x, x + max}) for (int zz : new int[] {z, z + max}) v.fill(xx, 6, zz, xx, wallTop + 4, zz, LOG);
        int doorX = x + size / 2;
        v.fill(doorX - 1, 6, z + max, doorX + 1, 8, z + max, AIR);
        int deck = wallTop + 1;
        int stand = deck + 1;
        v.fill(x, deck, z, x + max, deck, z + max, PLANKS);
        for (int i = 0; i <= max; i++) {
            v.set(x + i, stand, z, FENCE); v.set(x + i, stand, z + max, FENCE);
            v.set(x, stand, z + i, FENCE); v.set(x + max, stand, z + i, FENCE);
        }
        for (int px = x + 1; px <= x + max - 1; px++) for (int pz = z + 1; pz <= z + max - 1; pz++) v.platforms.add(new Pos(px, stand, pz));
        int lx = x + size / 2;
        v.fill(lx, 6, z, lx, stand + 1, z, LOG);
        v.fill(lx, 6, z + 1, lx, stand, z + 1, state("ladder", "facing", "south", "waterlogged", "false"));
        v.ladders.add(new Pos(lx, 6, z + 1));
        v.fill(x - 1, stand + 3, z - 1, x + max + 1, stand + 3, z + max + 1, state("spruce_planks"));
        v.fill(x, stand + 4, z, x + max, stand + 4, z + max, state("spruce_slab", "type", "bottom", "waterlogged", "false"));
        v.set(lx, stand + 2, z + size / 2, state("lantern", "hanging", "true", "waterlogged", "false"));
        path(v, doorX - 1, z + max + 1, doorX + 1, z + max + 2);
    }

    private static void smallTent(Voxels v, int x, int z, int w, int d, String cloth, String stripe, String loot) {
        v.fill(x, FLOOR, z, x + w - 1, FLOOR, z + d - 1, state("spruce_planks"));
        for (int dx = 0; dx < w; dx++) {
            int roof = 7 + Math.min(dx, w - 1 - dx);
            State wool = state((dx == w / 2 ? stripe : cloth) + "_wool");
            v.fill(x + dx, roof, z, x + dx, roof, z + d - 1, wool);
            v.fill(x + dx, 6, z, x + dx, roof, z, wool);
            v.fill(x + dx, 6, z + d - 1, x + dx, roof, z + d - 1, wool);
        }
        v.fill(x, 6, z, x, 7, z + d - 1, LOG);
        v.fill(x + w - 1, 6, z, x + w - 1, 7, z + d - 1, LOG);
        v.fill(x + w / 2 - 1, 6, z + d - 1, x + w / 2 + 1, 7, z + d - 1, AIR);
        bed(v, x + 1, z + 1, stripe);
        if (loot != null) chest(v, x + w - 2, 6, z + 1, loot, "south");
        v.set(x + w / 2, 8, z + 1, state("wall_torch", "facing", "south"));
        v.target(x + w / 2, 6, z + d - 1);
        v.target(x + w / 2, 6, z + d / 2);
    }

    private static void well(Voxels v, int x, int z) {
        v.fill(x, FLOOR, z, x + 2, FLOOR, z + 2, state("stone_bricks"));
        v.set(x + 1, FLOOR, z + 1, state("water", "level", "0"));
        v.fill(x, 6, z, x + 2, 6, z + 2, state("cobblestone_wall", "up", "true", "north", "none", "south", "none", "east", "none", "west", "none", "waterlogged", "false"));
        v.fill(x, 7, z, x, 9, z, LOG); v.fill(x + 2, 7, z, x + 2, 9, z, LOG);
        v.fill(x, 10, z, x + 2, 10, z + 2, state("spruce_slab", "type", "bottom", "waterlogged", "false"));
        v.target(x + 1, 6, z + 3);
    }

    private static void pen(Voxels v, int x, int z, int w, int d) {
        v.fill(x, FLOOR, z, x + w - 1, FLOOR, z + d - 1, SETTLEMENT_GRASS);
        for (int xx = x; xx < x + w; xx++) { v.set(xx, 6, z, FENCE); v.set(xx, 6, z + d - 1, FENCE); }
        for (int zz = z; zz < z + d; zz++) { v.set(x, 6, zz, FENCE); v.set(x + w - 1, 6, zz, FENCE); }
        v.fill(x + 2, 6, z + d - 1, x + 4, 6, z + d - 1, AIR);
        v.set(x + 1, 6, z + 1, state("hay_block", "axis", "y"));
        v.target(x + 3, 6, z + d - 1);
    }

    private static void marketStall(Voxels v, int x, int z, String color) {
        v.fill(x, FLOOR, z, x + 4, FLOOR, z + 3, state("stone_bricks"));
        v.fill(x, 6, z, x, 8, z, LOG); v.fill(x + 4, 6, z, x + 4, 8, z, LOG);
        v.fill(x, 9, z, x + 4, 9, z + 3, state(color + "_wool"));
        v.set(x + 1, 6, z + 1, state("barrel", "facing", "up", "open", "false"));
        v.set(x + 3, 6, z + 1, state("crafting_table"));
    }


    private static Voxels hamletSmall() {
        Voxels v = settlement("hamlet_small", 31, 18);
        palisade(v, 1, 29, 1, 29, 14, 16, true);
        path(v, 14, 0, 16, 30); path(v, 2, 14, 28, 16);
        customTower(v, 3, 3, 5, 8);
        house(v, 4, 18, 7, 5, false, "yellow", 1, "composter", "hamlet_supplies");
        house(v, 19, 17, 9, 7, false, "blue", 2, "fletching_table", "hamlet_supplies");
        path(v, 7, 16, 23, 17);
        farm(v, 22, 5);
        v.fill(12, FLOOR, 10, 18, FLOOR, 16, state("stone_bricks"));
        marketStall(v, 12, 11, "white");
        board(v, 15, 15, false);
        v.set(16, 6, 13, COBBLE);
        v.set(16, 7, 13, state("bell", "attachment", "floor", "facing", "north", "powered", "false"));
        for (int[] p : new int[][] {{15,2},{10,8},{20,8},{10,22},{20,22},{3,16},{27,16},{15,27}}) lamp(v, p[0], p[1]);
        v.mob("guard", 14.5, 6, 25.5, 30, false);
        v.mob("guard", 5.5, 10, 5.5, 30, true);
        v.mob("quartermaster", 14.5, 6, 12.5, 24, false);
        v.villager(7.5, 6, 20.5, "farmer");
        v.villager(22.5, 6, 20.5, "fletcher");
        v.expected = Map.of(NS + "guard", 2, NS + "quartermaster", 1, "minecraft:villager", 2);
        v.expectedBeds = 3; v.expectedChests = 2; v.expectedBoards = 1; v.expectedOutpost = 0; v.expectedArchers = 1;
        return v;
    }

    private static Voxels hamletLarge() {
        Voxels v = settlement("hamlet_large", 49, 24);
        palisade(v, 1, 47, 1, 47, 23, 25, true);
        path(v, 23, 0, 25, 48); path(v, 2, 23, 46, 25); path(v, 12, 12, 36, 14); path(v, 12, 34, 36, 36);
        customTower(v, 3, 3, 7, 10); customTower(v, 41, 3, 5, 8); customTower(v, 3, 39, 5, 8); customTower(v, 39, 39, 7, 12);
        path(v, 6, 10, 23, 12); path(v, 24, 8, 41, 12); path(v, 5, 36, 23, 41); path(v, 25, 36, 42, 46);
        house(v, 5, 17, 11, 7, true, "red", 4, null, "tower_supplies");
        house(v, 18, 6, 13, 7, true, "green", 4, "lectern", "hamlet_supplies");
        house(v, 33, 17, 9, 7, true, "blue", 2, "cartography_table", "hamlet_supplies");
        house(v, 7, 29, 7, 5, false, "yellow", 1, "composter", "hamlet_supplies");
        house(v, 34, 30, 9, 7, false, "cyan", 2, "loom", "hamlet_supplies");
        house(v, 18, 34, 11, 7, false, "lime", 3, "stonecutter", "hamlet_supplies");
        house(v, 32, 6, 9, 7, true, "orange", 1, "smithing_table", "hamlet_supplies");
        v.set(34, 6, 8, state("blast_furnace", "facing", "south", "lit", "false"));
        v.set(35, 6, 8, state("anvil", "facing", "east"));
        house(v, 30, 38, 7, 7, false, "brown", 0, null, "hamlet_supplies");
        path(v, 31, 36, 35, 37);
        v.set(31, 6, 40, state("barrel", "facing", "up", "open", "false")); v.set(32, 6, 40, state("hay_block", "axis", "y"));
        well(v, 23, 28); pen(v, 10, 3, 8, 6); path(v, 13, 9, 13, 12); farm(v, 29, 33); farm(v, 43, 9); path(v, 41, 10, 43, 10); path(v, 43, 8, 43, 10);
        v.fill(21, FLOOR, 20, 28, FLOOR, 27, state("stone_bricks"));
        board(v, 24, 20, false);
        v.set(24, 6, 26, COBBLE); v.set(24, 7, 26, state("bell", "attachment", "floor", "facing", "north", "powered", "false"));
        marketStall(v, 18, 22, "white"); marketStall(v, 27, 22, "light_blue");
        for (int[] p : new int[][] {{12,14},{36,14},{12,36},{36,36},{23,10},{25,40},{5,25},{43,25},{20,2},{28,46},{23,23},{27,27},{42,45}}) lamp(v, p[0], p[1]);
        v.mob("guard", 10.5, 6, 20.5, 30, false); v.mob("guard", 36.5, 6, 20.5, 30, false);
        v.mob("guard", 24.5, 6, 43.5, 30, false); v.mob("guard", 8.5, 12, 6.5, 30, true); v.mob("guard", 42.5, 14, 42.5, 30, true);
        v.mob("quartermaster", 25.5, 6, 23.5, 24, false); v.mob("quartermaster", 19.5, 6, 20.5, 24, false);
        v.villager(21.5, 6, 9.5, "librarian"); v.villager(36.5, 6, 9.5, "toolsmith"); v.villager(37.5, 6, 33.5, "shepherd");
        v.villager(10.5, 6, 31.5, "farmer"); v.villager(23.5, 6, 25.5, "mason"); v.villager(33.5, 6, 35.5, "farmer");
        v.expected = Map.of(NS + "guard", 5, NS + "quartermaster", 2, "minecraft:villager", 6);
        v.expectedBeds = 17; v.expectedChests = 8; v.expectedBoards = 1; v.expectedOutpost = 0; v.expectedArchers = 2;
        return v;
    }

    private static Voxels campSmall() {
        Voxels v = settlement("bandit_camp_small", 17, 14);
        path(v, 7, 8, 9, 16); path(v, 4, 8, 12, 10);
        smallTent(v, 2, 5, 5, 5, "brown", "orange", "bandit_cache");
        smallTent(v, 10, 5, 5, 5, "gray", "red", "bandit_cache");
        v.set(8, 6, 10, state("campfire", "facing", "north", "lit", "true", "signal_fire", "false", "waterlogged", "false"));
        for (int[] p : new int[][] {{4,4},{12,4},{4,12},{12,12}}) { v.fill(p[0], 6, p[1], p[0], 7, p[1], FENCE); v.set(p[0], 8, p[1], state("torch")); }
        v.mob("bandit", 4.5, 6, 7.5, 24, false); v.mob("bandit", 12.5, 6, 7.5, 24, true);
        v.expected = Map.of(NS + "bandit", 2); v.expectedBeds = 2; v.expectedChests = 2; v.expectedBoards = 0; v.expectedArchers = 1;
        return v;
    }

    private static Voxels campLarge() {
        Voxels v = settlement("bandit_camp_large", 31, 18);
        palisade(v, 1, 29, 1, 29, 14, 16, false);
        path(v, 14, 0, 16, 30); path(v, 3, 15, 27, 17); path(v, 15, 4, 15, 27);
        smallTent(v, 3, 6, 7, 6, "brown", "orange", "bandit_cache"); smallTent(v, 21, 6, 7, 6, "gray", "red", "bandit_cache");
        smallTent(v, 4, 20, 7, 6, "light_gray", "red", "bandit_cache"); smallTent(v, 18, 19, 9, 7, "black", "red", "bandit_cache");
        customTower(v, 13, 3, 5, 8);
        v.set(15, 6, 17, state("campfire", "facing", "north", "lit", "true", "signal_fire", "false", "waterlogged", "false"));
        v.fill(23, 6, 14, 25, 6, 14, state("barrel", "facing", "up", "open", "false")); v.set(24, 6, 15, state("hay_block", "axis", "y"));
        chest(v, 25, 6, 15, "bandit_cache", "south");
        for (int[] p : new int[][] {{5,4},{25,4},{5,15},{25,15},{7,27},{23,27},{15,23}}) { v.fill(p[0], 6, p[1], p[0], 7, p[1], FENCE); v.set(p[0], 8, p[1], state("torch")); }
        v.mob("bandit", 6.5, 6, 8.5, 24, false); v.mob("bandit", 24.5, 6, 8.5, 24, true); v.mob("bandit", 7.5, 6, 22.5, 24, false);
        v.mob("bandit", 15.5, 10, 5.5, 24, true); v.mob("bandit", 22.5, 6, 22.5, 24, false); v.mob("bandit_leader", 22.5, 6, 21.5, 48, false);
        v.expected = Map.of(NS + "bandit", 5, NS + "bandit_leader", 1); v.expectedBeds = 4; v.expectedChests = 4; v.expectedBoards = 0; v.expectedArchers = 2;
        return v;
    }

    private static Voxels watchtowerSmall() {
        Voxels v = settlement("watchtower_small", 9, 18);
        customTower(v, 2, 1, 5, 8);
        house(v, 1, 5, 7, 3, false, "blue", 1, null, "tower_supplies");
        board(v, 6, 8, true);
        lamp(v, 1, 8);
        v.mob("guard", 4.5, 10, 3.5, 30, true); v.mob("guard", 5.5, 6, 8.5, 30, false);
        v.expected = Map.of(NS + "guard", 2); v.expectedBeds = 1; v.expectedChests = 1; v.expectedBoards = 1; v.expectedOutpost = 1; v.expectedArchers = 1;
        v.gate(3, 5, 8);
        return v;
    }

    private static Voxels watchtowerLarge() {
        Voxels v = settlement("watchtower_large", 29, 20);
        palisade(v, 1, 27, 1, 27, 13, 15, true);
        path(v, 13, 0, 15, 28); path(v, 3, 14, 25, 16);
        customTower(v, 3, 3, 7, 10); customTower(v, 19, 3, 7, 10);
        house(v, 4, 18, 11, 7, false, "blue", 4, null, "tower_supplies");
        house(v, 17, 18, 9, 7, false, "green", 1, "fletching_table", "tower_supplies");
        path(v, 8, 16, 10, 18); path(v, 20, 16, 22, 18);
        v.fill(11, FLOOR, 10, 17, FLOOR, 16, state("stone_bricks")); board(v, 14, 11, true);
        v.set(15, 6, 13, state("barrel", "facing", "up", "open", "false"));
        v.set(13, 6, 15, state("bell", "attachment", "floor", "facing", "north", "powered", "false"));
        well(v, 22, 12); marketStall(v, 5, 12, "white");
        for (int[] p : new int[][] {{9,10},{19,10},{5,25},{23,25},{14,4},{14,24},{3,15},{25,15}}) lamp(v, p[0], p[1]);
        v.mob("guard", 8.5, 12, 6.5, 30, true); v.mob("guard", 22.5, 12, 6.5, 30, true); v.mob("guard", 15.5, 6, 24.5, 30, false); v.mob("guard", 22.5, 6, 21.5, 30, false);
        v.mob("quartermaster", 15.5, 6, 16.5, 24, false); v.villager(21.5, 6, 21.5, "fletcher");
        v.expected = Map.of(NS + "guard", 4, NS + "quartermaster", 1, "minecraft:villager", 1); v.expectedBeds = 5; v.expectedChests = 2; v.expectedBoards = 1; v.expectedOutpost = 1; v.expectedArchers = 2;
        return v;
    }

    private static Voxels settlement(String name, int width, int height) {
        Voxels v = new Voxels(name, width, height, width);
        v.fill(0, 0, 0, width - 1, 3, width - 1, COBBLE);
        v.fill(0, 4, 0, width - 1, 4, width - 1, COBBLE);
        v.fill(0, FLOOR, 0, width - 1, FLOOR, width - 1, SETTLEMENT_GRASS);
        return v;
    }

    private static void palisade(Voxels v, int x0, int x1, int z0, int z1,
                                 int gate0, int gate1, boolean northGate) {
        for (int x = x0; x <= x1; x++) for (int z : new int[] {z0, z1}) {
            boolean opening = x >= gate0 && x <= gate1 && (z == z1 || northGate);
            if (!opening) {
                v.fill(x, 6, z, x, 8, z, (x % 3 == 0 || x == x0 || x == x1) ? LOG : FENCE);
                if (x % 3 == 0 || x == x0 || x == x1) {
                    v.set(x, 9, z, state("spruce_slab", "type", "bottom", "waterlogged", "false"));
                }
            } else v.set(x, 9, z, LOG);
        }
        for (int z = z0; z <= z1; z++) for (int x : new int[] {x0, x1}) {
            v.fill(x, 6, z, x, 8, z, (z % 3 == 0) ? LOG : FENCE);
            if (z % 3 == 0) v.set(x, 9, z,
                state("spruce_slab", "type", "bottom", "waterlogged", "false"));
        }
        v.gate(gate0, gate1, z1);
        if (northGate) v.gate(gate0, gate1, z0);
    }

    private static void path(Voxels v, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            v.set(x, FLOOR, z, state((x + z) % 4 == 0 ? "mossy_cobblestone" : "cobblestone"));
            v.paths.add(new Pos(x, 6, z));
        }
    }

    private static void cottage(Voxels v, int x, int z, boolean south, String color, String job) {
        v.fill(x, FLOOR, z, x + 8, FLOOR, z + 6, PLANKS);
        v.fill(x, 6, z, x + 8, 8, z, PLANKS);
        v.fill(x, 6, z + 6, x + 8, 8, z + 6, PLANKS);
        v.fill(x, 6, z, x, 8, z + 6, PLANKS);
        v.fill(x + 8, 6, z, x + 8, 8, z + 6, PLANKS);
        for (int xx : new int[] {x, x + 8}) for (int zz : new int[] {z, z + 6}) {
            v.fill(xx, 6, zz, xx, 8, zz, LOG);
        }
        State glass = state("glass_pane", "north", "true", "south", "true",
            "east", "false", "west", "false", "waterlogged", "false");
        v.set(x, 7, z + 3, glass);
        v.set(x + 8, 7, z + 3, glass);
        for (int dx = -1; dx <= 9; dx++) {
            int y = 9 + Math.min(3, Math.min(Math.max(dx, 0), Math.max(8 - dx, 0)));
            State roof = dx < 3 ? stairs("spruce", "east")
                : dx > 5 ? stairs("spruce", "west") : state("spruce_planks");
            for (int dz = -1; dz <= 7; dz++) v.set(x + dx, y, z + dz, roof);
            if (dx >= 0 && dx <= 8) {
                for (int yy = 9; yy < y; yy++) {
                    v.set(x + dx, yy, z, PLANKS);
                    v.set(x + dx, yy, z + 6, PLANKS);
                }
            }
        }
        int doorZ = south ? z + 6 : z;
        for (int y = 6; y <= 8; y++) v.set(x + 4, y, doorZ, AIR);
        String facing = south ? "south" : "north";
        v.set(x + 4, 6, doorZ, state("oak_door", "facing", facing, "half", "lower",
            "hinge", "left", "open", "false", "powered", "false"));
        v.set(x + 4, 7, doorZ, state("oak_door", "facing", facing, "half", "upper",
            "hinge", "left", "open", "false", "powered", "false"));
        v.doors.add(new Pos(x + 4, 6, doorZ));
        path(v, x + 3, south ? doorZ + 1 : 20, x + 5, south ? 20 : doorZ - 1);
        v.target(x + 4, 6, z + 3);
        bed(v, x + 1, z + 2, color);
        bed(v, x + 6, z + 2, color);
        State station = state(job);
        if (job.equals("composter")) station = state(job, "level", "0");
        if (job.equals("stonecutter")) station = state(job, "facing", "north");
        v.set(x + 2, 6, z + 5, station);
        v.target(x + 3, 6, z + 5);
        chest(v, x + 6, 6, z + 5, "hamlet_supplies", "north");
        v.set(x + 7, 8, z + 3, state("wall_torch", "facing", "west"));
    }

    private static State stairs(String wood, String facing) {
        return state(wood + "_stairs", "facing", facing, "half", "bottom",
            "shape", "straight", "waterlogged", "false");
    }

    private static void bed(Voxels v, int x, int z, String color) {
        v.set(x, 6, z, state(color + "_bed", "part", "foot", "facing", "south", "occupied", "false"));
        v.set(x, 6, z + 1, state(color + "_bed", "part", "head", "facing", "south", "occupied", "false"));
    }

    private static void roofedTower(Voxels v, int x, int z) {
        v.fill(x, FLOOR, z, x + 6, FLOOR, z + 6, COBBLE);
        v.fill(x, 6, z, x + 6, 10, z, state("spruce_planks"));
        v.fill(x, 6, z + 6, x + 6, 10, z + 6, state("spruce_planks"));
        v.fill(x, 6, z, x, 10, z + 6, state("spruce_planks"));
        v.fill(x + 6, 6, z, x + 6, 10, z + 6, state("spruce_planks"));
        for (int xx : new int[] {x, x + 6}) for (int zz : new int[] {z, z + 6}) {
            v.fill(xx, 6, zz, xx, 14, zz, LOG);
        }
        v.fill(x + 2, 6, z + 6, x + 4, 8, z + 6, AIR);
        v.set(x, 8, z + 3, state("glass_pane", "north", "true", "south", "true",
            "east", "false", "west", "false", "waterlogged", "false"));
        v.set(x + 6, 8, z + 3, state("glass_pane", "north", "true", "south", "true",
            "east", "false", "west", "false", "waterlogged", "false"));
        v.fill(x, 11, z, x + 6, 11, z + 6, PLANKS);
        for (int px = x + 1; px <= x + 5; px++) for (int pz = z + 1; pz <= z + 5; pz++) v.platforms.add(new Pos(px, 12, pz));
        for (int i = 0; i <= 6; i++) {
            v.set(x + i, 12, z, FENCE);
            v.set(x + i, 12, z + 6, FENCE);
            v.set(x, 12, z + i, FENCE);
            v.set(x + 6, 12, z + i, FENCE);
        }
        for (int xx : new int[] {x, x + 6}) for (int zz : new int[] {z, z + 6}) {
            v.fill(xx, 12, zz, xx, 14, zz, LOG);
        }
        v.fill(x + 3, 6, z, x + 3, 13, z, LOG);
        v.fill(x + 3, 6, z + 1, x + 3, 12, z + 1,
            state("ladder", "facing", "south", "waterlogged", "false"));
        v.ladders.add(new Pos(x + 3, 6, z + 1));
        v.target(x + 3, 6, z + 1);
        v.target(x + 3, 6, z + 5);
        v.fill(x - 1, 15, z - 1, x + 7, 15, z + 7, state("spruce_planks"));
        v.fill(x, 16, z, x + 6, 16, z + 6, state("spruce_planks"));
        v.fill(x + 1, 17, z + 1, x + 5, 17, z + 5,
            state("spruce_slab", "type", "bottom", "waterlogged", "false"));
        v.set(x + 3, 14, z + 3, state("lantern", "hanging", "true", "waterlogged", "false"));
        v.set(x + 1, 8, z + 2, state("wall_torch", "facing", "east"));
        path(v, x + 2, z + 7, x + 4, z + 8);
    }

    private static void farm(Voxels v, int x, int z) {
        v.fill(x - 1, FLOOR, z - 1, x + 3, FLOOR, z + 9, state("spruce_planks"));
        for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 9; dz++) {
            if (dx == 1 && dz == 4) {
                v.set(x + dx, FLOOR, z + dz, state("water", "level", "0"));
                v.set(x + dx, 6, z + dz, state("lily_pad"));
            } else {
                v.set(x + dx, FLOOR, z + dz, state("farmland", "moisture", "7"));
                v.set(x + dx, 6, z + dz,
                    state(List.of("wheat", "carrots", "potatoes").get((dx + dz) % 3), "age", "7"));
            }
        }
        v.target(x, 6, z - 1);
    }

    private static void tent(Voxels v, int x, int z, String cloth, String stripe) {
        v.fill(x, FLOOR, z, x + 6, FLOOR, z + 5, state("spruce_planks"));
        for (int dx = 0; dx <= 6; dx++) {
            int roof = 7 + Math.min(dx, 6 - dx);
            State wool = state((dx == 3 ? stripe : cloth) + "_wool");
            v.fill(x + dx, roof, z, x + dx, roof, z + 5, wool);
            v.fill(x + dx, 6, z, x + dx, roof, z, wool);
            v.fill(x + dx, 6, z + 5, x + dx, roof, z + 5, wool);
        }
        v.fill(x, 6, z, x, 7, z + 5, LOG);
        v.fill(x + 6, 6, z, x + 6, 7, z + 5, LOG);
        v.fill(x + 2, 6, z + 5, x + 4, 7, z + 5, AIR);
        bed(v, x + 1, z + 2, stripe);
        chest(v, x + 5, 6, z + 2, "bandit_cache", "south");
        v.set(x + 3, 8, z + 1, state("wall_torch", "facing", "south"));
        v.target(x + 3, 6, z + 3);
        v.target(x + 3, 6, z + 5);
    }

    private static void lamp(Voxels v, int x, int z) {
        v.set(x, FLOOR, z, COBBLE);
        v.fill(x, 6, z, x, 7, z, FENCE);
        v.set(x, 8, z, state("lantern", "hanging", "false", "waterlogged", "false"));
    }

    private static void board(Voxels v, int x, int z, boolean outpost) {
        v.set(x, 6, z, state(NS + "settlement_board"),
            compound("id", string(NS + "settlement_board"), "Outpost", bit(outpost)));
        v.target(x - 1, 6, z);
    }

    private static void chest(Voxels v, int x, int y, int z, String table, String facing) {
        v.set(x, y, z, state("chest", "facing", facing, "type", "single", "waterlogged", "false"),
            compound("id", string("minecraft:chest"), "LootTable", string(NS + "chests/" + table)));
        v.set(x, y + 1, z, AIR);
    }

    private record Pos(int x, int y, int z) {}
    private record State(String name, Map<String, String> properties) {}
    private record Placed(Pos pos, State state, Tag nbt) {}
    private record Entity(String id, double x, double y, double z, Tag nbt) {}
    private record Tag(byte type, Object value) {}
    private record NbtList(byte elementType, List<Tag> values) {}

    private static State state(String name, String... properties) {
        require(properties.length % 2 == 0, "Unpaired property");
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < properties.length; i += 2) map.put(properties[i], properties[i + 1]);
        return new State(name.contains(":") ? name : "minecraft:" + name, Collections.unmodifiableMap(map));
    }

    private static final class Voxels {
        final String name;
        final int width, height, depth;
        final Map<Pos, Placed> blocks = new HashMap<>();
        final List<Entity> entities = new ArrayList<>();
        final List<Pos> doors = new ArrayList<>(), ladders = new ArrayList<>();
        final Set<Pos> targets = new HashSet<>(), paths = new HashSet<>();
        Map<String, Integer> expected = Map.of();
        int expectedBeds, expectedChests, expectedBoards, expectedOutpost, expectedArchers = 1;
        final Set<Pos> platforms = new HashSet<>();

        Voxels(String name, int width, int height, int depth) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.depth = depth;
            fill(0, 0, 0, width - 1, height - 1, depth - 1, AIR);
        }

        void set(int x, int y, int z, State state) { set(x, y, z, state, null); }

        void set(int x, int y, int z, State state, Tag nbt) {
            require(x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth,
                name + ": out-of-bounds block " + x + "," + y + "," + z);
            Pos p = new Pos(x, y, z);
            blocks.put(p, new Placed(p, state, nbt));
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, State state) {
            require(x0 <= x1 && y0 <= y1 && z0 <= z1, name + ": reversed fill");
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) set(x, y, z, state);
            }
        }

        State at(int x, int y, int z) {
            Placed b = blocks.get(new Pos(x, y, z));
            return b == null ? AIR : b.state;
        }

        void target(int x, int y, int z) { targets.add(new Pos(x, y, z)); }

        void gate(int x0, int x1, int z) {
            for (int x = x0; x <= x1; x++) {
                for (int y = 6; y <= 8; y++) {
                    require(at(x, y, z).equals(AIR), name + ": blocked gate " + new Pos(x, y, z));
                }
                target(x, 6, z);
            }
        }

        void mob(String kind, double x, double y, double z, float health, boolean archer) {
            Map<String, Tag> nbt = entityNbt(NS + kind, x, y, z, health);
            if (kind.equals("guard")) nbt.put("HomeBound", bit(true));
            if (archer) nbt.put("Archer", bit(true));
            entities.add(new Entity(NS + kind, x, y, z, new Tag((byte) 10, nbt)));
        }

        void villager(double x, double y, double z, String profession) {
            Map<String, Tag> nbt = entityNbt("minecraft:villager", x, y, z, 20);
            nbt.put("Age", integer(0));
            nbt.put("VillagerData", compound("type", string("minecraft:plains"),
                "profession", string("minecraft:" + profession), "level", integer(1)));
            entities.add(new Entity("minecraft:villager", x, y, z, new Tag((byte) 10, nbt)));
        }

        private Map<String, Tag> entityNbt(String id, double x, double y, double z, float health) {
            return fields("id", string(id), "Pos", doubles(x, y, z), "Rotation", floats(0, 0),
                "PersistenceRequired", bit(true), "Health", floating(health));
        }

        Tag template() {
            List<Placed> ordered = new ArrayList<>(blocks.values());
            ordered.sort(Comparator.comparingInt((Placed b) -> b.pos.y)
                .thenComparingInt(b -> b.pos.z).thenComparingInt(b -> b.pos.x));
            Map<State, Integer> palette = new LinkedHashMap<>();
            List<Tag> blockTags = new ArrayList<>();
            for (Placed b : ordered) {
                int index = palette.computeIfAbsent(b.state, s -> palette.size());
                Map<String, Tag> entry = fields("pos", ints(b.pos.x, b.pos.y, b.pos.z),
                    "state", integer(index));
                if (b.nbt != null) entry.put("nbt", b.nbt);
                blockTags.add(new Tag((byte) 10, entry));
            }
            List<Tag> paletteTags = new ArrayList<>();
            for (State state : palette.keySet()) {
                Map<String, Tag> entry = fields("Name", string(state.name));
                if (!state.properties.isEmpty()) {
                    Map<String, Tag> props = new LinkedHashMap<>();
                    state.properties.forEach((key, value) -> props.put(key, string(value)));
                    entry.put("Properties", new Tag((byte) 10, props));
                }
                paletteTags.add(new Tag((byte) 10, entry));
            }
            List<Tag> entityTags = new ArrayList<>();
            for (Entity e : entities) {
                entityTags.add(compound("pos", doubles(e.x, e.y, e.z),
                    "blockPos", ints((int) Math.floor(e.x), (int) Math.floor(e.y), (int) Math.floor(e.z)),
                    "nbt", e.nbt));
            }
            return compound("DataVersion", integer(DATA_VERSION), "size", ints(width, height, depth),
                "palette", list(10, paletteTags), "blocks", list(10, blockTags), "entities", list(10, entityTags));
        }

        boolean accessible(Pos p) {
            return p.x >= 0 && p.x < width && p.z >= 0 && p.z < depth
                && passable(at(p.x, p.y, p.z)) && passable(at(p.x, p.y + 1, p.z))
                && supports(at(p.x, p.y - 1, p.z));
        }

        void validate() {
            connectBarriers();
            require(blocks.size() == width * height * depth, name + ": incomplete clearing volume");
            for (int y = 0; y < FLOOR; y++) for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    require(supports(at(x, y, z)), name + ": foundation gap " + new Pos(x, y, z));
                }
            }
            Map<String, Integer> counts = new TreeMap<>();
            int archers = 0;
            for (Entity e : entities) {
                counts.merge(e.id, 1, Integer::sum);
                Map<String, Tag> nbt = asCompound(e.nbt);
                require(nbt.get("PersistenceRequired").equals(bit(true)), name + ": nonpersistent entity");
                require(nbt.get("Pos").equals(doubles(e.x, e.y, e.z)), name + ": mismatched entity position");
                require(nbt.get("Rotation").equals(floats(0, 0)), name + ": incorrect Rotation types");
                if (e.id.equals(NS + "guard")) {
                    require(nbt.get("HomeBound").equals(bit(true)), name + ": unbound guard");
                    require(nbt.keySet().equals(nbt.containsKey("Archer")
                        ? Set.of("id", "Pos", "Rotation", "PersistenceRequired", "Health", "HomeBound", "Archer")
                        : Set.of("id", "Pos", "Rotation", "PersistenceRequired", "Health", "HomeBound")),
                        name + ": guard must not embed custom home coordinates");
                }
                if (nbt.containsKey("Archer")) {
                    require(nbt.get("Archer").equals(bit(true)), name + ": incorrect Archer type");
                    archers++;
                }
                for (int y = (int) Math.floor(e.y); y < Math.ceil(e.y + 1.95); y++) {
                    for (int z = (int) Math.floor(e.z - .35); z <= Math.floor(e.z + .35); z++) {
                        for (int x = (int) Math.floor(e.x - .35); x <= Math.floor(e.x + .35); x++) {
                            require(at(x, y, z).equals(AIR), name + ": entity intersects " + new Pos(x, y, z));
                        }
                    }
                }
                int x = (int) Math.floor(e.x), z = (int) Math.floor(e.z), y = (int) Math.floor(e.y);
                require(supports(at(x, y - 1, z)), name + ": entity lacks floor");
                if (y == 6) target(x, y, z);
                else require(platforms.contains(new Pos(x, y, z)) && accessible(new Pos(x, y, z)), name + ": invalid platform spawn");
            }
            require(counts.equals(expected), name + ": unexpected populations " + counts);
            require(archers == expectedArchers, name + ": expected " + expectedArchers + " archers");
            int boards = 0, chests = 0, beds = 0;
            List<Placed> lights = new ArrayList<>();
            for (Placed b : blocks.values()) {
                if (emission(b.state) > 0) lights.add(b);
                if (b.state.name.equals(NS + "settlement_board")) {
                    boards++;
                    require(b.nbt != null && asCompound(b.nbt).get("id").equals(string(NS + "settlement_board"))
                        && asCompound(b.nbt).get("Outpost").equals(new Tag((byte) 1, (byte) expectedOutpost)),
                        name + ": board block entity mismatch");
                    require(accessible(new Pos(b.pos.x - 1, b.pos.y, b.pos.z))
                        && accessible(new Pos(b.pos.x + 1, b.pos.y, b.pos.z)), name + ": inaccessible board");
                }
                if (b.state.name.equals("minecraft:chest")) {
                    chests++;
                    require(b.nbt != null && asCompound(b.nbt).get("id").equals(string("minecraft:chest"))
                        && LOOT.contains(asCompound(b.nbt).get("LootTable").value),
                        name + ": missing namespaced chest loot");
                    require(at(b.pos.x, b.pos.y + 1, b.pos.z).equals(AIR), name + ": blocked chest lid " + b.pos);
                }
                if (b.state.name.endsWith("_bed") && "foot".equals(b.state.properties.get("part"))) {
                    beds++;
                    State head = at(b.pos.x, b.pos.y, b.pos.z + 1);
                    require(head.name.equals(b.state.name) && "head".equals(head.properties.get("part"))
                        && "south".equals(head.properties.get("facing")), name + ": broken bed");
                    require(supports(at(b.pos.x, b.pos.y - 1, b.pos.z))
                        && supports(at(b.pos.x, b.pos.y - 1, b.pos.z + 1)), name + ": unsupported bed");
                    boolean roof = false;
                    for (int y = b.pos.y + 2; y < height; y++) {
                        roof |= supports(at(b.pos.x, y, b.pos.z));
                    }
                    require(roof, name + ": bed has no shelter");
                }
            }
            require(boards == expectedBoards && beds == expectedBeds && chests == expectedChests,
                name + ": incorrect boards/beds/chests " + boards + "/" + beds + "/" + chests);
            for (Pos p : doors) {
                require("lower".equals(at(p.x, p.y, p.z).properties.get("half"))
                    && "upper".equals(at(p.x, p.y + 1, p.z).properties.get("half"))
                    && at(p.x, p.y + 2, p.z).equals(AIR), name + ": invalid cottage doorway " + p + " block=" + at(p.x, p.y + 2, p.z).name);
            }
            for (Pos p : ladders) {
                int top = p.y - 1;
                for (int y = p.y; y < height && at(p.x, y, p.z).name.equals("minecraft:ladder"); y++) {
                    require("south".equals(at(p.x, y, p.z).properties.get("facing"))
                        && at(p.x, y, p.z - 1).equals(LOG), name + ": unsupported/misoriented ladder");
                    top = y;
                }
                require(top >= p.y && accessible(new Pos(p.x + 1, top, p.z)), name + ": obstructed ladder exit");
            }
            Set<Pos> reachable = new HashSet<>();
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            Pos start = new Pos(width / 2, 6, depth - 1);
            require(accessible(start), name + ": obstructed approach");
            queue.add(start);
            reachable.add(start);
            while (!queue.isEmpty()) {
                Pos p = queue.remove();
                for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    Pos next = new Pos(p.x + d[0], 6, p.z + d[1]);
                    if (accessible(next) && reachable.add(next)) queue.add(next);
                }
            }
            for (Pos target : targets) {
                require(reachable.contains(target), name + ": unreachable ground target " + target);
            }
            // Conservative distance check; engine occlusion/terrain is intentionally not simulated.
            for (Pos p : paths) if (reachable.contains(p)) {
                boolean lit = false;
                for (Placed lamp : lights) {
                    int distance = Math.abs(p.x - lamp.pos.x) + Math.abs(p.y - lamp.pos.y)
                        + Math.abs(p.z - lamp.pos.z);
                    if (distance < emission(lamp.state)) { lit = true; break; }
                }
                require(lit, name + ": unlit path " + p);
            }
        }

        private void connectBarriers() {
            for (Placed b : new ArrayList<>(blocks.values())) {
                boolean fence = b.state.name.endsWith("_fence");
                boolean wall = b.state.name.endsWith("_wall");
                if (!fence && !wall) continue;
                Map<String, String> properties = new TreeMap<>(b.state.properties);
                int[][] offsets = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
                String[] directions = {"north", "south", "east", "west"};
                for (int i = 0; i < directions.length; i++) {
                    State neighbor = at(b.pos.x + offsets[i][0], b.pos.y, b.pos.z + offsets[i][1]);
                    boolean connected = neighbor.name.equals(b.state.name) || sturdy(neighbor);
                    properties.put(directions[i], fence ? Boolean.toString(connected) : connected ? "low" : "none");
                }
                set(b.pos.x, b.pos.y, b.pos.z,
                    new State(b.state.name, Collections.unmodifiableMap(properties)), b.nbt);
            }
        }

        void report(Path file) {
            System.out.println(file + ": " + width + " x " + height + " x " + depth
                + "; floor y=5, six foundation layers y=0..5.");
            for (Entity e : entities) {
                System.out.println("  " + e.id + " @ (" + e.x + "," + e.y + "," + e.z + ")"
                    + (asCompound(e.nbt).containsKey("Archer") ? " Archer=1" : ""));
            }
            blocks.values().stream().filter(b -> b.nbt != null)
                .sorted(Comparator.comparingInt((Placed b) -> b.pos.z).thenComparingInt(b -> b.pos.x))
                .forEach(b -> System.out.println("  " + b.state.name + " @ " + b.pos + " " + asCompound(b.nbt)));
            for (Pos p : ladders) {
                System.out.println("  South-facing ladder @ x=" + p.x + ", z=" + p.z
                    + ", y=6..12; support z=" + (p.z - 1) + "; deck y=11, clear side exit.");
            }
            blocks.values().stream().filter(b -> b.state.name.endsWith("_bed")
                    && "foot".equals(b.state.properties.get("part")))
                .sorted(Comparator.comparingInt((Placed b) -> b.pos.z).thenComparingInt(b -> b.pos.x))
                .forEach(b -> System.out.println("  " + b.state.name + " foot @ " + b.pos
                    + "; head at z=" + (b.pos.z + 1)));
            System.out.println("  " + expectedBeds + " beds; " + expectedChests + " loot chests; "
                + ladders.size() + " ladder columns; all ground targets connected to south approach.");
        }
    }

    private static boolean supports(State s) {
        return !passable(s) && !s.name.equals("minecraft:water") && !s.name.equals("minecraft:lily_pad")
            && !s.name.endsWith("_fence") && !s.name.endsWith("_wall");
    }

    private static boolean sturdy(State s) {
        return s.name.endsWith("_planks") || s.name.endsWith("_log") || s.name.endsWith("_wool")
            || Set.of("minecraft:cobblestone", "minecraft:mossy_cobblestone",
                "minecraft:stone_bricks").contains(s.name);
    }

    private static boolean passable(State s) {
        return Set.of("minecraft:air", "minecraft:ladder", "minecraft:torch", "minecraft:wall_torch",
            "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots", "minecraft:lily_pad").contains(s.name)
            || s.name.endsWith("_door");
    }

    private static int emission(State s) {
        return switch (s.name) {
            case "minecraft:lantern" -> 15;
            case "minecraft:torch", "minecraft:wall_torch" -> 14;
            case "minecraft:campfire" -> "true".equals(s.properties.get("lit")) ? 15 : 0;
            default -> 0;
        };
    }

    private static Tag bit(boolean value) { return new Tag((byte) 1, (byte) (value ? 1 : 0)); }
    private static Tag integer(int value) { return new Tag((byte) 3, value); }
    private static Tag floating(float value) { return new Tag((byte) 5, value); }
    private static Tag string(String value) { return new Tag((byte) 8, value); }
    private static Tag list(int type, List<Tag> values) {
        for (Tag tag : values) require(tag.type == type, "Heterogeneous NBT list");
        return new Tag((byte) 9, new NbtList((byte) type, List.copyOf(values)));
    }
    private static Tag ints(int... values) {
        List<Tag> tags = new ArrayList<>();
        for (int value : values) tags.add(integer(value));
        return list(3, tags);
    }
    private static Tag doubles(double... values) {
        List<Tag> tags = new ArrayList<>();
        for (double value : values) tags.add(new Tag((byte) 6, value));
        return list(6, tags);
    }
    private static Tag floats(float... values) {
        List<Tag> tags = new ArrayList<>();
        for (float value : values) tags.add(floating(value));
        return list(5, tags);
    }
    private static Map<String, Tag> fields(Object... pairs) {
        require(pairs.length % 2 == 0, "Unpaired NBT field");
        Map<String, Tag> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            require(fields.put((String) pairs[i], (Tag) pairs[i + 1]) == null, "Duplicate NBT field");
        }
        return fields;
    }
    private static Tag compound(Object... pairs) { return new Tag((byte) 10, fields(pairs)); }
    @SuppressWarnings("unchecked")
    private static Map<String, Tag> asCompound(Tag tag) {
        require(tag.type == 10, "Expected compound");
        return (Map<String, Tag>) tag.value;
    }

    private static void writeVerified(Path path, Tag root) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream file = Files.newOutputStream(path);
             DataOutputStream out = new DataOutputStream(new GZIPOutputStream(file))) {
            out.writeByte(10);
            out.writeUTF("");
            writePayload(out, root);
        }
        try (InputStream file = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(new GZIPInputStream(file))) {
            require(in.readUnsignedByte() == 10 && in.readUTF().isEmpty(), "Invalid NBT root " + path);
            require(root.equals(readPayload(in, (byte) 10)), "NBT round-trip mismatch " + path);
            require(in.read() == -1, "Trailing NBT bytes " + path);
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void writePayload(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type) {
            case 1 -> out.writeByte((Byte) tag.value);
            case 3 -> out.writeInt((Integer) tag.value);
            case 5 -> out.writeFloat((Float) tag.value);
            case 6 -> out.writeDouble((Double) tag.value);
            case 8 -> out.writeUTF((String) tag.value);
            case 9 -> {
                NbtList list = (NbtList) tag.value;
                out.writeByte(list.elementType);
                out.writeInt(list.values.size());
                for (Tag element : list.values) writePayload(out, element);
            }
            case 10 -> {
                for (Map.Entry<String, Tag> entry : asCompound(tag).entrySet()) {
                    out.writeByte(entry.getValue().type);
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue());
                }
                out.writeByte(0);
            }
            default -> throw new IOException("Unsupported NBT type " + tag.type);
        }
    }

    private static Tag readPayload(DataInputStream in, byte type) throws IOException {
        return switch (type) {
            case 1 -> new Tag(type, in.readByte());
            case 3 -> new Tag(type, in.readInt());
            case 5 -> new Tag(type, in.readFloat());
            case 6 -> new Tag(type, in.readDouble());
            case 8 -> new Tag(type, in.readUTF());
            case 9 -> {
                byte elementType = in.readByte();
                int count = in.readInt();
                require(count >= 0, "Negative NBT list");
                List<Tag> tags = new ArrayList<>(count);
                for (int i = 0; i < count; i++) tags.add(readPayload(in, elementType));
                yield list(elementType, tags);
            }
            case 10 -> {
                Map<String, Tag> map = new LinkedHashMap<>();
                byte nested;
                while ((nested = in.readByte()) != 0) {
                    String key = in.readUTF();
                    require(!map.containsKey(key), "Duplicate NBT field in serialized output");
                    map.put(key, readPayload(in, nested));
                }
                yield new Tag(type, map);
            }
            default -> throw new IOException("Unsupported NBT type " + type);
        };
    }

    private static BufferedImage skin(int role) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int skin = List.of(0xD8AA82, 0xC4966D, 0xBB8A64, 0xE1B590).get(role);
        int coat = List.of(0x294A75, 0x71503A, 0x292C35, 0x35614B).get(role);
        int trousers = List.of(0x39465A, 0x4D3C32, 0x303039, 0x35453B).get(role);
        int boots = List.of(0x353039, 0x302720, 0x29242A, 0x42352C).get(role);
        int trim = List.of(0xB9C9D5, 0xAA443A, 0xCBA44F, 0xC7A359).get(role);
        cuboid(image, 0, 0, 8, 8, 8, skin);
        cuboid(image, 16, 16, 8, 12, 4, coat);
        cuboid(image, 0, 16, 4, 12, 4, trousers);
        cuboid(image, 16, 48, 4, 12, 4, trousers);
        cuboid(image, 40, 16, 4, 12, 4, coat);
        cuboid(image, 32, 48, 4, 12, 4, coat);

        int hood = role == 0 ? 0x728697 : role == 1 ? 0x594234 : role == 2 ? 0x242730 : 0x284D3B;
        paint(image, 8, 0, 8, 8, hood);
        for (int x : new int[] {0, 8, 16, 24}) {
            paint(image, x, 8, 8, 2, hood);
            if (x != 8) paint(image, x, 10, 8, 6, role == 0 ? 0x667C8F : hood);
        }
        paint(image, 8, 9, 8, 1, trim);
        paint(image, 9, 11, 2, 1, 0xE9E4D9);
        paint(image, 13, 11, 2, 1, 0xE9E4D9);
        paint(image, 10, 11, 1, 1, role == 0 ? 0x354C6D : 0x423526);
        paint(image, 13, 11, 1, 1, role == 0 ? 0x354C6D : 0x423526);
        paint(image, 11, 12, 2, 1, shade(skin, -19));
        if (role == 1 || role == 2) {
            paint(image, 8, 13, 8, 3, role == 1 ? 0x994036 : 0x882B35);
            paint(image, 9, 14, 6, 1, role == 1 ? 0xB95743 : 0xA13A42);
        } else {
            paint(image, 11, 14, 2, 1, 0x765146);
            if (role == 3) paint(image, 10, 13, 4, 1, 0x685044);
        }

        if (role == 0) {
            paint(image, 21, 21, 6, 6, 0x7C93A9);
            paint(image, 22, 22, 4, 4, 0xB4C4D2);
            paint(image, 23, 23, 2, 2, 0x53789D);
        } else if (role == 1) {
            for (int i = 0; i < 8; i++) paint(image, 20 + i, 20 + i, 1, 2, trim);
        } else if (role == 2) {
            paint(image, 21, 20, 6, 9, 0x78303A);
            paint(image, 20, 20, 1, 9, trim);
            paint(image, 27, 20, 1, 9, trim);
            paint(image, 32, 21, 8, 10, 0x692D37);
            paint(image, 35, 22, 2, 8, 0x873840);
        } else {
            paint(image, 22, 20, 4, 7, 0xD8CB9D);
            paint(image, 21, 20, 1, 8, trim);
            paint(image, 26, 20, 1, 8, trim);
            paint(image, 16, 27, 3, 4, 0x876644);
        }
        paint(image, 16, 29, 24, 2, 0x48382F);
        paint(image, 23, 29, 2, 2, role == 1 ? 0xA79881 : 0xD7B66B);
        for (int[] limb : new int[][] {{40, 16}, {32, 48}}) {
            paint(image, limb[0], limb[1] + 4, 16, 2, trim);
            paint(image, limb[0], limb[1] + 12, 16, 2, boots);
            paint(image, limb[0], limb[1] + 14, 16, 2, skin);
            paint(image, limb[0] + 8, limb[1], 4, 4, skin);
        }
        for (int[] leg : new int[][] {{0, 16}, {16, 48}}) {
            paint(image, leg[0], leg[1] + 12, 16, 4, boots);
            paint(image, leg[0] + 4, leg[1] + 12, 4, 1, shade(boots, 20));
            paint(image, leg[0] + 8, leg[1], 4, 4, boots);
        }
        // Base cube faces are opaque; unused areas and all outer-layer UVs remain transparent.
        require((image.getRGB(10, 11) >>> 24) == 255
            && (image.getRGB(40, 8) >>> 24) == 0, "Invalid skin UV opacity");
        return image;
    }

    private static void cuboid(BufferedImage image, int u, int v, int width, int height, int depth, int color) {
        paint(image, u + depth, v, width, depth, shade(color, 9));
        paint(image, u + depth + width, v, width, depth, shade(color, -18));
        paint(image, u, v + depth, depth, height, shade(color, -12));
        paint(image, u + depth, v + depth, width, height, color);
        paint(image, u + depth + width, v + depth, depth, height, shade(color, -7));
        paint(image, u + depth * 2 + width, v + depth, width, height, shade(color, -15));
    }

    private static void paint(BufferedImage image, int x, int y, int width, int height, int color) {
        for (int yy = y; yy < y + height; yy++) for (int xx = x; xx < x + width; xx++) {
            int grain = Math.floorMod(xx * 17 + yy * 31 + color, 5) - 2;
            image.setRGB(xx, yy, 0xFF000000 | shade(color, grain));
        }
    }

    private static int shade(int color, int delta) {
        int r = Math.max(0, Math.min(255, ((color >>> 16) & 255) + delta));
        int g = Math.max(0, Math.min(255, ((color >>> 8) & 255) + delta));
        int b = Math.max(0, Math.min(255, (color & 255) + delta));
        return r << 16 | g << 8 | b;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}




