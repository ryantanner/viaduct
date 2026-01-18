package viaduct.graphql.schema

import graphql.language.NullValue
import graphql.language.Value
import viaduct.utils.collections.BitVector

/**
 * Abstract representation of a GraphQL schema.  The main entry into
 * a schema is the [types] map from type-names to
 * [ViaductSchema.TypeDef] instances.
 *
 * [ViaductSchema] defines a collection of interfaces that are
 * meant to be defined by different "implementations" of
 * [ViaductSchema], e.g., [GJSchema] is an implementation
 * that wraps a graphql-java schema.  To that end, there are no
 * default implementations for any function that returns a type
 * intended to be provided by an implementation.  This class
 * does provided a few default implementations for member
 * functions that return scalars like Boolean and String, and
 * a few static functions for default implementations of member
 * functions that return other types.
 *
 * There is an important invariant maintained by all implementations
 * of [ViaductSchema], which is that they are "closed."  Put
 * differently, object-equality is equality in the schema.  That is
 * within a [ViaductSchema], there's exactly one TypeDef object
 * for each unique type-definition in the GraphQL schema.  And the
 * same is true for field- and enum-value definitions.
 *
 * We have four implementations of [ViaductSchema]:
 *
 *  * `GJSchema` - wraps graphql-java's `GraphQLSchema` — a parsed, validated, and semantically analyzed schema.  Factory: ViaductSchema.fromGraphQLSchema
 *  * `GJSchemaRaw` - wraps graphql-java's `TypeDefinitionRegistry` — a parsed but not semantically validated schema.  Factory: ViaductSchema.fromTypeDefinitionRegistry
 *  * `BSchema` - native `ViaductSchema` implementation with a compact binary serialization format that enables fast loading and reduced memory footprint.  ViaductSchema.fromBinaryFile
 *  * `FilteredSchema` - projects an existing `ViaductSchema` through a filter to create a restricted view.  ViaductSchema.filter
 *
 * The first three of these are extensions on the ViaductSchema
 * companion object that need to be imported.  The fourth
 * is a built-in instance method.
 *
 * If you want to translate [ViaductSchema] object into
 * a graphql-java [TypeDefinitionRegistry], you can call
 * the memeber function [ViaductSchema.toRegistry] (and
 * extension functions).  Among other things this is used
 * to pretty-print schemas by using graphql-java's schema
 * printer.
 *
 * If you want to generate the binary file read by
 * `fromBinaryFile` call [ViaductSchema.toBinaryFile]
 * *also an extension function).
 *
 * [ViaductSchema] allows for types that have no fields, which
 * graphql-java does not.  To increase interoperability with
 * graphql-java and other toolsets, the
 * [ViaductSchema.toRegistry] function inserts a fake field
 * named [ViaductSchema.VIADUCT_IGNORE_SYMBOL] into every object,
 * interface, and input-object type in the schema. In addition,
 * it inserts the fake object-type [ViaductSchema.VIADUCT_IGNORE_SYMBOL]
 * into every schema, and adds this fake type as a fake member to
 * every union of the schema.  This ensures that pretty-printed versions
 * of [ViaductSchema] will be valid GraphQL schemas.  On the
 * other side, both [GJSchema] and [GJSchemaRaw] strip out these fake
 * entries and the fake type.
 *
 * There are a number of places in these classes where we need to
 * represent constant values, in particular the default values of
 * input fields and arguments as well as the values of
 * arguments to applied directives.  [ViaductSchema] uses GraphQL
 * Java's [Value<*>] classes to represent these values.  Keep in
 * mind that these type represent _syntactic_ constants, not
 * semantic ones.  Thus, for example, a [ObjectValue] constant
 * is used to represent both a constant for an GraphQL input
 * type as well as a constant for a JSON scalar.
 */
interface ViaductSchema {
    /** Map of all type definitions in the schema. */
    val types: Map<String, TypeDef>

    /** Map of all directive definitions in the schema. */
    val directives: Map<String, Directive>

    /**
     * Root query type if one defined.  (Unliked GraphQL itself
     * [ViaductSchema] allows for schemas with no root type
     * defined.)  If not null, guaranteed to be a member
     * of [types.values].
     */
    val queryTypeDef: Object?

    /**
     * Root mutation type if one defined.  If not null,
     * guaranteed to be a member of [types.values].
     */
    val mutationTypeDef: Object?

    /**
     * Root subscription type if one defined.  If not null,
     * guaranteed to be a member of [types.values].
     */
    val subscriptionTypeDef: Object?

    /**
     * Returns true if the schema has a custom schema definition, meaning:
     * - Any of the root types (query/mutation/subscription) have non-standard names
     */
    val hasCustomSchema: Boolean
        get() {
            // Check for non-standard root type names
            if (queryTypeDef != null && queryTypeDef?.name != "Query") return true
            if (mutationTypeDef != null && mutationTypeDef?.name != "Mutation") return true
            if (subscriptionTypeDef != null && subscriptionTypeDef?.name != "Subscription") return true
            return false
        }

    /** For testing. */
    object Empty : ViaductSchema {
        override val types = emptyMap<String, TypeDef>()
        override val directives = emptyMap<String, Directive>()
        override val queryTypeDef = null
        override val mutationTypeDef = null
        override val subscriptionTypeDef = null
    }

    /**
     * Produces a projection of [this] according to the
     * logic provided by [filter].  The result is first
     * checked for basic invariants: this check will
     * honor the flags passed in [schemaInvariantOptions].
     */
    fun filter(
        filter: SchemaFilter,
        schemaInvariantOptions: SchemaInvariantOptions = SchemaInvariantOptions.DEFAULT,
    ): ViaductSchema =
        filteredSchema(
            filter,
            this.types.entries,
            directives.entries,
            schemaInvariantOptions,
            queryTypeDef?.name,
            mutationTypeDef?.name,
            subscriptionTypeDef?.name
        )

    /**
     * The directive and arguments of a directive applied to a schema element
     * (e.g., a type-definition, field-definition, etc.).  This is a
     * data class with value-type semantics: [equals] and [hashCode]
     * are based on value equality, not on reference equality.
     *
     * AppliedDirectives are "dense", meaning there is a value for
     * _every_ argument of the directive, including "missing" ones for
     * arguments that either have a default or are nullable.  This is
     * consistent with [ViaductSchema] being for valid schemas, in
     * which case there needs to be values for all arguments.  This
     * dense representation means consumers don't have to chase down
     * directive definitions and apply defauling logic to them.
     *
     * The type parameter [D] allows implementations to preserve type
     * information about the directive definition.
     */
    data class AppliedDirective<out D : ViaductSchema.Directive> private constructor(
        val directive: D,
        val arguments: Map<String, Value<*>>
    ) {
        val name: String get() = directive.name

        override fun toString() =
            "@$name${
                if (arguments.entries.isNotEmpty()) {
                    "(${
                        arguments.entries.sortedBy { it.key }.joinToString(", ") {
                            "${it.key}: ${it.value}"
                        }
                    })"
                } else {
                    ""
                }
            }"

        companion object {
            /**
             * Factory method to create an AppliedDirective.
             * @param directive The directive definition
             * @param arguments A Map of argument names to values
             * @return An AppliedDirective instance
             */
            fun <D : ViaductSchema.Directive> of(
                directive: D,
                arguments: Map<String, Value<*>>
            ) = AppliedDirective(directive, arguments)
        }
    }

    /**
     * Not supported yet:  a placeholder for future use cases...
     */
    interface Selection {
        val subselections: Collection<Selection>
        val directives: Collection<ViaductSchema.AppliedDirective<*>>

        interface Conditional : Selection {
            val condition: TypeDef
        }

        interface Field : Selection {
            val fieldName: String
            val arguments: Map<String, Value<*>>
        }
    }

    enum class TypeDefKind {
        ENUM,
        INPUT,
        INTERFACE,
        SCALAR,
        OBJECT,
        UNION;

        /** True for enum and scalar types. */
        val isSimple
            get() = (this == SCALAR || this == ENUM)

        /** True for object, interface, and union types. */
        val isComposite
            get() = (this == OBJECT || this == INTERFACE || this == UNION)

        /** True for input types. */
        val isInput
            get() = (this == INPUT)

        /** True for output types (object, interface, union, scalar, enum). */
        val isOutput
            get() = (this != INPUT)
    }

    interface Directive : TopLevelDef {
        val containingSchema: ViaductSchema
        override val name: String
        val args: Collection<DirectiveArg>
        val allowedLocations: Set<Location>
        val isRepeatable: Boolean

        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>

        override fun describe() = "Directive<$name>[${if (isRepeatable) "repeatable on" else ""} ${allowedLocations.joinToString("| ")}]"

        /**
         * Locations in the GraphQL schema that a directive is allowed
         * to be applied.  Names come from the GraphQL specification.
         */
        enum class Location {
            QUERY,
            MUTATION,
            SUBSCRIPTION,
            FIELD,
            FRAGMENT_DEFINITION,
            FRAGMENT_SPREAD,
            INLINE_FRAGMENT,
            VARIABLE_DEFINITION,
            SCHEMA,
            SCALAR,
            OBJECT,
            FIELD_DEFINITION,
            ARGUMENT_DEFINITION,
            INTERFACE,
            UNION,
            ENUM,
            ENUM_VALUE,
            INPUT_OBJECT,
            INPUT_FIELD_DEFINITION
        }
    }

    data class SourceLocation(
        val sourceName: String
    )

    interface Extension<out D : TypeDef, out M : Def> {
        val def: D
        val members: Collection<M>
        val isBase: Boolean
        val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>
        val sourceLocation: SourceLocation?

        fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        companion object {
            fun <D : TypeDef, M : Def> of(
                def: D,
                memberFactory: (Extension<D, M>) -> Collection<M>,
                isBase: Boolean,
                appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>,
                sourceLocation: SourceLocation? = null
            ) = object : Extension<D, M> {
                override val def = def
                override val members = memberFactory(this)
                override val isBase = isBase
                override val appliedDirectives = appliedDirectives
                override val sourceLocation = sourceLocation
            }
        }
    }

    interface ExtensionWithSupers<out D : TypeDef, out M : Def> : Extension<D, M> {
        val supers: Collection<Interface>

        companion object {
            fun <D : TypeDef, M : Def> of(
                def: D,
                memberFactory: (Extension<D, M>) -> Collection<M>,
                isBase: Boolean,
                appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>,
                supers: Collection<Interface>,
                sourceLocation: SourceLocation? = null
            ) = object : ExtensionWithSupers<D, M> {
                override val def = def
                override val members = memberFactory(this)
                override val isBase = isBase
                override val appliedDirectives = appliedDirectives
                override val supers = supers
                override val sourceLocation = sourceLocation
            }
        }
    }

    /** Supertype of all type definitions, as well as field
     *  enum-value definitions.  Compare to GraphQLDirectiveContainer
     *  in graphql-java.
     */
    interface Def {
        val name: String
        val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>
        val sourceLocation: SourceLocation?

        fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }

        fun describe(): String

        /** Override this in schema implementations that wrap other definitions, e.g. FilteredSchema */
        fun unwrapAll(): Def = this
    }

    /**
     * Base type for top-level definitions that appear in a schema (Directive and TypeDef).
     */
    interface TopLevelDef : Def

    interface TypeDef : TopLevelDef {
        val containingSchema: ViaductSchema
        val kind: TypeDefKind
        val extensions: Collection<Extension<TypeDef, Def>>

        /** True for scalar and enumeration types. */
        val isSimple: Boolean
            get() = kind.isSimple

        /** True for object, interface, and union types. */
        val isComposite: Boolean
            get() = kind.isComposite

        /** True for input types. */
        val isInput: Boolean
            get() = kind.isInput

        /** True for output types (object, interface, union, scalar, enum). */
        val isOutput: Boolean
            get() = kind.isOutput

        /** Returns a _nullable_ type-expr for this def. */
        fun asTypeExpr(): TypeExpr<TypeDef>

        /** Returns the set of Object types possibly subsumed by this
         *  type definition.  It's the empty set for any type other
         *  than Object, Interface, or Union. */
        val possibleObjectTypes: Set<Object>
    }

    interface Scalar : TypeDef {
        override val kind get() = TypeDefKind.SCALAR
        override val extensions: Collection<Extension<Scalar, Nothing>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Scalar $this has no extensions" }
            it.sourceLocation
        }

        override fun describe() = "Scalar<$name>"
    }

    interface EnumValue : Def {
        val containingDef: Enum
        val containingExtension: Extension<ViaductSchema.Enum, ViaductSchema.EnumValue>
        override val sourceLocation get() = containingExtension.sourceLocation

        override fun describe() = "EnumValue<$name>"
    }

    interface Enum : TypeDef {
        override val kind get() = TypeDefKind.ENUM

        val values: Collection<EnumValue>
        override val extensions: Collection<Extension<Enum, EnumValue>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Enum $this has no extensions" }
            it.sourceLocation
        }

        fun value(name: String): EnumValue?

        override fun describe() = "Enum<$name>"
    }

    interface Union : TypeDef {
        override val kind get() = TypeDefKind.UNION
        override val extensions: Collection<Extension<Union, Object>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Union $this has no extensions" }
            it.sourceLocation
        }

        override fun describe() = "Union<$name>"
    }

    interface HasDefaultValue : Def {
        val containingDef: Def
        val type: TypeExpr<TypeDef>
        override val sourceLocation get() = containingDef.sourceLocation

        /**
         * Returns the default value; throws NoSuchElementException if none is explicit in the schema.
         */
        val defaultValue: Value<*>

        /** Returns true if there's an explicitly defined default. */
        val hasDefault: Boolean

        /** Returns the explicit default value if there is one, or NullValue if the field
         *  is a nullable field of a non-Object containing definition.
         *  Throws NoSuchElementException for the rest.
         */
        val effectiveDefaultValue: Value<*>
            get() =
                when {
                    hasDefault -> defaultValue
                    type.isNullable && (containingDef as? TypeDef)?.isOutput != true -> NullValue.of()
                    else -> throw NoSuchElementException("No default value for ${this.describe()}")
                }

        /** Returns true iff [effectiveDefaultValue] would _not_ throw an exception. */
        val hasEffectiveDefault
            get() =
                hasDefault || (type.isNullable && (containingDef as? TypeDef)?.isOutput != true)
    }

    interface Arg : HasDefaultValue

    interface FieldArg : Arg {
        override val containingDef: Field

        override fun describe() = "FieldArg<${containingDef.containingDef.name}.${containingDef.name}.$name:$type>"
    }

    interface DirectiveArg : Arg {
        override val containingDef: Directive

        override fun describe() = "DirectiveArg<${containingDef.name}.$name:$type>"
    }

    /** Represents fields for all of interface, object, and input types. */
    interface Field : HasDefaultValue {
        override val containingDef: Record
        val containingExtension: Extension<Record, Field>
        override val sourceLocation get() = containingExtension.sourceLocation

        /** This is ordered based on the ordering in the schema source text.
         *  Important because code generators may want to order
         *  generated function-signatures in an order that matches
         *  what's in the schema. */
        val args: Collection<FieldArg>

        /** For fields in interfaces and objects, this is true if
         *  this field definition is overriding one from an
         *  implemented interface.  False in all other cases.
         */
        val isOverride: Boolean

        val hasArgs get() = args.isNotEmpty()

        override fun describe() = "Field<$name:$type>"
    }

    /** Supertype for GraphQL interface-, input-, and object-types.
     *  This common interface is useful because various aspects of codegen
     *  work the same for all three types. */
    interface Record : TypeDef {
        val fields: Collection<Field>
        override val extensions: Collection<Extension<Record, Field>>
        override val sourceLocation get() = extensions.firstOrNull().let {
            requireNotNull(it) { "Record $this has no extensions" }
            it.sourceLocation
        }

        fun field(name: String): Field?

        // override with "= super.field(path) as Field" to get more precise typing
        fun field(path: Iterable<String>): Field
    }

    /** Supertype for GraphQL interface- and object-types (output record types).
     *  These types have extensions with supers, unlike Input types. */
    interface OutputRecord : Record {
        override val extensions: Collection<ExtensionWithSupers<OutputRecord, Field>>

        /** The list of interfaces directly implemented by this type. */
        val supers: Collection<Interface>
    }

    interface Interface : OutputRecord {
        override val kind get() = TypeDefKind.INTERFACE
        override val extensions: Collection<ExtensionWithSupers<Interface, Field>>

        override fun describe() = "Interface<$name>"
    }

    interface Object : OutputRecord {
        override val kind get() = TypeDefKind.OBJECT
        override val extensions: Collection<ExtensionWithSupers<Object, Field>>

        /** The list of unions that contain this object type. */
        val unions: Collection<Union>

        override fun describe() = "Object<$name>"
    }

    interface Input : Record {
        override val kind get() = TypeDefKind.INPUT
        override val extensions: Collection<Extension<Input, Field>>

        override fun describe() = "Input<$name>"
    }

    /** A type expression is the type of a GraphQL value.
     *  Type expressions are used to provide static types
     *  to fields and to arguments.
     *
     *  The property `baseTypeDef` contains the base-type
     *  of the expression.
     *
     *  The property `baseTypeNullable` indicates whether or
     *  not that base-type is nullable.
     *
     *  The property `listNullable` is a bit-vector describing
     *  the list-structure of the type (if any).  The size of
     *  this vector indicates the depth of the list-nesting
     *  (size zero means the type is not a list, size one
     *  means a list of the base type, size two means a list
     *  of lists of the base type, and so forth).
     *
     *  For each list depth, the corresponding bit in `listDepth`
     *  indicates whether or not that list is nullable.  (Note
     *  bit zero corresponds to list-depth zero corresponds to
     *  the _outermost_ list-wrapper for the type.)
     *
     *  Examples:
     *
     *     - base= String, baseNullable= true, listNullable.size=0
     *     this would a nullable String.
     *
     *     - base= Int, baseNullable = false, listNullable=0b10
     *     this would be a non-nullable list of nullable lists
     *     of non-nullable integers.  (Why?  The outer-most
     *     non-nullable is list-depth zero, which corresponds
     *     to bit zero (LSB), whose value is zero - which means
     *     non-nullable.  The inner-list is list-depth one, which
     *     corresponds to bit one, whose value is one - which means
     *     nullable.)
     *
     *  The equality (and hashcode) operations reflect the following
     *  assumptions: two type-expressions are equal iff (a) their
     *  type-names are equal (because they are assumed to come from
     *  the same schema), their base-type nullable indicators are
     *  equal, and their listNullable bit vectors are equal.
     *
     *  The type parameter `T` allows implementations to preserve
     *  type information about the baseTypeDef.
     */
    class TypeExpr<out T : TypeDef>(
        val baseTypeDef: T,
        val baseTypeNullable: Boolean = true,
        val listNullable: BitVector = NO_WRAPPERS
    ) {
        /** Scalar or enum type. */
        val isSimple get() = (listNullable.size == 0 && baseTypeDef.isSimple)
        val isList get() = (listNullable.size != 0)
        val isNullable get() = if (isList) listNullable.get(0) else baseTypeNullable

        val listDepth get() = listNullable.size

        /** Strip all list wrappers but maintain both base type and
         *  its nullability. */
        fun unwrapLists(): TypeExpr<T> = TypeExpr(baseTypeDef, baseTypeNullable, NO_WRAPPERS)

        /**
         * Unwrap one level of list depth.
         *
         * @returns null if [this] is not a list, the unwrapped list otherwise
         */
        fun unwrapList(): TypeExpr<T>? =
            if (listNullable.size == 0) {
                null
            } else {
                TypeExpr(baseTypeDef, baseTypeNullable, listNullable.lsr())
            }

        fun nullableAtDepth(depth: Int): Boolean {
            require(depth in 0..listDepth)
            return if (isList && depth < listDepth) listNullable.get(depth) else baseTypeNullable
        }

        override fun equals(other: Any?) =
            other is TypeExpr<*> &&
                baseTypeDef.name == other.baseTypeDef.name &&
                baseTypeNullable == other.baseTypeNullable &&
                listNullable == other.listNullable

        override fun hashCode() = (31 * 31) * baseTypeDef.name.hashCode() + 31 * baseTypeNullable.hashCode() + listNullable.hashCode()

        override fun toString() = "${unparseWrappers()} ${baseTypeDef.name}"

        companion object {
            val NO_WRAPPERS = BitVector(0)
        }
    }

    companion object {
        // Used to indicate that a field or type should be ignored by ViaductSchema
        // but allowed to be used in other intermediate representations like GJSchema, GJSchemaRaw,
        // compilation schema sdl files, etc.
        final const val VIADUCT_IGNORE_SYMBOL = "VIADUCT_IGNORE"

        // use in impl class as "override fun field(...): Field = ViaductSchema.field(this, rec, path)"
        inline fun <reified T : Field> field(
            rec: Record,
            path: Iterable<String>
        ): T {
            val pathIter = path.iterator()
            if (!pathIter.hasNext()) throw IllegalArgumentException("Path must have at least one member.")
            var i = 0
            var result: Field? = rec.field(pathIter.next())
            while (true) {
                if (result == null) throw IllegalArgumentException("Missing path segment ($path @ $i).")
                if (!pathIter.hasNext()) break
                val subrec = result.type.baseTypeDef
                if (subrec !is Record) throw IllegalArgumentException("Non-record path segment ($path @ $i).")
                result = subrec.field(pathIter.next())
                i++
            }
            return result!! as T
        }

        // use as "override val isOverride by lazy { ViaductSchema.isOverride(this) }"
        fun isOverride(field: Field): Boolean {
            val containingDef = field.containingDef
            if (containingDef !is OutputRecord) return false
            for (s in containingDef.supers) {
                if (s.field(field.name) != null) return true
            }
            return false
        }
    }
}
