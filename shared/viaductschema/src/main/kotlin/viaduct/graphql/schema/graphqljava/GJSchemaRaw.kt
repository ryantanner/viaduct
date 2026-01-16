package viaduct.graphql.schema.graphqljava

import graphql.language.Description
import graphql.language.Directive
import graphql.language.DirectiveDefinition
import graphql.language.DirectiveLocation
import graphql.language.EnumTypeDefinition
import graphql.language.EnumTypeExtensionDefinition
import graphql.language.EnumValueDefinition
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputObjectTypeExtensionDefinition
import graphql.language.InputValueDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.InterfaceTypeExtensionDefinition
import graphql.language.ListType
import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.NonNullType
import graphql.language.NullValue
import graphql.language.ObjectTypeDefinition
import graphql.language.ObjectTypeExtensionDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.ScalarTypeExtensionDefinition
import graphql.language.SchemaDefinition
import graphql.language.StringValue
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.language.TypeName
import graphql.language.UnionTypeDefinition
import graphql.language.UnionTypeExtensionDefinition
import graphql.language.Value
import graphql.schema.idl.TypeDefinitionRegistry
import java.io.File
import java.net.URL
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.parseWrappers
import viaduct.utils.collections.BitVector
import viaduct.utils.timer.Timer

private typealias RawTypeMap = Map<String, GJSchemaRaw.TypeDef>

/** This is an implementation of the [ViaductSchema] classes that uses the
 *  `graphql.language` classes from the graphql-java library as the underlying
 *  representation.  It is an alternative to [GJSchema], which uses the
 *  `graphql.schema` classes: it's quite expensive in graphql-java to convert
 *  from the `language` to the `schema` classes, and this implementation avoids
 *  that cost.
 *
 *  This classes represents "real values" exactly the same as [GJSchema], so it
 *  can be a drop-in replacement.  See [GJSchema] for details on that representation.
 *
 *  The construction of the (query/mutation/subscription)TypeDef fields for this
 *  type is unusual.  As noted in the KDoc for [ViaductSchema.queryTypeDef],
 *  [ViaductSchema] can be used for "partial" schemas that do not define any
 *  of the schema-root types.  Because [graphql.schema.GraphQLSchema] represents
 *  a valid schema, [GJSchema] can't be used for such a partial schema, but
 *  [GJSchemaRaw] can be.
 *
 *  This class will attempt to populate the root type using the
 *  `schema` definition ([graphql.language.SchemaDefinition]) found
 *  while parsing the schema.  However, it *only* looks at the base
 *  definition, it does not consider extensions.
 *
 *  The factory-functions for [GJSchemaRaw] take optional parameters
 *  for the names of the query, mutation, and subscription root types.
 *  These parameters are all nullable.  If they are not-null, the
 *  parameter overrides what is found in the `SchemaDefinition`.  For
 *  historical compatibility, if one of these parameters is null and
 *  there is no `SchemaDefinition`, the factory will use the
 *  "standard" name to populate the root type.  For example, if the
 *  `queryTypeName` parameter is null, then the factory will look for
 *  a type named "Query" and, if it exists, use that as the root query
 *  type.  It might be possible that this default behavior is
 *  undesired.  If so, the special value [NO_ROOT_TYPE_DEFAULT]
 *  passed as the value of (query/mutation/subscription)TypeName will
 *  use what's found in the `schema` definition and will use null if
 *  nothing is found there.
 */
@Suppress("ktlint:standard:indent")
class GJSchemaRaw private constructor(
    override val types: Map<String, TypeDef>,
    override val directives: Map<String, GJSchemaRaw.Directive>,
    override val queryTypeDef: Object?,
    override val mutationTypeDef: Object?,
    override val subscriptionTypeDef: Object?
) : ViaductSchema {
    override fun toString() = types.toString()

    fun toTypeExpr(
        wrappers: String,
        baseString: String
    ): ViaductSchema.TypeExpr<TypeDef> {
        val baseTypeDef = requireNotNull(this.types[baseString]) {
            "Type not found: $baseString"
        }
        val listNullable = parseWrappers(wrappers) // Checks syntax for us
        val baseNullable = (wrappers.last() == '?')
        return ViaductSchema.TypeExpr(baseTypeDef, baseNullable, listNullable)
    }

    companion object {
        /** Convert collection of .graphqls files into a schema
         *  bridge.  (This function doesn't require callers to
         *  have a direct dependency on graphql-java.) */
        fun fromURLs(
            inputFiles: List<URL>,
            valueConverter: ValueConverter = ValueConverter.default,
            queryTypeName: String? = null,
            mutationTypeName: String? = null,
            subscriptionTypeName: String? = null
        ) = fromRegistry(
            readTypesFromURLs(inputFiles),
            valueConverter = valueConverter,
            queryTypeName = queryTypeName,
            mutationTypeName = mutationTypeName,
            subscriptionTypeName = subscriptionTypeName
        )

        fun fromFiles(
            inputFiles: List<File>,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
            queryTypeName: String? = null,
            mutationTypeName: String? = null,
            subscriptionTypeName: String? = null,
        ): GJSchemaRaw {
            val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(inputFiles) }
            return fromRegistry(
                typeDefRegistry,
                timer,
                valueConverter,
                queryTypeName,
                mutationTypeName,
                subscriptionTypeName,
            )
        }

        fun fromSDL(
            sdl: String,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
            queryTypeName: String? = null,
            mutationTypeName: String? = null,
            subscriptionTypeName: String? = null,
        ): GJSchemaRaw {
            val typeDefRegistry = timer.time("readTypes") { readTypes(sdl) }
            return fromRegistry(
                typeDefRegistry,
                timer,
                valueConverter,
                queryTypeName,
                mutationTypeName,
                subscriptionTypeName,
            )
        }

        /** Convert a graphql-java TypeDefinitionRegistry into
         *  a schema sketch. */
        fun fromRegistry(
            registry: TypeDefinitionRegistry,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
            queryTypeName: String? = null,
            mutationTypeName: String? = null,
            subscriptionTypeName: String? = null,
        ): GJSchemaRaw =
            timer.time("fromSchema") {
                // graphql-java assumes these get created during language->schema translation
                val deprecatedDirectiveDef =
                    DirectiveDefinition
                        .newDirectiveDefinition()
                        .name("deprecated")
                        .inputValueDefinition(
                            InputValueDefinition("reason", NonNullType(TypeName("String")), StringValue("No longer supported"))
                        ).directiveLocations(
                            listOf(
                                DirectiveLocation("FIELD_DEFINITION"),
                                DirectiveLocation("ARGUMENT_DEFINITION"),
                                DirectiveLocation("INPUT_FIELD_DEFINITION"),
                                DirectiveLocation("ENUM_VALUE")
                            )
                        ).build()
                registry.add(deprecatedDirectiveDef)
                val specifiedByDirectiveDef =
                    DirectiveDefinition
                        .newDirectiveDefinition()
                        .name("specifiedBy")
                        .inputValueDefinition(
                            InputValueDefinition("url", NonNullType(TypeName("String")))
                        ).directiveLocations(listOf(DirectiveLocation("SCALAR")))
                        .build()
                registry.add(specifiedByDirectiveDef)
                val oneOfDirectiveDef =
                    DirectiveDefinition
                        .newDirectiveDefinition()
                        .name("oneOf")
                        .description(
                            Description(
                                "Indicates an Input Object is a OneOf Input Object.",
                                null,
                                false
                            )
                        ).directiveLocations(listOf(DirectiveLocation("INPUT_OBJECT")))
                        .build()
                registry.add(oneOfDirectiveDef)

                // Take a first pass, translating unions and accumulating a
                // name-to-list-of-unions map for interfaces and object types
                val unionsMap = mutableMapOf<String, MutableSet<String>>()
                val unionDefs: List<UnionTypeDefinition> =
                    registry.getTypes(UnionTypeDefinition::class.java) + registry.unionTypeExtensions().values.flatten()
                for (def in unionDefs) {
                    def.memberTypes.forEach {
                        unionsMap
                            .getOrPut((it as TypeName).name) {
                            mutableSetOf()
                        }.add(def.name)
                    }
                }
                val membersMap = mutableMapOf<String, MutableSet<ImplementingTypeDefinition<*>>>()
                val implTypeDefs: List<ImplementingTypeDefinition<*>> =
                    (
                        registry.getTypes(ImplementingTypeDefinition::class.java) +
                            registry.interfaceTypeExtensions().values.flatten() +
                            registry.objectTypeExtensions().values.flatten()
                    )
                for (def in implTypeDefs) {
                    def.implements.forEach {
                        membersMap
                            .getOrPut((it as TypeName).name) {
                            mutableSetOf()
                        }.add(def)
                    }
                }

                // Lookup all extension definitions upfront (for Phase 1)
                val enumExtensions = registry.enumTypeExtensions()
                val inputObjectExtensions = registry.inputObjectTypeExtensions()
                val interfaceExtensions = registry.interfaceTypeExtensions()
                val objectExtensions = registry.objectTypeExtensions()
                val scalarExtensions = registry.scalarTypeExtensions()
                val unionExtensions = registry.unionTypeExtensions()

                // Phase 1: Create all TypeDef and Directive shells (with def and extensionDefs)
                val result = buildMap<String, TypeDef>(registry.types().size + registry.scalars().size) {
                    registry.types().values.forEach { graphqlDef ->
                        val typeDef = when (graphqlDef) {
                            is EnumTypeDefinition -> Enum(graphqlDef, enumExtensions[graphqlDef.name] ?: emptyList(), graphqlDef.name)
                            is InputObjectTypeDefinition -> Input(graphqlDef, inputObjectExtensions[graphqlDef.name] ?: emptyList(), graphqlDef.name)
                            is InterfaceTypeDefinition -> Interface(graphqlDef, interfaceExtensions[graphqlDef.name] ?: emptyList(), graphqlDef.name)
                            is ObjectTypeDefinition ->
                                if (graphqlDef.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL) {
                                    Object(graphqlDef, objectExtensions[graphqlDef.name] ?: emptyList(), graphqlDef.name)
                                } else {
                                    null
                                }
                            is UnionTypeDefinition -> Union(graphqlDef, unionExtensions[graphqlDef.name] ?: emptyList(), graphqlDef.name)
                            else -> null
                        }
                        if (typeDef != null) {
                            put(graphqlDef.name, typeDef)
                        }
                    }
                    // registry.scalars gets built-ins as well as explicitly-defined scalars
                    registry.scalars().values.forEach {
                        val scalarDef = it as ScalarTypeDefinition
                        put(scalarDef.name, Scalar(scalarDef, scalarExtensions[scalarDef.name] ?: emptyList(), scalarDef.name))
                    }
                }

                val directives = registry.directiveDefinitions.entries.associate {
                    it.key to Directive(it.value, it.value.name)
                }

                // Phase 2: Populate all TypeDefs and Directives using the decoder
                val decoder = TypeDefinitionRegistryDecoder(registry, result, valueConverter)

                registry.getTypes(EnumTypeDefinition::class.java).forEach {
                    val enumDef = result[it.name] as Enum
                    enumDef.populate(decoder.createEnumExtensions(enumDef))
                }
                registry.getTypes(InputObjectTypeDefinition::class.java).forEach {
                    val inputDef = result[it.name] as Input
                    inputDef.populate(decoder.createInputExtensions(inputDef))
                }
                registry.getTypes(InterfaceTypeDefinition::class.java).forEach {
                    val interfaceDef = result[it.name] as Interface
                    val possibleObjectTypes = allObjectTypes(it.name, membersMap).map { result[it] as Object }.toSet()
                    interfaceDef.populate(decoder.createInterfaceExtensions(interfaceDef), possibleObjectTypes)
                }
                registry.getTypes(ObjectTypeDefinition::class.java).forEach {
                    if (it.name != ViaductSchema.VIADUCT_IGNORE_SYMBOL) {
                        val objectDef = result[it.name] as Object
                        val unions = (unionsMap[it.name] ?: emptySet()).map { result[it] as Union }
                        objectDef.populate(decoder.createObjectExtensions(objectDef), unions)
                    }
                }
                registry.scalars().values.forEach {
                    val scalarDef = result[it.name] as Scalar
                    scalarDef.populate(decoder.createScalarExtensions(scalarDef))
                }
                registry.getTypes(UnionTypeDefinition::class.java).forEach {
                    val unionDef = result[it.name] as Union
                    unionDef.populate(decoder.createUnionExtensions(unionDef))
                }

                directives.values.forEach { directive ->
                    decoder.populate(directive)
                }

                val schemaDef = registry.schemaDefinition().orElse(null)
                val queryTypeDef = rootDef(result, schemaDef, queryTypeName, "Query")
                val mutationTypeDef = rootDef(result, schemaDef, mutationTypeName, "Mutation")
                val subscriptionTypeDef = rootDef(result, schemaDef, subscriptionTypeName, "Subscription")

                GJSchemaRaw(
                    result,
                    directives,
                    queryTypeDef,
                    mutationTypeDef,
                    subscriptionTypeDef
                )
            }

        private fun rootDef(
            defs: Map<String, TypeDef>,
            schemaDef: SchemaDefinition?,
            nameFromParam: String?,
            stdName: String
        ): Object? {
            var nameFromSchema =
                schemaDef
                    ?.operationTypeDefinitions
                    ?.find { it.name == stdName.lowercase() }
                    ?.typeName
                    ?.name
            var result: TypeDef? = nameFromSchema?.let {
                defs[nameFromSchema] ?: throw IllegalArgumentException("Type not found: $nameFromSchema")
            }
            if (nameFromParam != null && nameFromParam != NO_ROOT_TYPE_DEFAULT) {
                result = (defs[nameFromParam] ?: throw IllegalArgumentException("Type not found: $nameFromParam"))
            }
            if (result == null && nameFromParam != NO_ROOT_TYPE_DEFAULT) {
                result = defs[stdName]
            }
            if (result == null) return null
            if (result !is Object) {
                throw IllegalArgumentException("$stdName type ($nameFromParam) is not an object type.")
            }
            return result
        }

        private fun allObjectTypes(
            interfaceName: String,
            directs: Map<String, Iterable<ImplementingTypeDefinition<*>>>
        ): List<String> {
            val result = mutableListOf<String>()

            fun allObjectTypes(toAdd: ImplementingTypeDefinition<*>) {
                if (toAdd.name == interfaceName) {
                    throw IllegalArgumentException("Cyclical inheritance.")
                }
                if (toAdd is ObjectTypeDefinition) {
                    result.add(toAdd.name)
                } else {
                    directs[toAdd.name]?.let { namedTypes ->
                        namedTypes.forEach { allObjectTypes(it) }
                    }
                }
            }

            directs[interfaceName]?.let { namedTypes ->
                namedTypes.forEach { allObjectTypes(it) }
            }
            return result
        }

        /** Constant used to override default value for populating
         *  the root-type definitions.  See KDoc for [GJSchemaRaw].
         */
        const val NO_ROOT_TYPE_DEFAULT = "!!none"
    }

    // Well-designed GraphQL schemas are both immutable and highly
    // cyclical.  But the Kotlin(/Java) object-construction process
    // isn't friendly to immutable, cyclical data structures.  We
    // address this gap by passing a "type map" around during object
    // construction, which we use to lazily resolve references to
    // cyclical references.
    //
    // We've established the following protocol to ensure that our
    // lazy resolution is correct.  We differentiate between
    // "top-level" objects -- which are specifically [TypeDef]s in
    // our design -- from objects nested under those top-level
    // objects (for example, [EnumValue]s and [Field]s).  When it
    // comes to the construction of top-level objects, this is
    // done using `by lazy`, using [typeMap] to resolve references.
    // However, when it comes to the construction of nested objects,
    // since those constructors are being called when [typeMap] has
    // been populatd, we can use [typeMap] without further laziness.

    sealed interface Def : ViaductSchema.Def {
        val def: NamedNode<*>

        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }
    }

    sealed interface TypeDef :
        ViaductSchema.TypeDef,
        Def {
        override val def: TypeDefinition<*>
        val extensionDefs: List<TypeDefinition<*>>

        override fun asTypeExpr(): ViaductSchema.TypeExpr<TypeDef>

        override val possibleObjectTypes: Set<Object>
    }

    sealed class TypeDefImpl(
        override val name: String
    ) : TypeDef {
        override fun asTypeExpr() = ViaductSchema.TypeExpr(this)

        override fun toString() = describe()

        open override val possibleObjectTypes: Set<Object> get() = emptySet()
    }

    sealed class Arg(
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : HasDefaultValue(name, type, appliedDirectives, hasDefault, defaultValue),
        ViaductSchema.Arg

    class DirectiveArg internal constructor(
        override val def: InputValueDefinition,
        override val containingDef: Directive,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : Arg(name, type, appliedDirectives, hasDefault, defaultValue),
        ViaductSchema.DirectiveArg {
        override fun toString() = describe()
    }

    class Directive internal constructor(
        override val def: DirectiveDefinition,
        override val name: String,
    ) : ViaductSchema.Directive, Def {
            private var mIsRepeatable: Boolean? = null
        private var mAllowedLocations: Set<ViaductSchema.Directive.Location>? = null
        private var mSourceLocation: ViaductSchema.SourceLocation? = null
        private var mArgs: List<DirectiveArg>? = null

        override val isRepeatable: Boolean get() = guardedGet(mIsRepeatable)
        override val allowedLocations: Set<ViaductSchema.Directive.Location> get() = guardedGet(mAllowedLocations)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = guardedGetNullable(mSourceLocation, mArgs)
        override val args: List<DirectiveArg> get() = guardedGet(mArgs)

        override val appliedDirectives: List<ViaductSchema.AppliedDirective> = emptyList()

        internal fun populate(
            isRepeatable: Boolean,
            allowedLocations: Set<ViaductSchema.Directive.Location>,
            sourceLocation: ViaductSchema.SourceLocation?,
            args: List<DirectiveArg>
        ) {
            check(mArgs == null) { "Directive $name has already been populated; populate() can only be called once" }
            mIsRepeatable = isRepeatable
            mAllowedLocations = allowedLocations
            mSourceLocation = sourceLocation
            mArgs = args
        }

        override fun toString() = describe()
    }

    class Scalar internal constructor(
        override val def: ScalarTypeDefinition,
        override val extensionDefs: List<ScalarTypeExtensionDefinition>,
        name: String,
    ) : TypeDefImpl(name),
        ViaductSchema.Scalar {
        private var mExtensions: List<ViaductSchema.Extension<Scalar, Nothing>>? = null

        override val extensions: List<ViaductSchema.Extension<Scalar, Nothing>> get() = guardedGet(mExtensions)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = extensions.flatMap { it.appliedDirectives }
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(extensions: List<ViaductSchema.Extension<Scalar, Nothing>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
        }
    }

    class EnumValue internal constructor(
        override val def: EnumValueDefinition,
        override val containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
    ) : ViaductSchema.EnumValue,
        Def {
        override val containingDef: Enum get() = containingExtension.def

        override fun toString() = describe()
    }

    class Enum internal constructor(
        override val def: EnumTypeDefinition,
        override val extensionDefs: List<EnumTypeExtensionDefinition>,
        name: String,
    ) : TypeDefImpl(name),
        ViaductSchema.Enum {
        private var mValues: List<EnumValue>? = null
        override val values: List<EnumValue> get() = guardedGet(mValues)

        private var mExtensions: List<ViaductSchema.Extension<Enum, EnumValue>>? = null
        override val extensions: List<ViaductSchema.Extension<Enum, EnumValue>> get() = guardedGet(mExtensions)

        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(extensions: List<ViaductSchema.Extension<Enum, EnumValue>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mValues = extensions.flatMap { it.members }
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
        }

        override fun value(name: String): EnumValue? = values.find { name == it.name }
    }

    class Union internal constructor(
        override val def: UnionTypeDefinition,
        override val extensionDefs: List<UnionTypeExtensionDefinition>,
        name: String,
    ) : TypeDefImpl(name), ViaductSchema.Union {
        private var mExtensions: List<ViaductSchema.Extension<Union, Object>>? = null
        private var mPossibleObjectTypes: Set<Object>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        override val extensions: List<ViaductSchema.Extension<Union, Object>> get() = guardedGet(mExtensions)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(extensions: List<ViaductSchema.Extension<Union, Object>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mPossibleObjectTypes = extensions.flatMap { it.members }.toSet()
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
        }
    }

    sealed class HasDefaultValue(
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        override val hasDefault: Boolean,
        private val mDefaultValue: Any?,
    ) : ViaductSchema.HasDefaultValue,
        Def {
        // Leave abstract so we can narrow the type
        abstract override val containingDef: Def

        override val defaultValue: Any?
            get() =
                if (hasDefault) {
                    mDefaultValue
                } else {
                    throw NoSuchElementException("No default value for ${this.describe()}")
                }
    }

    class FieldArg internal constructor(
        override val containingDef: OutputField,
        override val def: InputValueDefinition,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : Arg(name, type, appliedDirectives, hasDefault, defaultValue),
        ViaductSchema.FieldArg {
        override fun toString() = describe()
    }

    sealed class Field(
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : HasDefaultValue(name, type, appliedDirectives, hasDefault, defaultValue),
        ViaductSchema.Field {
        abstract override val containingDef: Record
        abstract override val containingExtension: ViaductSchema.Extension<Record, Field>
        abstract override val args: List<FieldArg>

        override fun toString() = describe()
    }

    class OutputField internal constructor(
        override val def: FieldDefinition,
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
        argsFactory: (OutputField) -> List<FieldArg>,
    ) : Field(name, type, appliedDirectives, hasDefault, defaultValue) {
        override val isOverride by lazy { ViaductSchema.isOverride(this) }
        override val containingDef get() = containingExtension.def
        override val args: List<FieldArg> = argsFactory(this)
    }

    class InputField internal constructor(
        override val def: InputValueDefinition,
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : Field(name, type, appliedDirectives, hasDefault, defaultValue) {
        override val isOverride by lazy { ViaductSchema.isOverride(this) }
        override val args = emptyList<FieldArg>()
        override val containingDef get() = containingExtension.def as Input
    }

    sealed interface Record :
        ViaductSchema.Record,
        TypeDef {
        override val fields: List<Field>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field = ViaductSchema.field(this, path)
    }

    sealed interface OutputRecord : ViaductSchema.OutputRecord, Record {
        override val extensions: List<ViaductSchema.ExtensionWithSupers<OutputRecord, Field>>
        override val supers: List<Interface>
    }

    class Interface internal constructor(
        override val def: InterfaceTypeDefinition,
        override val extensionDefs: List<InterfaceTypeExtensionDefinition>,
        name: String,
    ) : TypeDefImpl(name), ViaductSchema.Interface, OutputRecord {
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>? = null
        private var mFields: List<Field>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mSupers: List<Interface>? = null
        private var mPossibleObjectTypes: Set<Object>? = null
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>,
            possibleObjectTypes: Set<Object>
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mFields = extensions.flatMap { it.members }
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            @Suppress("UNCHECKED_CAST")
            mSupers = (extensions.flatMap { it.supers } as List<Interface>).distinct()
            mPossibleObjectTypes = possibleObjectTypes
        }
    }

    class Object internal constructor(
        override val def: ObjectTypeDefinition,
        override val extensionDefs: List<ObjectTypeExtensionDefinition>,
        name: String,
    ) : TypeDefImpl(name), ViaductSchema.Object, OutputRecord {
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>>? = null
        private var mFields: List<Field>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mSupers: List<Interface>? = null
        private var mUnions: List<Union>? = null
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val unions: List<Union> get() = guardedGet(mUnions)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val possibleObjectTypes: Set<Object> get() = setOf(this)

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>>,
            unions: List<Union>
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mFields = extensions.flatMap { it.members }
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            @Suppress("UNCHECKED_CAST")
            mSupers = (extensions.flatMap { it.supers } as List<Interface>).distinct()
            mUnions = unions
        }
    }

    class Input internal constructor(
        override val def: InputObjectTypeDefinition,
        override val extensionDefs: List<InputObjectTypeExtensionDefinition>,
        name: String,
    ) : TypeDefImpl(name),
        ViaductSchema.Input,
        Record {
        private var mExtensions: List<ViaductSchema.Extension<Input, Field>>? = null
        private var mFields: List<Field>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        override val extensions: List<ViaductSchema.Extension<Input, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(extensions: List<ViaductSchema.Extension<Input, Field>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mFields = extensions.flatMap { it.members }
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
        }
    }
}

internal fun Directive.toAppliedDirective(
    def: DirectiveDefinition,
    valueConverter: ValueConverter,
    typeExprConverter: (Type<*>) -> ViaductSchema.TypeExpr<*>
): ViaductSchema.AppliedDirective {
    val args = def.inputValueDefinitions
    return ViaductSchema.AppliedDirective.of(
        this.name,
        args.fold(mutableMapOf<String, Any?>()) { m, arg ->
            val t = typeExprConverter(arg.type)
            val v: Value<*> =
                this.getArgument(arg.name)?.value ?: arg.defaultValue
                    ?: NullValue.of().also {
                        if (!t.isNullable) {
                            throw IllegalStateException("No default value for non-nullable argument ${arg.name}")
                        }
                    }
            m[arg.name] = valueConverter.convert(t, v)
            m
        }
    )
}

internal fun <T : ViaductSchema.TypeDef> Type<*>.toTypeExpr(createTypeExpr: (String, Boolean, BitVector) -> ViaductSchema.TypeExpr<T>): ViaductSchema.TypeExpr<T> {
    val listNullable = BitVector.Builder()
    var currentNullableBit = 1L
    var t = this
    while (t !is TypeName) {
        if (t is ListType) {
            listNullable.add(currentNullableBit, 1)
            currentNullableBit = 1L
            t = t.type
        } else if (t is NonNullType) {
            currentNullableBit = 0L
            t = t.type
        } else {
            throw IllegalStateException("Unexpected GraphQL wrapper $this.")
        }
    }
    return createTypeExpr(t.name, (currentNullableBit == 1L), listNullable.build())
}

private fun RawTypeMap.toTypeExpr(type: Type<*>): ViaductSchema.TypeExpr<GJSchemaRaw.TypeDef> =
    type.toTypeExpr { baseTypeDefName, baseTypeNullable, listNullable ->
        ViaductSchema.TypeExpr(
            this[baseTypeDefName] ?: throw IllegalStateException("Type not found: $baseTypeDefName"),
            baseTypeNullable,
            listNullable
        )
    }

private fun RawTypeMap.toAppliedDirective(
    registry: TypeDefinitionRegistry,
    dir: Directive,
    valueConverter: ValueConverter
): ViaductSchema.AppliedDirective {
    val def =
        registry.getDirectiveDefinition(dir.name).orElse(null)
            ?: throw IllegalStateException("Directive @${dir.name} not found in schema.")
    return dir.toAppliedDirective(def, valueConverter) { this.toTypeExpr(it) }
}

private fun Iterable<Directive>.toAppliedDirectives(
    registry: TypeDefinitionRegistry,
    typeMap: RawTypeMap,
    valueConverter: ValueConverter
) = this.map { typeMap.toAppliedDirective(registry, it, valueConverter) }

private fun InputValueDefinition.defaultValue(
    typeMap: RawTypeMap,
    valueConverter: ValueConverter
) = if (defaultValue != null) {
    valueConverter.convert(typeMap.toTypeExpr(type), defaultValue)
} else {
    null
}

private fun Node<*>.toSourceLocation() = sourceLocation?.sourceName?.let { ViaductSchema.SourceLocation(it) }

private inline fun <T> GJSchemaRaw.TypeDef.guardedGet(v: T?): T = checkNotNull(v) { "Type ${this.name} has not been populated; call populate() first" }

private inline fun <T> GJSchemaRaw.TypeDef.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Type ${this.name} has not been populated; call populate() first" }
    return v
}

private inline fun <T> GJSchemaRaw.Directive.guardedGet(v: T?): T = checkNotNull(v) { "Directive ${this.name} has not been populated; call populate() first" }

private inline fun <T> GJSchemaRaw.Directive.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Directive ${this.name} has not been populated; call populate() first" }
    return v
}
