package io.github.roberbarras.racha.identity.adapter.security;

import io.github.roberbarras.racha.identity.domain.IdentityPorts;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counter per key (email, client address); the limit depends on the key's kind (the part before ':'). A single self-hosted server needs nothing distributed; the
 * map is pruned as it grows so an attacker cannot fill memory with random keys.
 */
public final class InMemoryAttemptLimiter implements IdentityPorts.AttemptLimiter {
    private record Window(Instant start, int failures) {}

    private final java.util.Map<String, Integer> maxByKind;
    private final Duration window;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryAttemptLimiter(java.util.Map<String, Integer> maxByKind, Duration window) {
        this.maxByKind = java.util.Map.copyOf(maxByKind);
        this.window = window;
    }

    private int max(String key) {
        return maxByKind.getOrDefault(key.substring(0, Math.max(0, key.indexOf(':'))), 10);
    }

    @Override
    public boolean allowed(String key, Instant now) {
        var current = windows.get(key);
        return current == null || expired(current, now) || current.failures() < max(key);
    }

    @Override
    public void failed(String key, Instant now) {
        if (windows.size() > 100_000) windows.values().removeIf(w -> expired(w, now));
        windows.merge(key, new Window(now, 1), (old, fresh) -> expired(old, now) ? fresh : new Window(old.start(), old.failures() + 1));
    }

    @Override
    public void succeeded(String key) {
        windows.remove(key);
    }

    private boolean expired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(window));
    }
}
