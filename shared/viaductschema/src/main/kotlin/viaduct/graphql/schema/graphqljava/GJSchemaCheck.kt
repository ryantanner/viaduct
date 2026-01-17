package viaduct.graphql.schema.graphqljava

import graphql.language.Node
import graphql.language.NullValue
import graphql.language.Value
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.checkBridgeSchemaInvariants
import viaduct.invariants.InvariantChecker

class GJSchemaCheck(
    private val schema: GJSchema,
    private val gjSchema: GraphQLSchema,
    private val check: InvariantChecker = InvariantChecker(),
) {
    fun assertEmpty(separator: String) = check.assertEmpty(separator)

    init {
        checkBridgeSchemaInvariants(schema, check)
        check.containsExactlyElementsIn(
            gjSchema.allTypesAsList.map { it.name },
            schema.types.values.map { it.name },
            "TYPES_AGREE"
        )
        schema.types.values.checkAgreement()
        checkFieldInvariants()
        checkDirectiveInvariants()
        checkSourceLocationInvariants()
    }

    private fun Iterable<GJSchema.Def>.checkAgreement(): Unit = forEach { it.checkAgreement() }

    private fun GJSchema.Def.checkAgreement() {
        check.isEqualTo(def.name, name, "NAME_AGREEMENT")
        if (def is GraphQLDirectiveContainer) {
            check.containsExactlyElementsIn(
                (def as GraphQLDirectiveContainer).appliedDirectives.map { appliedDirective ->
                    // Get directive definition to iterate over ALL arguments (not just explicitly provided ones)
                    val directiveDef = gjSchema.getDirective(appliedDirective.name)
                        ?: error("Directive @${appliedDirective.name} not found in schema.")
                    val argsString = if (directiveDef.arguments.isNotEmpty()) {
                        val args = directiveDef.arguments.sortedBy { it.name }.joinToString(", ") { argDef ->
                            "${argDef.name}: ${convertAppliedDirectiveArg(appliedDirective, argDef)}"
                        }
                        "($args)"
                    } else {
                        ""
                    }
                    "@${appliedDirective.name}$argsString"
                },
                appliedDirectives.map { it.toString() },
                "APPLIED_DIRECTIVES_AGREE"
            )
        }

        check.pushContext(this.name)
        when (this) {
            is GJSchema.FieldArg -> { }
            is GJSchema.DirectiveArg -> { }
            is GJSchema.Enum -> {
                check.containsExactlyElementsIn(
                    def.values.map { it.name },
                    values.map { it.name },
                    "ENUM_VALUES_AGREE"
                )
            }
            is GJSchema.EnumValue -> { }
            is GJSchema.Directive -> {
                args.checkAgreement()
            }
            is GJSchema.Field -> {
                args.checkAgreement()
            }
            is GJSchema.Input -> {
                check.containsExactlyElementsIn(
                    def.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
            }
            is GJSchema.Interface -> {
                check.containsExactlyElementsIn(
                    def.interfaces.map { it.name },
                    supers.map { it.name },
                    "SUPERS_AGREE"
                )
                check.containsExactlyElementsIn(
                    def.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
            }
            is GJSchema.Object -> {
                check.containsExactlyElementsIn(
                    def.interfaces.map { it.name },
                    supers.map { it.name },
                    "SUPERS_AGREE"
                )
                check.containsExactlyElementsIn(
                    def.fields.map { it.name },
                    fields.map { it.name },
                    "FIELDS_AGREE"
                )
                fields.checkAgreement()
                for (union in schema.types.values) {
                    if (union is GJSchema.Union) {
                        if (gjSchema.isPossibleType(
                                gjSchema.getType(union.name) as GraphQLNamedType,
                                gjSchema.getObjectType(def.name)!!
                            )
                        ) {
                            check.isTrue(
                                unions.contains(union),
                                "HAS_ALL_NEEDED_UNIONS: {0} is missing.",
                                arrayOf(union.name)
                            )
                        } else {
                            check.isFalse(
                                unions.contains(union),
                                "HAS_ONLY_NEEDED_UNIONS: {0} is extra.",
                                arrayOf(union.name)
                            )
                        }
                    }
                }
            }
            is GJSchema.Scalar -> { }
            is GJSchema.Union -> {
                check.containsExactlyElementsIn(
                    def.types.map { it.name },
                    possibleObjectTypes.map { it.name },
                    "UNION_MEMBERS_AGREE"
                )
            }
            else -> throw IllegalArgumentException("Unknown type ($def).")
        }
        check.popContext()
    }

    /**
     * Convert an applied directive argument to a Value, handling missing arguments
     * by using defaults or NullValue for nullable types.
     */
    private fun convertAppliedDirectiveArg(
        appliedDirective: GraphQLAppliedDirective,
        argDef: GraphQLArgument
    ): Value<*> {
        val appliedArg = appliedDirective.getArgument(argDef.name)
        val type = schema.toTypeExpr(argDef.type)
        val convertedValue = when {
            appliedArg != null -> ValueConverter.convert(type, appliedArg.argumentValue)
            argDef.hasSetDefaultValue() -> ValueConverter.convert(type, argDef.argumentDefaultValue)
            else -> null
        }
        return convertedValue ?: NullValue.of().also {
            require(type.isNullable) {
                "No value for non-nullable argument ${argDef.name} on @${appliedDirective.name}"
            }
        }
    }

    private fun checkDefaultValue(
        actual: ViaductSchema.HasDefaultValue,
        check: InvariantChecker
    ) {
        if (!actual.hasDefault) {
            check.doesThrow<NoSuchElementException>("HAS_NO_DEFAULT") {
                actual.defaultValue
            }
        } else {
            check
                .doesNotThrow("HAS_DEFAULT") {
                    actual.defaultValue
                }.ifNoThrow { default ->
                    if (default is NullValue) {
                        check.isTrue(actual.type.isNullable, "DEFAULT_NULLABLE")
                    } else {
                        ValueConverter.javaClassFor(actual.type).let {
                            check.isInstanceOf(it.kotlin, default, "DEFAULT_CORRECT_TYPE")
                        }
                    }
                }
        }
    }

    private fun checkFieldInvariants() {
        for (def in schema.types.values) {
            if (def !is ViaductSchema.Record) continue
            check.withContext(def.name) {
                for (field in def.fields) {
                    check.withContext(field.name) {
                        checkDefaultValue(field, check)
                        for (arg in field.args) {
                            checkDefaultValue(arg, check)
                        }
                    }
                }
            }
        }
    }

    private fun checkDirectiveInvariants() {
        for (def in schema.directives) {
            check.withContext(def.key) {
                for (arg in def.value.args) {
                    checkDefaultValue(arg, check)
                }
            }
        }
    }

    private fun checkSourceLocationInvariants() {
        for (d in schema.types.values) {
            check.withContext(d.name) {
                val expectedExts: List<Node<*>?> =
                    when (d) {
                        is GJSchema.Enum -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Input -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Interface -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Object -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Scalar -> listOf(d.def.definition) + d.def.extensionDefinitions
                        is GJSchema.Union -> listOf(d.def.definition) + d.def.extensionDefinitions
                        else -> throw IllegalArgumentException("Unknown type ($d).")
                    }
                val expectedSourceNames = expectedExts.map { it?.sourceLocation?.sourceName }.filterNotNull()
                val actualSourceNames = d.extensions.map { it.sourceLocation?.sourceName }.filterNotNull()
                check.containsExactlyElementsIn(expectedSourceNames, actualSourceNames, "SOURCE_NAMES_AGREE")
            }
        }
    }
}
