package org.github.forax.framework.interceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public final class InterceptorRegistry {
    private final HashMap<Class<? extends Annotation>, ArrayList<Interceptor>> interceptors = new HashMap<>();

    public void addAroundAdvice(Class<? extends Annotation> annotationType, AroundAdvice advice) {
        Objects.requireNonNull(annotationType);
        Objects.requireNonNull(advice);
        var interceptor = new Interceptor(){
            @Override
            public Object intercept(Object instance, Method method, Object[] args, Invocation invocation) throws Throwable {
                advice.before(instance, method, args);
                Object ret = null;
                try {
                    ret = invocation.proceed(instance, method, args);
                } finally {
                    advice.after(instance, method, args, ret);
                }
                return ret;
            }
        };
        addInterceptor(annotationType, interceptor);
    }

    public void addInterceptor(Class<? extends Annotation> annotationType, Interceptor interceptor) {
        Objects.requireNonNull(annotationType);
        Objects.requireNonNull(interceptor);
        interceptors.computeIfAbsent(annotationType, __ -> new ArrayList<>()).add(interceptor);
    }

    List<Interceptor> findInterceptors(Method m) {
        return Arrays.stream(m.getAnnotations())
                .map(Annotation::annotationType).filter(interceptors::containsKey)
                .flatMap(annotationType -> interceptors.get(annotationType).stream()).toList();
    }

    static Invocation getInvocation(List<? extends Interceptor> interceptors) {
        Invocation invocation = Utils::invokeMethod;
        for (var interceptor : interceptors.reversed()) {
            var previousInvocation = invocation;
            invocation = ((instance, method, args) ->
                    interceptor.intercept(instance, method, args, previousInvocation));
        }
        return invocation;
    }

    public <T> T createProxy(Class<? extends T> interfaceType, T delegate) {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(delegate);
        return interfaceType.cast(Proxy.newProxyInstance(interfaceType.getClassLoader(), new Class<?>[]{interfaceType},
                (Object __, Method method, Object[] args) -> {
                    /*Object ret = null;
                    var advices = findAdvices(method);
                    var invocation = getInvocation(findInterceptors(method));
                    for (var advice : advices) {
                        advice.before(delegate, method, args);
                    }
                    try {
                        ret = Utils.invokeMethod(delegate, method, args);
                    } finally {
                        for (var advice : advices.reversed()) {
                            advice.after(delegate, method, args, ret);
                        }
                    }
                    return ret;*/
                    return getInvocation(findInterceptors(method)).proceed(delegate, method, args);
                }));
    }
}
