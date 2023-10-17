package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
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

  static PropertyDescriptor findProperty(BeanInfo beanInfo, String id) {
      return Arrays.stream(beanInfo.getPropertyDescriptors())
        .filter(prop -> prop.getName().equals(Introspector.decapitalize(id)))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No property found"));
  }

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
        try{
          block.run();
        }
        catch (UncheckedSQLException e) {
          throw e.getCause();
        }
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

  static Object toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    Object instance = Utils.newInstance(constructor);
    var props = beanInfo.getPropertyDescriptors();
    var index = 1;
    for (PropertyDescriptor prop : props) {
      if (prop.getName().equals("class")) {
        continue;
      }
      var value = resultSet.getObject(index++);
      var setter = prop.getWriteMethod();
      Utils.invokeMethod(instance, setter, value);
    }
    return instance;
  }

  static List<?> findAll(Class<?> beanType) throws SQLException {
    Connection connection = currentConnection();
    String sqlQuery = "SELECT * FROM " + findTableName(beanType);
    var beanInfo = Utils.beanInfo(beanType);
    var defaultConstructor = Utils.defaultConstructor(beanType);
    return findAll(connection, sqlQuery, beanInfo, defaultConstructor);
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo) {
    var props = beanInfo.getPropertyDescriptors();
    var columns = Arrays.stream(props)
            .filter(prop -> !findColumnName(prop).equals("CLASS"))
            .map(ORM::findColumnName)
            .collect(Collectors.joining(", "));
    var values = Arrays.stream(props)
            .filter(prop -> !findColumnName(prop).equals("CLASS"))
            .map(e -> "?")
            .collect(Collectors.joining(", "));
    return "MERGE INTO " + tableName + " (" + columns + ") VALUES (" + values + ");";
  }

  static Object save(Connection connection, String tableName, BeanInfo beanInfo, Object bean, PropertyDescriptor idProperty) throws SQLException {
    try(PreparedStatement statement = connection.prepareStatement(createSaveQuery(tableName,beanInfo), Statement.RETURN_GENERATED_KEYS)){
      var index = 1;
      for (PropertyDescriptor prop : beanInfo.getPropertyDescriptors()) {
        if (prop.getName().equals("class")) {
          continue;
        }
        var value = Utils.invokeMethod(bean, prop.getReadMethod());
        statement.setObject(index++, value);
      }
      statement.executeUpdate();
      if (idProperty != null) {
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
          if (resultSet.next()) {
            var id = resultSet.getObject(1);
            Utils.invokeMethod(bean, idProperty.getWriteMethod(), id);
          }
        }
      }
    }
    return bean;
  }

  static List<?> findAll(Connection connection, String query, BeanInfo beanInfo, Constructor<?> constructor) throws SQLException {
    return findAll(connection, query, beanInfo, constructor, new Object[0]);
  }

  static List<?> findAll(Connection connection, String query, BeanInfo beanInfo, Constructor<?> constructor, Object[] args) throws SQLException {
    var list = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(query)) {
      var index = 1;
      for (Object arg : args) {
        statement.setObject(index++, arg);
      }
      try(ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          var instance = toEntityClass(resultSet, beanInfo, constructor);
          list.add(instance);
        }
      }
    }
    return list;
  }

  static PropertyDescriptor findId(BeanInfo beanInfo) {
    var propertiesId = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(prop -> prop.getReadMethod().getAnnotation(Id.class) != null)
            .toList();
    return switch (propertiesId.size()) {
      case 0 -> null;
      case 1 -> propertiesId.get(0);
      default -> throw new IllegalStateException("Unexpected value: " + propertiesId.size());
    };
  }

  public static<T extends Repository<?,?>> T createRepository(Class<T> repositoryType) {
    Objects.requireNonNull(repositoryType);
    var beanType = findBeanTypeFromRepository(repositoryType);
    var tableName = findTableName(beanType);
    var beanInfo = Utils.beanInfo(beanType);
    return repositoryType.cast(Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[]{repositoryType},
            (Object proxy, Method method, Object[] args) -> {
      try {
        if (method.isAnnotationPresent(Query.class)) {
          var query = method.getAnnotation(Query.class).value();
          var constructor = Utils.defaultConstructor(beanType);
          var argsToPass = args == null ? new Object[0] : args;
          var call = findAll(currentConnection(), query, beanInfo, constructor, argsToPass);
          if (method.getReturnType() == List.class) {
            return call;
          }
          return call.stream().findFirst();
        }
        var name = method.getName();
        if (name.startsWith("findBy") && !name.equals("findById")) {
          var property = findProperty(beanInfo, name.substring(6));
          var query = "SELECT * FROM " + tableName + " WHERE " + property.getName() + " = ?";
          var constructor = Utils.defaultConstructor(beanType);
          return findAll(currentConnection(), query, beanInfo, constructor, new Object[]{args[0]}).stream().findFirst();
        }
        return switch (method.getName()) {
          case "findAll" -> findAll(beanType);
          case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException("");
          case "findById" -> {
            var id = args[0];
            var idProperty = findId(beanInfo);
            var query = "SELECT * FROM " + tableName + " WHERE " + findColumnName(idProperty) + " = ?";
            var constructor = Utils.defaultConstructor(beanType);
            yield findAll(currentConnection(), query, beanInfo, constructor, new Object[]{id}).stream().findFirst();
          }
          case "save" -> {
            var bean = args[0];
            var idProperty = findId(beanInfo);
            yield save(currentConnection(), tableName, beanInfo, bean, idProperty);
          }
          default -> throw new IllegalStateException("Unexpected value: " + method.getName());
        };
      }
      catch (SQLException e) {
        throw new UncheckedSQLException(e);
      }
    }));
  }
}
