package viaduct.graphql.schema.graphqljava

import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.Node
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.idl.TypeDefinitionRegistry
import viaduct.graphql.schema.ViaductSchema

/**
 * Transforms graphql-java TypeDefinitionRegistry elements into ViaductSchema elements.
 * This class centralizes all AST-to-ViaductSchema transformation logic, separating it
 * from the populate() methods in TypeDef classes.
 *
 * Analogous to BSchema's DefinitionsDecoder.
 */
internal class TypeDefinitionRegistryDecoder(
    private val registry: TypeDefinitionRegistry,
    private val types: Map<String, GJSchemaRaw.TypeDef>,
    private val valueConverter: ValueConverter
) {
    // ========== Core Decoding Primitives ==========

    fun decodeTypeExpr(type: Type<*>): ViaductSchema.TypeExpr<GJSchemaRaw.TypeDef> =
        type.toTypeExpr { baseTypeDefName, baseTypeNullable, listNullable ->
            ViaductSchema.TypeExpr(
                types[baseTypeDefName] ?: error("Type not found: $baseTypeDefName"),
                baseTypeNullable,
                listNullable
            )
        }

    fun decodeSourceLocation(node: Node<*>?): ViaductSchema.SourceLocation? = node?.sourceLocation?.sourceName?.let { ViaductSchema.SourceLocation(it) }

    fun decodeAppliedDirectives(directives: List<Directive>): List<ViaductSchema.AppliedDirective> = directives.map { decodeAppliedDirective(it) }

    private fun decodeAppliedDirective(dir: Directive): ViaductSchema.AppliedDirective {
        val def = registry.getDirectiveDefinition(dir.name).orElse(null)
            ?: error("Directive @${dir.name} not found in schema.")
        return dir.toAppliedDirective(def, valueConverter) { decodeTypeExpr(it) }
    }

    fun decodeHasDefault(ivd: InputValueDefinition): Boolean = ivd.defaultValue != null

    fun decodeDefaultValue(ivd: InputValueDefinition): Any? =
        if (ivd.defaultValue != null) {
            valueConverter.convert(decodeTypeExpr(ivd.type), ivd.defaultValue)
        } else {
            null
        }

    // ========== Scalar ==========

    fun createScalarExtensions(scalarDef: GJSchemaRaw.Scalar): List<ViaductSchema.Extension<GJSchemaRaw.Scalar, Nothing>> {
        return (listOf(scalarDef.def) + scalarDef.extensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = scalarDef,
                memberFactory = { _ -> emptyList() },
                isBase = gjLangTypeDef == scalarDef.def,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Enum ==========

    fun createEnumExtensions(enumDef: GJSchemaRaw.Enum): List<ViaductSchema.Extension<GJSchemaRaw.Enum, GJSchemaRaw.EnumValue>> {
        return (listOf(enumDef.def) + enumDef.extensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = enumDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.enumValueDefinitions.map { evd ->
                        GJSchemaRaw.EnumValue(
                            evd,
                            containingExtension,
                            evd.name,
                            decodeAppliedDirectives(evd.directives)
                        )
                    }
                },
                isBase = gjLangTypeDef == enumDef.def,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Input ==========

    fun createInputExtensions(inputDef: GJSchemaRaw.Input): List<ViaductSchema.Extension<GJSchemaRaw.Input, GJSchemaRaw.Field>> {
        return (listOf(inputDef.def) + inputDef.extensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = inputDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.inputValueDefinitions.map { ivd ->
                        GJSchemaRaw.InputField(
                            ivd,
                            containingExtension,
                            ivd.name,
                            decodeTypeExpr(ivd.type),
                            decodeAppliedDirectives(ivd.directives),
                            decodeHasDefault(ivd),
                            decodeDefaultValue(ivd),
                        )
                    }
                },
                isBase = gjLangTypeDef == inputDef.def,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Interface ==========

    fun createInterfaceExtensions(interfaceDef: GJSchemaRaw.Interface): List<ViaductSchema.ExtensionWithSupers<GJSchemaRaw.Interface, GJSchemaRaw.Field>> {
        return (listOf(interfaceDef.def) + interfaceDef.extensionDefs).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = interfaceDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.fieldDefinitions
                        .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                        .map { fieldDef ->
                            @Suppress("UNCHECKED_CAST")
                            createOutputField(
                                fieldDef,
                                containingExtension as ViaductSchema.Extension<GJSchemaRaw.Record, GJSchemaRaw.Field>
                            )
                        }
                },
                isBase = gjLangTypeDef == interfaceDef.def,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                supers = gjLangTypeDef.implements.map { types[(it as TypeName).name] as GJSchemaRaw.Interface },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Object ==========

    fun createObjectExtensions(objectDef: GJSchemaRaw.Object): List<ViaductSchema.ExtensionWithSupers<GJSchemaRaw.Object, GJSchemaRaw.Field>> {
        return (listOf(objectDef.def) + objectDef.extensionDefs).map { gjLangTypeDef ->
            ViaductSchema.ExtensionWithSupers.of(
                def = objectDef,
                memberFactory = { containingExtension ->
                    gjLangTypeDef.fieldDefinitions
                        .filter { it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                        .map { fieldDef ->
                            @Suppress("UNCHECKED_CAST")
                            createOutputField(
                                fieldDef,
                                containingExtension as ViaductSchema.Extension<GJSchemaRaw.Record, GJSchemaRaw.Field>
                            )
                        }
                },
                isBase = gjLangTypeDef == objectDef.def,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                supers = gjLangTypeDef.implements.map { types[(it as TypeName).name] as GJSchemaRaw.Interface },
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Union ==========

    fun createUnionExtensions(unionDef: GJSchemaRaw.Union): List<ViaductSchema.Extension<GJSchemaRaw.Union, GJSchemaRaw.Object>> {
        return (listOf(unionDef.def) + unionDef.extensionDefs).map { gjLangTypeDef ->
            ViaductSchema.Extension.of(
                def = unionDef,
                memberFactory = { _ ->
                    gjLangTypeDef.memberTypes
                        .filter { (it as TypeName).name != ViaductSchema.VIADUCT_IGNORE_SYMBOL }
                        .map { types[(it as TypeName).name] as GJSchemaRaw.Object }
                },
                isBase = gjLangTypeDef == unionDef.def,
                appliedDirectives = decodeAppliedDirectives(gjLangTypeDef.directives),
                sourceLocation = decodeSourceLocation(gjLangTypeDef)
            )
        }
    }

    // ========== Directive ==========

    fun createDirective(def: DirectiveDefinition): GJSchemaRaw.Directive {
        return GJSchemaRaw.Directive(def, def.name)
    }

    fun populate(directive: GJSchemaRaw.Directive) {
        val def = directive.def
        val isRepeatable = def.isRepeatable
        val allowedLocations = def.directiveLocations
            .map { ViaductSchema.Directive.Location.valueOf(it.name) }
            .toSet()
        val sourceLocation = decodeSourceLocation(def)
        val args = def.inputValueDefinitions.map {
            val hasDefault = it.defaultValue != null
            val default = if (hasDefault) {
                valueConverter.convert(decodeTypeExpr(it.type), it.defaultValue)
            } else {
                null
            }
            GJSchemaRaw.DirectiveArg(it, directive, it.name, decodeTypeExpr(it.type), emptyList(), hasDefault, default)
        }
        directive.populate(isRepeatable, allowedLocations, sourceLocation, args)
    }

    // ========== Helper: OutputField ==========

    private fun createOutputField(
        fieldDef: FieldDefinition,
        containingExtension: ViaductSchema.Extension<GJSchemaRaw.Record, GJSchemaRaw.Field>
    ): GJSchemaRaw.OutputField {
        return GJSchemaRaw.OutputField(
            fieldDef,
            containingExtension,
            fieldDef.name,
            decodeTypeExpr(fieldDef.type),
            decodeAppliedDirectives(fieldDef.directives),
            hasDefault = false,
            mDefaultValue = null,
            argsFactory = { field -> createFieldArgs(field, fieldDef) }
        )
    }

    private fun createFieldArgs(
        field: GJSchemaRaw.OutputField,
        fieldDef: FieldDefinition
    ): List<GJSchemaRaw.FieldArg> =
        fieldDef.inputValueDefinitions.map { ivd ->
            val hasDefault = ivd.defaultValue != null
            val default = if (hasDefault) {
                valueConverter.convert(decodeTypeExpr(ivd.type), ivd.defaultValue)
            } else {
                null
            }
            GJSchemaRaw.FieldArg(
                field,
                ivd,
                ivd.name,
                decodeTypeExpr(ivd.type),
                decodeAppliedDirectives(ivd.directives),
                hasDefault,
                default,
            )
        }
}
