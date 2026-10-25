package io.github.octomiku01.lodgen;

/**
 * How much of the CPU LOD generation may use, as a share of the cores this machine has. The workers run at the lowest
 * priority, so the game is still preferred over them; the levels decide how many cores they may fill at most.
 */
enum LodPerformance {
    VERY_LOW,
    LOW,
    BALANCED,
    FAST,
    /** All but two cores, which stay free for the game (server thread and rendering) */
    FULL,
    /** The threads and threadsWhilePaused settings */
    CUSTOM;

    static final int CORES = Runtime.getRuntime().availableProcessors();

    /** Worker threads while playing; not used for CUSTOM */
    int threads() {
        return switch (this) {
            case VERY_LOW -> 1;
            case LOW -> Math.max(1, CORES / 8);
            case BALANCED, CUSTOM -> Math.max(1, CORES / 4);
            case FAST -> Math.max(1, CORES / 2);
            case FULL -> Math.max(1, CORES - 2);
        };
    }

    /** While the single player game is paused it needs no CPU time: at least as many threads as FAST */
    int threadsWhilePaused() {
        return Math.max(this.threads(), FAST.threads());
    }

    /** The key of its name in the language files */
    String key() {
        return "config.lodgen.performance." + this.name().toLowerCase(java.util.Locale.ROOT);
    }
}
