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
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.parseWrappers
import viaduct.utils.collections.BitVector
import viaduct.utils.timer.Timer

/** This is an implementation of the [ViaductSchema] classes that uses the
 *  `graphql.schema` classes from the graphql-java library as the underlying
 *  representation.
 *
 *  The [ViaductSchema] docs indicate that implementations should document
 *  how they represent "real" values for things like applied-directive arguments
 *  and input-field defaults.  As this class is based on graphql-java, it uses
 *  graphql-java's [graphql.language.Value] classes for this purpose, with a few
 *  twists.  First, with its [graphql.schema.InputValueWithState] class, graphql-java
 *  attempts to preserve the distinction between fields that have been explicitly set,
 *  and those that are null because they are nullable and have not been set.  We don't
 *  attempt to maintain this distinction.
 *
 *  Second, graphql-java has the type [graphql.language.NullValue] to represent an
 *  explicitly-set null value.  At the top level we convert these to actual null values
 *  (which is why our representation of "real values" is `Value<*>?` rather than `Value<*>`).
 *  However, we do _not_ do this recursively: if the top-level value is a list, for example,
 *  [graphql.language.NullValue] can be present as a list element.
 */
class GJSchema internal constructor(
    override val types: Map<String, TypeDef>,
    override val directives: Map<String, GJSchema.Directive>,
    gjSchema: GraphQLSchema
) : ViaductSchema {
    private fun rootDef(
        name: String?,
        stdName: String
    ) = run {
        val result = name?.let { types[it] }
        if (result != null) {
            if (result !is Object) throw IllegalArgumentException("$stdName type ($name) is not an object type.")
            result
        } else {
            null
        }
    }

    override val queryTypeDef: Object = rootDef(gjSchema.queryType.name, "Query")
        ?: throw IllegalStateException("Query name (${gjSchema.queryType.name}) not found.")

    override val mutationTypeDef: Object? = rootDef(gjSchema.mutationType?.name, "Mutation")
    override val subscriptionTypeDef: Object? = rootDef(gjSchema.subscriptionType?.name, "Subscription")

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

    // Internal for testing (GJSchemaCheck)
    internal fun toTypeExpr(gtype: GraphQLType): ViaductSchema.TypeExpr<TypeDef> {
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

    companion object {
        /** Convert collection of .graphqls files into a schema
         *  bridge.  (This function doesn't require callers to
         *  have a direct dependency on graphql-java.) */

        fun fromURLs(
            inputFiles: List<URL>,
            valueConverter: ValueConverter = ValueConverter.default
        ) = fromRegistry(readTypesFromURLs(inputFiles), valueConverter = valueConverter)

        fun fromFiles(
            inputFiles: List<File>,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
        ): GJSchema {
            val typeDefRegistry = timer.time("readTypesFromFiles") { readTypesFromFiles(inputFiles) }
            return fromRegistry(typeDefRegistry, timer, valueConverter)
        }

        /** Convert a graphql-java TypeDefinitionRegistry into
         *  a schema sketch. */
        fun fromRegistry(
            registry: TypeDefinitionRegistry,
            timer: Timer = Timer(),
            valueConverter: ValueConverter = ValueConverter.default,
        ): GJSchema {
            val unexecutableSchema =
                timer.time("makeUnexecutableSchema") {
                    UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
                }
            return timer.time("fromSchema") { fromSchema(unexecutableSchema, valueConverter) }
        }

        fun fromSchema(
            schema: GraphQLSchema,
            valueConverter: ValueConverter = ValueConverter.default
        ): GJSchema {
            // Phase 1: Create all TypeDef and Directive shells (just underlying def and name)
            val types = mutableMapOf<String, TypeDef>()
            for (def in schema.allTypesAsList) {
                val typeDef = when (def) {
                    is GraphQLScalarType -> Scalar(def, def.name)
                    is GraphQLEnumType -> Enum(def, def.name)
                    is GraphQLUnionType -> Union(def, def.name)
                    is GraphQLInterfaceType -> Interface(def, def.name)
                    is GraphQLObjectType -> Object(def, def.name)
                    is GraphQLInputObjectType -> Input(def, def.name)
                    else -> throw RuntimeException("Unexpected GraphQL type: $def")
                }
                types[def.name] = typeDef
            }

            val directives = schema.directives.associate { it.name to Directive(it, it.name) }

            // Phase 2: Create decoder and populate all types and directives
            val decoder = GraphQLSchemaDecoder(schema, types, valueConverter)

            types.values.forEach { typeDef ->
                when (typeDef) {
                    is Scalar -> typeDef.populate(decoder.createScalarExtensions(typeDef))
                    is Enum -> typeDef.populate(decoder.createEnumExtensions(typeDef))
                    is Union -> typeDef.populate(decoder.createUnionExtensions(typeDef))
                    is Interface -> typeDef.populate(
                        decoder.createInterfaceExtensions(typeDef),
                        decoder.computePossibleObjectTypes(typeDef)
                    )
                    is Object -> typeDef.populate(
                        decoder.createObjectExtensions(typeDef),
                        decoder.computeUnions(typeDef)
                    )
                    is Input -> typeDef.populate(decoder.createInputExtensions(typeDef))
                }
            }

            directives.values.forEach { directive ->
                decoder.populate(directive)
            }

            return GJSchema(types, directives, schema)
        }
    }

    // Well-designed GraphQL schemas are both immutable and highly
    // cyclical.  But the Kotlin(/Java) object-construction process
    // isn't friendly to immutable, cyclical data structures.  We
    // address this using a "mutate-then-freeze" pattern:
    //
    // Phase 1: Create all TypeDef "shells" with just their underlying
    // graphql-java definition (and extensionDefinitions where applicable).
    //
    // Phase 2: Use GraphQLSchemaDecoder to populate each TypeDef with
    // its cross-references (extensions, fields, appliedDirectives, etc.).
    // At this point the type map is fully populated, so we can resolve
    // any type reference.
    //
    // This pattern achieves direct references instead of map lookups,
    // single mutation point per type (the populate() method), and
    // tight encapsulation via nullable private backing fields.

    sealed interface Def : ViaductSchema.Def {
        val def: GraphQLNamedSchemaElement

        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }
    }

    sealed interface TypeDef :
        ViaductSchema.TypeDef,
        Def {
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
        override val def: GraphQLArgument,
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
        override val def: GraphQLDirective,
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
        override val def: GraphQLScalarType,
        name: String,
    ) : ViaductSchema.Scalar,
        TypeDefImpl(name) {
        private var mExtensions: List<ViaductSchema.Extension<Scalar, Nothing>>? = null

        override val extensions: List<ViaductSchema.Extension<Scalar, Nothing>> get() = guardedGet(mExtensions)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = extensions.flatMap { it.appliedDirectives }
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        internal fun populate(extensions: List<ViaductSchema.Extension<Scalar, Nothing>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
        }
    }

    class EnumValue internal constructor(
        override val def: GraphQLEnumValueDefinition,
        override val containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
    ) : ViaductSchema.EnumValue,
        Def {
        override val containingDef: Enum get() = containingExtension.def

        override fun toString() = describe()
    }

    class Enum internal constructor(
        override val def: GraphQLEnumType,
        name: String,
    ) : ViaductSchema.Enum,
        TypeDefImpl(name) {
        private var mExtensions: List<ViaductSchema.Extension<Enum, EnumValue>>? = null
        private var mValues: List<EnumValue>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.Extension<Enum, EnumValue>> get() = guardedGet(mExtensions)
        override val values: List<EnumValue> get() = guardedGet(mValues)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        override fun value(name: String): EnumValue? = values.find { name == it.name }

        internal fun populate(extensions: List<ViaductSchema.Extension<Enum, EnumValue>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mValues = extensions.flatMap { it.members }
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
        }
    }

    class Union internal constructor(
        override val def: GraphQLUnionType,
        name: String,
    ) : ViaductSchema.Union, TypeDefImpl(name) {
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
        override val def: GraphQLArgument,
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
        override val def: GraphQLFieldDefinition,
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
        argsFactory: (OutputField) -> List<FieldArg>,
    ) : Field(name, type, appliedDirectives, hasDefault, defaultValue) {
        // isOverride must be lazy because it accesses containingDef.supers which may not be populated yet
        override val isOverride by lazy { ViaductSchema.isOverride(this) }
        override val containingDef get() = containingExtension.def
        override val args: List<FieldArg> = argsFactory(this)
    }

    class InputField internal constructor(
        override val def: GraphQLInputObjectField,
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : Field(name, type, appliedDirectives, hasDefault, defaultValue) {
        // isOverride must be lazy because it accesses containingDef.supers which may not be populated yet
        override val isOverride by lazy { ViaductSchema.isOverride(this) }
        override val containingDef get() = containingExtension.def as Input
        override val args = emptyList<FieldArg>()
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
        override val def: GraphQLInterfaceType,
        name: String,
    ) : ViaductSchema.Interface, OutputRecord, TypeDefImpl(name) {
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

        override fun field(name: String) = super<OutputRecord>.field(name)

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
        override val def: GraphQLObjectType,
        name: String,
    ) : ViaductSchema.Object, OutputRecord, TypeDefImpl(name) {
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

        override fun field(name: String) = super<OutputRecord>.field(name)

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
        override val def: GraphQLInputObjectType,
        name: String,
    ) : ViaductSchema.Input,
        Record,
        TypeDefImpl(name) {
        private var mExtensions: List<ViaductSchema.Extension<Input, Field>>? = null
        private var mFields: List<Field>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.Extension<Input, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation

        override val possibleObjectTypes = setOf<Object>()

        internal fun populate(extensions: List<ViaductSchema.Extension<Input, Field>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            mExtensions = extensions
            mFields = extensions.flatMap { it.members }
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
        }
    }
}

private inline fun <T> GJSchema.TypeDef.guardedGet(v: T?): T = checkNotNull(v) { "Type ${this.name} has not been populated; call populate() first" }

private inline fun <T> GJSchema.TypeDef.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Type ${this.name} has not been populated; call populate() first" }
    return v
}

private inline fun <T> GJSchema.Directive.guardedGet(v: T?): T = checkNotNull(v) { "Directive ${this.name} has not been populated; call populate() first" }

private inline fun <T> GJSchema.Directive.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Directive ${this.name} has not been populated; call populate() first" }
    return v
}
