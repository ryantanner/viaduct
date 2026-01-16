package viaduct.graphql.schema.graphqljava.extensions

import graphql.schema.GraphQLSchema
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.net.URL
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.utils.timer.Timer

/**
 * Creates a [GraphQLSchema] from SDL and uses it to create a
 * [ViaductSchema].
 */
fun ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<URL>,): ViaductSchema = GJSchema.fromURLs(inputFiles)

/**
 * Creates a [GraphQLSchema] from SDL and uses it to create a
 * [ViaductSchema].
 */
fun ViaductSchema.Companion.fromGraphQLSchema(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): ViaductSchema = GJSchema.fromFiles(inputFiles, timer)

/**
 * Creates a [GraphQLSchema] from [registry] and uses it to
 * create a [ViaductSchema].
 */
fun ViaductSchema.Companion.fromGraphQLSchema(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): ViaductSchema = GJSchema.fromRegistry(registry, timer)

/** Creates a [ViaductSchema] from a [GraphQLSchema]. */
fun ViaductSchema.Companion.fromGraphQLSchema(schema: GraphQLSchema,): ViaductSchema = GJSchema.fromSchema(schema)

/**
 * Creates a [TypeDefinitionRegistry] from SDL and uses it to
 * create a [ViaductSchema].
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<URL>,): ViaductSchema = GJSchemaRaw.fromURLs(inputFiles)

/**
 * Creates a [TypeDefinitionRegistry] from SDL and uses it to
 * create a [ViaductSchema].
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): ViaductSchema = GJSchemaRaw.fromFiles(inputFiles, timer)

/**
 * Create a [ViaductSchema] from a [TypeDefinitionRegistry]
 */
fun ViaductSchema.Companion.fromTypeDefinitionRegistry(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): ViaductSchema = GJSchemaRaw.fromRegistry(registry, timer)
