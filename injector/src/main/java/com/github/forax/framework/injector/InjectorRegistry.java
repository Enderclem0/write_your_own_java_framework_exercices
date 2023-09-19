package com.github.forax.framework.injector;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.Supplier;

public final class InjectorRegistry {
    private final HashMap<Class<?>, Supplier<?>> registry = new HashMap<>();

    public <T> void registerInstance(Class<T> type, T instance) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(instance);
        var present = registry.putIfAbsent(type, () -> instance);
        if (present != null) {
            throw new IllegalStateException("type " + type + " is already registered");
        }
    }

    public <T> void registerProvider(Class<T> type, Supplier<T> supplier) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        var present = registry.putIfAbsent(type, supplier);
        if (present != null) {
            throw new IllegalStateException("type " + type + " is already registered");
        }
    }
    public <T> T lookupInstance(Class<T> type) {
        Objects.requireNonNull(type);
        var present = registry.get(type);
        if (present == null) {
            throw new IllegalStateException("type " + type + " is not registered");
        }
        return type.cast(present.get());
    }
}