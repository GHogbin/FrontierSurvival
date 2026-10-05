package dev.frontiersurvival.worldgen;

import com.mojang.serialization.Codec;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Regional building materials. Templates are authored in the {@link #OAK} palette using a small set of
 * canonical blocks; every other palette swaps those blocks (and cloth colours) while keeping block properties.
 */
public enum Palette implements StringRepresentable {
    OAK("oak", VillagerType.PLAINS, DyeColor.GREEN, "oak_log", "cobblestone", "gravel", Map.of(), Map.of()),
    SPRUCE("spruce", VillagerType.TAIGA, DyeColor.RED, "spruce_log", "cobblestone", "dirt_path", Map.ofEntries(
            Map.entry("oak_planks", "spruce_planks"),
            Map.entry("spruce_planks", "dark_oak_planks"),
            Map.entry("stripped_spruce_log", "spruce_log"),
            Map.entry("oak_log", "spruce_log"),
            Map.entry("oak_fence", "spruce_fence"),
            Map.entry("spruce_stairs", "dark_oak_stairs"),
            Map.entry("spruce_slab", "dark_oak_slab"),
            Map.entry("oak_door", "spruce_door"),
            Map.entry("oak_trapdoor", "spruce_trapdoor"),
            Map.entry("stone_bricks", "mossy_stone_bricks"),
            Map.entry("cobblestone_wall", "mossy_cobblestone_wall")),
            colours("blue", "green", "cyan", "brown", "yellow", "red", "green", "lime", "light_gray", "white",
                    "orange", "red")),
    BIRCH("birch", VillagerType.PLAINS, DyeColor.YELLOW, "birch_log", "cobblestone", "gravel", Map.ofEntries(
            Map.entry("oak_planks", "birch_planks"),
            Map.entry("stripped_spruce_log", "birch_log"),
            Map.entry("oak_log", "birch_log"),
            Map.entry("oak_fence", "birch_fence"),
            Map.entry("spruce_stairs", "dark_oak_stairs"),
            Map.entry("spruce_slab", "dark_oak_slab"),
            Map.entry("oak_door", "birch_door"),
            Map.entry("oak_trapdoor", "birch_trapdoor"),
            Map.entry("mossy_cobblestone", "cobblestone")),
            colours("blue", "light_blue", "cyan", "white", "green", "lime", "brown", "yellow", "gray", "light_gray")),
    DARK_OAK("dark_oak", VillagerType.TAIGA, DyeColor.PURPLE, "dark_oak_log", "mossy_cobblestone", "dirt_path",
            Map.ofEntries(
                    Map.entry("oak_planks", "dark_oak_planks"),
                    Map.entry("stripped_spruce_log", "dark_oak_log"),
                    Map.entry("oak_log", "dark_oak_log"),
                    Map.entry("oak_fence", "dark_oak_fence"),
                    Map.entry("spruce_stairs", "mossy_cobblestone_stairs"),
                    Map.entry("spruce_slab", "mossy_cobblestone_slab"),
                    Map.entry("oak_door", "dark_oak_door"),
                    Map.entry("oak_trapdoor", "dark_oak_trapdoor"),
                    Map.entry("cobblestone", "mossy_cobblestone"),
                    Map.entry("stone_bricks", "mossy_stone_bricks"),
                    Map.entry("cobblestone_wall", "mossy_cobblestone_wall")),
            colours("blue", "purple", "cyan", "gray", "yellow", "red", "brown", "black", "light_gray", "gray",
                    "orange", "red")),
    ACACIA("acacia", VillagerType.SAVANNA, DyeColor.ORANGE, "acacia_log", "cobblestone", "dirt_path", Map.ofEntries(
            Map.entry("oak_planks", "acacia_planks"),
            Map.entry("spruce_planks", "dark_oak_planks"),
            Map.entry("stripped_spruce_log", "acacia_log"),
            Map.entry("oak_log", "acacia_log"),
            Map.entry("oak_fence", "acacia_fence"),
            Map.entry("spruce_stairs", "dark_oak_stairs"),
            Map.entry("spruce_slab", "dark_oak_slab"),
            Map.entry("oak_door", "acacia_door"),
            Map.entry("oak_trapdoor", "acacia_trapdoor"),
            Map.entry("mossy_cobblestone", "cobblestone"),
            Map.entry("stone_bricks", "smooth_sandstone")),
            colours("blue", "orange", "cyan", "yellow", "yellow", "red", "green", "lime", "light_gray", "yellow",
                    "gray", "brown", "brown", "orange")),
    SNOW("snow", VillagerType.SNOW, DyeColor.LIGHT_BLUE, "spruce_log", "stone_bricks", "gravel", Map.ofEntries(
            Map.entry("oak_planks", "spruce_planks"),
            Map.entry("spruce_planks", "dark_oak_planks"),
            Map.entry("stripped_spruce_log", "spruce_log"),
            Map.entry("oak_log", "spruce_log"),
            Map.entry("oak_fence", "spruce_fence"),
            Map.entry("spruce_stairs", "dark_oak_stairs"),
            Map.entry("spruce_slab", "dark_oak_slab"),
            Map.entry("oak_door", "spruce_door"),
            Map.entry("oak_trapdoor", "spruce_trapdoor"),
            Map.entry("cobblestone", "stone_bricks"),
            Map.entry("mossy_cobblestone", "cobblestone"),
            Map.entry("cobblestone_wall", "stone_brick_wall")),
            colours("blue", "light_blue", "cyan", "white", "yellow", "light_gray", "green", "cyan", "brown", "white",
                    "gray", "light_gray", "orange", "red")),
    DESERT("desert", VillagerType.DESERT, DyeColor.YELLOW, "cut_sandstone", "sandstone", "smooth_sandstone",
            Map.ofEntries(
                    Map.entry("oak_planks", "smooth_sandstone"),
                    Map.entry("spruce_planks", "cut_sandstone"),
                    Map.entry("stripped_spruce_log", "chiseled_sandstone"),
                    Map.entry("oak_log", "cut_sandstone"),
                    Map.entry("oak_fence", "birch_fence"),
                    Map.entry("spruce_stairs", "smooth_sandstone_stairs"),
                    Map.entry("spruce_slab", "smooth_sandstone_slab"),
                    Map.entry("oak_door", "birch_door"),
                    Map.entry("oak_trapdoor", "birch_trapdoor"),
                    Map.entry("cobblestone", "sandstone"),
                    Map.entry("mossy_cobblestone", "smooth_sandstone"),
                    Map.entry("stone_bricks", "cut_sandstone"),
                    Map.entry("cobblestone_wall", "sandstone_wall")),
            colours("blue", "orange", "cyan", "yellow", "yellow", "red", "green", "lime", "brown", "yellow",
                    "light_gray", "white", "gray", "orange"));

    public static final Codec<Palette> CODEC = StringRepresentable.fromEnum(Palette::values);
    private static final String[] DYED = {"wool", "bed", "carpet", "banner", "wall_banner", "terracotta",
            "stained_glass", "stained_glass_pane", "concrete", "glazed_terracotta"};

    private final String name;
    private final VillagerType villagerType;
    private final DyeColor accent;
    private final String palisade;
    private final String foundation;
    private final String path;
    private final Map<String, String> names;
    private final Map<DyeColor, DyeColor> colours;
    private volatile Map<Block, Block> blocks;

    Palette(String name, VillagerType villagerType, DyeColor accent, String palisade, String foundation, String path,
            Map<String, String> names, Map<DyeColor, DyeColor> colours) {
        this.name = name;
        this.villagerType = villagerType;
        this.accent = accent;
        this.palisade = palisade;
        this.foundation = foundation;
        this.path = path;
        this.names = names;
        this.colours = colours;
    }

    private static Map<DyeColor, DyeColor> colours(String... pairs) {
        Map<DyeColor, DyeColor> map = new EnumMap<>(DyeColor.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(DyeColor.byName(pairs[i], null), DyeColor.byName(pairs[i + 1], null));
        }
        if (map.containsKey(null) || map.containsValue(null)) throw new IllegalStateException("Unknown dye colour");
        return Map.copyOf(map);
    }

    @Override
    public String getSerializedName() { return name; }

    public static Palette byName(String name) {
        for (Palette palette : values()) if (palette.name.equals(name)) return palette;
        return OAK;
    }

    public VillagerType villagerType() { return villagerType; }
    public DyeColor accent() { return accent; }
    public DyeColor colour(DyeColor source) { return colours.getOrDefault(source, source); }
    /** Upright palisade posts for ground walls. */
    public BlockState palisade() { return block(palisade).defaultBlockState(); }
    /** Masonry for wall bases, foundations and tower plinths. */
    public BlockState foundation() { return block(foundation).defaultBlockState(); }
    /** Surface of graded settlement paths and gateways; never dirt-tagged, so nothing takes root on it. */
    public BlockState path() { return block(path).defaultBlockState(); }
    public BlockState planks() { return apply(Blocks.OAK_PLANKS.defaultBlockState()); }
    public BlockState fence() { return apply(Blocks.OAK_FENCE.defaultBlockState()); }
    public BlockState slab() { return apply(Blocks.SPRUCE_SLAB.defaultBlockState()); }
    public BlockState stairs() { return apply(Blocks.SPRUCE_STAIRS.defaultBlockState()); }
    public BlockState door() { return apply(Blocks.OAK_DOOR.defaultBlockState()); }
    public BlockState frame() { return apply(Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()); }

    /** Swaps a canonical (oak palette) block for this region's equivalent, preserving matching properties. */
    public BlockState apply(BlockState state) {
        Block target = blocks().get(state.getBlock());
        return target == null ? state : copyProperties(state, target.defaultBlockState());
    }

    private Map<Block, Block> blocks() {
        Map<Block, Block> map = blocks;
        if (map == null) {
            Map<Block, Block> built = new HashMap<>();
            names.forEach((from, to) -> built.put(block(from), block(to)));
            for (DyeColor from : DyeColor.values()) {
                DyeColor to = colour(from);
                if (to == from) continue;
                for (String suffix : DYED) {
                    Optional<Block> source = optional(from.getName() + "_" + suffix);
                    Optional<Block> replacement = optional(to.getName() + "_" + suffix);
                    if (source.isPresent() && replacement.isPresent()) built.put(source.get(), replacement.get());
                }
            }
            map = Map.copyOf(built);
            blocks = map;
        }
        return map;
    }

    private static Optional<Block> optional(String name) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.fromNamespaceAndPath("minecraft", name));
    }

    private static Block block(String name) {
        return optional(name).orElseThrow(() -> new IllegalStateException("Unknown palette block " + name));
    }

    public static BlockState copyProperties(BlockState from, BlockState to) {
        for (Property<?> property : from.getProperties()) {
            Property<?> target = to.getBlock().getStateDefinition().getProperty(property.getName());
            if (target != null) to = copy(from, property, to, target);
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, Property<?> source, BlockState to,
                                                             Property<T> target) {
        Optional<T> value = target.getValue(valueName(from, source));
        return value.isPresent() ? to.setValue(target, value.get()) : to;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Frontier sites take their materials from the biome at the site centre. */
    public static Palette forBiome(Holder<Biome> biome) {
        if (biome.is(BiomeTags.SPAWNS_SNOW_FOXES) || biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA)) {
            return SNOW;
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) return ACACIA;
        if (biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS)) return DESERT;
        if (biome.is(Biomes.DARK_FOREST)) return DARK_OAK;
        if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) return BIRCH;
        if (biome.is(BiomeTags.IS_TAIGA)) return SPRUCE;
        return OAK;
    }

    /** Vanilla villages keep their own regional style; unknown (modded) villages fall back to the biome. */
    public static Palette forVillage(ResourceLocation structure, Holder<Biome> biome) {
        String path = structure.getPath().toLowerCase(Locale.ROOT);
        if ("minecraft".equals(structure.getNamespace())) {
            switch (path) {
                case "village_plains": return OAK;
                case "village_desert": return DESERT;
                case "village_savanna": return ACACIA;
                case "village_snowy": return SNOW;
                case "village_taiga": return SPRUCE;
                default: break;
            }
        }
        return forBiome(biome);
    }
}
