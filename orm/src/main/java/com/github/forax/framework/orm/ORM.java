package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
  private ORM() {
    throw new AssertionError();
  }

  private static final ThreadLocal<Connection> currentConnection = new ThreadLocal<>();

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }


  // --- do not change the code above

  static Connection currentConnection() {
    var connection = currentConnection.get();
    if (connection == null) {
      throw new IllegalStateException("no current connection");
    }
    return connection;
  }

  public static void transaction(DataSource source, TransactionBlock block) throws SQLException {
    Objects.requireNonNull(source);
    Objects.requireNonNull(block);
    try(var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      currentConnection.set(connection);
      try {
        block.run();
        connection.commit();
      } catch (SQLException e) {
        try {
          connection.rollback();
        }
        catch (SQLException rollbackException) {
          throw e;
        }
        throw e;
      } finally {
        currentConnection.remove();
      }
    }
  }

  static <T> String findTableName(Class<T> beanType) {
    var annotation = beanType.getAnnotation(Table.class);
    return ((annotation == null)? beanType.getSimpleName(): annotation.value()).toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor property) {
    if (property.getReadMethod() == null) {
      throw new IllegalArgumentException("property " + property.getName() + " has no getter");
    }
    var annotation = property.getReadMethod().getAnnotation(Column.class);
    return ((annotation == null)? property.getName(): annotation.value()).toUpperCase(Locale.ROOT);
  }

  static String findColumnType(PropertyDescriptor property) {
    var type = TYPE_MAPPING.get(property.getPropertyType());
    if (type == null) {
      throw new IllegalArgumentException("cannot map " + property.getPropertyType() + " to a SQL type");
    }
    if (property.getReadMethod() == null) {
      throw new IllegalArgumentException("property " + property.getName() + " has no getter");
    }
    var id = property.getReadMethod().getAnnotation(Id.class);
    var generatedValue = property.getReadMethod().getAnnotation(GeneratedValue.class);
    var notNull = property.getReadMethod().getReturnType().isPrimitive() ? " NOT NULL" : "";
    return type + (generatedValue == null? "": " AUTO_INCREMENT") + notNull
           + (id == null? "": ",\nPRIMARY KEY ("+ findColumnName(property) + ")");
  }

  public static void createTable(Class<?> beanType) throws SQLException {
    Objects.requireNonNull(beanType);
    var tableName = findTableName(beanType);
    var beanInfo = Utils.beanInfo(beanType);

    var createTableSQL = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> !findColumnName(property).equals("CLASS"))
            .map(property -> {
              var columnName = findColumnName(property);
              var columnType = findColumnType(property);

              return columnName + " " + columnType;
            })
            .collect(Collectors.joining(", ", "CREATE TABLE " + tableName + " (", ")"));



    try(var statement = currentConnection().createStatement()) {
      statement.executeUpdate(createTableSQL);
    }
  }

  private static List<?> findAll(Class<?> beanType) throws SQLException {
    Connection connection = currentConnection();
    String sqlQuery = "SELECT * FROM " + findTableName(beanType);
    var beanInfo = Utils.beanInfo(beanType);
    var props = beanInfo.getPropertyDescriptors();
    var list = new ArrayList<>();
    var defaultConstructor = Utils.defaultConstructor(beanType);
    try (PreparedStatement statement = connection.prepareStatement(sqlQuery)) {
      try(ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var instance = Utils.newInstance(defaultConstructor);
          var index = 1;
          for (PropertyDescriptor prop : props) {
            if (prop.getName().equals("class")) {
              continue;
            }
            var value = resultSet.getObject(index++);
            var setter = prop.getWriteMethod();
            Utils.invokeMethod(instance, setter, value);
          }
          list.add(instance);
        }
      }
    }
    return list;
  }

  public static<T extends Repository<?,?>> T createRepository(Class<T> repositoryType) {
    Objects.requireNonNull(repositoryType);
    var beanType = findBeanTypeFromRepository(repositoryType);
    return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType},
            (Object proxy, Method method, Object[] args) -> {
      try {
        return switch (method.getName()) {
          case "findAll" -> findAll(beanType);
          case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException("");
          default -> throw new IllegalStateException("Unexpected value: " + method.getName());
        };
      }
      catch (SQLException e) {
        throw new UncheckedSQLException(e);
      }
    }));
  }
}
