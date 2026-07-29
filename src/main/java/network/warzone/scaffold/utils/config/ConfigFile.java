package network.warzone.scaffold.utils.config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;

public class ConfigFile extends Config {
    private final File file;

    @SuppressWarnings("unchecked")
    public ConfigFile(File file) {
        this.file = file;
        try (FileReader reader = new FileReader(file)) {
            Map map = yaml.loadAs(reader, Map.class);
            if (map != null) {
                set(map);
            }
        } catch (FileNotFoundException e) {
            throw new ConfigException("failed to read config", e);
        } catch (IOException e) {
            throw new ConfigException("failed to close config", e);
        }
    }

    public void save() {
        save(this.file);
    }
}
