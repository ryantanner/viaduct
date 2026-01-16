package viaduct.graphql.schema

import viaduct.invariants.InvariantChecker
import viaduct.utils.collections.BitVector

private typealias TypeMap = Map<String, FilteredSchema.TypeDef<out ViaductSchema.TypeDef>>

/** See KDoc for [ViaductSchema] for a background.
 *
 *  The `xyzTypeNameFromBaseSchema` parameters here work a bit differently from
 *  their analogs in `GJSchemaRaw`.  In `GJSchemaRaw`, they are used to set
 *  the root types, and thus if they name a non-existent type, the right behavior
 *  is to fail.  Here, the base schema is assumed to have (or not have) a root type
 *  defs, and the `xyzTypeNameFromBaseSchema` is intended to pass the name of those
 *  types in.  Now, it might be the case that those types get filtered out, so
 *  it's _not_ an error if they name non-existent types.  The code can't actually
 *  know, however, if `xyzTypeNameFromBaseSchema` is actually from the base schema,
 *  so it _does_ check to ensure that it names an object type.
 *
 *  (There's a bigger issue here that FilteredSchema does not take an actual schema
 *  as a constructor argument. The reason is that ViaductSchema itself is not
 *  parameterized by a `TypeDef` param, which we'd need to make the typing of
 *  FilteredSchema to work. Not sure if we want to fix this.)
 */
class FilteredSchema<T : ViaductSchema.TypeDef>(
    filter: SchemaFilter,
    schemaEntries: Iterable<Map.Entry<String, T>>,
    directiveEntries: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
    schemaInvariantOptions: SchemaInvariantOptions,
    queryTypeNameFromBaseSchema: String?,
    mutationTypeNameFromBaseSchema: String?,
    subscriptionTypeNameFromBaseSchema: String?
) : ViaductSchema {
    override val types: Map<String, TypeDef<out T>>
    override val directives: Map<String, Directive<*>>

    init {
        // Phase 1: Create all TypeDef shells (no filter or defs passed to constructors)
        val defs = buildMap {
            schemaEntries
                .filter { (_, value) -> filter.includeTypeDef(value) }
                .forEach { (k, v) ->
                    val shell = when (v) {
                        is ViaductSchema.Enum -> Enum(v, v.name)
                        is ViaductSchema.Input -> Input(v, v.name)
                        is ViaductSchema.Interface -> Interface(v, v.name)
                        is ViaductSchema.Object -> Object(v, v.name)
                        is ViaductSchema.Union -> Union(v, v.name)
                        is ViaductSchema.Scalar -> Scalar(v, v.name)
                        else -> throw IllegalArgumentException("Unexpected type definition $v")
                    }
                    put(k, shell)
                }
        }

        // Create directive shells
        directives = directiveEntries.associate { (k, v) -> k to Directive(v, v.name) }

        // Phase 2: Create decoder and populate all types and directives
        val decoder = FilteredSchemaDecoder(filter, defs)

        for (typeDef in defs.values) {
            when (typeDef) {
                is Scalar<*> -> decoder.decodeScalarExtensions(typeDef).let {
                    typeDef.populate(it.appliedDirectives, it.sourceLocation)
                }
                is Enum<*> -> typeDef.populate(
                    decoder.createEnumExtensions(typeDef)
                )
                is Input<*> -> typeDef.populate(
                    decoder.createInputExtensions(typeDef)
                )
                is Union<*> -> typeDef.populate(
                    decoder.createUnionExtensions(typeDef)
                )
                is Interface<*> -> {
                    val filteredSupers = decoder.computeFilteredSupers(typeDef.unfilteredDef)
                    typeDef.populate(
                        decoder.createInterfaceExtensions(typeDef, filteredSupers),
                        decoder.computePossibleObjectTypes(typeDef)
                    )
                }
                is Object<*> -> {
                    val filteredSupers = decoder.computeFilteredSupers(typeDef.unfilteredDef)
                    typeDef.populate(
                        decoder.createObjectExtensions(typeDef, filteredSupers),
                        decoder.computeFilteredUnions(typeDef)
                    )
                }
            }
        }

        for (directive in directives.values) {
            decoder.populate(directive)
        }

        types = defs

        val violations = InvariantChecker()
        checkBridgeSchemaInvariants(this, violations, schemaInvariantOptions)
        violations.assertEmptyMultiline("FilteredSchema failed the following invariant checks:\n")
    }

    private fun rootDef(nameFromBaseSchema: String?): ViaductSchema.Object? {
        // As noted earlier, we shouldn't fail if the named type doesn't exist
        val result = nameFromBaseSchema?.let { types[it] }
        if (result != null && result !is ViaductSchema.Object) {
            throw IllegalArgumentException("$result is not an object type.")
        }
        return result as? ViaductSchema.Object
    }

    override val queryTypeDef = rootDef(queryTypeNameFromBaseSchema)
    override val mutationTypeDef = rootDef(mutationTypeNameFromBaseSchema)
    override val subscriptionTypeDef = rootDef(subscriptionTypeNameFromBaseSchema)

    override fun toString() = types.toString()

    sealed interface Def<D : ViaductSchema.Def> : ViaductSchema.Def {
        val unfilteredDef: D

        override fun unwrapAll(): ViaductSchema.Def = this.unfilteredDef.unwrapAll()

        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }
    }

    sealed interface TypeDef<T : ViaductSchema.TypeDef> :
        Def<T>,
        ViaductSchema.TypeDef {
        override fun asTypeExpr(): TypeExpr

        override val possibleObjectTypes: Set<Object<out ViaductSchema.Object>>
    }

    sealed class TypeDefImpl<T : ViaductSchema.TypeDef>(
        override val name: String
    ) : TypeDef<T> {
        override fun toString() = describe()

        override fun asTypeExpr() = TypeExpr(this, true)

        open override val possibleObjectTypes: Set<Object<out ViaductSchema.Object>> get() = emptySet()
    }

    sealed class Arg<D : ViaductSchema.Def, A : ViaductSchema.Arg>(
        name: String,
        type: TypeExpr,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : HasDefaultValue<D, A>(name, type, appliedDirectives, hasDefault, defaultValue),
        ViaductSchema.Arg

    interface HasArgs<D : ViaductSchema.Def> :
        Def<D>,
        ViaductSchema.HasArgs {
        override val args: List<Arg<D, out ViaductSchema.Arg>>
    }

    class DirectiveArg<D : ViaductSchema.Directive, A : ViaductSchema.DirectiveArg> internal constructor(
        override val unfilteredDef: A,
        override val containingDef: Directive<D>,
        name: String,
        type: TypeExpr,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : Arg<D, A>(
            name,
            type,
            appliedDirectives,
            hasDefault,
            defaultValue,
        ),
        ViaductSchema.DirectiveArg {
        override fun toString() = describe()
    }

    class Directive<D : ViaductSchema.Directive> internal constructor(
        override val unfilteredDef: D,
        override val name: String,
    ) : HasArgs<D>,
        ViaductSchema.Directive {
        private var mIsRepeatable: Boolean? = null
        private var mAllowedLocations: Set<ViaductSchema.Directive.Location>? = null
        private var mSourceLocation: ViaductSchema.SourceLocation? = null
        private var mArgs: List<DirectiveArg<D, *>>? = null

        override val isRepeatable: Boolean get() = guardedGet(mIsRepeatable)
        override val allowedLocations: Set<ViaductSchema.Directive.Location> get() = guardedGet(mAllowedLocations)
        override val sourceLocation: ViaductSchema.SourceLocation? get() = guardedGetNullable(mSourceLocation, mArgs)
        override val args: List<DirectiveArg<D, *>> get() = guardedGet(mArgs)

        override val appliedDirectives: List<ViaductSchema.AppliedDirective> = emptyList()

        internal fun populate(
            isRepeatable: Boolean,
            allowedLocations: Set<ViaductSchema.Directive.Location>,
            sourceLocation: ViaductSchema.SourceLocation?,
            args: List<DirectiveArg<D, *>>
        ) {
            check(mArgs == null) { "Directive $name has already been populated; populate() can only be called once" }
            mIsRepeatable = isRepeatable
            mAllowedLocations = allowedLocations
            mSourceLocation = sourceLocation
            mArgs = args
        }

        override fun toString() = describe()
    }

    class Scalar<S : ViaductSchema.Scalar> internal constructor(
        override val unfilteredDef: S,
        name: String,
    ) : TypeDefImpl<S>(name),
        ViaductSchema.Scalar {
        private var mSourceLocation: ViaductSchema.SourceLocation? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = guardedGetNullable(mSourceLocation, mAppliedDirectives)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)

        internal fun populate(
            appliedDirectives: List<ViaductSchema.AppliedDirective>,
            sourceLocation: ViaductSchema.SourceLocation?
        ) {
            check(mAppliedDirectives == null) { "Type $name has already been populated; populate() can only be called once" }
            mAppliedDirectives = appliedDirectives
            mSourceLocation = sourceLocation
        }
    }

    class EnumValue<E : ViaductSchema.Enum, V : ViaductSchema.EnumValue> internal constructor(
        override val unfilteredDef: V,
        override val containingExtension: ViaductSchema.Extension<Enum<E>, EnumValue<E, *>>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
    ) : Def<V>,
        ViaductSchema.EnumValue {
        override val containingDef: Enum<E> get() = containingExtension.def

        override fun toString() = describe()
    }

    class Enum<E : ViaductSchema.Enum> internal constructor(
        override val unfilteredDef: E,
        name: String,
    ) : TypeDefImpl<E>(name),
        ViaductSchema.Enum {
        private var mExtensions: List<ViaductSchema.Extension<Enum<E>, EnumValue<E, *>>>? = null
        private var mValues: List<EnumValue<E, *>>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.Extension<Enum<E>, EnumValue<E, *>>>
            get() = guardedGet(mExtensions)
        override val values: List<EnumValue<E, *>>
            get() = guardedGet(mValues)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
            get() = guardedGet(mAppliedDirectives)

        override fun value(name: String): EnumValue<E, *>? = values.find { name == it.name }

        @Suppress("UNCHECKED_CAST")
        internal fun populate(extensions: List<ViaductSchema.Extension<Enum<*>, EnumValue<*, *>>>) {
            check(mExtensions == null) { "Type $name has already been populated" }
            mExtensions = extensions as List<ViaductSchema.Extension<Enum<E>, EnumValue<E, *>>>
            mValues = mExtensions!!.flatMap { it.members }
            mAppliedDirectives = mExtensions!!.flatMap { it.appliedDirectives }
        }
    }

    class Union<U : ViaductSchema.Union> internal constructor(
        override val unfilteredDef: U,
        name: String,
    ) : TypeDefImpl<U>(name), ViaductSchema.Union {
        private var mExtensions: List<ViaductSchema.Extension<Union<U>, Object<*>>>? = null
        private var mPossibleObjectTypes: Set<Object<*>>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.Extension<Union<U>, Object<*>>>
            get() = guardedGet(mExtensions)
        override val possibleObjectTypes: Set<Object<*>>
            get() = guardedGet(mPossibleObjectTypes)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
            get() = guardedGet(mAppliedDirectives)

        @Suppress("UNCHECKED_CAST")
        internal fun populate(extensions: List<ViaductSchema.Extension<Union<*>, Object<*>>>) {
            check(mExtensions == null) { "Type $name has already been populated" }
            mExtensions = extensions as List<ViaductSchema.Extension<Union<U>, Object<*>>>
            mPossibleObjectTypes = mExtensions!!.flatMap { it.members }.toSet()
            mAppliedDirectives = mExtensions!!.flatMap { it.appliedDirectives }
        }
    }

    sealed class HasDefaultValue<P : ViaductSchema.Def, H : ViaductSchema.HasDefaultValue>(
        override val name: String,
        override val type: TypeExpr,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        override val hasDefault: Boolean,
        private val mDefaultValue: Any?,
    ) : ViaductSchema.HasDefaultValue, Def<H> {
        // Leave abstract so we can narrow the type
        abstract override val containingDef: Def<P>

        override val defaultValue: Any?
            get() =
                if (hasDefault) {
                    mDefaultValue
                } else {
                    throw NoSuchElementException("No default value for ${this.describe()}")
                }
    }

    class FieldArg<R : ViaductSchema.Record, F : ViaductSchema.Field, A : ViaductSchema.FieldArg> internal constructor(
        override val unfilteredDef: A,
        override val containingDef: Field<R, F>,
        name: String,
        type: TypeExpr,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : Arg<F, A>(
            name,
            type,
            appliedDirectives,
            hasDefault,
            defaultValue,
        ),
        ViaductSchema.FieldArg {
        override fun toString() = describe()
    }

    class Field<R : ViaductSchema.Record, F : ViaductSchema.Field> internal constructor(
        override val unfilteredDef: F,
        override val containingDef: Record<R>,
        override val containingExtension: ViaductSchema.Extension<Record<R>, Field<R, *>>,
        name: String,
        type: TypeExpr,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
        argsFactory: (Field<R, F>) -> List<FieldArg<R, F, *>>,
    ) : HasDefaultValue<R, F>(
            name,
            type,
            appliedDirectives,
            hasDefault,
            defaultValue,
        ),
        HasArgs<F>,
        ViaductSchema.Field {
        override val args = argsFactory(this)
        override val isOverride by lazy { ViaductSchema.isOverride(this) }

        override fun toString() = describe()
    }

    sealed interface Record<R : ViaductSchema.Record> :
        TypeDef<R>,
        ViaductSchema.Record {
        override val fields: List<Field<R, out ViaductSchema.Field>>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field<R, out ViaductSchema.Field> = ViaductSchema.field(this, path)

        override val supers: List<Interface<*>>
        override val unions: List<Union<*>>
    }

    class Interface<I : ViaductSchema.Interface> internal constructor(
        override val unfilteredDef: I,
        name: String,
    ) : TypeDefImpl<I>(name), Record<I>, ViaductSchema.Interface {
        override val unions = emptyList<Union<*>>()

        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Interface<I>, Field<I, *>>>? = null
        private var mFields: List<Field<I, *>>? = null
        private var mSupers: List<Interface<*>>? = null
        private var mPossibleObjectTypes: Set<Object<*>>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.ExtensionWithSupers<Interface<I>, Field<I, *>>>
            get() = guardedGet(mExtensions)
        override val fields: List<Field<I, *>>
            get() = guardedGet(mFields)
        override val supers: List<Interface<*>>
            get() = guardedGet(mSupers)
        override val possibleObjectTypes: Set<Object<*>>
            get() = guardedGet(mPossibleObjectTypes)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
            get() = guardedGet(mAppliedDirectives)

        @Suppress("UNCHECKED_CAST")
        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Interface<*>, Field<*, *>>>,
            possibleObjectTypes: Set<Object<*>>
        ) {
            check(mExtensions == null) { "Type $name has already been populated" }
            mExtensions = extensions as List<ViaductSchema.ExtensionWithSupers<Interface<I>, Field<I, *>>>
            mFields = mExtensions!!.flatMap { it.members }
            mSupers = (mExtensions!!.flatMap { it.supers } as List<Interface<*>>).distinct()
            mPossibleObjectTypes = possibleObjectTypes
            mAppliedDirectives = mExtensions!!.flatMap { it.appliedDirectives }
        }
    }

    class Object<O : ViaductSchema.Object> internal constructor(
        override val unfilteredDef: O,
        name: String,
    ) : TypeDefImpl<O>(name), Record<O>, ViaductSchema.Object {
        override val possibleObjectTypes = setOf(this)

        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Object<O>, Field<O, *>>>? = null
        private var mFields: List<Field<O, *>>? = null
        private var mSupers: List<Interface<*>>? = null
        private var mUnions: List<Union<*>>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.ExtensionWithSupers<Object<O>, Field<O, *>>>
            get() = guardedGet(mExtensions)
        override val fields: List<Field<O, *>>
            get() = guardedGet(mFields)
        override val supers: List<Interface<*>>
            get() = guardedGet(mSupers)
        override val unions: List<Union<*>>
            get() = guardedGet(mUnions)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
            get() = guardedGet(mAppliedDirectives)

        @Suppress("UNCHECKED_CAST")
        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Object<*>, Field<*, *>>>,
            unions: List<Union<*>>
        ) {
            check(mExtensions == null) { "Type $name has already been populated" }
            mExtensions = extensions as List<ViaductSchema.ExtensionWithSupers<Object<O>, Field<O, *>>>
            mFields = mExtensions!!.flatMap { it.members }
            mSupers = (mExtensions!!.flatMap { it.supers } as List<Interface<*>>).distinct()
            mUnions = unions
            mAppliedDirectives = mExtensions!!.flatMap { it.appliedDirectives }
        }
    }

    class Input<I : ViaductSchema.Input> internal constructor(
        override val unfilteredDef: I,
        name: String,
    ) : TypeDefImpl<I>(name),
        Record<I>,
        ViaductSchema.Input {
        override val supers = emptyList<Interface<*>>()
        override val unions = emptyList<Union<*>>()

        private var mExtensions: List<ViaductSchema.Extension<Input<I>, Field<I, *>>>? = null
        private var mFields: List<Field<I, *>>? = null
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null

        override val extensions: List<ViaductSchema.Extension<Input<I>, Field<I, *>>>
            get() = guardedGet(mExtensions)
        override val fields: List<Field<I, *>>
            get() = guardedGet(mFields)
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>
            get() = guardedGet(mAppliedDirectives)

        @Suppress("UNCHECKED_CAST")
        internal fun populate(extensions: List<ViaductSchema.Extension<Input<*>, Field<*, *>>>) {
            check(mExtensions == null) { "Type $name has already been populated" }
            mExtensions = extensions as List<ViaductSchema.Extension<Input<I>, Field<I, *>>>
            mFields = mExtensions!!.flatMap { it.members }
            mAppliedDirectives = mExtensions!!.flatMap { it.appliedDirectives }
        }
    }

    class TypeExpr internal constructor(
        override val baseTypeDef: TypeDef<out ViaductSchema.TypeDef>,
        override val baseTypeNullable: Boolean,
        override val listNullable: BitVector = ViaductSchema.TypeExpr.NO_WRAPPERS,
    ) : ViaductSchema.TypeExpr() {
        override fun unwrapLists() = TypeExpr(baseTypeDef, baseTypeNullable)

        override fun unwrapList(): TypeExpr? =
            if (listNullable.size == 0) {
                null
            } else {
                TypeExpr(baseTypeDef, baseTypeNullable, listNullable.lsr())
            }
    }
}

private inline fun <T> FilteredSchema.TypeDef<*>.guardedGet(v: T?): T = checkNotNull(v) { "Type ${this.name} has not been populated; call populate() first" }

private inline fun <T> FilteredSchema.TypeDef<*>.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Type ${this.name} has not been populated; call populate() first" }
    return v
}

private inline fun <T> FilteredSchema.Directive<*>.guardedGet(v: T?): T = checkNotNull(v) { "Directive ${this.name} has not been populated; call populate() first" }

private inline fun <T> FilteredSchema.Directive<*>.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "Directive ${this.name} has not been populated; call populate() first" }
    return v
}

/**
 * Transforms unfiltered ViaductSchema elements into filtered ViaductSchema elements.
 * This class centralizes all filtering and transformation logic, separating it from
 * the TypeDef classes themselves.
 *
 * Analogous to TypeDefinitionRegistryDecoder for GJSchemaRaw and GraphQLSchemaDecoder for GJSchema.
 */
internal class FilteredSchemaDecoder<T : ViaductSchema.TypeDef>(
    private val filter: SchemaFilter,
    private val filteredTypes: Map<String, FilteredSchema.TypeDef<out T>>
) {
    // ========== Core: Type Resolution ==========

    fun getFilteredType(name: String): FilteredSchema.TypeDef<out T>? = filteredTypes[name]

    // ========== Scalar ==========

    data class ScalarExtensions(
        val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        val sourceLocation: ViaductSchema.SourceLocation?
    )

    fun <S : ViaductSchema.Scalar> decodeScalarExtensions(scalar: FilteredSchema.Scalar<S>): ScalarExtensions =
        ScalarExtensions(
            scalar.unfilteredDef.appliedDirectives.toList(),
            scalar.unfilteredDef.sourceLocation
        )

    // ========== Enum ==========

    fun <E : ViaductSchema.Enum> createEnumExtensions(enumDef: FilteredSchema.Enum<E>): List<ViaductSchema.Extension<FilteredSchema.Enum<E>, FilteredSchema.EnumValue<E, *>>> =
        enumDef.unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = enumDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter(filter::includeEnumValue)
                        .map { FilteredSchema.EnumValue(it, ext, it.name, it.appliedDirectives.toList()) }
                },
                isBase = unfilteredExt == enumDef.unfilteredDef.extensions.first(),
                appliedDirectives = unfilteredExt.appliedDirectives,
                sourceLocation = unfilteredExt.sourceLocation
            )
        }

    // ========== Input ==========

    fun <I : ViaductSchema.Input> createInputExtensions(inputDef: FilteredSchema.Input<I>): List<ViaductSchema.Extension<FilteredSchema.Input<I>, FilteredSchema.Field<I, *>>> =
        inputDef.unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = inputDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter { filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef) }
                        .map { createField(it, inputDef, ext) }
                },
                isBase = unfilteredExt == inputDef.unfilteredDef.extensions.first(),
                appliedDirectives = unfilteredExt.appliedDirectives,
                sourceLocation = unfilteredExt.sourceLocation
            )
        }

    // ========== Union ==========

    fun <U : ViaductSchema.Union> createUnionExtensions(unionDef: FilteredSchema.Union<U>): List<ViaductSchema.Extension<FilteredSchema.Union<U>, FilteredSchema.Object<*>>> =
        unionDef.unfilteredDef.extensions.map { unfilteredExt ->
            ViaductSchema.Extension.of(
                def = unionDef,
                memberFactory = { _ ->
                    unfilteredExt.members
                        .filter(filter::includeTypeDef)
                        .map { filteredTypes[it.name] as FilteredSchema.Object<*> }
                },
                isBase = unfilteredExt == unionDef.unfilteredDef.extensions.first(),
                appliedDirectives = unfilteredExt.appliedDirectives,
                sourceLocation = unfilteredExt.sourceLocation
            )
        }

    // ========== Interface ==========

    fun <I : ViaductSchema.Interface> createInterfaceExtensions(
        interfaceDef: FilteredSchema.Interface<I>,
        filteredSupers: List<FilteredSchema.Interface<*>>
    ): List<ViaductSchema.ExtensionWithSupers<FilteredSchema.Interface<I>, FilteredSchema.Field<I, *>>> {
        val superNames = filteredSupers.map { it.name }.toSet()
        return interfaceDef.unfilteredDef.extensions.map { unfilteredExt ->
            val newSupers = unfilteredExt.supers
                .filter { superNames.contains(it.name) }
                .map { filteredTypes[it.name] as FilteredSchema.Interface<*> }
            ViaductSchema.ExtensionWithSupers.of(
                def = interfaceDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter { filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef) }
                        .map { createField(it, interfaceDef, ext) }
                },
                isBase = unfilteredExt == interfaceDef.unfilteredDef.extensions.first(),
                appliedDirectives = unfilteredExt.appliedDirectives,
                sourceLocation = unfilteredExt.sourceLocation,
                supers = newSupers
            )
        }
    }

    fun computeFilteredSupers(unfilteredDef: ViaductSchema.HasExtensionsWithSupers<*, *>): List<FilteredSchema.Interface<*>> =
        unfilteredDef.supers
            .filter { filter.includeSuper(unfilteredDef, it) && filter.includeTypeDef(it) }
            .map { filteredTypes[it.name] as FilteredSchema.Interface<*> }

    fun <I : ViaductSchema.Interface> computePossibleObjectTypes(interfaceDef: FilteredSchema.Interface<I>): Set<FilteredSchema.Object<*>> =
        interfaceDef.unfilteredDef.possibleObjectTypes
            .filter { includePossibleSubType(it, interfaceDef.unfilteredDef) }
            .map { filteredTypes[it.name] as FilteredSchema.Object<*> }
            .toSet()

    private fun includePossibleSubType(
        possibleSubType: ViaductSchema.HasExtensionsWithSupers<*, *>,
        targetSuperType: ViaductSchema.Interface
    ): Boolean =
        when {
            possibleSubType.supers.contains(targetSuperType) -> filter.includeSuper(possibleSubType, targetSuperType)
            else ->
                possibleSubType.supers.any {
                    filter.includeSuper(possibleSubType, it) && includePossibleSubType(it, targetSuperType)
                }
        }

    // ========== Object ==========

    fun <O : ViaductSchema.Object> createObjectExtensions(
        objectDef: FilteredSchema.Object<O>,
        filteredSupers: List<FilteredSchema.Interface<*>>
    ): List<ViaductSchema.ExtensionWithSupers<FilteredSchema.Object<O>, FilteredSchema.Field<O, *>>> {
        val superNames = filteredSupers.map { it.name }.toSet()
        return objectDef.unfilteredDef.extensions.map { unfilteredExt ->
            val newSupers = unfilteredExt.supers
                .filter { superNames.contains(it.name) }
                .map { filteredTypes[it.name] as FilteredSchema.Interface<*> }
            ViaductSchema.ExtensionWithSupers.of(
                def = objectDef,
                memberFactory = { ext ->
                    unfilteredExt.members
                        .filter { filter.includeField(it) && filter.includeTypeDef(it.type.baseTypeDef) }
                        .map { createField(it, objectDef, ext) }
                },
                isBase = unfilteredExt == objectDef.unfilteredDef.extensions.first(),
                appliedDirectives = unfilteredExt.appliedDirectives,
                sourceLocation = unfilteredExt.sourceLocation,
                supers = newSupers
            )
        }
    }

    fun <O : ViaductSchema.Object> computeFilteredUnions(objectDef: FilteredSchema.Object<O>): List<FilteredSchema.Union<*>> =
        objectDef.unfilteredDef.unions
            .filter { filter.includeTypeDef(it) }
            .map { filteredTypes[it.name] as FilteredSchema.Union<*> }

    // ========== Directive ==========

    fun <D : ViaductSchema.Directive> populate(directive: FilteredSchema.Directive<D>) {
        val unfilteredDef = directive.unfilteredDef
        val args = unfilteredDef.args.map { createDirectiveArg(it, directive) }
        directive.populate(
            unfilteredDef.isRepeatable,
            unfilteredDef.allowedLocations,
            unfilteredDef.sourceLocation,
            args
        )
    }

    // ========== Helper: Field and Args ==========

    private fun <R : ViaductSchema.Record, F : ViaductSchema.Field> createField(
        unfilteredField: F,
        containingDef: FilteredSchema.Record<R>,
        containingExtension: ViaductSchema.Extension<FilteredSchema.Record<R>, FilteredSchema.Field<R, *>>
    ): FilteredSchema.Field<R, F> {
        val typeExpr = createTypeExprFromDefs(unfilteredField.type)
        return FilteredSchema.Field(
            unfilteredField,
            containingDef,
            containingExtension,
            unfilteredField.name,
            typeExpr,
            unfilteredField.appliedDirectives.toList(),
            unfilteredField.hasDefault,
            if (unfilteredField.hasDefault) unfilteredField.defaultValue else null,
            argsFactory = { field -> createFieldArgs(field, unfilteredField) }
        )
    }

    private fun <R : ViaductSchema.Record, F : ViaductSchema.Field> createFieldArgs(
        field: FilteredSchema.Field<R, F>,
        unfilteredField: F
    ): List<FilteredSchema.FieldArg<R, F, *>> =
        unfilteredField.args.map { arg ->
            val typeExpr = createTypeExprFromDefs(arg.type)
            FilteredSchema.FieldArg(
                arg,
                field,
                arg.name,
                typeExpr,
                arg.appliedDirectives.toList(),
                arg.hasDefault,
                if (arg.hasDefault) arg.defaultValue else null
            )
        }

    private fun <D : ViaductSchema.Directive, A : ViaductSchema.DirectiveArg> createDirectiveArg(
        unfilteredArg: A,
        directive: FilteredSchema.Directive<D>
    ): FilteredSchema.DirectiveArg<D, A> {
        val typeExpr = createTypeExprFromDefs(unfilteredArg.type)
        return FilteredSchema.DirectiveArg(
            unfilteredArg,
            directive,
            unfilteredArg.name,
            typeExpr,
            unfilteredArg.appliedDirectives.toList(),
            unfilteredArg.hasDefault,
            if (unfilteredArg.hasDefault) unfilteredArg.defaultValue else null
        )
    }

    private fun createTypeExprFromDefs(unfilteredTypeExpr: ViaductSchema.TypeExpr): FilteredSchema.TypeExpr {
        val baseTypeDef = filteredTypes[unfilteredTypeExpr.baseTypeDef.name]
            ?: error("${unfilteredTypeExpr.baseTypeDef.name} not found in filtered types")
        return FilteredSchema.TypeExpr(baseTypeDef, unfilteredTypeExpr.baseTypeNullable, unfilteredTypeExpr.listNullable)
    }
}

/** See KDoc for [ViaductSchema] for a background. */
interface SchemaFilter {
    fun includeTypeDef(typeDef: ViaductSchema.TypeDef): Boolean

    fun includeField(field: ViaductSchema.Field): Boolean

    fun includeEnumValue(enumValue: ViaductSchema.EnumValue): Boolean

    fun includeSuper(
        record: ViaductSchema.HasExtensionsWithSupers<*, *>,
        superInterface: ViaductSchema.Interface
    ): Boolean
}
