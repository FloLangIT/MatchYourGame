package de.flolang.matchyourgame.config;

import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ConfigManager {

    private File configFile;
    private static Map<String, Object> config;

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);


    public ConfigManager() {
        load();
    }

    private void load() {
        LOGGER.debug("Try to load config.yml");
        try {
            File configsDirectory = new File("config/");
            configsDirectory.mkdirs();

            this.configFile = new File(configsDirectory, "config.yml");
            if (!configFile.exists()) {
                configFile.createNewFile();
            }
            config = (Map<String, Object>) loadYaml(configFile);
            if (config == null) config = new LinkedHashMap<>();
            Map<String, Object> configDefaults = new Yaml().load(this.getClass().getResourceAsStream("/default-config.yml"));
            mergeDefaults(config, configDefaults);
            saveConfig();
            LOGGER.info("config.yml loaded");
        } catch (Exception e) {
            LOGGER.error("Error while loading config.yml", e);
        }
    }

    public void saveConfig() throws IOException {
        saveYaml(config, configFile);
    }

    private Object loadYaml(File file) throws IOException {
        FileReader reader = new FileReader(file);
        Object data = new Yaml().load(reader);
        reader.close();
        return data;
    }

    private void saveYaml(Object data, File file) throws IOException {
        FileWriter writer = new FileWriter(file);
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        new Yaml(dumperOptions).dump(data, writer);
        writer.close();
    }

    public boolean getBoolean(String path) {
        return getRaw(path).toString().equalsIgnoreCase("true");
    }

    public List<String> getStringList(String path) {
        return (List<String>) getRaw(path);
    }

    public static Object getRaw(String path) {
        Map<String, Object> temp = config;
        String[] split = path.split("\\.");
        for (String s : split) {
            if (Objects.equals(s, split[split.length - 1])) break;
            Object o = temp.get(s);
            if (o instanceof Map) {
                temp = ((Map<String, Object>) o);
            } else {
                return null;
            }
        }
        return temp.get(split[split.length - 1]);
    }

    public void set(String key, Object value) {
        config.put(key, value);
    }

    public static String getString(String path, String fallback) {
        Object raw = getRaw(path);
        return raw == null ? fallback : raw.toString();
    }

    public static String getString(String path) {
        return getString(path, null);
    }

    public static int getInt(String path, int fallback) {
        Object raw = getRaw(path);
        if (raw == null) {
            return fallback;
        }
        return Integer.parseInt(raw.toString());
    }

    public long getLong(String path, long fallback) {
        Object raw = getRaw(path);
        if (raw == null) {
            return fallback;
        }
        return Long.parseLong(raw.toString());
    }

    private void mergeDefaults(Map<String, Object> target, Map<String, Object> defaults) {
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object currentValue = target.get(entry.getKey());
            Object defaultValue = entry.getValue();

            if (currentValue == null) {
                target.put(entry.getKey(), defaultValue);
                continue;
            }

            if (currentValue instanceof Map && defaultValue instanceof Map) {
                mergeDefaults((Map<String, Object>) currentValue, (Map<String, Object>) defaultValue);
            }
        }
    }

}
