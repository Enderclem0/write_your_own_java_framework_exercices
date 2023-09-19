package com.github.forax.framework.injector;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

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

    private <T> T constructInstance(Class<T> type, Constructor<?> cst) {
        var parameters = Arrays.stream(cst.getParameters())
                .map(t -> lookupInstance(t.getType())).toArray();
        var instance = Utils.newInstance(cst, parameters);
        var props = findInjectableProperties(type);
        for (PropertyDescriptor prop : props) {
            Utils.invokeMethod(instance, prop.getWriteMethod(), lookupInstance(prop.getPropertyType()));
        }
        return type.cast(instance);
    }

    private Optional<Constructor<?>> getInjectedConstructorIfPresent(Class<?> type) {
        var constructors = Arrays.stream(type.getConstructors())
                            .filter(c -> c.isAnnotationPresent(Inject.class))
                            .toList();
        var size = constructors.size();
        if (size > 1) {
            throw new IllegalStateException("More than one injected constructor found");
        }
        return size == 1 ? Optional.of(constructors.get(0)) : Optional.empty();
    }
    
    public <T> void registerProviderClass(Class<T> type, Class<? extends T> cls) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(cls);
        var constructor = getInjectedConstructorIfPresent(cls).orElseGet(() -> Utils.defaultConstructor(cls));
        var present = registry.putIfAbsent(type, () -> constructInstance(type, constructor));
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

    public static List<PropertyDescriptor> findInjectableProperties(Class<?> type) {
        Objects.requireNonNull(type);
        return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
                .filter(propertyDescriptor -> !propertyDescriptor.getName().equals("class"))
                .filter(p -> {
                    var writeMethod = p.getWriteMethod();
                    return writeMethod != null && p.getWriteMethod().isAnnotationPresent(Inject.class);
                })
                .toList();
    }
}