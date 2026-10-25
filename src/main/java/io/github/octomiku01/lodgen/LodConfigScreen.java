package io.github.octomiku01.lodgen;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * The settings screen in Mod Menu, built like vanilla's option screens, so no other mod is needed for it. Closing it
 * writes {@code config/lodgen.json}; a world this game hosts picks the new settings up right away. Options nobody
 * touched keep their value, unless it lies beyond what the slider offers (set by hand in the file).
 */
public final class LodConfigScreen extends OptionsSubScreen {
    private final LodConfig config = LodConfig.load();

    private final OptionInstance<Boolean> enabled = this.toggle("enabled", this.config.enabled);
    private final OptionInstance<Integer> radius = this.slider("radius", this.config.radius, 1, 4096, null);
    private final OptionInstance<Integer> featureRadius = this.slider("featureRadius", this.config.featureRadius, 0, 4096, null);
    private final OptionInstance<Integer> mapRadius = this.slider("mapRadius", this.config.mapRadius, 0, 16384, LodConfigScreen::offAtZero);
    private final OptionInstance<Boolean> terrainFirst = this.toggle("terrainFirst", this.config.terrainFirst);
    private final OptionInstance<Boolean> structures = this.toggle("structures", this.config.structures);
    private final OptionInstance<Boolean> sendSavedChunks = this.toggle("sendSavedChunks", this.config.sendSavedChunks);
    private final OptionInstance<Boolean> keepUnderground = this.toggle("keepUnderground", this.config.keepUnderground);
    private final OptionInstance<Integer> viewDistanceMargin = this.slider("viewDistanceMargin", this.config.viewDistanceMargin, 0, 16, null);

    private final OptionInstance<LodPerformance> performance = new OptionInstance<>("config.lodgen.performance",
            OptionInstance.cachedConstantTooltip(tooltip("performance")),
            // The cycle button puts the caption in front itself
            (caption, level) -> performanceName(level),
            new OptionInstance.Enum<>(Arrays.asList(LodPerformance.values()), Codec.STRING.xmap(LodPerformance::valueOf, LodPerformance::name)),
            this.config.performance, level -> this.updateCustomThreads());
    private final OptionInstance<Integer> threads = this.slider("threads", Math.min(this.config.threads, LodPerformance.CORES), 1, LodPerformance.CORES, null);
    private final OptionInstance<Integer> threadsWhilePaused = this.slider("threadsWhilePaused",
            Math.min(this.config.threadsWhilePaused, LodPerformance.CORES), 1, LodPerformance.CORES, null);
    private final OptionInstance<Integer> tickTimeLimit = this.slider("tickTimeLimit", Math.min(this.config.tickTimeLimit, 100), 0, 100,
            value -> value == 0 ? Component.translatable("config.lodgen.off") : Component.translatable("config.lodgen.milliseconds", value));
    private final OptionInstance<Integer> tileSize = this.slider("tileSize", this.config.tileSize, 1, 32, null);

    private final OptionInstance<Boolean> sendToPlayers = this.toggle("sendToPlayers", this.config.sendToPlayers);
    private final OptionInstance<Boolean> farPlayers = this.toggle("farPlayers", this.config.farPlayers);
    private final OptionInstance<Integer> maxChunksPerSecondPerPlayer = this.slider("maxChunksPerSecondPerPlayer",
            this.config.maxChunksPerSecondPerPlayer, 1, 1000, null);

    private final OptionInstance<Boolean> checkForUpdates = this.toggle("checkForUpdates", this.config.checkForUpdates);
    private final OptionInstance<Boolean> autoUpdate = this.toggle("autoUpdate", this.config.autoUpdate);
    private final OptionInstance<Integer> updateMessageDelay = this.slider("updateMessageDelay", this.config.updateMessageDelay, 0, 60,
            value -> Component.translatable("config.lodgen.seconds", value));

    public LodConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("config.lodgen.title"));
    }

    @Override
    protected void addOptions() {
        MultiLineTextWidget where = new MultiLineTextWidget(Component.translatable("config.lodgen.where"), this.font);
        where.setMaxWidth(this.list.getRowWidth());
        this.list.addBig(where);

        this.list.addHeader(Component.translatable("config.lodgen.category.generation"));
        this.list.addSmall(this.enabled, this.terrainFirst);
        this.list.addSmall(this.radius, this.featureRadius);
        this.list.addSmall(this.mapRadius, this.viewDistanceMargin);
        this.list.addSmall(this.structures, this.sendSavedChunks);
        this.list.addSmall(this.keepUnderground);

        this.list.addHeader(Component.translatable("config.lodgen.category.performance"));
        this.list.addBig(this.performance);
        this.list.addSmall(this.threads, this.threadsWhilePaused);
        this.list.addSmall(this.tickTimeLimit, this.tileSize);

        this.list.addHeader(Component.translatable("config.lodgen.category.multiplayer"));
        this.list.addSmall(this.sendToPlayers, this.farPlayers);
        this.list.addBig(this.maxChunksPerSecondPerPlayer);

        this.list.addHeader(Component.translatable("config.lodgen.category.updates"));
        this.list.addSmall(this.checkForUpdates, this.autoUpdate);
        this.list.addBig(this.updateMessageDelay);
        this.updateCustomThreads();
    }

    // The thread sliders only count with the Custom level
    private void updateCustomThreads() {
        if (this.list == null) {
            return;
        }
        boolean custom = this.performance.get() == LodPerformance.CUSTOM;
        for (OptionInstance<Integer> option : List.of(this.threads, this.threadsWhilePaused)) {
            AbstractWidget widget = this.list.findOption(option);
            if (widget != null) {
                widget.active = custom;
            }
        }
    }

    @Override
    public void removed() {
        this.config.enabled = this.enabled.get();
        this.config.radius = this.radius.get();
        this.config.featureRadius = this.featureRadius.get();
        this.config.mapRadius = this.mapRadius.get();
        this.config.terrainFirst = this.terrainFirst.get();
        this.config.structures = this.structures.get();
        this.config.sendSavedChunks = this.sendSavedChunks.get();
        this.config.keepUnderground = this.keepUnderground.get();
        this.config.viewDistanceMargin = this.viewDistanceMargin.get();
        this.config.performance = this.performance.get();
        this.config.threads = this.threads.get();
        this.config.threadsWhilePaused = this.threadsWhilePaused.get();
        this.config.tickTimeLimit = this.tickTimeLimit.get();
        this.config.tileSize = this.tileSize.get();
        this.config.sendToPlayers = this.sendToPlayers.get();
        this.config.farPlayers = this.farPlayers.get();
        this.config.maxChunksPerSecondPerPlayer = this.maxChunksPerSecondPerPlayer.get();
        this.config.checkForUpdates = this.checkForUpdates.get();
        this.config.autoUpdate = this.autoUpdate.get();
        this.config.updateMessageDelay = this.updateMessageDelay.get();
        this.config.save();
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            LodGen.restart(server);
        }
        super.removed();
    }

    /** The level with the threads it gives on this machine, e.g. "Balanced (4 threads)" */
    static Component performanceName(LodPerformance level) {
        if (level == LodPerformance.CUSTOM) {
            return Component.translatable(level.key());
        }
        return Component.translatable("config.lodgen.performance.withThreads", Component.translatable(level.key()), level.threads());
    }

    private OptionInstance<Boolean> toggle(String option, boolean value) {
        return OptionInstance.createBoolean("config.lodgen." + option, OptionInstance.cachedConstantTooltip(tooltip(option)), value);
    }

    /** @param label how a value reads, plain numbers without one */
    private OptionInstance<Integer> slider(String option, int value, int min, int max, @Nullable IntFunction<Component> label) {
        return new OptionInstance<>("config.lodgen." + option, OptionInstance.cachedConstantTooltip(tooltip(option)),
                (caption, current) -> Options.genericValueLabel(caption, label == null ? Component.literal(Integer.toString(current)) : label.apply(current)),
                new OptionInstance.IntRange(min, max), Math.clamp(value, min, max), current -> {
                });
    }

    private static Component offAtZero(int value) {
        return value == 0 ? Component.translatable("config.lodgen.off") : Component.literal(Integer.toString(value));
    }

    private static Component tooltip(String option) {
        return Component.translatable("config.lodgen." + option + ".tooltip");
    }
}
