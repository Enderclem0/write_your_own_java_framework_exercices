package com.github.forax.framework.mapper;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public final class JSONWriter {
  private final ClassValue<PropertyDescriptor[]> pdCache = new ClassValue<>() {
      @Override
      protected PropertyDescriptor[] computeValue(Class<?> type) {
          try {
              return Introspector.getBeanInfo(type).getPropertyDescriptors();
          } catch (IntrospectionException e) {
              throw new IllegalArgumentException("Unsupported class");
          }
      }
  };

  private String propertyToJson(PropertyDescriptor pd, Object o) {
    return '"'+pd.getName()+ "\": "+toJSON(Utils.invokeMethod(o, pd.getReadMethod()));
  }

  private String instanceToJson(Object o){
    var pds = pdCache.get(o.getClass());
    return Arrays.stream(pds)
            .filter(Objects::nonNull)
            .filter(p -> !p.getName().equals("class"))
            .map(p -> propertyToJson(p, o))
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
