package com.github.forax.framework.mapper;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class JSONWriter {
  @FunctionalInterface
  private interface Generator {
    String generate(JSONWriter writer, Object bean);
  }

  private static final ClassValue<List<Generator>> GEN_CACHE = new ClassValue<>() {
      @Override
      protected List<Generator> computeValue(Class<?> type) {
          return Arrays.stream(Utils.beanInfo(type).getPropertyDescriptors())
                  .filter(propertyDescriptor -> !propertyDescriptor.getName().equals("class"))
                  .<Generator>map(p -> {
                      String key;
                      var readMethod = p.getReadMethod();
                      var annotation = readMethod.getAnnotation(JSONProperty.class);
                      if (annotation != null) {
                          key = "\"" + annotation.value() + "\": ";
                      }
                      else {
                          key = "\"" + p.getName() + "\": ";
                      }
                      return (writer, o) -> key + writer.toJSON(Utils.invokeMethod(o, readMethod));
                  })
                  .toList();
      }
  };

  private String instanceToJson(Object o){
    return GEN_CACHE.get(o.getClass()).stream()
            .map(generator -> generator.generate(this, o))
            .collect(Collectors.joining(", ", "{", "}"));
  }

  public String toJSON(Object o){
    return switch (o) {
      case null -> "null";
      case Boolean b ->  b.toString();
      case Integer i -> i.toString();
      case Double d -> d.toString();
      case Float f -> f.toString();
      case String s -> '"'+s+'"';
      default -> instanceToJson(o);
    };
  }
}
