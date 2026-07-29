package network.warzone.scaffold;

import com.google.common.base.Preconditions;
import network.warzone.scaffold.utils.config.Config;
import network.warzone.scaffold.utils.config.ConfigFile;
import org.apache.commons.io.FileUtils;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ScaffoldWorld {

    private static final String WORLD_PREFIX = "scaffold_";
    private static final Map<String, Integer> AUTO_SAVE_TASKS = new HashMap<>();

    private final String name;
    private final String worldName;
    private final NamespacedKey worldKey;
    private final File folder;
    private final File legacyFolder;
    private final File configFile;
    private final File legacyConfigFile;
    private int taskID = -1;

    public ScaffoldWorld(String name) {
        this.name = name.trim();
        Preconditions.checkArgument(!this.name.isEmpty(), "World name cannot be empty.");

        this.worldName = toWorldName(this.name);
        this.worldKey = NamespacedKey.minecraft(this.worldName);
        this.folder = worldFolder(this.worldKey);
        this.legacyFolder = new File(Bukkit.getWorldContainer(), this.worldName);
        this.configFile = new File(this.folder, "scaffold.yml");
        this.legacyConfigFile = new File(this.legacyFolder, "scaffold.yml");
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public File getFolder() {
        return folder;
    }

    public File getConfigFile() {
        return configFile;
    }

    public Optional<World> getWorld() {
        return Optional.ofNullable(Bukkit.getWorld(this.worldKey));
    }

    public Optional<Config> getConfig() {
        try {
            if (this.configFile.exists())
                return Optional.of(new ConfigFile(this.configFile));
            if (this.legacyConfigFile.exists()) {
                Config config = new ConfigFile(this.legacyConfigFile);
                if (isCreated()) {
                    config.save(this.configFile);
                }
                return Optional.of(config);
            }
            return Optional.empty();
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    public boolean isOpen() {
        return getWorld().isPresent();
    }

    public boolean isCreated() {
        return isOpen() || this.configFile.exists() || this.legacyConfigFile.exists() && hasWorldData();
    }

    public boolean hasWorldData() {
        return hasWorldData(this.folder);
    }

    public World create(WorldType type, Environment env, long seed) {
        Preconditions.checkArgument(!isOpen(), "World already loaded.");
        Preconditions.checkArgument(!isCreated(), "World already created.");

        Config config = createConfig(type, env, seed);

        WorldCreator creator = worldCreator(Optional.of(config));
        World world = creator.createWorld();
        Preconditions.checkState(world != null, "World failed to create.");

        world.setSpawnLocation(0, 3, 0);
        world.setAutoSave(true);

        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.COMMAND_BLOCK_OUTPUT, false);
        world.setGameRule(GameRules.ELYTRA_MOVEMENT_CHECK, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.LOG_ADMIN_COMMANDS, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);


        Vector min = new Vector(-1, 0, -1);
        Vector max = new Vector(1, 0, 1);

        for (int x = min.getBlockX(); x <= max.getBlockX(); x++)
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++)
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++)
                    world.getBlockAt(x, y, z).setType(Material.GLASS);

        world.save(true);
        config.save(this.configFile);
        startAutoSaveTask();

        return world;
    }

    public void saveConfig(WorldType type, Environment env, long seed) {
        createConfig(type, env, seed).save(this.configFile);
    }

    public World load() {
        Preconditions.checkArgument(!isOpen(), "World already loaded.");
        Preconditions.checkArgument(isCreated(), "World is not created.");
        deleteUid();
        Optional<Config> config = getConfig();
        WorldCreator creator = worldCreator(config);
        World world = creator.createWorld();
        Preconditions.checkState(world != null, "World failed to load.");

        world.setAutoSave(true);
        startAutoSaveTask();
        return world;
    }

    private void deleteUid() {
        try {
            FileUtils.forceDelete(new File(this.folder, "uid.dat"));
        } catch (Exception e) {
            // meh...
            // ??? tf u mean "meh" luuke???
        }
    }

    public boolean unload() {
        Preconditions.checkArgument(isOpen(), "World is not loaded.");
        Preconditions.checkArgument(isCreated(), "World is not created.");

        World world = getWorld().get();
        world.save(true);
        boolean unloaded = Bukkit.unloadWorld(world, true);
        if (unloaded) {
            cancelAutoSaveTask();
            Scaffold.get().unlock(this);
        }
        return unloaded;
    }

    public void copyTo(File destination) throws IOException {
        FileUtils.copyDirectory(this.folder, destination, file -> !file.getName().equals("session.lock"));
    }

    public void delete() throws IOException {
        if (isOpen() && !unload()) {
            throw new IOException("unable to unload world before deletion");
        }

        if (this.folder.exists()) {
            FileUtils.deleteDirectory(this.folder);
        }

        if (this.legacyFolder.exists()) {
            FileUtils.deleteDirectory(this.legacyFolder);
        }
    }

    private WorldCreator worldCreator(Optional<Config> config) {
        WorldCreator creator = WorldCreator.ofKey(this.worldKey);
        creator.generator(new NullChunkGenerator());
        if (config.isPresent()) {
            WorldType type = WorldType.valueOf(config.get().getAsString("type").toUpperCase());
            Environment environment =  Environment.valueOf(config.get().getAsString("environment").toUpperCase());
            long seed = config.get().getLong("seed");

            creator.type(type);
            creator.environment(environment);
            creator.seed(seed);
        }
        return creator;
    }

    public static Optional<ScaffoldWorld> ofWorld(World world) {
        if (!world.getName().startsWith(WORLD_PREFIX))
            return Optional.empty();

        return Optional.of(withConfiguredName(new ScaffoldWorld(nameFromWorldName(world.getName()))));
    }

    public static ScaffoldWorld ofSearch(String query) {
        ScaffoldWorld direct = new ScaffoldWorld(query);
        if (direct.isCreated() || direct.configFile.exists() || direct.legacyConfigFile.exists()) {
            return direct;
        }

        for (ScaffoldWorld world : all()) {
            if (world.getName().equalsIgnoreCase(query) || world.getWorldName().equalsIgnoreCase(query)) {
                return world;
            }
        }
        return direct;
    }

    public static List<ScaffoldWorld> all() {
        Map<String, ScaffoldWorld> worlds = new LinkedHashMap<>();

        File[] files = namespaceFolder(NamespacedKey.MINECRAFT).listFiles();
        if (files != null) {
            for (File file : files) {
                ofFolder(file).ifPresent(world -> worlds.put(world.getWorldName(), world));
            }
        }

        for (World world : Bukkit.getWorlds()) {
            ofWorld(world).ifPresent(wrapper -> worlds.put(wrapper.getWorldName(), wrapper));
        }

        return new ArrayList<>(worlds.values());
    }

    private static Optional<ScaffoldWorld> ofFolder(File folder) {
        if (!folder.isDirectory() || !folder.getName().startsWith(WORLD_PREFIX)) {
            return Optional.empty();
        }

        ScaffoldWorld world = new ScaffoldWorld(nameFromWorldName(folder.getName()));
        world = withConfiguredName(world);

        return world.isCreated() ? Optional.of(world) : Optional.empty();
    }

    private static ScaffoldWorld withConfiguredName(ScaffoldWorld world) {
        Optional<Config> config = world.getConfig();
        String configuredName = config.map(value -> value.getAsString("name")).orElse(null);
        if (configuredName != null && !configuredName.isBlank() && toWorldName(configuredName).equals(world.getWorldName())) {
            return new ScaffoldWorld(configuredName);
        }
        return world;
    }

    private Config createConfig(WorldType type, Environment env, long seed) {
        Config config = new Config();
        config.set("name", this.name);
        config.set("type", type.name());
        config.set("environment", env.name());
        config.set("seed", seed);
        return config;
    }

    private static String toWorldName(String name) {
        String safeName = name.trim()
                .toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9._-]", "_")
                .replaceAll("_+", "_");

        Preconditions.checkArgument(!safeName.isEmpty(), "World name must contain at least one valid character.");
        return WORLD_PREFIX + safeName;
    }

    private static File worldFolder(NamespacedKey worldKey) {
        return Bukkit.getServer().getLevelDirectory()
                .resolve("dimensions")
                .resolve(worldKey.getNamespace())
                .resolve(worldKey.getKey())
                .toFile();
    }

    private static File namespaceFolder(String namespace) {
        return Bukkit.getServer().getLevelDirectory()
                .resolve("dimensions")
                .resolve(namespace)
                .toFile();
    }

    private static boolean hasWorldData(File folder) {
        File[] contents = folder.listFiles(file -> !file.getName().equals("scaffold.yml"));
        return contents != null && contents.length > 0;
    }

    private static String nameFromWorldName(String worldName) {
        return worldName.substring(WORLD_PREFIX.length());
    }

    @Override
    public String toString() {
        return "ScaffoldWorld{" +
                "name='" + name + '\'' +
                ", worldName='" + worldName + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScaffoldWorld)) return false;
        ScaffoldWorld that = (ScaffoldWorld) o;
        return name.equals(that.name) && worldName.equals(that.worldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, worldName);
    }

    // Schedule an async repeating task every 5 minutes (5 * 60 * 20 ticks)
    private void startAutoSaveTask() {
        cancelAutoSaveTask();

        this.taskID = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                Scaffold.get(),
                () -> Scaffold.get().async(() -> {
                    Optional<World> optionalWorld = getWorld();
                    optionalWorld.ifPresent(world -> Scaffold.get().sync(() -> {
                        world.save();
                        Bukkit.getLogger().info("Auto-saved world: " + world.getName());
                    }));
                }),
                0L,
                5 * 60 * 20L
        );
        AUTO_SAVE_TASKS.put(this.worldName, this.taskID);
    }

    private void cancelAutoSaveTask() {
        Integer existingTaskID = AUTO_SAVE_TASKS.remove(this.worldName);
        if (existingTaskID != null) {
            Bukkit.getScheduler().cancelTask(existingTaskID);
        }

        if (this.taskID != -1 && !Objects.equals(existingTaskID, this.taskID)) {
            Bukkit.getScheduler().cancelTask(this.taskID);
        }
        this.taskID = -1;
    }
}
