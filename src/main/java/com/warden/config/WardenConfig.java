package com.warden.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class WardenConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Warden");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("warden.json");

    public boolean explosionLimitsEnabled = true;
    public Map<String, ExplosionSourceConfig> explosionSources = new LinkedHashMap<>();

    public boolean itemLimitsEnabled = true;
    public int checkIntervalTicks = 20;
    public int dropPickupDelay = 60;
    public boolean deleteOverflowItem = false;
    public Map<String, Integer> itemLimits = new LinkedHashMap<>();

    public boolean itemUsageEnabled = true;
    public Set<String> blockedItemUsage = new LinkedHashSet<>();

    public boolean weaponLimitsEnabled = true;
    public Map<String, WeaponLimitConfig> weaponLimits = new LinkedHashMap<>();

    public boolean enchantmentLimitsEnabled = true;
    public Map<String, Integer> enchantmentLimits = new LinkedHashMap<>();
    public Map<String, Map<String, Integer>> itemEnchantmentOverrides = new LinkedHashMap<>();

    public boolean effectLimitsEnabled = true;
    public Map<String, EffectLimitConfig> effectLimits = new LinkedHashMap<>();

    public boolean xpLimitsEnabled = true;
    public Map<String, Integer> xpLimits = new LinkedHashMap<>();
    public Map<String, Map<String, Integer>> xpOverrides = new LinkedHashMap<>();

    public boolean itemActionBarEnabled = true;
    public boolean weaponActionBarEnabled = true;
    public boolean enchantmentActionBarEnabled = true;
    public boolean effectActionBarEnabled = true;
    public boolean xpActionBarEnabled = true;
    public Map<String, Set<String>> playerActionBarDisabled = new LinkedHashMap<>();

    public boolean dimensionLimitsEnabled = true;
    public Set<String> blockedDimensions = new LinkedHashSet<>();
    // registry keys are interned, so this is an identity lookup with no string work per check
    public transient Set<RegistryKey<World>> blockedDimensionKeys = Set.of();

    public boolean exemptCreative = true;
    public Set<String> exemptPlayers = new LinkedHashSet<>();

    // scrambles loot-table seeds rolled during world generation so they can't be used to
    // brute-force the world seed; see com.warden.seed.SeedHash
    public boolean antiSeedCrackEnabled = true;

    // chunk-ban guard: strips items / block entities whose encoded size could push a packet past
    // the client's 2 MB frame limit and disconnect everyone who loads the chunk
    public boolean chunkBanEnabled = true;
    public int maxItemBytes = 65536;
    public int maxBlockEntityBytes = 32768;
    public int maxChunkBlockEntityBytes = 1048576;

    // bucket-drain guard: refuses further source-block pickups once a player fills more than
    // maxBucketDrains buckets within bucketDrainWindowTicks
    public boolean bucketDrainEnabled = true;
    public int maxBucketDrains = 6;
    public int bucketDrainWindowTicks = 100;

    public static class ExplosionSourceConfig {
        public boolean enabled;
        public float maxPower;

        public ExplosionSourceConfig(boolean enabled, float maxPower) {
            this.enabled = enabled;
            this.maxPower = maxPower;
        }
    }

    public static class WeaponLimitConfig {
        public Double attackDamage;
        public Double attackSpeed;
        public Float reach;
        public Integer disableCooldownTicks;
        public Double projectileDamage;
        public Integer rechargeTicks;

        public WeaponLimitConfig() {
        }

        public WeaponLimitConfig(Double attackDamage, Double attackSpeed, Float reach, Integer disableCooldownTicks,
                                 Double projectileDamage, Integer rechargeTicks) {
            this.attackDamage = attackDamage;
            this.attackSpeed = attackSpeed;
            this.reach = reach;
            this.disableCooldownTicks = disableCooldownTicks;
            this.projectileDamage = projectileDamage;
            this.rechargeTicks = rechargeTicks;
        }

        public boolean isConfigured() {
            return attackDamage != null || attackSpeed != null || reach != null || disableCooldownTicks != null
                    || projectileDamage != null || rechargeTicks != null;
        }
    }

    public static class EffectLimitConfig {
        public int maxLevel;
        public int maxDuration;

        public EffectLimitConfig(int maxLevel, int maxDuration) {
            this.maxLevel = maxLevel;
            this.maxDuration = maxDuration;
        }
    }

    public static WardenConfig load() {
        WardenConfig config = new WardenConfig();
        if (!Files.exists(CONFIG_PATH)) {
            config.populateDefaults();
            config.save();
            LOGGER.info("[Warden] Created default config at {}", CONFIG_PATH);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            config.readFrom(root);
            config.normalize();
            LOGGER.info("[Warden] Loaded config from {}", CONFIG_PATH);
        } catch (Exception e) {
            LOGGER.error("[Warden] Failed to read config, using defaults: {}", e.getMessage());
            // keep the broken file around so a truncated write can't silently wipe every rule
            Path broken = CONFIG_PATH.resolveSibling("warden.json.broken");
            try {
                Files.copy(CONFIG_PATH, broken, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.error("[Warden] unreadable config copied to {}", broken);
            } catch (IOException copyError) {
                LOGGER.error("[Warden] could not back up the unreadable config: {}", copyError.getMessage());
            }
            config.populateDefaults();
        }
        return config;
    }

    public void save() {
        normalize();
        // write beside the file and swap it in, so a crash mid-write can't leave a truncated config
        Path tmp = CONFIG_PATH.resolveSibling("warden.json.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(toJson(), writer);
            }
            Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[Warden] Failed to save config: {}", e.getMessage());
        }
    }

    public void resetAll() {
        populateDefaults();
    }

    public JsonObject toJsonObject() {
        return toJson();
    }

    public static WardenConfig fromJson(JsonObject root) {
        WardenConfig config = new WardenConfig();
        config.readFrom(root);
        config.normalize();
        return config;
    }

    public boolean resetCategory(String category) {
        switch (category) {
            case "explosion" -> {
                explosionLimitsEnabled = true;
                explosionSources.clear();
            }
            case "item" -> {
                itemLimitsEnabled = true;
                checkIntervalTicks = 20;
                dropPickupDelay = 60;
                deleteOverflowItem = false;
                itemLimits.clear();
            }
            case "usage" -> {
                itemUsageEnabled = true;
                blockedItemUsage.clear();
            }
            case "weapon" -> {
                weaponLimitsEnabled = true;
                weaponLimits.clear();
            }
            case "enchant" -> {
                enchantmentLimitsEnabled = true;
                enchantmentLimits.clear();
                itemEnchantmentOverrides.clear();
            }
            case "effect" -> {
                effectLimitsEnabled = true;
                effectLimits.clear();
            }
            case "xp" -> {
                xpLimitsEnabled = true;
                xpLimits.clear();
                xpOverrides.clear();
            }
            case "actionbar" -> {
                itemActionBarEnabled = true;
                weaponActionBarEnabled = true;
                enchantmentActionBarEnabled = true;
                effectActionBarEnabled = true;
                xpActionBarEnabled = true;
                playerActionBarDisabled.clear();
            }
            case "dimension" -> {
                dimensionLimitsEnabled = true;
                blockedDimensions.clear();
                blockedDimensionKeys = Set.of();
            }
            case "exempt" -> {
                exemptCreative = true;
                exemptPlayers.clear();
            }
            case "anticrack" -> antiSeedCrackEnabled = true;
            case "chunkban" -> {
                chunkBanEnabled = true;
                maxItemBytes = 65536;
                maxBlockEntityBytes = 32768;
                maxChunkBlockEntityBytes = 1048576;
            }
            case "bucketdrain" -> {
                bucketDrainEnabled = true;
                maxBucketDrains = 6;
                bucketDrainWindowTicks = 100;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void populateDefaults() {
        explosionLimitsEnabled = true;
        explosionSources.clear();

        itemLimitsEnabled = true;
        checkIntervalTicks = 20;
        dropPickupDelay = 60;
        deleteOverflowItem = false;
        itemLimits.clear();

        itemUsageEnabled = true;
        blockedItemUsage.clear();

        weaponLimitsEnabled = true;
        weaponLimits.clear();

        enchantmentLimitsEnabled = true;
        enchantmentLimits.clear();
        itemEnchantmentOverrides.clear();
        effectLimitsEnabled = true;
        effectLimits.clear();
        itemActionBarEnabled = true;
        weaponActionBarEnabled = true;
        enchantmentActionBarEnabled = true;
        effectActionBarEnabled = true;
        xpActionBarEnabled = true;
        xpLimitsEnabled = true;
        xpLimits.clear();
        xpOverrides.clear();
        playerActionBarDisabled.clear();
        dimensionLimitsEnabled = true;
        blockedDimensions.clear();
        blockedDimensionKeys = Set.of();
        exemptCreative = true;
        exemptPlayers.clear();
        antiSeedCrackEnabled = true;
        chunkBanEnabled = true;
        maxItemBytes = 65536;
        maxBlockEntityBytes = 32768;
        maxChunkBlockEntityBytes = 1048576;
        bucketDrainEnabled = true;
        maxBucketDrains = 6;
        bucketDrainWindowTicks = 100;
    }

    private void readFrom(JsonObject root) {
        if (root.has("explosion_limits")) {
            JsonObject expl = root.getAsJsonObject("explosion_limits");
            explosionLimitsEnabled = getBool(expl, "enabled", true);
            if (expl.has("sources")) {
                JsonObject sources = expl.getAsJsonObject("sources");
                for (Map.Entry<String, JsonElement> entry : sources.entrySet()) {
                    JsonObject src = entry.getValue().getAsJsonObject();
                    boolean enabled = getBool(src, "enabled", true);
                    float power = src.has("max_power") ? src.get("max_power").getAsFloat() : 4.0f;
                    explosionSources.put(entry.getKey(), new ExplosionSourceConfig(enabled, power));
                }
            }
        }

        if (root.has("item_limits")) {
            JsonObject items = root.getAsJsonObject("item_limits");
            itemLimitsEnabled = getBool(items, "enabled", true);
            checkIntervalTicks = items.has("check_interval_ticks") ? items.get("check_interval_ticks").getAsInt() : 20;
            dropPickupDelay = items.has("drop_pickup_delay") ? items.get("drop_pickup_delay").getAsInt() : 60;
            deleteOverflowItem = getBool(items, "delete_overflow_item", false);
            if (items.has("items")) {
                JsonObject itemMap = items.getAsJsonObject("items");
                for (Map.Entry<String, JsonElement> entry : itemMap.entrySet()) {
                    String id = canonicalId(entry.getKey(), "item_limits");
                    if (id != null) itemLimits.put(id, entry.getValue().getAsInt());
                }
            }
        }

        if (root.has("item_usage")) {
            JsonObject usage = root.getAsJsonObject("item_usage");
            itemUsageEnabled = getBool(usage, "enabled", true);
            if (usage.has("blocked")) {
                for (JsonElement item : usage.getAsJsonArray("blocked")) {
                    Identifier id = Identifier.tryParse(item.getAsString().trim());
                    if (id != null) blockedItemUsage.add(id.toString());
                }
            }
        }

        if (root.has("weapon_limits")) {
            JsonObject weapons = root.getAsJsonObject("weapon_limits");
            weaponLimitsEnabled = getBool(weapons, "enabled", true);
            if (weapons.has("items")) {
                JsonObject itemMap = weapons.getAsJsonObject("items");
                for (Map.Entry<String, JsonElement> entry : itemMap.entrySet()) {
                    JsonObject itemCfg = entry.getValue().getAsJsonObject();
                    WeaponLimitConfig cfg = new WeaponLimitConfig();
                    if (itemCfg.has("attack_damage")) {
                        cfg.attackDamage = itemCfg.get("attack_damage").getAsDouble();
                    }
                    if (itemCfg.has("attack_speed")) {
                        cfg.attackSpeed = itemCfg.get("attack_speed").getAsDouble();
                    }
                    if (itemCfg.has("reach")) {
                        cfg.reach = itemCfg.get("reach").getAsFloat();
                    }
                    if (itemCfg.has("disable_cooldown_ticks")) {
                        cfg.disableCooldownTicks = itemCfg.get("disable_cooldown_ticks").getAsInt();
                    }
                    if (itemCfg.has("projectile_damage")) {
                        cfg.projectileDamage = itemCfg.get("projectile_damage").getAsDouble();
                    }
                    if (itemCfg.has("recharge_ticks")) {
                        cfg.rechargeTicks = itemCfg.get("recharge_ticks").getAsInt();
                    }
                    String id = canonicalId(entry.getKey(), "weapon_limits");
                    if (id != null && cfg.isConfigured()) {
                        weaponLimits.put(id, cfg);
                    }
                }
            }
        }

        if (root.has("enchantment_limits")) {
            JsonObject enchl = root.getAsJsonObject("enchantment_limits");
            enchantmentLimitsEnabled = getBool(enchl, "enabled", true);
            if (enchl.has("enchantments")) {
                JsonObject enchMap = enchl.getAsJsonObject("enchantments");
                for (Map.Entry<String, JsonElement> entry : enchMap.entrySet()) {
                    String id = canonicalId(entry.getKey(), "enchantment_limits");
                    if (id != null) enchantmentLimits.put(id, entry.getValue().getAsInt());
                }
            }
            if (enchl.has("item_overrides")) {
                JsonObject overrides = enchl.getAsJsonObject("item_overrides");
                for (Map.Entry<String, JsonElement> itemEntry : overrides.entrySet()) {
                    Map<String, Integer> itemMap = new LinkedHashMap<>();
                    JsonObject itemEnchants = itemEntry.getValue().getAsJsonObject();
                    for (Map.Entry<String, JsonElement> enchEntry : itemEnchants.entrySet()) {
                        String enchId = canonicalId(enchEntry.getKey(), "item_overrides");
                        if (enchId != null) itemMap.put(enchId, enchEntry.getValue().getAsInt());
                    }
                    String itemId = canonicalId(itemEntry.getKey(), "item_overrides");
                    if (itemId != null) itemEnchantmentOverrides.put(itemId, itemMap);
                }
            }
        }

        if (root.has("effect_limits")) {
            JsonObject effl = root.getAsJsonObject("effect_limits");
            effectLimitsEnabled = getBool(effl, "enabled", true);
            if (effl.has("effects")) {
                JsonObject effMap = effl.getAsJsonObject("effects");
                for (Map.Entry<String, JsonElement> entry : effMap.entrySet()) {
                    JsonObject eff = entry.getValue().getAsJsonObject();
                    int maxLevel = eff.has("max_level") ? eff.get("max_level").getAsInt() : -1;
                    int maxDuration = eff.has("max_duration") ? eff.get("max_duration").getAsInt() : -1;
                    String id = canonicalId(entry.getKey(), "effect_limits");
                    if (id != null) effectLimits.put(id, new EffectLimitConfig(maxLevel, maxDuration));
                }
            }
        }

        if (root.has("xp_limits")) {
            JsonObject xpl = root.getAsJsonObject("xp_limits");
            xpLimitsEnabled = getBool(xpl, "enabled", true);
            if (xpl.has("sources")) {
                JsonObject sources = xpl.getAsJsonObject("sources");
                for (Map.Entry<String, JsonElement> entry : sources.entrySet()) {
                    xpLimits.put(entry.getKey(), entry.getValue().getAsInt());
                }
            }
            if (xpl.has("overrides")) {
                JsonObject overrides = xpl.getAsJsonObject("overrides");
                for (Map.Entry<String, JsonElement> sourceEntry : overrides.entrySet()) {
                    Map<String, Integer> sourceMap = new LinkedHashMap<>();
                    JsonObject identifiers = sourceEntry.getValue().getAsJsonObject();
                    for (Map.Entry<String, JsonElement> idEntry : identifiers.entrySet()) {
                        String id = canonicalId(idEntry.getKey(), "xp_limits.overrides");
                        if (id != null) sourceMap.put(id, idEntry.getValue().getAsInt());
                    }
                    xpOverrides.put(sourceEntry.getKey(), sourceMap);
                }
            }
        }

        if (root.has("action_bar")) {
            JsonObject actionBar = root.getAsJsonObject("action_bar");
            itemActionBarEnabled = getBool(actionBar, "item", true);
            weaponActionBarEnabled = getBool(actionBar, "weapon", true);
            enchantmentActionBarEnabled = getBool(actionBar, "enchantment", true);
            effectActionBarEnabled = getBool(actionBar, "effect", true);
            xpActionBarEnabled = getBool(actionBar, "xp", true);
            if (actionBar.has("players")) {
                JsonObject players = actionBar.getAsJsonObject("players");
                playerActionBarDisabled.clear();
                for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
                    Set<String> disabled = new LinkedHashSet<>();
                    for (JsonElement el : entry.getValue().getAsJsonArray()) {
                        disabled.add(el.getAsString());
                    }
                    if (!disabled.isEmpty()) {
                        playerActionBarDisabled.put(entry.getKey(), disabled);
                    }
                }
            }
        }

        if (root.has("dimension_limits")) {
            JsonObject dims = root.getAsJsonObject("dimension_limits");
            dimensionLimitsEnabled = getBool(dims, "enabled", true);
            blockedDimensions.clear();
            if (dims.has("blocked")) {
                for (JsonElement el : dims.getAsJsonArray("blocked")) {
                    blockedDimensions.add(el.getAsString());
                }
            }
        }

        if (root.has("anti_seedcrack")) {
            antiSeedCrackEnabled = getBool(root.getAsJsonObject("anti_seedcrack"), "enabled", true);
        }

        if (root.has("chunk_ban")) {
            JsonObject cb = root.getAsJsonObject("chunk_ban");
            chunkBanEnabled = getBool(cb, "enabled", true);
            maxItemBytes = cb.has("max_item_bytes") ? cb.get("max_item_bytes").getAsInt() : 65536;
            maxBlockEntityBytes = cb.has("max_block_entity_bytes") ? cb.get("max_block_entity_bytes").getAsInt() : 32768;
            maxChunkBlockEntityBytes = cb.has("max_chunk_block_entity_bytes") ? cb.get("max_chunk_block_entity_bytes").getAsInt() : 1048576;
        }

        if (root.has("bucket_drain")) {
            JsonObject bd = root.getAsJsonObject("bucket_drain");
            bucketDrainEnabled = getBool(bd, "enabled", true);
            maxBucketDrains = bd.has("max_drains") ? bd.get("max_drains").getAsInt() : 6;
            bucketDrainWindowTicks = bd.has("window_ticks") ? bd.get("window_ticks").getAsInt() : 100;
        }

        if (root.has("exempt")) {
            JsonObject ex = root.getAsJsonObject("exempt");
            exemptCreative = getBool(ex, "creative", true);
            exemptPlayers.clear();
            if (ex.has("players")) {
                for (JsonElement el : ex.getAsJsonArray("players")) {
                    exemptPlayers.add(el.getAsString());
                }
            }
        }
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();

        JsonObject expl = new JsonObject();
        expl.addProperty("enabled", explosionLimitsEnabled);
        JsonObject sources = new JsonObject();
        for (Map.Entry<String, ExplosionSourceConfig> e : explosionSources.entrySet()) {
            JsonObject src = new JsonObject();
            src.addProperty("enabled", e.getValue().enabled);
            src.addProperty("max_power", e.getValue().maxPower);
            sources.add(e.getKey(), src);
        }
        expl.add("sources", sources);
        root.add("explosion_limits", expl);

        JsonObject itemSection = new JsonObject();
        itemSection.addProperty("enabled", itemLimitsEnabled);
        itemSection.addProperty("check_interval_ticks", checkIntervalTicks);
        itemSection.addProperty("drop_pickup_delay", dropPickupDelay);
        itemSection.addProperty("delete_overflow_item", deleteOverflowItem);
        JsonObject itemMap = new JsonObject();
        for (Map.Entry<String, Integer> e : itemLimits.entrySet()) {
            itemMap.addProperty(e.getKey(), e.getValue());
        }
        itemSection.add("items", itemMap);
        root.add("item_limits", itemSection);

        JsonObject usageSection = new JsonObject();
        usageSection.addProperty("enabled", itemUsageEnabled);
        JsonArray usageItems = new JsonArray();
        blockedItemUsage.forEach(usageItems::add);
        usageSection.add("blocked", usageItems);
        root.add("item_usage", usageSection);

        JsonObject weaponSection = new JsonObject();
        weaponSection.addProperty("enabled", weaponLimitsEnabled);
        JsonObject weaponItems = new JsonObject();
        for (Map.Entry<String, WeaponLimitConfig> e : weaponLimits.entrySet()) {
            if (!e.getValue().isConfigured()) {
                continue;
            }
            JsonObject itemCfg = new JsonObject();
            if (e.getValue().attackDamage != null) {
                itemCfg.addProperty("attack_damage", e.getValue().attackDamage);
            }
            if (e.getValue().attackSpeed != null) {
                itemCfg.addProperty("attack_speed", e.getValue().attackSpeed);
            }
            if (e.getValue().reach != null) {
                itemCfg.addProperty("reach", e.getValue().reach);
            }
            if (e.getValue().disableCooldownTicks != null) {
                itemCfg.addProperty("disable_cooldown_ticks", e.getValue().disableCooldownTicks);
            }
            if (e.getValue().projectileDamage != null) {
                itemCfg.addProperty("projectile_damage", e.getValue().projectileDamage);
            }
            if (e.getValue().rechargeTicks != null) {
                itemCfg.addProperty("recharge_ticks", e.getValue().rechargeTicks);
            }
            weaponItems.add(e.getKey(), itemCfg);
        }
        weaponSection.add("items", weaponItems);
        root.add("weapon_limits", weaponSection);

        JsonObject enchlSection = new JsonObject();
        enchlSection.addProperty("enabled", enchantmentLimitsEnabled);
        JsonObject enchMap = new JsonObject();
        for (Map.Entry<String, Integer> e : enchantmentLimits.entrySet()) {
            enchMap.addProperty(e.getKey(), e.getValue());
        }
        enchlSection.add("enchantments", enchMap);
        JsonObject itemOverridesJson = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> itemEntry : itemEnchantmentOverrides.entrySet()) {
            JsonObject itemEnchJson = new JsonObject();
            for (Map.Entry<String, Integer> e : itemEntry.getValue().entrySet()) {
                itemEnchJson.addProperty(e.getKey(), e.getValue());
            }
            itemOverridesJson.add(itemEntry.getKey(), itemEnchJson);
        }
        enchlSection.add("item_overrides", itemOverridesJson);
        root.add("enchantment_limits", enchlSection);

        JsonObject efflSection = new JsonObject();
        efflSection.addProperty("enabled", effectLimitsEnabled);
        JsonObject effMap = new JsonObject();
        for (Map.Entry<String, EffectLimitConfig> e : effectLimits.entrySet()) {
            JsonObject eff = new JsonObject();
            eff.addProperty("max_level", e.getValue().maxLevel);
            eff.addProperty("max_duration", e.getValue().maxDuration);
            effMap.add(e.getKey(), eff);
        }
        efflSection.add("effects", effMap);
        root.add("effect_limits", efflSection);

        JsonObject xplSection = new JsonObject();
        xplSection.addProperty("enabled", xpLimitsEnabled);
        JsonObject xplMap = new JsonObject();
        for (Map.Entry<String, Integer> e : xpLimits.entrySet()) {
            xplMap.addProperty(e.getKey(), e.getValue());
        }
        xplSection.add("sources", xplMap);
        JsonObject overridesMap = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> sourceEntry : xpOverrides.entrySet()) {
            JsonObject sourceJson = new JsonObject();
            for (Map.Entry<String, Integer> e : sourceEntry.getValue().entrySet()) {
                sourceJson.addProperty(e.getKey(), e.getValue());
            }
            overridesMap.add(sourceEntry.getKey(), sourceJson);
        }
        xplSection.add("overrides", overridesMap);
        root.add("xp_limits", xplSection);

        JsonObject actionBarSection = new JsonObject();
        actionBarSection.addProperty("item", itemActionBarEnabled);
        actionBarSection.addProperty("weapon", weaponActionBarEnabled);
        actionBarSection.addProperty("enchantment", enchantmentActionBarEnabled);
        actionBarSection.addProperty("effect", effectActionBarEnabled);
        actionBarSection.addProperty("xp", xpActionBarEnabled);
        JsonObject playerActionBar = new JsonObject();
        for (Map.Entry<String, Set<String>> entry : playerActionBarDisabled.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            JsonArray categories = new JsonArray();
            for (String category : entry.getValue()) {
                categories.add(category);
            }
            playerActionBar.add(entry.getKey(), categories);
        }
        actionBarSection.add("players", playerActionBar);
        root.add("action_bar", actionBarSection);

        JsonObject dimSection = new JsonObject();
        dimSection.addProperty("enabled", dimensionLimitsEnabled);
        JsonArray blockedArr = new JsonArray();
        for (String d : blockedDimensions) {
            blockedArr.add(d);
        }
        dimSection.add("blocked", blockedArr);
        root.add("dimension_limits", dimSection);

        JsonObject exemptSection = new JsonObject();
        exemptSection.addProperty("creative", exemptCreative);
        JsonArray playersArr = new JsonArray();
        for (String p : exemptPlayers) {
            playersArr.add(p);
        }
        exemptSection.add("players", playersArr);
        root.add("exempt", exemptSection);

        JsonObject antiCrackSection = new JsonObject();
        antiCrackSection.addProperty("enabled", antiSeedCrackEnabled);
        root.add("anti_seedcrack", antiCrackSection);

        JsonObject chunkBanSection = new JsonObject();
        chunkBanSection.addProperty("enabled", chunkBanEnabled);
        chunkBanSection.addProperty("max_item_bytes", maxItemBytes);
        chunkBanSection.addProperty("max_block_entity_bytes", maxBlockEntityBytes);
        chunkBanSection.addProperty("max_chunk_block_entity_bytes", maxChunkBlockEntityBytes);
        root.add("chunk_ban", chunkBanSection);

        JsonObject bucketDrainSection = new JsonObject();
        bucketDrainSection.addProperty("enabled", bucketDrainEnabled);
        bucketDrainSection.addProperty("max_drains", maxBucketDrains);
        bucketDrainSection.addProperty("window_ticks", bucketDrainWindowTicks);
        root.add("bucket_drain", bucketDrainSection);

        return root;
    }

    private void normalize() {
        checkIntervalTicks = Math.max(1, checkIntervalTicks);
        weaponLimits.entrySet().removeIf(entry -> {
            WeaponLimitConfig cfg = entry.getValue();
            return cfg == null || !cfg.isConfigured();
        });

        itemEnchantmentOverrides.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());

        xpOverrides.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());

        effectLimits.entrySet().removeIf(entry -> {
            EffectLimitConfig cfg = entry.getValue();
            return cfg == null || (cfg.maxLevel == -1 && cfg.maxDuration == -1);
        });

        playerActionBarDisabled.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());

        Set<RegistryKey<World>> keys = new HashSet<>();
        blockedDimensions.removeIf(id -> {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) {
                LOGGER.warn("[Warden] ignoring invalid dimension id in config: {}", id);
                return true;
            }
            keys.add(RegistryKey.of(RegistryKeys.WORLD, ident));
            return false;
        });
        blockedDimensionKeys = keys.isEmpty() ? Set.of() : Set.copyOf(keys);
    }

    // "diamond" and "minecraft:diamond" have to land on one key or only one of them is enforced
    private static String canonicalId(String key, String section) {
        Identifier id = Identifier.tryParse(key.trim());
        if (id == null) {
            LOGGER.warn("[Warden] ignoring invalid id in {}: {}", section, key);
            return null;
        }
        return id.toString();
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }
}
