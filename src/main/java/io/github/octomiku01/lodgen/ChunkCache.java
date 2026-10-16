package io.github.octomiku01.lodgen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * Remembers something computed per chunk, for all workers. Memory stays bounded: when it is full, what was not asked
 * for since the last time it was full is forgotten. The workers move outward in rings, so what they still need is
 * what they used recently.
 */
final class ChunkCache<V> {
    private final int capacity;
    private volatile Map<Long, V> recent = new ConcurrentHashMap<>();
    private volatile Map<Long, V> older = Map.of();

    ChunkCache(int capacity) {
        this.capacity = capacity;
    }

    V get(long key, LongFunction<V> compute) {
        Map<Long, V> recent = this.recent;
        V value = recent.get(key);
        if (value != null) {
            return value;
        }
        value = this.older.get(key);
        if (value == null) {
            value = compute.apply(key);
        }
        if (recent.size() >= this.capacity) {
            this.rotate(recent);
        }
        V raced = this.recent.putIfAbsent(key, value);
        return raced != null ? raced : value;
    }

    private synchronized void rotate(Map<Long, V> full) {
        // Another worker may have rotated it already
        if (this.recent == full) {
            this.older = full;
            this.recent = new ConcurrentHashMap<>();
        }
    }
}
