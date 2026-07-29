package network.warzone.scaffold;

import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class StandaloneWorldExporter {

    private static final String LEVEL_DAT = "level.dat";
    private static final String WORLD_GEN_SETTINGS_DAT = "world_gen_settings.dat";
    private static final String VOID_BIOME = "minecraft:the_void";
    private static final int UNKNOWN_DATA_VERSION = 0;

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    private StandaloneWorldExporter() {
    }

    @SuppressWarnings("removal")
    public static Options optionsFor(ScaffoldWorld wrapper, World world) {
        Location spawn = world.getSpawnLocation();
        Map<String, String> gameRules = new LinkedHashMap<>();
        // level.dat stores gamerules by their serialized string names.
        for (String gameRule : world.getGameRules()) {
            gameRules.put(gameRule, world.getGameRuleValue(gameRule));
        }

        return new Options(
                wrapper.getName(),
                world.getSeed(),
                spawn.getBlockX(),
                spawn.getBlockY(),
                spawn.getBlockZ(),
                world.getFullTime(),
                world.getTime(),
                world.hasStorm(),
                world.getWeatherDuration(),
                world.isThundering(),
                world.getThunderDuration(),
                world.getDifficulty().getValue(),
                gameRules
        );
    }

    public static void export(ScaffoldWorld wrapper, Options options, File destination) throws IOException {
        export(
                wrapper,
                options,
                Bukkit.getServer().getLevelDirectory().resolve(LEVEL_DAT).toFile(),
                destination
        );
    }

    public static void export(ScaffoldWorld wrapper, Options options, File sourceLevelDat, File destination) throws IOException {
        FileUtils.forceMkdir(destination);
        File overworldFolder = new File(destination, "dimensions/minecraft/overworld");
        FileUtils.copyDirectory(wrapper.getFolder(), overworldFolder, file -> {
            String name = file.getName();
            return !name.equals("session.lock") && !name.equals("uid.dat") && !name.equals("scaffold.yml");
        });

        if (!sourceLevelDat.isFile()) {
            throw new IOException("unable to find server level.dat at " + sourceLevelDat.getAbsolutePath());
        }

        NbtFile levelDat = NbtFile.read(sourceLevelDat);
        patchLevelDat(levelDat.root(), options);
        levelDat.write(new File(destination, LEVEL_DAT));

        NbtFile worldGenSettings = new NbtFile("", savedDataRoot(dataVersion(levelDat.root())));
        patchWorldGenSettings(savedData(worldGenSettings.root()), options);
        File worldGenSettingsDat = new File(destination, "data/minecraft/" + WORLD_GEN_SETTINGS_DAT);
        worldGenSettings.write(worldGenSettingsDat);
        validateWorldGenSettings(worldGenSettingsDat);
    }

    private static void patchLevelDat(Map<String, Object> root, Options options) {
        Map<String, Object> data = compound(root, "Data");
        data.put("LevelName", options.levelName());
        data.put("RandomSeed", options.seed());
        data.put("SpawnX", options.spawnX());
        data.put("SpawnY", options.spawnY());
        data.put("SpawnZ", options.spawnZ());
        data.put("Time", options.fullTime());
        data.put("DayTime", options.dayTime());
        data.put("raining", bool(options.raining()));
        data.put("rainTime", options.rainTime());
        data.put("thundering", bool(options.thundering()));
        data.put("thunderTime", options.thunderTime());
        data.put("Difficulty", (byte) options.difficulty());

        Map<String, Object> gameRules = compound(data, "GameRules");
        for (Map.Entry<String, String> entry : options.gameRules().entrySet()) {
            gameRules.put(entry.getKey(), entry.getValue());
        }
        patchDataPacks(data);

        Map<String, Object> worldGenSettings = compound(data, "WorldGenSettings");
        worldGenSettings.put("seed", options.seed());
        worldGenSettings.put("generate_structures", (byte) 0);
        worldGenSettings.put("bonus_chest", (byte) 0);

        Map<String, Object> dimensions = compound(worldGenSettings, "dimensions");
        Map<String, Object> overworld = compound(dimensions, "minecraft:overworld");
        overworld.put("type", "minecraft:overworld");
        overworld.put("generator", voidFlatGenerator());

        data.put("generatorName", "flat");
        data.put("generatorVersion", 0);
        data.put("generatorOptions", "");
    }

    private static void patchWorldGenSettings(Map<String, Object> worldGenSettings, Options options) {
        worldGenSettings.put("seed", options.seed());
        worldGenSettings.put("generate_structures", (byte) 0);
        worldGenSettings.put("bonus_chest", (byte) 0);

        Map<String, Object> dimensions = compound(worldGenSettings, "dimensions");
        Map<String, Object> overworld = compound(dimensions, "minecraft:overworld");
        overworld.put("type", "minecraft:overworld");
        overworld.put("generator", voidFlatGenerator());
    }

    private static void validateWorldGenSettings(File file) throws IOException {
        Map<String, Object> root = NbtFile.read(file).root();
        Object data = root.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            throw new IOException("standalone world_gen_settings.dat is missing SavedData wrapper");
        }

        Map<?, ?> worldGenSettings = dataMap;
        if (!(worldGenSettings.get("seed") instanceof Long)) {
            throw new IOException("standalone world_gen_settings.dat is missing seed");
        }

        Object dimensions = worldGenSettings.get("dimensions");
        if (!(dimensions instanceof Map<?, ?> dimensionsMap)) {
            throw new IOException("standalone world_gen_settings.dat is missing dimensions");
        }

        if (!(dimensionsMap.get("minecraft:overworld") instanceof Map<?, ?>)) {
            throw new IOException("standalone world_gen_settings.dat is missing minecraft:overworld");
        }
    }

    private static Map<String, Object> savedDataRoot(int dataVersion) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("data", new LinkedHashMap<String, Object>());
        if (dataVersion != UNKNOWN_DATA_VERSION) {
            root.put("DataVersion", dataVersion);
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> savedData(Map<String, Object> root) {
        return (Map<String, Object>) root.get("data");
    }

    private static int dataVersion(Map<String, Object> root) {
        Object rootDataVersion = root.get("DataVersion");
        if (rootDataVersion instanceof Integer value) {
            return value;
        }

        Object data = root.get("Data");
        if (data instanceof Map<?, ?> dataMap) {
            Object dataVersion = dataMap.get("DataVersion");
            if (dataVersion instanceof Integer value) {
                return value;
            }
        }

        return UNKNOWN_DATA_VERSION;
    }

    private static void patchDataPacks(Map<String, Object> data) {
        Map<String, Object> dataPacks = compound(data, "DataPacks");
        List<Object> enabled = stringListValues(dataPacks.get("Enabled"));
        List<Object> disabled = stringListValues(dataPacks.get("Disabled"));

        enabled.removeIf(value -> value.equals("paper") || value.equals("file/bukkit"));
        disabled.removeIf(value -> value.equals("paper") || value.equals("file/bukkit"));
        if (!enabled.contains("vanilla")) {
            enabled.add(0, "vanilla");
        }

        dataPacks.put("Enabled", new ListTag(TAG_STRING, enabled));
        dataPacks.put("Disabled", new ListTag(TAG_STRING, disabled));
    }

    private static List<Object> stringListValues(Object value) {
        if (value instanceof ListTag listTag && listTag.elementType() == TAG_STRING) {
            return new ArrayList<>(listTag.values());
        }
        return new ArrayList<>();
    }

    private static Map<String, Object> voidFlatGenerator() {
        Map<String, Object> airLayer = new LinkedHashMap<>();
        airLayer.put("block", "minecraft:air");
        airLayer.put("height", 1);

        ListTag layers = new ListTag(TAG_COMPOUND, new ArrayList<>());
        layers.values().add(airLayer);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("biome", VOID_BIOME);
        settings.put("layers", layers);
        settings.put("structure_overrides", ListTag.empty(TAG_STRING));
        settings.put("features", (byte) 1);
        settings.put("lakes", (byte) 0);

        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("type", "minecraft:flat");
        generator.put("settings", settings);
        return generator;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private static byte bool(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    public record Options(
            String levelName,
            long seed,
            int spawnX,
            int spawnY,
            int spawnZ,
            long fullTime,
            long dayTime,
            boolean raining,
            int rainTime,
            boolean thundering,
            int thunderTime,
            int difficulty,
            Map<String, String> gameRules
    ) {
    }

    private record NbtFile(String name, Map<String, Object> root) {

        static NbtFile read(File file) throws IOException {
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
                int type = in.readUnsignedByte();
                if (type != TAG_COMPOUND) {
                    throw new IOException("level.dat root tag is not a compound");
                }

                String name = readString(in);
                return new NbtFile(name, readCompoundPayload(in));
            }
        }

        void write(File file) throws IOException {
            Path parent = file.toPath().getParent();
            if (parent != null) {
                FileUtils.forceMkdir(parent.toFile());
            }

            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file))))) {
                out.writeByte(TAG_COMPOUND);
                writeString(out, name);
                writeCompoundPayload(out, root);
            }
        }

    }

    private record ListTag(int elementType, List<Object> values) {
        static ListTag empty(int elementType) {
            return new ListTag(elementType, new ArrayList<>());
        }
    }

    private static Map<String, Object> readCompoundPayload(DataInput in) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return value;
            }

            String name = readString(in);
            value.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInput in, int type) throws IOException {
        return switch (type) {
            case TAG_BYTE -> in.readByte();
            case TAG_SHORT -> in.readShort();
            case TAG_INT -> in.readInt();
            case TAG_LONG -> in.readLong();
            case TAG_FLOAT -> in.readFloat();
            case TAG_DOUBLE -> in.readDouble();
            case TAG_BYTE_ARRAY -> readByteArray(in);
            case TAG_STRING -> readString(in);
            case TAG_LIST -> readList(in);
            case TAG_COMPOUND -> readCompoundPayload(in);
            case TAG_INT_ARRAY -> readIntArray(in);
            case TAG_LONG_ARRAY -> readLongArray(in);
            default -> throw new IOException("unsupported NBT tag type " + type);
        };
    }

    private static byte[] readByteArray(DataInput in) throws IOException {
        byte[] value = new byte[in.readInt()];
        in.readFully(value);
        return value;
    }

    private static ListTag readList(DataInput in) throws IOException {
        int elementType = in.readUnsignedByte();
        int size = in.readInt();
        List<Object> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(readPayload(in, elementType));
        }
        return new ListTag(elementType, values);
    }

    private static int[] readIntArray(DataInput in) throws IOException {
        int[] value = new int[in.readInt()];
        for (int i = 0; i < value.length; i++) {
            value[i] = in.readInt();
        }
        return value;
    }

    private static long[] readLongArray(DataInput in) throws IOException {
        long[] value = new long[in.readInt()];
        for (int i = 0; i < value.length; i++) {
            value[i] = in.readLong();
        }
        return value;
    }

    private static void writeCompoundPayload(DataOutput out, Map<String, Object> value) throws IOException {
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object child = entry.getValue();
            int type = tagType(child);
            out.writeByte(type);
            writeString(out, entry.getKey());
            writePayload(out, type, child);
        }
        out.writeByte(TAG_END);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutput out, int type, Object value) throws IOException {
        switch (type) {
            case TAG_BYTE -> out.writeByte((Byte) value);
            case TAG_SHORT -> out.writeShort((Short) value);
            case TAG_INT -> out.writeInt((Integer) value);
            case TAG_LONG -> out.writeLong((Long) value);
            case TAG_FLOAT -> out.writeFloat((Float) value);
            case TAG_DOUBLE -> out.writeDouble((Double) value);
            case TAG_BYTE_ARRAY -> writeByteArray(out, (byte[]) value);
            case TAG_STRING -> writeString(out, (String) value);
            case TAG_LIST -> writeList(out, (ListTag) value);
            case TAG_COMPOUND -> writeCompoundPayload(out, (Map<String, Object>) value);
            case TAG_INT_ARRAY -> writeIntArray(out, (int[]) value);
            case TAG_LONG_ARRAY -> writeLongArray(out, (long[]) value);
            default -> throw new IOException("unsupported NBT tag type " + type);
        }
    }

    private static void writeByteArray(DataOutput out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static void writeList(DataOutput out, ListTag value) throws IOException {
        out.writeByte(value.elementType());
        out.writeInt(value.values().size());
        for (Object item : value.values()) {
            writePayload(out, value.elementType(), item);
        }
    }

    private static void writeIntArray(DataOutput out, int[] value) throws IOException {
        out.writeInt(value.length);
        for (int item : value) {
            out.writeInt(item);
        }
    }

    private static void writeLongArray(DataOutput out, long[] value) throws IOException {
        out.writeInt(value.length);
        for (long item : value) {
            out.writeLong(item);
        }
    }

    private static int tagType(Object value) throws IOException {
        if (value instanceof Byte) return TAG_BYTE;
        if (value instanceof Short) return TAG_SHORT;
        if (value instanceof Integer) return TAG_INT;
        if (value instanceof Long) return TAG_LONG;
        if (value instanceof Float) return TAG_FLOAT;
        if (value instanceof Double) return TAG_DOUBLE;
        if (value instanceof byte[]) return TAG_BYTE_ARRAY;
        if (value instanceof String) return TAG_STRING;
        if (value instanceof ListTag) return TAG_LIST;
        if (value instanceof Map<?, ?>) return TAG_COMPOUND;
        if (value instanceof int[]) return TAG_INT_ARRAY;
        if (value instanceof long[]) return TAG_LONG_ARRAY;
        throw new IOException("unsupported NBT value type " + value.getClass().getName());
    }

    private static String readString(DataInput in) throws IOException {
        byte[] bytes = new byte[in.readUnsignedShort()];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutput out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IOException("NBT string is too long");
        }

        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
