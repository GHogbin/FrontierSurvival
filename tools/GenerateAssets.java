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

        List<Voxels> structures = List.of(hamlet(), watchtower(), camp());
        for (Voxels voxels : structures) {
            voxels.validate();
            Path file = data.resolve("structures").resolve(voxels.name + ".nbt");
            writeVerified(file, voxels.template());
            voxels.report(file);
        }
        Voxels arena = new Voxels("test/arena", 48, 24, 48);
        arena.fill(0, 0, 0, 47, 0, 47, state("grass_block", "snowy", "false"));
        require(arena.entities.isEmpty(), "Arena must be empty");
        for (Placed block : arena.blocks.values()) {
            require(block.pos.y == 0 ? block.state.name.equals("minecraft:grass_block")
                : block.state.equals(AIR), "Unexpected arena obstruction");
        }
        writeVerified(root.resolve("src").resolve("gametest").resolve("resources").resolve("data")
            .resolve("frontiersurvival").resolve("structures").resolve("test").resolve("arena.nbt"),
            arena.template());
        System.out.println("Arena test/arena: 48 x 24 x 48; grass y=0, air y=1..23, zero entities.");

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

    private static Voxels settlement(String name, int width, int height) {
        Voxels v = new Voxels(name, width, height, width);
        v.fill(0, 0, 0, width - 1, 3, width - 1, COBBLE);
        v.fill(0, 4, 0, width - 1, 4, width - 1, state("dirt"));
        v.fill(0, FLOOR, 0, width - 1, FLOOR, width - 1, state("grass_block", "snowy", "false"));
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
        v.target(x + 1, 6, z);
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
        v.target(x + 1, 6, z);
    }

    private static void chest(Voxels v, int x, int y, int z, String table, String facing) {
        v.set(x, y, z, state("chest", "facing", facing, "type", "single", "waterlogged", "false"),
            compound("id", string("minecraft:chest"), "LootTable", string(NS + "chests/" + table)));
        v.target(x - 1, y, z);
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
        int expectedBeds, expectedChests, expectedBoards, expectedOutpost;

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
                else require(y == 12 && accessible(new Pos(x, y, z)), name + ": invalid platform spawn");
            }
            require(counts.equals(expected), name + ": unexpected populations " + counts);
            require(archers == 1, name + ": expected exactly one archer");
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
                    require(at(b.pos.x, b.pos.y + 1, b.pos.z).equals(AIR), name + ": blocked chest lid");
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
                    && at(p.x, p.y + 2, p.z).equals(AIR), name + ": invalid cottage doorway");
            }
            for (Pos p : ladders) {
                for (int y = 6; y <= 12; y++) {
                    require(at(p.x, y, p.z).name.equals("minecraft:ladder")
                        && "south".equals(at(p.x, y, p.z).properties.get("facing"))
                        && at(p.x, y, p.z - 1).equals(LOG), name + ": unsupported/misoriented ladder");
                }
                require(accessible(new Pos(p.x + 1, 12, p.z)), name + ": obstructed ladder exit");
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
            "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:lily_pad").contains(s.name)
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
