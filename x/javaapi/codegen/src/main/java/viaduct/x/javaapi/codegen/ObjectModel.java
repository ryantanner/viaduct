package viaduct.x.javaapi.codegen;

import java.util.List;

/** Model representing a GraphQL object type for code generation. */
public record ObjectModel(
    String packageName,
    String className,
    List<String> implementedInterfaces,
    List<FieldModel> fields,
    String description) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getPackageName() {
    return packageName;
  }

  public String getClassName() {
    return className;
  }

  public List<String> getImplementedInterfaces() {
    return implementedInterfaces;
  }

  public List<FieldModel> getFields() {
    return fields;
  }

  public String getDescription() {
    return description;
  }

  public boolean getHasDescription() {
    return description != null && !description.isEmpty();
  }

  public boolean getHasInterfaces() {
    return implementedInterfaces != null && !implementedInterfaces.isEmpty();
  }

  /**
   * Returns the implements clause for the class declaration. Always includes GraphQLObject, plus
   * any implemented interfaces.
   */
  public String getImplementsClause() {
    StringBuilder sb = new StringBuilder("GraphQLObject");
    if (implementedInterfaces != null) {
      for (String iface : implementedInterfaces) {
        sb.append(", ").append(iface);
      }
    }
    return sb.toString();
  }
}
