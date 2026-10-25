package io.github.octomiku01.lodgen;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * The settings screen in Mod Menu, built like vanilla's option screens with tabs like the world creation screen, so no
 * other mod is needed for it. Switching tabs only refills the list; the options keep what was set. Closing it
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
    private final OptionInstance<Integer> updateMessageDelay = this.slider("updateMessageDelay", this.config.updateMessageDelay, 0, 60,
            value -> Component.translatable("config.lodgen.seconds", value));

    private final List<Tab> tabs = List.of(
            new GridLayoutTab(Component.translatable("config.lodgen.category.generation")),
            new GridLayoutTab(Component.translatable("config.lodgen.category.performance")),
            new GridLayoutTab(Component.translatable("config.lodgen.category.multiplayer")),
            new GridLayoutTab(Component.translatable("config.lodgen.category.updates")));
    private final TabManager tabManager = new TabManager(widget -> {
    }, widget -> {
    }, this::tabSelected, tab -> {
    });
    private @Nullable TabNavigationBar tabBar;
    private int tab;

    public LodConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("config.lodgen.title"));
    }

    // The tabs take the place of the title
    @Override
    protected void addTitle() {
        this.tabBar = MenuTabBar.builder(this.tabManager, this.width).addTabs(this.tabs.toArray(Tab[]::new)).build();
        this.addRenderableWidget(this.tabBar);
    }

    @Override
    protected void addContents() {
        this.list = this.layout.addToContents(new TabList(this));
        this.addOptions();
        if (this.tabBar != null) {
            this.tabBar.selectTab(this.tab, false);
        }
    }

    private void tabSelected(Tab selected) {
        int index = this.tabs.indexOf(selected);
        if (index != this.tab && this.list instanceof TabList list) {
            this.tab = index;
            list.clear();
            this.addOptions();
            list.setScrollAmount(0);
        }
    }

    @Override
    protected void repositionElements() {
        if (this.tabBar != null) {
            this.tabBar.arrangeElements(this.width);
            this.layout.setHeaderHeight(this.tabBar.getRectangle().bottom());
        }
        super.repositionElements();
    }

    @Override
    protected void addOptions() {
        switch (this.tab) {
            case 0 -> {
                ((TabList) this.list).addText(Component.translatable("config.lodgen.where"));
                this.list.addSmall(this.enabled, this.terrainFirst);
                this.list.addSmall(this.radius, this.featureRadius);
                this.list.addSmall(this.mapRadius, this.viewDistanceMargin);
                this.list.addSmall(this.structures, this.sendSavedChunks);
                this.list.addSmall(this.keepUnderground);
            }
            case 1 -> {
                this.list.addBig(this.performance);
                this.list.addSmall(this.threads, this.threadsWhilePaused);
                this.list.addBig(this.tickTimeLimit);
                this.list.addBig(this.tileSize);
            }
            case 2 -> {
                this.list.addSmall(this.sendToPlayers, this.farPlayers);
                this.list.addBig(this.maxChunksPerSecondPerPlayer);
            }
            default -> {
                this.list.addBig(this.checkForUpdates);
                this.list.addBig(this.updateMessageDelay);
            }
        }
        this.updateCustomThreads();
    }

    // Reset and Done side by side, instead of Done alone
    @Override
    protected void addFooter() {
        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.translatable("config.lodgen.reset"), button -> this.resetTab())
                .tooltip(Tooltip.create(Component.translatable("config.lodgen.reset.tooltip"))).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(150).build());
    }

    /** Puts the options of the open tab back to their defaults; like every change, it is saved when leaving */
    private void resetTab() {
        LodConfig defaults = new LodConfig();
        switch (this.tab) {
            case 0 -> {
                this.enabled.set(defaults.enabled);
                this.terrainFirst.set(defaults.terrainFirst);
                this.radius.set(defaults.radius);
                this.featureRadius.set(defaults.featureRadius);
                this.mapRadius.set(defaults.mapRadius);
                this.viewDistanceMargin.set(defaults.viewDistanceMargin);
                this.structures.set(defaults.structures);
                this.sendSavedChunks.set(defaults.sendSavedChunks);
                this.keepUnderground.set(defaults.keepUnderground);
            }
            case 1 -> {
                this.performance.set(defaults.performance);
                this.threads.set(Math.min(defaults.threads, LodPerformance.CORES));
                this.threadsWhilePaused.set(Math.min(defaults.threadsWhilePaused, LodPerformance.CORES));
                this.tickTimeLimit.set(defaults.tickTimeLimit);
                this.tileSize.set(defaults.tileSize);
            }
            case 2 -> {
                this.sendToPlayers.set(defaults.sendToPlayers);
                this.farPlayers.set(defaults.farPlayers);
                this.maxChunksPerSecondPerPlayer.set(defaults.maxChunksPerSecondPerPlayer);
            }
            default -> {
                this.checkForUpdates.set(defaults.checkForUpdates);
                this.updateMessageDelay.set(defaults.updateMessageDelay);
            }
        }
        // The buttons show the values they were made with
        if (this.list instanceof TabList list) {
            double scroll = list.scrollAmount();
            list.clear();
            this.addOptions();
            list.setScrollAmount(scroll);
        }
    }

    /** The list of the options screen, emptied when another tab is chosen */
    private static final class TabList extends OptionsList {
        private final LodConfigScreen screen;

        TabList(LodConfigScreen screen) {
            super(Minecraft.getInstance(), screen.width, screen);
            this.screen = screen;
        }

        void clear() {
            this.clearEntries();
        }

        /** A paragraph in one entry as tall as its lines, instead of a row per line */
        void addText(Component text) {
            MultiLineTextWidget widget = new MultiLineTextWidget(text, this.screen.getFont()).setMaxWidth(this.getRowWidth());
            this.addEntry(Entry.big(widget, this.screen), widget.getHeight() + 6);
        }
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
