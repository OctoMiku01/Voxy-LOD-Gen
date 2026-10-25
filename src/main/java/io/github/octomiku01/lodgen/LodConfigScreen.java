package io.github.octomiku01.lodgen;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The settings screen in Mod Menu, built with Cloth Config. Saving writes {@code config/lodgen.json}; a world this
 * game hosts picks the new settings up right away. Only loaded when Cloth Config is installed.
 */
public final class LodConfigScreen {
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    private LodConfigScreen() {
    }

    public static Screen create(Screen parent) {
        LodConfig config = LodConfig.load();
        LodConfig defaults = new LodConfig();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.lodgen.title"))
                .setSavingRunnable(() -> {
                    config.save();
                    MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                    if (server != null) {
                        LodGen.restart(server);
                    }
                });
        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory generation = builder.getOrCreateCategory(Component.translatable("config.lodgen.category.generation"));
        generation.addEntry(entries.startTextDescription(Component.translatable("config.lodgen.where")).build());
        generation.addEntry(entries.startBooleanToggle(name("enabled"), config.enabled)
                .setDefaultValue(defaults.enabled).setTooltip(tooltip("enabled"))
                .setSaveConsumer(value -> config.enabled = value).build());
        generation.addEntry(entries.startIntField(name("radius"), config.radius)
                .setDefaultValue(defaults.radius).setMin(1).setMax(4096).setTooltip(tooltip("radius"))
                .setSaveConsumer(value -> config.radius = value).build());
        generation.addEntry(entries.startIntField(name("featureRadius"), config.featureRadius)
                .setDefaultValue(defaults.featureRadius).setMin(0).setMax(4096).setTooltip(tooltip("featureRadius"))
                .setSaveConsumer(value -> config.featureRadius = value).build());
        generation.addEntry(entries.startIntField(name("mapRadius"), config.mapRadius)
                .setDefaultValue(defaults.mapRadius).setMin(0).setMax(16384).setTooltip(tooltip("mapRadius"))
                .setSaveConsumer(value -> config.mapRadius = value).build());
        generation.addEntry(entries.startBooleanToggle(name("terrainFirst"), config.terrainFirst)
                .setDefaultValue(defaults.terrainFirst).setTooltip(tooltip("terrainFirst"))
                .setSaveConsumer(value -> config.terrainFirst = value).build());
        generation.addEntry(entries.startBooleanToggle(name("structures"), config.structures)
                .setDefaultValue(defaults.structures).setTooltip(tooltip("structures"))
                .setSaveConsumer(value -> config.structures = value).build());
        generation.addEntry(entries.startBooleanToggle(name("sendSavedChunks"), config.sendSavedChunks)
                .setDefaultValue(defaults.sendSavedChunks).setTooltip(tooltip("sendSavedChunks"))
                .setSaveConsumer(value -> config.sendSavedChunks = value).build());
        generation.addEntry(entries.startBooleanToggle(name("keepUnderground"), config.keepUnderground)
                .setDefaultValue(defaults.keepUnderground).setTooltip(tooltip("keepUnderground"))
                .setSaveConsumer(value -> config.keepUnderground = value).build());
        generation.addEntry(entries.startIntSlider(name("viewDistanceMargin"), config.viewDistanceMargin, 0, 16)
                .setDefaultValue(defaults.viewDistanceMargin).setTooltip(tooltip("viewDistanceMargin"))
                .setSaveConsumer(value -> config.viewDistanceMargin = value).build());

        ConfigCategory performance = builder.getOrCreateCategory(Component.translatable("config.lodgen.category.performance"));
        performance.addEntry(entries.startEnumSelector(name("performance"), LodPerformance.class, config.performance)
                .setDefaultValue(defaults.performance).setTooltip(tooltip("performance"))
                .setEnumNameProvider(level -> performanceName((LodPerformance) level))
                .setSaveConsumer(value -> config.performance = value).build());
        performance.addEntry(entries.startIntSlider(name("threads"), Math.min(config.threads, CORES), 1, CORES)
                .setDefaultValue(defaults.threads).setTooltip(tooltip("threads"))
                .setSaveConsumer(value -> config.threads = value).build());
        performance.addEntry(entries.startIntSlider(name("threadsWhilePaused"), Math.min(config.threadsWhilePaused, CORES), 1, CORES)
                .setDefaultValue(defaults.threadsWhilePaused).setTooltip(tooltip("threadsWhilePaused"))
                .setSaveConsumer(value -> config.threadsWhilePaused = value).build());
        performance.addEntry(entries.startIntSlider(name("tileSize"), config.tileSize, 1, 32)
                .setDefaultValue(defaults.tileSize).setTooltip(tooltip("tileSize"))
                .setSaveConsumer(value -> config.tileSize = value).build());

        ConfigCategory multiplayer = builder.getOrCreateCategory(Component.translatable("config.lodgen.category.multiplayer"));
        multiplayer.addEntry(entries.startBooleanToggle(name("sendToPlayers"), config.sendToPlayers)
                .setDefaultValue(defaults.sendToPlayers).setTooltip(tooltip("sendToPlayers"))
                .setSaveConsumer(value -> config.sendToPlayers = value).build());
        multiplayer.addEntry(entries.startBooleanToggle(name("farPlayers"), config.farPlayers)
                .setDefaultValue(defaults.farPlayers).setTooltip(tooltip("farPlayers"))
                .setSaveConsumer(value -> config.farPlayers = value).build());
        multiplayer.addEntry(entries.startIntField(name("maxChunksPerSecondPerPlayer"), config.maxChunksPerSecondPerPlayer)
                .setDefaultValue(defaults.maxChunksPerSecondPerPlayer).setMin(1).setMax(1000).setTooltip(tooltip("maxChunksPerSecondPerPlayer"))
                .setSaveConsumer(value -> config.maxChunksPerSecondPerPlayer = value).build());

        ConfigCategory updates = builder.getOrCreateCategory(Component.translatable("config.lodgen.category.updates"));
        updates.addEntry(entries.startBooleanToggle(name("checkForUpdates"), config.checkForUpdates)
                .setDefaultValue(defaults.checkForUpdates).setTooltip(tooltip("checkForUpdates"))
                .setSaveConsumer(value -> config.checkForUpdates = value).build());
        updates.addEntry(entries.startBooleanToggle(name("autoUpdate"), config.autoUpdate)
                .setDefaultValue(defaults.autoUpdate).setTooltip(tooltip("autoUpdate"))
                .setSaveConsumer(value -> config.autoUpdate = value).build());
        updates.addEntry(entries.startIntSlider(name("updateMessageDelay"), config.updateMessageDelay, 0, 60)
                .setDefaultValue(defaults.updateMessageDelay).setTooltip(tooltip("updateMessageDelay"))
                .setTextGetter(value -> Component.translatable("config.lodgen.seconds", value))
                .setSaveConsumer(value -> config.updateMessageDelay = value).build());
        return builder.build();
    }

    /** The level with the threads it gives on this machine, e.g. "Balanced (4 threads)" */
    static Component performanceName(LodPerformance level) {
        if (level == LodPerformance.CUSTOM) {
            return Component.translatable(level.key());
        }
        return Component.translatable("config.lodgen.performance.withThreads", Component.translatable(level.key()), level.threads());
    }

    private static Component name(String option) {
        return Component.translatable("config.lodgen." + option);
    }

    private static Component tooltip(String option) {
        return Component.translatable("config.lodgen." + option + ".tooltip");
    }
}
