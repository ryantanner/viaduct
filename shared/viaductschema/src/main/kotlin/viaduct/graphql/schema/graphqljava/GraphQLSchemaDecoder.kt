package viaduct.graphql.schema.graphqljava

import graphql.language.Directive
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLEnumValueDefinition
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLImplementingType
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector

/**
 * Transforms graphql-java GraphQLSchema elements into ViaductSchema elements.
 * This class centralizes all transformation logic, separating it from the
 * populate() methods in TypeDef classes.
 *
 * Analogous to TypeDefinitionRegistryDecoder for GJSchemaRaw.
 */
internal class GraphQLSchemaDecoder(
    private val schema: GraphQLSchema,
    private val types: Map<String, GJSchema.TypeDef>,
    private val valueConverter: ValueConverter
) {
    // Cache for union membership and interface implementors (computed once)
    private val unionMembership: Map<String, List<GJSchema.Union>> by lazy {
        buildMap<String, MutableList<GJSchema.Union>> {
            types.values.filterIsInstance<GJSchema.Union>().forEach { union ->
                union.def.types.forEach { memberType ->
                    getOrPut(memberType.name) { mutableListOf() }.add(union)
                }
            }
        }
    }

    private val interfaceImplementors: Map<String, List<GraphQLNamedType>> by lazy {
        buildMap<String, MutableList<GraphQLNamedType>> {
            schema.allTypesAsList.filterIsInstance<GraphQLImplementingType>().forEach { implType ->
                implType.interfaces.forEach { iface ->
                    getOrPut(iface.name) { mutableListOf() }.add(implType)
                }
            }
        }
    }

    // ========== Core Decoding Primitives ==========

    fun decodeTypeExpr(gtype: GraphQLType): ViaductSchema.TypeExpr<GJSchema.TypeDef> {
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

    private fun decodeTypeExprFromLang(type: Type<*>): ViaductSchema.TypeExpr<GJSchema.TypeDef> =
        type.toTypeExpr { baseTypeDefName, baseTypeNullable, listNullable ->
            val baseTypeDef = types[baseTypeDefName]
                ?: error("Type not found: $baseTypeDefName")
            ViaductSchema.TypeExpr(baseTypeDef, baseTypeNullable, listNullable)
        }

    fun decodeSourceLocation(def: graphql.language.Node<*>?): ViaductSchema.SourceLocation? = def?.sourceLocation?.sourceName?.let { ViaductSchema.SourceLocation(it) }

    fun decodeAppliedDirectives(container: GraphQLDirectiveContainer): List<ViaductSchema.AppliedDirective> =
        container.appliedDirectives.map { directive ->
            ViaductSchema.AppliedDirective.of(
                name = directive.name,
                arguments = directive.arguments.associate { arg ->
                    arg.name to valueConverter.convert(decodeTypeExpr(arg.type), arg.argumentValue)
                }
            )
        }

    private fun decodeAppliedDirectivesFromLang(directives: List<Directive>): List<ViaductSchema.AppliedDirective> =
        directives.map { dir ->
            val def = schema.getDirective(dir.name)?.definition
                ?: error("Directive @${dir.name} not found in schema.")
            dir.toAppliedDirective(def, valueConverter) { decodeTypeExprFromLang(it) }
        }

    fun decodeHasDefault(arg: GraphQLArgument): Boolean = arg.hasSetDefaultValue()

    fun decodeDefaultValue(arg: GraphQLArgument): Any? =
        if (arg.hasSetDefaultValue()) {
            valueConverter.convert(decodeTypeExpr(arg.type), arg.argumentDefaultValue)
        } else {
            null
        }

    // ========== Cross-Reference Computation ==========

    fun computeUnions(objectDef: GJSchema.Object): List<GJSchema.Union> = unionMembership[objectDef.name] ?: emptyList()

    fun computePossibleObjectTypes(interfaceDef: GJSchema.Interface): Set<GJSchema.Object> {
        val result = mutableSetOf<GJSchema.Object>()

        fun collectObjectTypes(implType: GraphQLNamedType) {
            if (implType.name == interfaceDef.name) {
                throw IllegalArgumentException("Cyclical inheritance.")
            }
            when (implType) {
                is GraphQLObjectType -> result.add(types[implType.name] as GJSchema.Object)
                is GraphQLInterfaceType -> {
                    interfaceImplementors[implType.name]?.forEach { collectObjectTypes(it) }
                }
            }
        }

        interfaceImplementors[interfaceDef.name]?.forEach { collectObjectTypes(it) }
        return result
    }

    // ========== Scalar ==========

    fun createScalarExtensions(scalarDef: GJSchema.Scalar): List<ViaductSchema.Extension<GJSchema.Scalar, Nothing>> {
        val gjDef = scalarDef.def
        // GraphQLScalarType from UnExecutableSchemaGenerator loses extension definitions
        // (extensionDefinitions is always empty), but it merges all directives from both
        // base and extensions into the scalar's appliedDirectives. So we create extensions
        // from the language-level definitions if available, falling back to merged directives.
        val langDefs = listOf(gjDef.definition) + gjDef.extensionDefinitions
        return if (langDefs.all { it == null } || (gjDef.extensionDefinitions.isEmpty() && gjDef.definition == null)) {
            // No language definitions available - create single extension with merged directives
            listOf(
                ViaductSchema.Extension.of(
                    def = scalarDef,
                    memberFactory = { _ -> emptyList() },
                    isBase = true,
                    appliedDirectives = decodeAppliedDirectives(gjDef),
                    sourceLocation = null
                )
            )
        } else {
            // Have language definitions - but graphql-java loses extension definitions for scalars
            // So we only have the base definition. Use its directives from the language AST,
            // but get extension directives from the merged appliedDirectives.
            val baseDirectives = gjDef.definition?.let { decodeAppliedDirectivesFromLang(it.directives) } ?: emptyList()
            val allDirectives = decodeAppliedDirectives(gjDef)
            // Extension directives are all directives that aren't in the base
            val baseDirectiveSet = baseDirectives.map { it.name to it.arguments }.toSet()
            val extensionDirectives = allDirectives.filterNot { (it.name to it.arguments) in baseDirectiveSet }

            buildList {
                // Base extension
                add(
                    ViaductSchema.Extension.of(
                        def = scalarDef,
                        memberFactory = { _ -> emptyList() },
                        isBase = true,
                        appliedDirectives = baseDirectives,
                        sourceLocation = decodeSourceLocation(gjDef.definition)
                    )
                )
                // If there are extension directives, create a synthetic extension for them
                if (extensionDirectives.isNotEmpty()) {
                    add(
                        ViaductSchema.Extension.of(
                            def = scalarDef,
                            memberFactory = { _ -> emptyList() },
                            isBase = false,
                            appliedDirectives = extensionDirectives,
                            sourceLocation = null
                        )
                    )
                }
            }
        }
    }

    // ========== Enum ==========

    fun createEnumExtensions(enumDef: GJSchema.Enum): List<ViaductSchema.Extension<GJSchema.Enum, GJSchema.EnumValue>> {
        val gjDef = enumDef.def
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = enumDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.values.map { evDef ->
                            createEnumValue(evDef, containingExtension, decodeAppliedDirectives(evDef))
                        }
                    } else {
                        gjLangTypeDef.enumValueDefinitions.map { evd ->
                            val evDef = gjDef.getValue(evd.name)
                                ?: error("Enum value ${evd.name} not found in ${gjDef.name}")
                            createEnumValue(
                                evDef,
                                containingExtension,
                                decodeAppliedDirectivesFromLang(evd.directives)
                            )
                        }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    private fun createEnumValue(
        evDef: GraphQLEnumValueDefinition,
        containingExtension: ViaductSchema.Extension<GJSchema.Enum, GJSchema.EnumValue>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>
    ) = GJSchema.EnumValue(evDef, containingExtension, evDef.name, appliedDirectives)

    // ========== Input ==========

    fun createInputExtensions(inputDef: GJSchema.Input): List<ViaductSchema.Extension<GJSchema.Input, GJSchema.Field>> {
        val gjDef = inputDef.def
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = inputDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.fields.map { fieldDef ->
                            createInputField(fieldDef, containingExtension, decodeAppliedDirectives(fieldDef))
                        }
                    } else {
                        gjLangTypeDef.inputValueDefinitions.map { ivd ->
                            val fieldDef = gjDef.getField(ivd.name)
                                ?: error("Field ${ivd.name} not found in ${gjDef.name}")
                            createInputField(
                                fieldDef,
                                containingExtension,
                                decodeAppliedDirectivesFromLang(ivd.directives)
                            )
                        }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    private fun createInputField(
        fieldDef: GraphQLInputObjectField,
        containingExtension: ViaductSchema.Extension<GJSchema.Input, GJSchema.Field>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>
    ): GJSchema.InputField {
        val hasDefault = fieldDef.hasSetDefaultValue()
        val defaultValue = if (hasDefault) {
            valueConverter.convert(decodeTypeExpr(fieldDef.type), fieldDef.inputFieldDefaultValue)
        } else {
            null
        }
        @Suppress("UNCHECKED_CAST")
        return GJSchema.InputField(
            fieldDef,
            containingExtension as ViaductSchema.Extension<GJSchema.Record, GJSchema.Field>,
            fieldDef.name,
            decodeTypeExpr(fieldDef.type),
            appliedDirectives,
            hasDefault,
            defaultValue,
        )
    }

    // ========== Interface ==========

    fun createInterfaceExtensions(interfaceDef: GJSchema.Interface): List<ViaductSchema.ExtensionWithSupers<GJSchema.Interface, GJSchema.Field>> {
        val gjDef = interfaceDef.def
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = interfaceDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.fields
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fieldDef ->
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<GJSchema.Record, GJSchema.Field>,
                                    decodeAppliedDirectives(fieldDef)
                                )
                            }
                    } else {
                        gjLangTypeDef.fieldDefinitions
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fd ->
                                val fieldDef = gjDef.getFieldDefinition(fd.name)
                                    ?: error("Field ${fd.name} not found in ${gjDef.name}")
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<GJSchema.Record, GJSchema.Field>,
                                    decodeAppliedDirectivesFromLang(fd.directives)
                                )
                            }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                supers = if (gjLangTypeDef == null) {
                    gjDef.interfaces.map { types[it.name] as GJSchema.Interface }
                } else {
                    gjLangTypeDef.implements.map { types[(it as TypeName).name] as GJSchema.Interface }
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Object ==========

    fun createObjectExtensions(objectDef: GJSchema.Object): List<ViaductSchema.ExtensionWithSupers<GJSchema.Object, GJSchema.Field>> {
        val gjDef = objectDef.def
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = objectDef,
                memberFactory = { containingExtension ->
                    if (gjLangTypeDef == null) {
                        gjDef.fields
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fieldDef ->
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<GJSchema.Record, GJSchema.Field>,
                                    decodeAppliedDirectives(fieldDef)
                                )
                            }
                    } else {
                        gjLangTypeDef.fieldDefinitions
                            .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { fd ->
                                val fieldDef = gjDef.getFieldDefinition(fd.name)
                                    ?: error("Field ${fd.name} not found in ${gjDef.name}")
                                @Suppress("UNCHECKED_CAST")
                                createOutputField(
                                    fieldDef,
                                    containingExtension as ViaductSchema.Extension<GJSchema.Record, GJSchema.Field>,
                                    decodeAppliedDirectivesFromLang(fd.directives)
                                )
                            }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                supers = if (gjLangTypeDef == null) {
                    gjDef.interfaces.map { types[it.name] as GJSchema.Interface }
                } else {
                    gjLangTypeDef.implements.map { types[(it as TypeName).name] as GJSchema.Interface }
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Union ==========

    fun createUnionExtensions(unionDef: GJSchema.Union): List<ViaductSchema.Extension<GJSchema.Union, GJSchema.Object>> {
        val gjDef = unionDef.def
        return (listOf(gjDef.definition) + gjDef.extensionDefinitions).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = unionDef,
                memberFactory = { _ ->
                    if (gjLangTypeDef == null) {
                        gjDef.types.map { types[it.name] as GJSchema.Object }
                    } else {
                        gjLangTypeDef.memberTypes
                            .filter { (it as TypeName).name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                            .map { types[(it as TypeName).name] as GJSchema.Object }
                    }
                },
                isBase = gjLangTypeDef == gjDef.definition,
                appliedDirectives = if (gjLangTypeDef != null) {
                    decodeAppliedDirectivesFromLang(gjLangTypeDef.directives)
                } else {
                    decodeAppliedDirectives(gjDef)
                },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Directive ==========

    fun createDirective(gjDef: GraphQLDirective): GJSchema.Directive {
        return GJSchema.Directive(gjDef, gjDef.name)
    }

    fun populate(directive: GJSchema.Directive) {
        val gjDef = directive.def
        val isRepeatable = gjDef.isRepeatable
        val allowedLocations = gjDef.validLocations()
            .map { ViaductSchema.Directive.Location.valueOf(it.name) }
            .toSet()
        val sourceLocation = gjDef.definition?.sourceLocation?.sourceName?.let {
            ViaductSchema.SourceLocation(it)
        }
        val args = gjDef.arguments.map { argDef ->
            GJSchema.DirectiveArg(
                argDef,
                directive,
                argDef.name,
                decodeTypeExpr(argDef.type),
                emptyList(), // Directive args don't have applied directives
                decodeHasDefault(argDef),
                decodeDefaultValue(argDef),
            )
        }
        directive.populate(isRepeatable, allowedLocations, sourceLocation, args)
    }

    // ========== Helper: OutputField ==========

    private fun createOutputField(
        fieldDef: GraphQLFieldDefinition,
        containingExtension: ViaductSchema.Extension<GJSchema.Record, GJSchema.Field>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>
    ): GJSchema.OutputField {
        return GJSchema.OutputField(
            fieldDef,
            containingExtension,
            fieldDef.name,
            decodeTypeExpr(fieldDef.type),
            appliedDirectives,
            hasDefault = false,
            defaultValue = null,
            argsFactory = { field -> createFieldArgs(field, fieldDef) }
        )
    }

    private fun createFieldArgs(
        field: GJSchema.OutputField,
        fieldDef: GraphQLFieldDefinition
    ): List<GJSchema.FieldArg> =
        fieldDef.arguments.map { argDef ->
            GJSchema.FieldArg(
                field,
                argDef,
                argDef.name,
                decodeTypeExpr(argDef.type),
                decodeAppliedDirectives(argDef),
                decodeHasDefault(argDef),
                decodeDefaultValue(argDef),
            )
        }
}
