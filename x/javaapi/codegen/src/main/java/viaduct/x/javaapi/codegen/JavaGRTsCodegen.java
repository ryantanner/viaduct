package viaduct.x.javaapi.codegen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import viaduct.graphql.schema.ViaductSchema;

/**
 * Main entry point for Java GRTs (GraphQL Representational Types) code generation. This class
 * handles the generation logic and can be called from CLI or programmatically. Uses ViaductSchema
 * as the schema abstraction layer.
 */
public class JavaGRTsCodegen {

  private final GraphQLSchemaParser parser;

  public JavaGRTsCodegen() {
    this.parser = new GraphQLSchemaParser();
  }

  /** Result of the code generation process. */
  public record Result(
      int enumCount,
      int objectCount,
      int inputCount,
      int interfaceCount,
      int unionCount,
      List<File> generatedFiles) {
    public int totalCount() {
      return enumCount + objectCount + inputCount + interfaceCount + unionCount;
    }
  }

  /**
   * Generates Java GRTs from GraphQL schema files.
   *
   * @param schemaFiles list of GraphQL schema files to parse
   * @param outputDir output directory for generated Java files
   * @param packageName Java package name for generated types
   * @return result containing counts of generated types
   * @throws IOException if there's an error reading or writing files
   */
  public Result generate(List<File> schemaFiles, File outputDir, String packageName)
      throws IOException {
    // Ensure output directory exists
    if (!outputDir.exists() && !outputDir.mkdirs()) {
      throw new IOException("Failed to create output directory: " + outputDir);
    }

    // Parse schemas into ViaductSchema
    ViaductSchema schema = parser.parse(schemaFiles);

    List<File> generatedFiles = new ArrayList<>();

    // Generate enums
    List<EnumModel> enumModels = parser.extractEnums(schema, packageName);
    for (EnumModel model : enumModels) {
      generatedFiles.add(JavaGRTGenerator.EnumGenerator.generateToFile(model, outputDir));
    }

    // Generate objects
    List<ObjectModel> objectModels = parser.extractObjects(schema, packageName);
    for (ObjectModel model : objectModels) {
      generatedFiles.add(JavaGRTGenerator.ObjectGenerator.generateToFile(model, outputDir));
    }

    // Generate inputs
    List<InputModel> inputModels = parser.extractInputs(schema, packageName);
    for (InputModel model : inputModels) {
      generatedFiles.add(JavaGRTGenerator.InputGenerator.generateToFile(model, outputDir));
    }

    // Generate interfaces
    List<InterfaceModel> interfaceModels = parser.extractInterfaces(schema, packageName);
    for (InterfaceModel model : interfaceModels) {
      generatedFiles.add(JavaGRTGenerator.InterfaceGenerator.generateToFile(model, outputDir));
    }

    // Generate unions
    List<UnionModel> unionModels = parser.extractUnions(schema, packageName);
    for (UnionModel model : unionModels) {
      generatedFiles.add(JavaGRTGenerator.UnionGenerator.generateToFile(model, outputDir));
    }

    return new Result(
        enumModels.size(),
        objectModels.size(),
        inputModels.size(),
        interfaceModels.size(),
        unionModels.size(),
        generatedFiles);
  }
}
