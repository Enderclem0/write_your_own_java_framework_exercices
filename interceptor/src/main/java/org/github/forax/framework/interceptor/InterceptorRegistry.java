package org.github.forax.framework.interceptor;

import jdk.jshell.execution.Util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class InterceptorRegistry {
    private final HashMap<Class<? extends Annotation>, ArrayList<AroundAdvice>> aroundAdvices = new HashMap<>();
    private final HashMap<Class<? extends Annotation>, ArrayList<Interceptor>> interceptors = new HashMap<>();

    public void addAroundAdvice(Class<? extends Annotation> annotationType, AroundAdvice advice) {
        Objects.requireNonNull(annotationType);
        Objects.requireNonNull(advice);
        aroundAdvices.computeIfAbsent(annotationType, k -> new ArrayList<>()).add(advice);
    }

    public void addInterceptor(Class<? extends Annotation> annotationType, Interceptor interceptor) {
        Objects.requireNonNull(annotationType);
        Objects.requireNonNull(interceptor);
        interceptors.computeIfAbsent(annotationType, k -> new ArrayList<>()).add(interceptor);
    }

    List<AroundAdvice> findAdvices(Method m) {
        return Arrays.stream(m.getAnnotations())
                .map(Annotation::annotationType).filter(aroundAdvices::containsKey)
                .flatMap(annotationType -> aroundAdvices.get(annotationType).stream()).toList();
    }

    List<Interceptor> findInterceptors(Method m) {
        return Arrays.stream(m.getAnnotations())
                .map(Annotation::annotationType).filter(interceptors::containsKey)
                .flatMap(annotationType -> interceptors.get(annotationType).stream()).toList();
    }

    public <T> T createProxy(Class<? extends T> interfaceType, T delegate) {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(delegate);
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class<?>[]{interfaceType},
                (Object __, Method method, Object[] args) -> {
                    Object ret = null;
                    var advices = findAdvices(method);
                    var interceptors = findInterceptors(method);
                    for (var advice : advices) {
                        advice.before(delegate, method, args);
                    }
                    try {
                        ret = Utils.invokeMethod(delegate, method, args);
                    } finally {
                        for (var advice : advices) {
                            advice.after(delegate, method, args, ret);
                        }
                    }
                    return ret;
                }));
    }
}
