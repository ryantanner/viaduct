@file:Suppress("ktlint:standard:indent")

package viaduct.graphql.schema.graphqljava

import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedSchemaElement
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import java.net.URL
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector
import viaduct.utils.timer.Timer

/**
 * Factory functions for creating [SchemaWithData] from graphql-java [GraphQLSchema].
 *
 * This is the validated schema path - [GraphQLSchema] objects are fully
 * validated by graphql-java, making this the safest option but also
 * slower to construct than the "raw" path (see [gjSchemaRawFromRegistry]).
 *
 * The auxiliary data stored in [SchemaWithData.Def.data] is the corresponding
 * graphql-java schema element (e.g., [GraphQLObjectType], [GraphQLFieldDefinition], etc.).
 *
 * Use factory functions like [gjSchemaFromSchema], [gjSchemaFromRegistry],
 * [gjSchemaFromFiles], or [gjSchemaFromURLs] to create instances.
 */

/** Convert collection of .graphqls files into a schema. */
fun gjSchemaFromURLs(inputFiles: List<URL>): SchemaWithData = gjSchemaFromRegistry(readTypesFromURLs(inputFiles))

fun gjSchemaFromFiles(
    inputFiles: List<File>,
    timer: Timer = Timer(),
): SchemaWithData {
    val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(inputFiles) }
    return gjSchemaFromRegistry(typeDefRegistry, timer)
}

/** Convert a graphql-java TypeDefinitionRegistry into a schema. */
fun gjSchemaFromRegistry(
    registry: TypeDefinitionRegistry,
    timer: Timer = Timer(),
): SchemaWithData {
    val unexecutableSchema =
        timer.time("makeUnexecutableSchema") {
            UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
        }
    return timer.time("fromSchema") { gjSchemaFromSchema(unexecutableSchema) }
}

/** Create a ViaductSchema from a validated graphql-java schema. */
fun gjSchemaFromSchema(schema: GraphQLSchema): SchemaWithData {
    // Phase 1: Create all TypeDef and Directive shells (just underlying def and name in data)
    val types = mutableMapOf<String, SchemaWithData.TypeDef>()
    for (def in schema.allTypesAsList) {
        val typeDef = when (def) {
            is GraphQLScalarType -> SchemaWithData.Scalar(def.name, def)
            is GraphQLEnumType -> SchemaWithData.Enum(def.name, def)
            is GraphQLUnionType -> SchemaWithData.Union(def.name, def)
            is GraphQLInterfaceType -> SchemaWithData.Interface(def.name, def)
            is GraphQLObjectType -> SchemaWithData.Object(def.name, def)
            is GraphQLInputObjectType -> SchemaWithData.Input(def.name, def)
            else -> throw RuntimeException("Unexpected GraphQL type: $def")
        }
        types[def.name] = typeDef
    }

    val directives = schema.directives.associate { it.name to SchemaWithData.Directive(it.name, it) }

    // Phase 2: Create decoder and populate all types and directives
    val decoder = GraphQLSchemaDecoder(schema, types)

    types.values.forEach { typeDef ->
        when (typeDef) {
            is SchemaWithData.Scalar -> typeDef.populate(decoder.createScalarExtensions(typeDef))
            is SchemaWithData.Enum -> typeDef.populate(decoder.createEnumExtensions(typeDef))
            is SchemaWithData.Union -> typeDef.populate(decoder.createUnionExtensions(typeDef))
            is SchemaWithData.Interface -> typeDef.populate(
                decoder.createInterfaceExtensions(typeDef),
                decoder.computePossibleObjectTypes(typeDef)
            )
            is SchemaWithData.Object -> typeDef.populate(
                decoder.createObjectExtensions(typeDef),
                decoder.computeUnions(typeDef)
            )
            is SchemaWithData.Input -> typeDef.populate(decoder.createInputExtensions(typeDef))
        }
    }

    directives.values.forEach { directive ->
        decoder.populate(directive)
    }

    // Determine root types
    val queryTypeDef = rootDef(types, schema.queryType?.name, "Query")
        ?: throw IllegalStateException("Query name (${schema.queryType?.name}) not found.")
    val mutationTypeDef = rootDef(types, schema.mutationType?.name, "Mutation")
    val subscriptionTypeDef = rootDef(types, schema.subscriptionType?.name, "Subscription")

    return SchemaWithData(directives, types, queryTypeDef, mutationTypeDef, subscriptionTypeDef)
}

private fun rootDef(
    types: Map<String, SchemaWithData.TypeDef>,
    name: String?,
    stdName: String
): SchemaWithData.Object? {
    val result = name?.let { types[it] }
    if (result != null) {
        require(result is SchemaWithData.Object) { "$stdName type ($name) is not an object type." }
        return result
    }
    return null
}

// Extension function toTypeExpr(wrappers, baseString) is provided by Utils.kt

// Internal for testing (GJSchemaCheck)
internal fun SchemaWithData.toTypeExpr(gtype: GraphQLType): ViaductSchema.TypeExpr<SchemaWithData.TypeDef> {
    var baseTypeNullable = true
    var listNullable = ViaductSchema.TypeExpr.NO_WRAPPERS

    var t = gtype
    if (GraphQLTypeUtil.isWrapped(t)) {
        val wrapperBuilder = BitVector.Builder()
        do {
            if (GraphQLTypeUtil.isList(t)) {
                wrapperBuilder.add(1L, 1)
                t = GraphQLTypeUtil.unwrapOne(t)
            } else if (GraphQLTypeUtil.isNonNull(t)) {
                t = GraphQLTypeUtil.unwrapOne(t)
                if (GraphQLTypeUtil.isList(t)) {
                    wrapperBuilder.add(0L, 1)
                    t = GraphQLTypeUtil.unwrapOne(t)
                } else if (GraphQLTypeUtil.isWrapped(t)) {
                    throw IllegalStateException("Unexpected GraphQL wrapping $gtype.")
                } else {
                    baseTypeNullable = false
                }
            } else {
                throw IllegalStateException("Unexpected GraphQL wrapper $gtype.")
            }
        } while (GraphQLTypeUtil.isWrapped(t))
        listNullable = wrapperBuilder.build()
    }

    val baseTypeDefName = GraphQLTypeUtil.unwrapAll(gtype).name
    val baseTypeDef = types[baseTypeDefName]
        ?: error("Type not found: $baseTypeDefName")
    return ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, listNullable)
}

//
// Type-safe extension properties for accessing the underlying graphql-java schema types.
// These use "gj" prefix to avoid conflicts with GJSchemaRawExtensions.kt which defines
// "def" properties for accessing graphql-java language types.
//

/** The underlying graphql-java element for any Def. */
val SchemaWithData.Def.gjDef: GraphQLNamedSchemaElement
    get() = data as GraphQLNamedSchemaElement

/** The underlying graphql-java element for any Def. */
val SchemaWithData.TypeDef.gjDef: GraphQLNamedType
    get() = data as GraphQLNamedType

/** The underlying GraphQLScalarType. */
val SchemaWithData.Scalar.gjDef: GraphQLScalarType
    get() = data as GraphQLScalarType

/** The underlying GraphQLEnumType. */
val SchemaWithData.Enum.gjDef: GraphQLEnumType
    get() = data as GraphQLEnumType

/** The underlying GraphQLEnumValueDefinition. */
val SchemaWithData.EnumValue.gjDef: GraphQLEnumValueDefinition
    get() = data as GraphQLEnumValueDefinition

/** The underlying GraphQLUnionType. */
val SchemaWithData.Union.gjDef: GraphQLUnionType
    get() = data as GraphQLUnionType

/** The underlying GraphQLInterfaceType. */
val SchemaWithData.Interface.gjDef: GraphQLInterfaceType
    get() = data as GraphQLInterfaceType

/** The underlying GraphQLObjectType. */
val SchemaWithData.Object.gjDef: GraphQLObjectType
    get() = data as GraphQLObjectType

/** The underlying GraphQLInputObjectType. */
val SchemaWithData.Input.gjDef: GraphQLInputObjectType
    get() = data as GraphQLInputObjectType

/** The underlying GraphQLDirective. */
val SchemaWithData.Directive.gjDef: GraphQLDirective
    get() = data as GraphQLDirective

/** The underlying GraphQLArgument for directive args. */
val SchemaWithData.DirectiveArg.gjDef: GraphQLArgument
    get() = data as GraphQLArgument

/** The underlying GraphQLArgument for field args. */
val SchemaWithData.FieldArg.gjDef: GraphQLArgument
    get() = data as GraphQLArgument

/**
 * The underlying GraphQLFieldDefinition for output fields.
 * This should only be used on fields from Object or Interface types.
 */
val SchemaWithData.Field.gjOutputDef: GraphQLFieldDefinition
    get() = data as GraphQLFieldDefinition

/**
 * The underlying GraphQLInputObjectField for input fields.
 * This should only be used on fields from Input types.
 */
val SchemaWithData.Field.gjInputDef: GraphQLInputObjectField
    get() = data as GraphQLInputObjectField
