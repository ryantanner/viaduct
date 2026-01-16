package viaduct.graphql.schema.binary

import viaduct.graphql.schema.ViaductSchema

internal class BSchema(
    override val directives: Map<String, Directive>,
    override val types: Map<String, TypeDef>,
    override val queryTypeDef: Object?,
    override val mutationTypeDef: Object?,
    override val subscriptionTypeDef: Object?,
) : ViaductSchema {
    //
    // [Def] related interfaces and classes

    sealed interface Def : ViaductSchema.Def {
        override fun hasAppliedDirective(name: String) = appliedDirectives.any { it.name == name }
    }

    /**
     * Marker interface for top-level definitions that appear a schema (Directive and TypeDef).
     */
    sealed interface TopLevelDef : Def

    //
    // "Contained" things:
    // [Arg], [Field] and [EnumValue] and related interfaces and classes

    sealed class HasDefaultValue(
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        override val hasDefault: Boolean,
        private val mDefaultValue: Any?,
    ) : ViaductSchema.HasDefaultValue, Def {
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

    sealed class Arg(
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : HasDefaultValue(name, type, appliedDirectives, hasDefault, defaultValue), ViaductSchema.Arg

    class DirectiveArg(
        override val containingDef: Directive,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : ViaductSchema.DirectiveArg, Arg(name, type, appliedDirectives, hasDefault, defaultValue) {
        override fun toString() = describe()
    }

    class FieldArg internal constructor(
        override val containingDef: Field,
        name: String,
        type: ViaductSchema.TypeExpr<TypeDef>,
        appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
    ) : ViaductSchema.FieldArg, Arg(name, type, appliedDirectives, hasDefault, defaultValue) {
        override fun toString() = describe()
    }

    class EnumValue internal constructor(
        override val containingExtension: ViaductSchema.Extension<Enum, EnumValue>,
        override val name: String,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
    ) : ViaductSchema.EnumValue, Def {
        override fun toString() = describe()

        override val containingDef get() = containingExtension.def
    }

    class Field(
        override val containingExtension: ViaductSchema.Extension<Record, Field>,
        override val name: String,
        override val type: ViaductSchema.TypeExpr<TypeDef>,
        override val appliedDirectives: List<ViaductSchema.AppliedDirective>,
        hasDefault: Boolean,
        defaultValue: Any?,
        argsFactory: (Field) -> List<FieldArg>,
    ) : ViaductSchema.Field, HasDefaultValue(name, type, appliedDirectives, hasDefault, defaultValue) {
        /** Secondary constructor for fields without arguments (e.g., input fields). */
        constructor(
            containingExtension: ViaductSchema.Extension<Record, Field>,
            name: String,
            type: ViaductSchema.TypeExpr<TypeDef>,
            appliedDirectives: List<ViaductSchema.AppliedDirective>,
            hasDefault: Boolean,
            defaultValue: Any?,
        ) : this(containingExtension, name, type, appliedDirectives, hasDefault, defaultValue, { emptyList() })

        override fun toString() = describe()

        override val args: List<FieldArg> = argsFactory(this)

        override val isOverride: Boolean by lazy { ViaductSchema.isOverride(this) }

        override val containingDef get() = containingExtension.def
    }

    //
    // [Directive] concrete class

    class Directive(
        override val name: String,
    ) : ViaductSchema.Directive, TopLevelDef {
        override fun toString() = describe()

        private var mSourceLocation: ViaductSchema.SourceLocation? = null
        private var mIsRepeatable: Boolean? = null
        private var mAllowedLocations: Set<ViaductSchema.Directive.Location>? = null
        private var mArgs: List<DirectiveArg>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = guardedGetNullable(mSourceLocation, mArgs)
        override val isRepeatable: Boolean get() = guardedGet(mIsRepeatable)
        override val allowedLocations: Set<ViaductSchema.Directive.Location> get() = guardedGet(mAllowedLocations)
        override val args: List<DirectiveArg> get() = guardedGet(mArgs)

        // Always empty for Directives
        override val appliedDirectives = emptyList<ViaductSchema.AppliedDirective>()

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
    }

    //
    // [TypeDef] related interfaces and abstract classes

    sealed interface TypeDef : ViaductSchema.TypeDef, TopLevelDef {
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

    //
    // Non-[Record] [TypeDef] concrete classes

    class Enum(
        name: String
    ) : ViaductSchema.Enum, TypeDefImpl(name) {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mExtensions: List<ViaductSchema.Extension<Enum, EnumValue>>? = null
        private var mValues: List<EnumValue>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Enum, EnumValue>> get() = guardedGet(mExtensions)
        override val values: List<EnumValue> get() = guardedGet(mValues)

        internal fun populate(extensions: List<ViaductSchema.Extension<Enum, EnumValue>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mValues = extensions.flatMap { it.members }
        }

        override fun value(name: String) = values.find { name == it.name }
    }

    class Scalar(
        name: String
    ) : ViaductSchema.Scalar, TypeDefImpl(name) {
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

    class Union(
        name: String
    ) : ViaductSchema.Union, TypeDefImpl(name) {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mExtensions: List<ViaductSchema.Extension<Union, Object>>? = null
        private var mPossibleObjectTypes: Set<Object>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Union, Object>> get() = guardedGet(mExtensions)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)

        internal fun populate(extensions: List<ViaductSchema.Extension<Union, Object>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            // Validate that all members are actually Object types
            for (ext in extensions) {
                @Suppress("UNCHECKED_CAST")
                val membersAsAny = ext.members as List<*>
                for (member in membersAsAny) {
                    if (member != null && member !is Object) {
                        val typeName = (member as? TypeDef)?.name ?: member.toString()
                        throw InvalidSchemaException(
                            "Union $name contains $typeName which is not an Object type " +
                                "(got ${member.javaClass.simpleName})"
                        )
                    }
                }
            }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mPossibleObjectTypes = extensions.flatMap { it.members }.toSet()
        }
    }

    //
    // [Record] and its concrete classes

    sealed interface Record : ViaductSchema.Record, TypeDef {
        override val fields: List<Field>

        override fun field(name: String) = fields.find { name == it.name }

        override fun field(path: Iterable<String>): Field = ViaductSchema.field(this, path)
    }

    sealed interface OutputRecord : ViaductSchema.OutputRecord, Record {
        override val extensions: List<ViaductSchema.ExtensionWithSupers<OutputRecord, Field>>
        override val supers: List<Interface>
    }

    class Interface(
        name: String
    ) : ViaductSchema.Interface, OutputRecord, TypeDefImpl(name) {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>? = null
        private var mFields: List<Field>? = null
        private var mSupers: List<Interface>? = null
        private var mPossibleObjectTypes: Set<Object>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val possibleObjectTypes: Set<Object> get() = guardedGet(mPossibleObjectTypes)

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Interface, Field>>,
            possibleObjectTypes: Set<Object>
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }

            // Validate that all supers are actually Interface types
            for (ext in extensions) {
                @Suppress("UNCHECKED_CAST")
                val supersAsAny = ext.supers as Collection<*>
                for (superType in supersAsAny) {
                    if (superType != null && superType !is Interface) {
                        val typeName = (superType as? TypeDef)?.name ?: superType.toString()
                        throw InvalidSchemaException(
                            "Type $name implements $typeName which is not an Interface type " +
                                "(got ${superType.javaClass.simpleName})"
                        )
                    }
                }
            }

            // Validate that all possibleObjectTypes are actually Object types
            @Suppress("UNCHECKED_CAST")
            val possibleObjectTypesAsAny = possibleObjectTypes as Set<*>
            for (obj in possibleObjectTypesAsAny) {
                if (obj != null && obj !is Object) {
                    val typeName = (obj as? TypeDef)?.name ?: obj.toString()
                    throw InvalidSchemaException(
                        "Interface $name possibleObjectTypes contains $typeName which is not an Object type " +
                            "(got ${obj.javaClass.simpleName})"
                    )
                }
            }

            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
            @Suppress("UNCHECKED_CAST")
            mSupers = extensions.flatMap { it.supers as Collection<Interface> }
            mPossibleObjectTypes = possibleObjectTypes
        }
    }

    class Input(
        name: String
    ) : ViaductSchema.Input, Record, TypeDefImpl(name) {
        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mExtensions: List<ViaductSchema.Extension<Input, Field>>? = null
        private var mFields: List<Field>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.Extension<Input, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)

        internal fun populate(extensions: List<ViaductSchema.Extension<Input, Field>>) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }
            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
        }
    }

    class Object(
        name: String
    ) : ViaductSchema.Object, OutputRecord, TypeDefImpl(name) {
        override val possibleObjectTypes = setOf(this)

        private var mAppliedDirectives: List<ViaductSchema.AppliedDirective>? = null
        private var mExtensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>>? = null
        private var mFields: List<Field>? = null
        private var mSupers: List<Interface>? = null
        private var mUnions: List<Union>? = null

        override val sourceLocation: ViaductSchema.SourceLocation? get() = extensions.first().sourceLocation
        override val appliedDirectives: List<ViaductSchema.AppliedDirective> get() = guardedGet(mAppliedDirectives)
        override val extensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>> get() = guardedGet(mExtensions)
        override val fields: List<Field> get() = guardedGet(mFields)
        override val supers: List<Interface> get() = guardedGet(mSupers)
        override val unions: List<Union> get() = guardedGet(mUnions)

        internal fun populate(
            extensions: List<ViaductSchema.ExtensionWithSupers<Object, Field>>,
            unions: List<Union>
        ) {
            check(mExtensions == null) { "Type $name has already been populated; populate() can only be called once" }
            require(extensions.isNotEmpty()) { "Types must have at least one extension ($this)." }

            // Validate that all supers are actually Interface types
            for (ext in extensions) {
                @Suppress("UNCHECKED_CAST")
                val supersAsAny = ext.supers as Collection<*>
                for (superType in supersAsAny) {
                    if (superType != null && superType !is Interface) {
                        val typeName = (superType as? TypeDef)?.name ?: superType.toString()
                        throw InvalidSchemaException(
                            "Type $name implements $typeName which is not an Interface type " +
                                "(got ${superType.javaClass.simpleName})"
                        )
                    }
                }
            }

            mExtensions = extensions
            mAppliedDirectives = extensions.flatMap { it.appliedDirectives }
            mFields = extensions.flatMap { it.members }
            @Suppress("UNCHECKED_CAST")
            mSupers = extensions.flatMap { it.supers as Collection<Interface> }
            mUnions = unions
        }
    }
}

private inline fun <T> BSchema.TopLevelDef.guardedGet(v: T?): T = checkNotNull(v) { "${this.name} has not been populated; call populate() first" }

private inline fun <T> BSchema.TopLevelDef.guardedGetNullable(
    v: T?,
    sentinel: Any?
): T? {
    check(sentinel != null) { "${this.name} has not been populated; call populate() first" }
    return v
}

/**
 * Validates that all applied directives reference existing directive definitions
 * and that their arguments match the definition.
 */
internal fun Map<String, BSchema.Directive>.validateAppliedDirectives(
    appliedDirectives: Collection<ViaductSchema.AppliedDirective>,
    context: String
) {
    for (applied in appliedDirectives) {
        val definition = this[applied.name]
            ?: throw InvalidSchemaException(
                "Applied directive @${applied.name} on $context references non-existent directive definition"
            )
        // Validate that all arguments in applied directive exist in definition
        for (argName in applied.arguments.keys) {
            if (definition.args.none { it.name == argName }) {
                throw InvalidSchemaException(
                    "Applied directive @${applied.name} on $context has argument '$argName' " +
                        "not defined in directive definition"
                )
            }
        }
    }
}

/**
 * Validates all applied directives on a type definition (including its extensions and members).
 * This is exposed for testing purposes.
 */
internal fun Map<String, BSchema.Directive>.validateAppliedDirectives(typeDef: BSchema.TypeDef) {
    when (typeDef) {
        is BSchema.Scalar -> {
            validateAppliedDirectives(typeDef.appliedDirectives, "scalar ${typeDef.name}")
        }
        is BSchema.Enum -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
        is BSchema.Union -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
            }
        }
        is BSchema.Interface -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
        is BSchema.Input -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
        is BSchema.Object -> {
            for (ext in typeDef.extensions) {
                validateAppliedDirectives(ext.appliedDirectives, "type ${typeDef.name}")
                for (member in ext.members) {
                    validateAppliedDirectives(member.appliedDirectives, member.describe())
                }
            }
        }
    }
}
