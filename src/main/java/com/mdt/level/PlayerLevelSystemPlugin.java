package com.mdt.level;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import mindustry.Vars;
import mindustry.game.EventType.PlayerJoin;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public final class PlayerLevelSystemPlugin extends Plugin {
    private static final String CONFIG_DIR_NAME = "mdt-player-level-system";
    private static final String CONFIG_FILE_NAME = "player-level-system.properties";

    private volatile Config config;

    @Override
    public void init() {
        try {
            ensureDefaultResources(resolveDataRoot());
            reloadConfig();
            Events.on(PlayerJoin.class, event -> {
                PlayerProfile profile = resolveProfile(event.player, true);
                if (profile != null && config.broadcastJoinMessage) {
                    Call.sendMessage(formatJoinMessage(profile));
                }
            });
            Log.info("MDT Player Level System loaded.");
            Log.info("Config directory: @", resolveDataRoot().getAbsolutePath());
        } catch (IOException exception) {
            throw new RuntimeException("Failed to initialize MDT Player Level System.", exception);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("level-check", "[playerOrComid]", "Show level information.", args -> {
            PlayerProfile profile = args.length == 0 ? firstOnlineProfile() : resolveProfile(args[0], false);
            if (profile == null) {
                Log.info("Level profile not found.");
                return;
            }
            printProfile(profile);
        });

        handler.register("level-sync", "[playerOrComid]", "Recalculate level from stored experience.", args -> {
            PlayerProfile profile = args.length == 0 ? firstOnlineProfile() : resolveProfile(args[0], true);
            if (profile == null) {
                Log.info("Level profile not found.");
                return;
            }
            recalculate(profile);
            saveProfile(profile);
            printProfile(profile);
        });

        handler.register("level-add-exp", "<playerOrComid> <amount>", "Add experience to a player.", args -> {
            Integer amount = parseInt(args[1]);
            if (amount == null) {
                Log.err("Amount must be an integer.");
                return;
            }
            PlayerProfile profile = resolveProfile(args[0], true);
            if (profile == null) {
                Log.info("Level profile not found.");
                return;
            }
            profile.experience += amount.intValue();
            if (profile.experience < 0) {
                profile.experience = 0;
            }
            recalculate(profile);
            saveProfile(profile);
            Log.info("Added @ exp to @", amount.intValue(), profile.displayKey());
            printProfile(profile);
        });

        handler.register("level-set", "<playerOrComid> <level> [experience]", "Set player level and optional experience.", args -> {
            Integer level = parseInt(args[1]);
            Integer experience = args.length >= 3 ? parseInt(args[2]) : null;
            if (level == null) {
                Log.err("Level must be an integer.");
                return;
            }
            PlayerProfile profile = resolveProfile(args[0], true);
            if (profile == null) {
                Log.info("Level profile not found.");
                return;
            }
            profile.level = clamp(level.intValue(), config.minLevel, config.maxLevel);
            profile.experience = experience == null ? minimumExperienceForLevel(profile.level) : Math.max(0, experience.intValue());
            recalculate(profile);
            saveProfile(profile);
            printProfile(profile);
        });

        handler.register("level-reload", "Reload level configuration.", args -> {
            try {
                reloadConfig();
                Log.info("Level config reloaded. list=@ levelField=@ experienceField=@",
                    config.listName, config.levelField, config.experienceField);
            } catch (IOException exception) {
                Log.err("Failed to reload level config: @", exception.getMessage());
            }
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("level", "[playerOrComid]", "Show level and experience.", (args, player) -> {
            PlayerProfile profile = args.length == 0 ? resolveProfile(player, true) : resolveProfile(args[0], false);
            if (profile == null) {
                player.sendMessage("[scarlet]Level profile not found.");
                return;
            }
            player.sendMessage(formatProfile(profile));
        });
    }

    public static int readLevel(String identity) {
        Map<String, String> object = findProfileObject("player_profile", identity);
        if (object.isEmpty()) {
            return 0;
        }
        return parseInt(object.get("level"), 0);
    }

    private PlayerProfile firstOnlineProfile() {
        Player player = Groups.player.isEmpty() ? null : Groups.player.first();
        return player == null ? null : resolveProfile(player, true);
    }

    private PlayerProfile resolveProfile(Object target, boolean createIfMissing) {
        if (target instanceof Player) {
            Player player = (Player)target;
            String uuid = safeUuid(player);
            String comid = resolveComIdByUuid(uuid);
            return loadProfile(profileKey(uuid, comid), uuid, comid, player.plainName(), createIfMissing);
        }

        String raw = target == null ? "" : target.toString().trim();
        if (raw.isEmpty()) {
            return null;
        }
        Player onlinePlayer = findPlayer(raw);
        if (onlinePlayer != null) {
            return resolveProfile((Object)onlinePlayer, createIfMissing);
        }
        Map.Entry<String, Map<String, String>> storedProfile = findStoredProfile(raw);
        if (storedProfile != null) {
            return loadProfile(
                storedProfile.getKey(),
                valueOrFallback(storedProfile.getValue().get(config.uuidField), raw),
                storedProfile.getValue().get(config.comidField),
                storedProfile.getValue().get(config.lastNameField),
                createIfMissing
            );
        }
        if (looksLikeComId(raw)) {
            return loadProfile(raw.toUpperCase(), "", raw.toUpperCase(), raw.toUpperCase(), createIfMissing);
        }
        String comid = resolveComIdByUuid(raw);
        return loadProfile(profileKey(raw, comid), raw, comid, raw, createIfMissing);
    }

    private PlayerProfile loadProfile(String key, String uuid, String comid, String lastName, boolean createIfMissing) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        Map<String, String> object = listDataObject(config.listName, key);
        if (object.isEmpty() && !createIfMissing) {
            return null;
        }
        PlayerProfile profile = new PlayerProfile();
        profile.key = key;
        profile.uuid = valueOrFallback(object.get(config.uuidField), uuid);
        profile.comid = valueOrFallback(object.get(config.comidField), comid);
        profile.lastName = valueOrFallback(object.get(config.lastNameField), lastName);
        profile.experience = parseInt(object.get(config.experienceField), 0);
        profile.level = parseInt(object.get(config.levelField), config.minLevel);
        recalculate(profile);
        if (createIfMissing || object.isEmpty()) {
            saveProfile(profile);
        }
        return profile;
    }

    private void recalculate(PlayerProfile profile) {
        if (profile == null) {
            return;
        }
        LevelSnapshot snapshot = snapshotForExperience(profile.experience);
        profile.level = snapshot.level;
        profile.experienceIntoLevel = snapshot.experienceIntoLevel;
        profile.nextRequirement = snapshot.nextRequirement;
        profile.updatedAt = nowText();
    }

    private LevelSnapshot snapshotForExperience(int experience) {
        int total = Math.max(0, experience);
        int level = config.minLevel;
        int remaining = total;
        while (level < config.maxLevel) {
            int required = requirementForLevel(level);
            if (required <= 0 || remaining < required) {
                return new LevelSnapshot(level, remaining, required);
            }
            remaining -= required;
            level++;
        }
        return new LevelSnapshot(config.maxLevel, remaining, 0);
    }

    private int requirementForLevel(int level) {
        Integer direct = config.requirements.get(Integer.valueOf(level));
        if (direct != null) {
            return Math.max(1, direct.intValue());
        }
        Map.Entry<Integer, Integer> floor = config.requirements.floorEntry(Integer.valueOf(level));
        if (floor != null) {
            return Math.max(1, floor.getValue().intValue());
        }
        return 100;
    }

    private int minimumExperienceForLevel(int level) {
        int total = 0;
        for (int current = config.minLevel; current < level; current++) {
            total += requirementForLevel(current);
        }
        return total;
    }

    private void saveProfile(PlayerProfile profile) {
        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put(config.uuidField, defaultString(profile.uuid));
        values.put(config.comidField, defaultString(profile.comid));
        values.put(config.lastNameField, defaultString(profile.lastName));
        values.put(config.levelField, String.valueOf(profile.level));
        values.put(config.experienceField, String.valueOf(Math.max(0, profile.experience)));
        values.put("experienceToNext", String.valueOf(profile.nextRequirement));
        values.put("experienceIntoLevel", String.valueOf(profile.experienceIntoLevel));
        values.put(config.updatedAtField, nowText());
        listDataPutObject(config.listName, profile.key, values);
    }

    private String formatJoinMessage(PlayerProfile profile) {
        String result = config.joinTemplate;
        result = result.replace("{name}", defaultString(profile.lastName, profile.key));
        result = result.replace("{level}", String.valueOf(profile.level));
        result = result.replace("{experience}", String.valueOf(profile.experience));
        result = result.replace("{comid}", defaultString(profile.comid, profile.key));
        return result;
    }

    private void printProfile(PlayerProfile profile) {
        Log.info("@ level=@ experience=@ intoLevel=@ next=@ comid=@ uuid=@",
            profile.displayKey(),
            profile.level,
            profile.experience,
            profile.experienceIntoLevel,
            profile.nextRequirement,
            defaultString(profile.comid),
            defaultString(profile.uuid));
    }

    private String formatProfile(PlayerProfile profile) {
        return "[accent]player[]: " + profile.displayKey()
            + "\n[accent]level[]: " + profile.level
            + "\n[accent]experience[]: " + profile.experience
            + "\n[accent]intoLevel[]: " + profile.experienceIntoLevel
            + "\n[accent]nextRequirement[]: " + profile.nextRequirement
            + "\n[accent]comid[]: " + defaultString(profile.comid)
            + "\n[accent]uuid[]: " + defaultString(profile.uuid);
    }

    private Player findPlayer(String value) {
        String normalized = Strings.stripColors(value).trim();
        return Groups.player.find(player ->
            player.plainName().equalsIgnoreCase(normalized)
                || Strings.stripColors(player.name).equalsIgnoreCase(normalized)
                || safeUuid(player).equalsIgnoreCase(normalized)
        );
    }

    private Map.Entry<String, Map<String, String>> findStoredProfile(String identity) {
        String normalized = Strings.stripColors(defaultString(identity, "")).trim();
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Map<String, String>> entry : listDataList(config.listName).entrySet()) {
            Map<String, String> value = entry.getValue();
            if (entry.getKey().equalsIgnoreCase(normalized)
                || normalized.equalsIgnoreCase(defaultString(value.get(config.uuidField), ""))
                || normalized.equalsIgnoreCase(defaultString(value.get(config.comidField), ""))
                || normalized.equalsIgnoreCase(Strings.stripColors(defaultString(value.get(config.lastNameField), "")))) {
                return entry;
            }
        }
        return null;
    }

    private String resolveComIdByUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return "";
        }
        try {
            Class<?> jumpPluginClass = Class.forName("com.mdt.jump.JumpComIdPlugin");
            Object api = jumpPluginClass.getMethod("getApi").invoke(null);
            if (api == null) {
                return "";
            }
            Object record = api.getClass().getMethod("getOrCreate", String.class).invoke(api, uuid);
            if (record == null) {
                return "";
            }
            Object value = record.getClass().getMethod("getComId").invoke(record);
            return value == null ? "" : value.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> listDataObject(String listName, String key) {
        try {
            Class<?> listDataClass = Class.forName("com.mdt.listdata.ListDataSystemPlugin");
            Object result = listDataClass.getMethod("getObject", String.class, String.class).invoke(null, listName, key);
            return result == null ? new LinkedHashMap<String, String>() : (Map<String, String>)result;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load list-data object.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> listDataList(String listName) {
        try {
            Class<?> listDataClass = Class.forName("com.mdt.listdata.ListDataSystemPlugin");
            Object result = listDataClass.getMethod("getList", String.class).invoke(null, listName);
            return result == null ? new LinkedHashMap<String, Map<String, String>>() : (Map<String, Map<String, String>>)result;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to load list-data list.", exception);
        }
    }

    private static Map<String, String> findProfileObject(String listName, String identity) {
        Map<String, String> direct = listDataObject(listName, identity);
        if (!direct.isEmpty()) {
            return direct;
        }
        String normalized = identity == null ? "" : identity.trim();
        if (normalized.isEmpty()) {
            return direct;
        }
        for (Map.Entry<String, Map<String, String>> entry : listDataList(listName).entrySet()) {
            Map<String, String> value = entry.getValue();
            if (entry.getKey().equalsIgnoreCase(normalized)
                || normalized.equalsIgnoreCase(defaultString(value.get("uuid"), ""))
                || normalized.equalsIgnoreCase(defaultString(value.get("comid"), ""))
                || normalized.equalsIgnoreCase(Strings.stripColors(defaultString(value.get("lastName"), "")))) {
                return value;
            }
        }
        return direct;
    }

    private static void listDataPutObject(String listName, String key, Map<String, String> values) {
        try {
            Class<?> listDataClass = Class.forName("com.mdt.listdata.ListDataSystemPlugin");
            Method method = listDataClass.getMethod("putObject", String.class, String.class, Map.class);
            method.invoke(null, listName, key, values);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to save list-data object.", exception);
        }
    }

    private void reloadConfig() throws IOException {
        File configFile = new File(resolveDataRoot(), CONFIG_FILE_NAME);
        Properties properties = new Properties();
        InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8);
        try {
            properties.load(reader);
        } finally {
            reader.close();
        }

        TreeMap<Integer, Integer> requirements = new TreeMap<Integer, Integer>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("level.requirement.")) {
                Integer key = parseInt(name.substring("level.requirement.".length()));
                Integer value = parseInt(properties.getProperty(name));
                if (key != null && value != null) {
                    requirements.put(key, value);
                }
            }
        }
        if (requirements.isEmpty()) {
            requirements.put(Integer.valueOf(1), Integer.valueOf(100));
            requirements.put(Integer.valueOf(2), Integer.valueOf(300));
            requirements.put(Integer.valueOf(3), Integer.valueOf(500));
            requirements.put(Integer.valueOf(4), Integer.valueOf(1000));
            requirements.put(Integer.valueOf(5), Integer.valueOf(1500));
        }

        config = new Config(
            readInt(properties, "level.min", 1),
            readInt(properties, "level.max", 100),
            readString(properties, "data.listName", "player_profile"),
            readString(properties, "data.experienceField", "experience"),
            readString(properties, "data.levelField", "level"),
            readString(properties, "data.lastNameField", "lastName"),
            readString(properties, "data.uuidField", "uuid"),
            readString(properties, "data.comidField", "comid"),
            readString(properties, "data.updatedAtField", "updatedAt"),
            readBoolean(properties, "message.broadcastOnJoin", true),
            readString(properties, "message.joinTemplate", "[accent]{name}[] current level [lime]Lv.{level}[] exp [accent]{experience}[]"),
            requirements
        );
    }

    private File resolveDataRoot() {
        File modsRoot = new File(Vars.dataDirectory.absolutePath(), "mods");
        return new File(new File(modsRoot, "config"), CONFIG_DIR_NAME);
    }

    private void ensureDefaultResources(File dataRoot) throws IOException {
        if (!dataRoot.exists() && !dataRoot.mkdirs() && !dataRoot.isDirectory()) {
            throw new IOException("Unable to create config directory: " + dataRoot.getAbsolutePath());
        }
        copyIfMissing(dataRoot, CONFIG_FILE_NAME);
    }

    private void copyIfMissing(File dataRoot, String resourceName) throws IOException {
        File target = new File(dataRoot, resourceName);
        if (target.exists()) {
            return;
        }
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("Missing bundled resource: " + resourceName);
            }
            Files.copy(inputStream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean looksLikeComId(String raw) {
        return raw.length() <= 8 && raw.matches("[A-Za-z0-9]+");
    }

    private String profileKey(String uuid, String comid) {
        if (comid != null && !comid.trim().isEmpty()) {
            return comid.trim().toUpperCase();
        }
        return uuid == null ? "" : uuid.trim();
    }

    private String safeUuid(Player player) {
        try {
            return player.uuid();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private Integer parseInt(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int readInt(Properties properties, String key, int fallback) {
        return parseInt(properties.getProperty(key), fallback);
    }

    private boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private String readString(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String defaultString(String value) {
        return defaultString(value, "-");
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String valueOrFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? defaultString(fallback, "") : value.trim();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static final class Config {
        private final int minLevel;
        private final int maxLevel;
        private final String listName;
        private final String experienceField;
        private final String levelField;
        private final String lastNameField;
        private final String uuidField;
        private final String comidField;
        private final String updatedAtField;
        private final boolean broadcastJoinMessage;
        private final String joinTemplate;
        private final TreeMap<Integer, Integer> requirements;

        private Config(
            int minLevel,
            int maxLevel,
            String listName,
            String experienceField,
            String levelField,
            String lastNameField,
            String uuidField,
            String comidField,
            String updatedAtField,
            boolean broadcastJoinMessage,
            String joinTemplate,
            TreeMap<Integer, Integer> requirements
        ) {
            this.minLevel = Math.max(1, minLevel);
            this.maxLevel = Math.max(this.minLevel, maxLevel);
            this.listName = listName;
            this.experienceField = experienceField;
            this.levelField = levelField;
            this.lastNameField = lastNameField;
            this.uuidField = uuidField;
            this.comidField = comidField;
            this.updatedAtField = updatedAtField;
            this.broadcastJoinMessage = broadcastJoinMessage;
            this.joinTemplate = joinTemplate;
            this.requirements = requirements;
        }
    }

    private static final class LevelSnapshot {
        private final int level;
        private final int experienceIntoLevel;
        private final int nextRequirement;

        private LevelSnapshot(int level, int experienceIntoLevel, int nextRequirement) {
            this.level = level;
            this.experienceIntoLevel = experienceIntoLevel;
            this.nextRequirement = nextRequirement;
        }
    }

    private static final class PlayerProfile {
        private String key;
        private String uuid;
        private String comid;
        private String lastName;
        private int level;
        private int experience;
        private int experienceIntoLevel;
        private int nextRequirement;
        private String updatedAt;

        private String displayKey() {
            if (lastName != null && !lastName.trim().isEmpty()) {
                return lastName;
            }
            if (comid != null && !comid.trim().isEmpty()) {
                return comid;
            }
            return key;
        }
    }
}
