package io.github.octomiku01.lodgen;

/**
 * A thread that builds LODs. Vanilla hands biomes and terrain to its own background pool, which is shared with the
 * real chunks and sized by the cores, not by the {@code threads} setting; on these threads that work runs right where
 * it is asked for instead, see {@link io.github.octomiku01.lodgen.mixin.GeneratorExecutorMixin}.
 */
public final class LodWorkerThread extends Thread {
    LodWorkerThread(Runnable task, String name) {
        super(task, name);
        this.setDaemon(true);
    }

    public static boolean isCurrent() {
        return Thread.currentThread() instanceof LodWorkerThread;
    }
}
