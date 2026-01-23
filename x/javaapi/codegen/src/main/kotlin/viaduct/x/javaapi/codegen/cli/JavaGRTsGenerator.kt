package viaduct.x.javaapi.codegen.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import viaduct.x.javaapi.codegen.JavaGRTsCodegen

/**
 * CLI entry point for generating Java GRTs (GraphQL Representational Types) from GraphQL schemas.
 */
class JavaGRTsGenerator : CliktCommand(
    name = "java-grts-generator",
    help = "Generates Java GRTs (GraphQL Representational Types) from GraphQL schema files"
) {
    private val schemaFiles: List<File> by option("--schema_files", help = "Comma-separated list of GraphQL schema files")
        .file(mustExist = true, canBeDir = false)
        .split(",")
        .required()

    private val outputDir: File by option("--output_dir", help = "Output directory for generated Java files")
        .file(mustExist = false, canBeFile = false)
        .required()

    private val packageName: String by option("--package", help = "Java package name for generated types")
        .required()

    private val verbose: Boolean by option("--verbose", help = "Print generation results")
        .flag()

    override fun run() {
        val codegen = JavaGRTsCodegen()
        val result = codegen.generate(schemaFiles, outputDir, packageName)

        if (verbose) {
            for (file in result.generatedFiles()) {
                echo("Generated: $file")
            }

            echo("Generated ${result.totalCount()} types to ${outputDir.absolutePath}:")
            echo("  - ${result.enumCount()} enum(s)")
            echo("  - ${result.objectCount()} object(s)")
            echo("  - ${result.inputCount()} input(s)")
            echo("  - ${result.interfaceCount()} interface(s)")
            echo("  - ${result.unionCount()} union(s)")
        }
    }

    object Main {
        @JvmStatic
        fun main(args: Array<String>) = JavaGRTsGenerator().main(args)
    }
}
