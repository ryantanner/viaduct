package viaduct.graphql.schema.binary

import graphql.language.NullValue
import viaduct.graphql.schema.ViaductSchema

/**
 * Decodes the root types and definitions sections.
 *
 * @param queryTypeDef The query root type
 * @param mutationTypeDef The mutation root type (may be null)
 * @param subscriptionTypeDef The subscription root type (may be null)
 */
internal class DefinitionsDecoder(
    val data: BInputStream,
    private val identifiers: IdentifiersDecoder,
    private val types: TypeExpressionsDecoder,
    private val sourceLocations: SourceLocationsDecoder,
    private val constants: ConstantsDecoder,
) {
    val queryTypeDef: BSchema.Object?
    val mutationTypeDef: BSchema.Object?
    val subscriptionTypeDef: BSchema.Object?

    init {
        // Read Root Types section
        data.validateMagicNumber(MAGIC_ROOT_TYPES, "root types")
        queryTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "query")
        mutationTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "mutation")
        subscriptionTypeDef = lookupRootType(identifiers.getRootName(data.readInt()), "subscription")

        // Read Definitions section
        data.validateMagicNumber(MAGIC_DEFINITIONS, "definitions")
        for (defIndex in identifiers.indexedDefs.indices) {
            // Read name reference to find the definition
            val nameRef = data.readInt() and IDX_MASK
            val defName = identifiers.get(nameRef)
            val def = identifiers.directives[defName] ?: identifiers.types[defName]
                ?: throw IllegalArgumentException("Definition not found: $defName")

            when (def) {
                is BSchema.Directive -> {
                    // Read RefPlus and source location
                    val refPlus = DefinitionRefPlus(data.readInt())
                    val sourceLocation = sourceLocations.get(refPlus.getIndex())

                    // Read directive info word
                    val directiveInfo = DirectiveInfo(data.readInt())

                    // Decode arguments if present
                    val args = if (directiveInfo.hasArgs()) {
                        decodeInputLikeFieldList(def, BSchema::DirectiveArg)
                    } else {
                        emptyList()
                    }

                    def.populate(
                        directiveInfo.isRepeatable(),
                        directiveInfo.allowedLocations(),
                        sourceLocation,
                        args
                    )
                }

                is BSchema.Enum -> {
                    def.populate(
                        decodeExtensionList<BSchema.Enum, BSchema.EnumValue>(def) { ext, v ->
                            val valueRefPlus = EnumValueRefPlus(v)
                            BSchema.EnumValue(
                                ext,
                                identifiers.get(valueRefPlus.getIndex()), // name
                                decodeAppliedDirectives(valueRefPlus.hasAppliedDirectives())
                            )
                        }
                    )
                }

                is BSchema.Input -> {
                    def.populate(
                        decodeExtensionList(def) { ext, v ->
                            decodeFieldOrArg(ext, FieldRefPlus(v), BSchema::Field)
                        }
                    )
                }

                is BSchema.Interface -> {
                    @Suppress("UNCHECKED_CAST")
                    def.populate(
                        decodeExtensionListWithSupers(def) { ext, v ->
                            decodeOutputField(ext, FieldRefPlus(v))
                        },
                        (decodeTypeDefList() as List<BSchema.Object>).toSet()
                    )
                }

                is BSchema.Object -> {
                    @Suppress("UNCHECKED_CAST")
                    def.populate(
                        decodeExtensionListWithSupers(def) { ext, v ->
                            decodeOutputField(ext, FieldRefPlus(v))
                        },
                        decodeTypeDefList() as List<BSchema.Union>
                    )
                }

                is BSchema.Scalar -> {
                    def.populate(decodeScalarExtensionList(def))
                }

                is BSchema.Union -> {
                    def.populate(
                        decodeExtensionList<BSchema.Union, BSchema.Object>(def) { _, v ->
                            typeDef<BSchema.Object>(v)
                        }
                    )
                }
            }
        }

        // Phase 2: Validate applied directives now that all definitions are populated
        validateAllAppliedDirectives()
    }

    /**
     * Validates that all applied directives reference existing directive definitions
     * and that their arguments match the definition.
     */
    private fun validateAllAppliedDirectives() {
        val directives = identifiers.directives
        for (typeDef in identifiers.types.values) {
            directives.validateAppliedDirectives(typeDef)
        }
    }

    //
    // Helper functions

    /**
     * Look up a root type (query/mutation/subscription) by name.
     * Returns null if name is null, otherwise validates that the type exists and is an Object.
     */
    private fun lookupRootType(
        name: String?,
        rootKind: String
    ): BSchema.Object? {
        if (name == null) return null
        val typeDef = identifiers.types[name]
            ?: throw InvalidSchemaException("$rootKind root type '$name' not found")
        if (typeDef !is BSchema.Object) {
            throw InvalidSchemaException(
                "$rootKind root type '$name' must be an Object type, but got ${typeDef.javaClass.simpleName}"
            )
        }
        return typeDef
    }

    inline fun <reified T : BSchema.TypeDef> typeDef(refPlus: Int): T {
        val name = identifiers.get(refPlus)
        return identifiers.types[name] as? T
            ?: throw IllegalArgumentException("Type not found or wrong type: $name")
    }

    /**
     * Return an empty list if _either_ [hasList] is false or
     * the first word is [EMPTY_LIST_MARKER].
     */
    fun decodeTypeDefList(hasList: Boolean = true): List<BSchema.TypeDef> {
        if (!hasList) return emptyList()
        var v = data.readInt()
        if (v == EMPTY_LIST_MARKER) {
            return emptyList()
        }
        return buildList {
            add(typeDef(v))
            while (!v.isBitSet(END_OF_LIST_BIT)) {
                v = data.readInt()
                add(typeDef(v))
            }
        }
    }

    fun decodeDefaultValue(hasDefaultValue: Boolean): Any? =
        when {
            hasDefaultValue -> {
                val decoded = constants.decodeConstant(data.readInt() and IDX_MASK)
                if (decoded is NullValue) null else decoded
            }
            else -> null
        }

    /**
     * Decode a field or argument (works for both input-like and output fields).
     * Input-like fields (directive args, field args, input type fields) may have default values.
     * Output fields (interface/object fields) may have arguments - use [decodeOutputField] for those.
     */
    fun <D, T> decodeFieldOrArg(
        container: D,
        refPlus: FieldRefPlus,
        create: (D, String, ViaductSchema.TypeExpr<BSchema.TypeDef>, List<ViaductSchema.AppliedDirective>, Boolean, Any?) -> T,
    ): T {
        // Read in binary format order: name, appliedDirectives, type, hasDefault, defaultValue
        val name = identifiers.get(refPlus.getIndex())
        val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
        val type = types.get(data.readInt())
        val hasDefault = refPlus.hasDefaultValue()
        val defaultValue = decodeDefaultValue(hasDefault)
        // Pass to constructor in standardized order: container, name, type, appliedDirectives, hasDefault, defaultValue
        return create(container, name, type, appliedDirectives, hasDefault, defaultValue)
    }

    /**
     * Decode an output field (interface/object field) which may have arguments.
     */
    fun decodeOutputField(
        container: ViaductSchema.Extension<BSchema.Record, BSchema.Field>,
        refPlus: FieldRefPlus,
    ): BSchema.Field {
        // Read in binary format order: name, appliedDirectives, type, hasDefault, defaultValue
        val name = identifiers.get(refPlus.getIndex())
        val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
        val type = types.get(data.readInt())
        val hasDefault = refPlus.hasDefaultValue()
        val defaultValue = decodeDefaultValue(hasDefault)
        val hasArgs = refPlus.hasArguments()

        // Pass to constructor in standardized order: container, name, type, appliedDirectives, hasDefault, defaultValue
        return BSchema.Field(
            container,
            name,
            type,
            appliedDirectives,
            hasDefault,
            defaultValue,
            argsFactory = { field ->
                if (hasArgs) {
                    decodeInputLikeFieldList(field, BSchema::FieldArg)
                } else {
                    emptyList()
                }
            }
        )
    }

    /**
     * Decode input-field-like elements, which are [Arg]s or [Field]s of
     * input types.
     *
     * Requires either that there's an EMPTY_LIST_MARKER marking the end of the
     * list _or_ that you know there's at least one element in the list.
     */
    fun <D, T> decodeInputLikeFieldList(
        container: D,
        create: (D, String, ViaductSchema.TypeExpr<BSchema.TypeDef>, List<ViaductSchema.AppliedDirective>, Boolean, Any?) -> T,
    ): List<T> {
        var v = data.readInt()
        if (v == EMPTY_LIST_MARKER) return emptyList()

        return buildList {
            while (true) {
                val refPlus = FieldRefPlus(v)
                val field = decodeFieldOrArg(container, refPlus, create)
                add(field)
                if (!refPlus.hasNext()) break
                v = data.readInt()
            }
        }
    }

    /**
     * Decode applied directives, populating arguments using this logic:
     *
     * | Scenario                                                           | What arguments map contains
     * |--------------------------------------------------------------------|----------------------------
     * | Argument explicitly specified                                      | Specified value
     * | Argument not explicitly specified but has default in definition    | Default from definition
     * | Argument not explicitly specified, has no default, but is nullable | Null
     * | Other                                                              | Nothing, key not defined
     *
     * **Note on Missing Directive Definitions**: If the directive definition is not available
     * (which can happen for applied directives on directive definition arguments due to circular
     * dependencies), we use only the explicitly provided arguments. In this case, the encoder
     * ensures ALL arguments are encoded explicitly (no omission optimization).
     */
    fun decodeAppliedDirectives(hasAppliedDirectives: Boolean): List<ViaductSchema.AppliedDirective> {
        if (!hasAppliedDirectives) return emptyList()

        return buildList {
            var refPlus: AppliedDirectiveRefPlus
            do {
                refPlus = AppliedDirectiveRefPlus(data.readInt())
                val directiveName = identifiers.get(refPlus.getIndex())

                // Read explicitly provided arguments from the binary data
                val explicitArgs: Map<String, Any?> = if (refPlus.hasArguments()) {
                    buildMap {
                        var argRefPlus: AppliedDirectiveArgRefPlus
                        do {
                            argRefPlus = AppliedDirectiveArgRefPlus(data.readInt())
                            val argName = identifiers.get(argRefPlus.getIndex())
                            val constantRef = data.readInt() and IDX_MASK

                            val decoded = constants.decodeConstant(constantRef)
                            put(argName, if (decoded is NullValue) null else decoded)
                        } while (argRefPlus.hasNext())
                    }
                } else {
                    emptyMap()
                }

                // Build final arguments map using directive definition for defaults.
                // If the directive definition is not yet available (can happen with circular
                // directive dependencies), we use only the explicit args. The encoder ensures
                // that in such cases, ALL arguments are encoded explicitly.
                val directiveDef = identifiers.directives[directiveName]
                val finalArgs = if (directiveDef != null && directiveDef.args.isNotEmpty()) {
                    buildMap {
                        for (argDef in directiveDef.args) {
                            when {
                                // Argument explicitly specified
                                argDef.name in explicitArgs -> put(argDef.name, explicitArgs[argDef.name])
                                // Argument has default in definition
                                argDef.hasDefault -> put(argDef.name, argDef.defaultValue)
                                // Argument is nullable (no default)
                                argDef.type.isNullable -> put(argDef.name, null)
                                // Other: key not defined (don't add to map)
                            }
                        }
                    }
                } else {
                    // Directive definition not available (or has no args).
                    // Use explicit args directly. This handles circular directive dependencies
                    // where @A's arg has @B applied and @B's arg has @A applied.
                    explicitArgs
                }

                add(ViaductSchema.AppliedDirective.of(directiveName, finalArgs))
            } while (refPlus.hasNext())
        }
    }

    fun decodeScalarExtensionList(def: BSchema.Scalar): List<ViaductSchema.Extension<BSchema.Scalar, Nothing>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())
                val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                val sourceLocation = sourceLocations.get(refPlus.getIndex())

                @Suppress("UNCHECKED_CAST")
                val ext = ViaductSchema.Extension.of(
                    def = def,
                    memberFactory = { emptyList<Nothing>() },
                    isBase = isBase,
                    appliedDirectives = appliedDirectives,
                    sourceLocation = sourceLocation
                ) as ViaductSchema.Extension<BSchema.Scalar, Nothing>
                add(ext)
                isBase = false
            } while (refPlus.hasNext())
        }

    fun <D : BSchema.TypeDef, M : BSchema.Def> decodeExtensionList(
        def: D,
        block: (ViaductSchema.Extension<D, M>, Int) -> M
    ): List<ViaductSchema.Extension<D, M>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())
                val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                val sourceLocation = sourceLocations.get(refPlus.getIndex())

                @Suppress("UNCHECKED_CAST")
                val ext = ViaductSchema.Extension.of(
                    def = def,
                    memberFactory = { ext ->
                        var v = data.readInt()
                        if (v == EMPTY_LIST_MARKER) {
                            emptyList()
                        } else {
                            buildList {
                                do {
                                    add(block(ext as ViaductSchema.Extension<D, M>, v))
                                    val hasNext = (0 == (v and END_OF_LIST_BIT))
                                    if (hasNext) {
                                        v = data.readInt()
                                    }
                                } while (hasNext)
                            }
                        }
                    },
                    isBase = isBase,
                    appliedDirectives = appliedDirectives,
                    sourceLocation = sourceLocation
                ) as ViaductSchema.Extension<D, M>
                add(ext)
                isBase = false
            } while (refPlus.hasNext())
        }

    fun <D : BSchema.TypeDef, M : BSchema.Def> decodeExtensionListWithSupers(
        def: D,
        block: (ViaductSchema.Extension<D, M>, Int) -> M
    ): List<ViaductSchema.ExtensionWithSupers<D, M>> =
        buildList {
            var isBase = true
            do {
                val refPlus = DefinitionRefPlus(data.readInt())
                val appliedDirectives = decodeAppliedDirectives(refPlus.hasAppliedDirectives())
                val sourceLocation = sourceLocations.get(refPlus.getIndex())

                @Suppress("UNCHECKED_CAST")
                val supers = decodeTypeDefList(refPlus.hasImplementedTypes()) as List<BSchema.Interface>

                // Validate that all supers are actually Interface types before creating extension
                for (superType in supers) {
                    if (superType !is BSchema.Interface) {
                        val typeName = superType.name
                        throw InvalidSchemaException(
                            "Type ${def.name} implements $typeName which is not an Interface type " +
                                "(got ${superType.javaClass.simpleName})"
                        )
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val ext = ViaductSchema.ExtensionWithSupers.of(
                    def = def,
                    memberFactory = { ext ->
                        var v = data.readInt()
                        if (v == EMPTY_LIST_MARKER) {
                            emptyList()
                        } else {
                            buildList {
                                do {
                                    add(block(ext as ViaductSchema.Extension<D, M>, v))
                                    val hasNext = (0 == (v and END_OF_LIST_BIT))
                                    if (hasNext) {
                                        v = data.readInt()
                                    }
                                } while (hasNext)
                            }
                        }
                    },
                    isBase = isBase,
                    appliedDirectives = appliedDirectives,
                    supers = supers,
                    sourceLocation = sourceLocation
                ) as ViaductSchema.ExtensionWithSupers<D, M>
                add(ext)
                isBase = false
            } while (refPlus.hasNext())
        }
}
