package com.github.forax.framework.mapper;

public final class JSONWriter {
  public String toJSON(Object o) {
    return switch (o) {
      case null -> "null";
      case Boolean b ->  b.toString();
      case Integer i -> i.toString();
      case Double d -> d.toString();
      case Float f -> f.toString();
      case String s -> '"'+s+'"';
      default -> throw new IllegalStateException("Unexpected value: " + o);
    };
  }
}
