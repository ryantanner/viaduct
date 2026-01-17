# ViaductSchema Library

## Purpose

The ViaductSchema library provides a unified abstraction layer for working with GraphQL schemas within the Viaduct ecosystem. It defines a comprehensive type-safe interface for accessing and manipulating GraphQL schema elements (types, fields, directives, etc.) independent of the underlying schema representation.

**Key capabilities:**

- **Schema navigation**: Navigate and query GraphQL type systems programmatically
- **Schema projections**: Filter schemas based on custom rules
- **Cross-representation compatibility**: Abstract away differences between schema representations
- **Contract validation**: Ensure different representations conform to abstraction's contract

## Goals and Non-Goals

### Goals

1. **Unified Interface**: Provide a single, consistent API for working with GraphQL schemas regardless of their underlying representation (parsed SDL, validated schema, filtered view, etc.)

2. **Navigability**: References "up" (e.g., `containingDef`) and "across" (e.g., `baseTypeDef`) the schema AST as well as "down" (e.g., `fields`)

3. **Immutability**: All schema representations are immutable to enable safe sharing and caching

4. **Performance**: Support efficient schema operations through efficient construction, lazy evaluation and careful memory management

5. **Extensibility**: Allow new schema flavors to be added without changing consuming code

6. **Testability**: Provide contract-based tests that validate implementation correctness

### Non-Goals

1. **Schema Execution**: This library does NOT execute GraphQL queries. It provides an abstraction for schema representations only.

2. **GraphQL Parsing**: This library does NOT provide a parser for GraphQL SDL (it does provide a bridge to graphql-java's `TypeDefinitionRegistry`).

3. **Schema Validation Logic**: This library does NOT implement GraphQL schema validation (it does provide a bridge to graphql-java's validated `GraphQLSchema`).

---

# Part 1: For Library Consumers

This section is for developers who use ViaductSchema to work with GraphQL schemas in their applications.

## The ViaductSchema Interface

The `ViaductSchema` interface is the entry point to the abstraction:

```kotlin
interface ViaductSchema {
    val types: Map<String, TypeDef>
    val directives: Map<String, Directive>
    val queryTypeDef: Object?
    val mutationTypeDef: Object?
    val subscriptionTypeDef: Object?
}
```

All operations go through this interface. You never need to work with implementation classes directly.

## Creating Schemas

ViaductSchema provides several factory methods as extension functions on `ViaductSchema.Companion`. Choose based on your source data and requirements.

### From GraphQL-Java Validated Schema

**Import:** `viaduct.graphql.schema.graphqljava.extensions`

```kotlin
ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<File>): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<URL>): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(registry: TypeDefinitionRegistry): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(schema: GraphQLSchema): ViaductSchema
```

**Characteristics:**

- Performs full GraphQL semantic validation
- Requires a query root type
- Slower to construct due to validation overhead
- Use when you need guaranteed schema validity

### From GraphQL-Java Raw Registry

**Import:** `viaduct.graphql.schema.graphqljava.extensions`

```kotlin
ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<File>): ViaductSchema
ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<URL>): ViaductSchema
ViaductSchema.Companion.fromTypeDefinitionRegistry(registry: TypeDefinitionRegistry): ViaductSchema
```

**Characteristics:**

- Fast to construct (no validation overhead)
- Supports partial schemas (no required query root)
- Use when your schema is already validated by other means (e.g., a previous build step)

### From Binary Format

**Import:** `viaduct.graphql.schema.binary.extensions`

```kotlin
// Reading
ViaductSchema.Companion.fromBinaryFile(file: File): ViaductSchema
ViaductSchema.Companion.fromBinaryFile(input: InputStream): ViaductSchema

// Writing
fun ViaductSchema.toBinaryFile(file: File)
fun ViaductSchema.toBinaryFile(output: OutputStream)
```

**Characteristics:**

- 10-20x faster to load than parsing SDL text
- Lower memory overhead than graphql-java's object model
- Use for production schema loading where startup time matters
- Pre-compile schemas to binary during build, load quickly at runtime

### Creating Filtered Schemas

```kotlin
fun ViaductSchema.filter(filter: SchemaFilter, options: SchemaInvariantOptions): ViaductSchema
```

**Example:**
```kotlin
val publicSchema = schema.filter(
    filter = object : SchemaFilter {
        override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) =
            !typeDef.hasAppliedDirective("internal")
        override fun includeField(field: ViaductSchema.Field) =
            !field.hasAppliedDirective("admin")
    }
)
```

**Characteristics:**

- Creates a restricted view of another schema
- Filtered schemas do _not_ need to be valid GraphQL schemas
- Primary use-case: "compilation schemas" (projections for tenant modules)

## Choosing a Schema Flavor

| Need | Recommended Approach |
|------|---------------------|
| Validated schema, can afford validation cost | `fromGraphQLSchema()` |
| Fast construction, schema already validated | `fromTypeDefinitionRegistry()` |
| Production loading, startup time matters | `fromBinaryFile()` |
| Restricted view of existing schema | `filter()` |

## Common Usage Patterns

### Finding a Field

```kotlin
val schema: ViaductSchema = ...
val userType = schema.types["User"] as ViaductSchema.Object
val nameField = userType.field("name")
```

### Navigating to Nested Fields

```kotlin
val addressStreet = userType.field(listOf("address", "street"))
```

### Checking for Directives

```kotlin
if (field.hasAppliedDirective("deprecated")) {
    // Handle deprecated field
}
```

### Filtering by Directive

```kotlin
val publicSchema = schema.filter(
    filter = object : SchemaFilter {
        override fun includeField(field: ViaductSchema.Field) =
            !field.hasAppliedDirective("internal")
        // inherit default "always include" logic for other methods
    }
)
```

### Walking the Schema

```kotlin
fun collectAppliedDirectives(schema: ViaductSchema): Set<ViaductSchema.AppliedDirective> {
    val result = mutableSetOf<ViaductSchema.AppliedDirective>()
    fun visit(def: ViaductSchema.Def) {
        result.addAll(def.appliedDirectives) // all def's have applied directives
        when (def) {
            is ViaductSchema.HasArgs -> // Handles `Directive` and `Field`
                def.args.forEach(::visit)
            is ViaductSchema.Record -> // Handles `Input`, `Interface` and `Object`
                def.fields.forEach(::visit)
            is ViaductSchema.Enum -> def.values.forEach(::visit)
            else -> { } // do nothing more for Scalar and Union
        }
    }
    schema.directives.values.forEach(::visit)
    schema.types.values.forEach(::visit)
    return result
}
```

## Type Hierarchy

The library models GraphQL's type system through a comprehensive hierarchy of nested interfaces:

```
Def
├── TypeDef
│   │
│   ├── HasExtensions
│   │   └── HasExtenionsWithSupers
│   │
│   ├── Enum - extends HasExtensions
│   ├── Record - extends HasExtensions
│   │   ├── Input
│   │   ├── Interface
│   │   └── Object
│   ├── Scalar
│   └── Union - extends HasExtensions
│
├── HasArgs
│
├── Directive - extends HasArgs
├── EnumValue
└── HasDefaultValue
    ├── Field ─ extends HasArgs
    └── Arg
        ├── FieldArg
        └── DirectiveArg
AppliedDirective
Extension
└── ExtensionWithSupers
SourceLocation
TypeExpr
```

TypeDef also has predicates `isSimple`, `isComposite`, `isInput`, and `isOutput` for categorizing types.

## Type Expressions

The `ViaductSchema.TypeExpr` type represents GraphQL type expressions (e.g., `String!`, `[Int]`, `[User!]!`):

- **baseTypeDef**: The underlying type definition (e.g., `String`, `Int`, `User`)
- **baseTypeNullable**: Whether the base type is nullable
- **listNullable**: Bit vector representing nullability at each list nesting level

TypeExpr has value-equality semantics (versus reference-equality for most other nested types).

## Extensions

GraphQL supports type extensions, and this library models them explicitly through `Extension` interfaces:

- Each extensible type (Object, Interface, Enum, Input, Union) has an `extensions` property
- Extensions track which is the "base" definition vs. extensions
- Extensions carry their own applied directives
- Source locations are preserved per extension

## Applied Directives

The `AppliedDirective` interface represents directive applications (e.g., `@deprecated(reason: "Use newField")`):

- **name**: Directive name
- **arguments**: Map of argument names to resolved values
- Implements value-type semantics (equality based on content, not identity)

**Important:** the `arguments` of an `AppliedDirective` contains the values of _all_ arguments, not just arguments explicitly provided in the schema. It's impossible to tell whether the input schema explicitly provided an argument value versus depended on a default value.

---

# Part 2: For External ViaductSchema Implementers

This section is for developers who want to create their own `ViaductSchema` implementation backed by a different underlying representation.

## Contract Test Suites

The library provides two contract test suites in `testFixtures` to verify your implementation's correctness:

### ViaductSchemaContract

Tests **behavioral correctness**—that your implementation behaves correctly according to GraphQL semantics.

```kotlin
class MySchemaContractTest : ViaductSchemaContract {
    override fun makeSchema(schema: String): ViaductSchema {
        return MySchema.fromSDL(schema)
    }
}
```

This interface provides comprehensive tests for:

- Default value handling for fields and arguments
- Field path navigation
- Override detection (`isOverride`)
- Extension lists and applied directives
- Root type referential integrity
- Type expression properties

### ViaductSchemaSubtypeContract

Tests **type structure**—that your implementation's nested types properly subtype `ViaductSchema`'s nested interfaces.

```kotlin
class MySchemaSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass(): KClass<*> = MySchema::class
}
```

This class uses Kotlin reflection to verify:

- All required nested classes exist (`Def`, `TypeDef`, `Field`, `Arg`, etc.)
- The class hierarchy is correct (e.g., `TypeDef` extends `TopLevelDef`)
- Return types are proper subtypes (e.g., `Field.containingDef` returns your implementation's `Record` type)

**Optional customization:**

- Override `skipExtensionTests = true` if your implementation delegates extension fields without wrapping them
- Override `classes` if your implementation uses non-standard nested class names

## Black-Box Testing with TestSchemas

In addition to the contracts, use the shared `TestSchemas` cases through your implementation for comprehensive coverage.

See [TEST_FIXTURES.md](TEST_FIXTURES.md) for documentation on test utilities and sample schemas available.

---

# Part 3: For Library Maintainers

This section is for developers who maintain or extend the ViaductSchema library itself.

## Unified Implementation: SchemaWithData

All schema flavors share a single `internal` implementation class, `SchemaWithData`, which directly implements `ViaductSchema`. The key insight is that each node has an optional `data: Any?` property that stores flavor-specific auxiliary data:

```kotlin
internal class SchemaWithData(...) : ViaductSchema {
    sealed class Def {
        abstract val data: Any?  // Flavor-specific data
        // ...
    }
    // Nested classes: TypeDef, Object, Interface, Field, etc.
}
```

The different "flavors" are distinguished by what they store in `data`:

| Flavor | What's in `data` | Use Case |
|--------|------------------|----------|
| Binary | `null` | Fast loading, compact storage |
| GJSchema | `graphql.schema.*` types | Validated schema access |
| GJSchemaRaw | `TypeDefData` with `graphql.language.*` | Fast parsing, build-time codegen |
| Filtered | Unfiltered `ViaductSchema.Def` | Schema projections |

## Internal Factory Functions

These are top-level `internal` functions that return `SchemaWithData` (not `ViaductSchema`), providing access to the `data` property for flavor-specific operations.

**GJSchema** (`viaduct.graphql.schema.graphqljava`):
```kotlin
internal fun gjSchemaFromSchema(gjSchema: GraphQLSchema): SchemaWithData
internal fun gjSchemaFromRegistry(registry: TypeDefinitionRegistry): SchemaWithData
internal fun gjSchemaFromFiles(inputFiles: List<File>): SchemaWithData
internal fun gjSchemaFromURLs(inputFiles: List<URL>): SchemaWithData
```

**GJSchemaRaw** (`viaduct.graphql.schema.graphqljava`):
```kotlin
internal fun gjSchemaRawFromSDL(sdl: String): SchemaWithData
internal fun gjSchemaRawFromRegistry(registry: TypeDefinitionRegistry): SchemaWithData
internal fun gjSchemaRawFromFiles(inputFiles: List<File>): SchemaWithData
internal fun gjSchemaRawFromURLs(inputFiles: List<URL>): SchemaWithData
```

**Binary** (`viaduct.graphql.schema.binary`):
```kotlin
internal fun readBSchema(input: InputStream): SchemaWithData
internal fun writeBSchema(schema: ViaductSchema, output: OutputStream)
```

**Filtered** (`viaduct.graphql.schema`):
```kotlin
internal fun filteredSchema(
    filter: SchemaFilter,
    schemaEntries: Iterable<Map.Entry<String, T>>,
    directiveEntries: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
    ...
): SchemaWithData
```

## Two-Phase Construction Pattern

ViaductSchema implementations must handle circular references between types—for example, an `Object` type needs references to its `Interface` supers, while an `Interface` needs references to its implementing `Object` types. All flavors address this using a two-phase construction pattern:

**Phase 1 (Shell Creation)**: All type definition and directive "shells" are created with just their names (and any raw source data in `data`). These shells are added to type/directive maps.

**Phase 2 (Population)**: Each shell is populated with its full data—including cross-references to other types—via a `populate()` method. At this point the type map is fully populated, so cross-references can be resolved directly.

Each `populate()` method includes an idempotency guard (`check(mFoo == null)`) to ensure it's called exactly once, and properties use `guardedGet()` accessors to verify population before access.

## Decoders

Each flavor uses a **decoder** class that transforms source data into `SchemaWithData` elements:

| Flavor | Decoder Class | Source Data |
|--------|---------------|-------------|
| GJSchema | GraphQLSchemaDecoder | `graphql.schema.GraphQLSchema` |
| GJSchemaRaw | TypeDefinitionRegistryDecoder | `graphql.language.TypeDefinitionRegistry` |
| Binary | DefinitionsDecoder | Binary input stream |
| Filtered | FilteredSchemaDecoder | Another `ViaductSchema` |

The decoder has access to the fully-populated type map, so it can resolve type references and build `Extension`, `Field`, and other nested objects with direct references rather than deferred lookups.

## Factory Callbacks for Bidirectional Containment

Nested objects like `Extension`, `Field`, `EnumValue`, and `Arg` are immutable from creation—they receive all their data in their constructors. For bidirectional containment relationships (e.g., a `Field` contains `args`, but each `FieldArg` references back to the `Field`), the pattern uses a **factory callback**: the container's constructor takes a `memberFactory: (Container) -> List<Member>` parameter, invokes it with `this`, and the factory creates members with the back-reference already set.

## Extension Properties for Type-Safe Data Access

Each flavor provides `internal` extension properties that cast `data` to the appropriate type:

**GJSchema** (in `viaduct.graphql.schema.graphqljava`):
```kotlin
internal val SchemaWithData.Object.gjDef: GraphQLObjectType
internal val SchemaWithData.Interface.gjDef: GraphQLInterfaceType
internal val SchemaWithData.Field.gjOutputDef: GraphQLFieldDefinition
internal val SchemaWithData.Field.gjInputDef: GraphQLInputObjectField
// etc.
```

**GJSchemaRaw** (in `viaduct.graphql.schema.graphqljava`):
```kotlin
internal val SchemaWithData.TypeDef.gjrDef: TypeDefinition<*>
internal val SchemaWithData.TypeDef.gjrExtensionDefs: List<TypeDefinition<*>>
internal val SchemaWithData.Object.gjrDef: ObjectTypeDefinition
// etc.
```

**Filtered** (in `viaduct.graphql.schema`):
```kotlin
internal val SchemaWithData.Def.unfilteredDef: ViaductSchema.Def
internal val SchemaWithData.Object.unfilteredDef: ViaductSchema.Object
internal val SchemaWithData.Field.unfilteredDef: ViaductSchema.Field
// etc.
```

## Testing Expectations

The library includes comprehensive testing infrastructure organized into black-box tests (verifying GraphQL semantics across implementations) and glass-box tests (verifying implementation-specific behavior). All `ViaductSchema` flavors should:

1. **Use contract testing**: Extend `ViaductSchemaContract` and `ViaductSchemaSubtypeContract`
2. **Use black-box testing**: Run the shared `TestSchemas` cases through your implementation
3. **Add glass-box tests**: Test implementation-specific behavior (encoding limits, caching, error handling)
4. **Verify invariants**: Schemas must satisfy structural invariants

See [TESTING.md](TESTING.md) for detailed testing guidelines for contributors.

See [TEST_FIXTURES.md](TEST_FIXTURES.md) for documentation on test utilities and sample schemas.

## Contribution Guidelines

When modifying this library:

1. **Maintain contracts**: Don't break `ViaductSchemaContract` tests
2. **Preserve immutability**: Never expose mutable state
3. **Test with real schemas**: Use actual Viaduct tenant schemas for integration testing
4. **Consider performance**: This code runs during every build
5. **Update this doc**: Keep AGENTS.md current with architectural changes
