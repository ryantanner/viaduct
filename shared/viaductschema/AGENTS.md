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

## Architectural Overview

### Root Abstraction: `ViaductSchema`

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

### Unified Implementation: `SchemaWithData`

All schema flavors share a single implementation class, `SchemaWithData`, which directly implements `ViaductSchema`. The key insight is that each node has an optional `data: Any?` property that stores flavor-specific auxiliary data:

```kotlin
class SchemaWithData(...) : ViaductSchema {
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

### Factory Functions

Schema instances are created through factory functions. There are two categories: **public factory functions** for external consumers, and **internal factory functions** used within the library.

#### Public Factory Functions

These are extension functions on `ViaductSchema.Companion` that provide a stable public API. Import them from the `extensions` packages.

**From GraphQL-Java validated schema** (`viaduct.graphql.schema.graphqljava.extensions`):
```kotlin
ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<File>): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(inputFiles: List<URL>): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(registry: TypeDefinitionRegistry): ViaductSchema
ViaductSchema.Companion.fromGraphQLSchema(schema: GraphQLSchema): ViaductSchema
```

**From GraphQL-Java raw registry** (`viaduct.graphql.schema.graphqljava.extensions`):
```kotlin
ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<File>): ViaductSchema
ViaductSchema.Companion.fromTypeDefinitionRegistry(inputFiles: List<URL>): ViaductSchema
ViaductSchema.Companion.fromTypeDefinitionRegistry(registry: TypeDefinitionRegistry): ViaductSchema
```

**Binary format** (`viaduct.graphql.schema.binary.extensions`):
```kotlin
// Reading
ViaductSchema.Companion.fromBinaryFile(file: File): ViaductSchema
ViaductSchema.Companion.fromBinaryFile(input: InputStream): ViaductSchema

// Writing
fun ViaductSchema.toBinaryFile(file: File)
fun ViaductSchema.toBinaryFile(output: OutputStream)
```

**Filtered schemas** (member function on `ViaductSchema`):
```kotlin
fun ViaductSchema.filter(filter: SchemaFilter, options: SchemaInvariantOptions): ViaductSchema
```

#### Internal Factory Functions

These are top-level functions used internally by the library. They return `SchemaWithData` (the concrete implementation class) rather than `ViaductSchema`, providing access to the `data` property for flavor-specific operations.

**GJSchema** (`viaduct.graphql.schema.graphqljava`):
```kotlin
gjSchemaFromSchema(gjSchema: GraphQLSchema): SchemaWithData
gjSchemaFromRegistry(registry: TypeDefinitionRegistry): SchemaWithData
gjSchemaFromFiles(inputFiles: List<File>): SchemaWithData
gjSchemaFromURLs(inputFiles: List<URL>): SchemaWithData
```

**GJSchemaRaw** (`viaduct.graphql.schema.graphqljava`):
```kotlin
gjSchemaRawFromSDL(sdl: String): SchemaWithData
gjSchemaRawFromRegistry(registry: TypeDefinitionRegistry): SchemaWithData
gjSchemaRawFromFiles(inputFiles: List<File>): SchemaWithData
gjSchemaRawFromURLs(inputFiles: List<URL>): SchemaWithData
```

**Binary** (`viaduct.graphql.schema.binary`, internal visibility):
```kotlin
internal fun readBSchema(input: InputStream): SchemaWithData
internal fun writeBSchema(schema: ViaductSchema, output: OutputStream)
```

**Filtered** (`viaduct.graphql.schema`, internal visibility):
```kotlin
internal fun filteredSchema(
    filter: SchemaFilter,
    schemaEntries: Iterable<Map.Entry<String, T>>,
    directiveEntries: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
    ...
): SchemaWithData
```

### Extension Properties for Type-Safe Data Access

Each flavor provides extension properties that cast `data` to the appropriate type:

**GJSchema** (in `viaduct.graphql.schema.graphqljava`):
```kotlin
val SchemaWithData.Object.gjDef: GraphQLObjectType
val SchemaWithData.Interface.gjDef: GraphQLInterfaceType
val SchemaWithData.Field.gjOutputDef: GraphQLFieldDefinition
val SchemaWithData.Field.gjInputDef: GraphQLInputObjectField
// etc.
```

**GJSchemaRaw** (in `viaduct.graphql.schema.graphqljava`):
```kotlin
val SchemaWithData.TypeDef.gjrDef: TypeDefinition<*>
val SchemaWithData.TypeDef.gjrExtensionDefs: List<TypeDefinition<*>>
val SchemaWithData.Object.gjrDef: ObjectTypeDefinition
// etc.
```

**Filtered** (in `viaduct.graphql.schema`):
```kotlin
val SchemaWithData.Def.unfilteredDef: ViaductSchema.Def
val SchemaWithData.Object.unfilteredDef: ViaductSchema.Object
val SchemaWithData.Field.unfilteredDef: ViaductSchema.Field
// etc.
```

### Two-Phase Construction Pattern

ViaductSchema implementations must handle circular references between types—for example, an `Object` type needs references to its `Interface` supers, while an `Interface` needs references to its implementing `Object` types. All flavors address this using a two-phase construction pattern:

**Phase 1 (Shell Creation)**: All type definition and directive "shells" are created with just their names (and any raw source data in `data`). These shells are added to type/directive maps.

**Phase 2 (Population)**: Each shell is populated with its full data—including cross-references to other types—via a `populate()` method. At this point the type map is fully populated, so cross-references can be resolved directly.

Each `populate()` method includes an idempotency guard (`check(mFoo == null)`) to ensure it's called exactly once, and properties use `guardedGet()` accessors to verify population before access.

### Decoders

Each flavor uses a **decoder** class that transforms source data into `SchemaWithData` elements:

| Flavor | Decoder Class | Source Data |
|--------|---------------|-------------|
| GJSchema | GraphQLSchemaDecoder | `graphql.schema.GraphQLSchema` |
| GJSchemaRaw | TypeDefinitionRegistryDecoder | `graphql.language.TypeDefinitionRegistry` |
| Binary | DefinitionsDecoder | Binary input stream |
| Filtered | FilteredSchemaDecoder | Another `ViaductSchema` |

The decoder has access to the fully-populated type map, so it can resolve type references and build `Extension`, `Field`, and other nested objects with direct references rather than deferred lookups.

### Factory Callbacks for Bidirectional Containment

Nested objects like `Extension`, `Field`, `EnumValue`, and `Arg` are immutable from creation—they receive all their data in their constructors. For bidirectional containment relationships (e.g., a `Field` contains `args`, but each `FieldArg` references back to the `Field`), the pattern uses a **factory callback**: the container's constructor takes a `memberFactory: (Container) -> List<Member>` parameter, invokes it with `this`, and the factory creates members with the back-reference already set.

### Type Hierarchy

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

### Type Expressions

The `ViaductSchema.TypeExpr` type represents GraphQL type expressions (e.g., `String!`, `[Int]`, `[User!]!`):

- **baseTypeDef**: The underlying type definition (e.g., `String`, `Int`, `User`)
- **baseTypeNullable**: Whether the base type is nullable
- **listNullable**: Bit vector representing nullability at each list nesting level

Unlike most types nested in `ViaductSchema`:

- `TypeExpr` is an abstract class, providing the implementation for list- and non-null "wrapping" and delegating just `baseTypeDef` to implementations of `ViaductSchema`
- `TypeExpr` has value-equality semantics, versus reference-equality for most of the other nested types. Value equality is implemented in the `TypeExpr` abstract class.

### Extensions

GraphQL supports type extensions, and this library models them explicitly through `Extension` interfaces:

- Each extensible type (Object, Interface, Enum, Input, Union) has an `extensions` property
- Extensions track which is the "base" definition vs. extensions
- Extensions carry their own applied directives
- Source locations are preserved per extension

### Applied Directives

The `AppliedDirective` interface represents directive applications (e.g., `@deprecated(reason: "Use newField")`):

- **name**: Directive name
- **arguments**: Map of argument names to resolved values
- Implements value-type semantics (equality based on content, not identity)

**Important:** the `arguments` of an `AppliedDirective` is expected to contain the values of _all_ arguments, not just arguments that are explicitly provided in the schema. It's impossible to tell in `ViaductSchema` when the input schema text explicitly provided an argument value versus depended on a default value (including a default value of `null` for nullable arguments).

## Schema Flavors

The library provides four schema flavors, all using the unified `SchemaWithData` implementation class. Each flavor is distinguished by factory functions, what's stored in `data`, and extension properties for accessing that data.

### 1. GJSchemaRaw (Raw GraphQL-Java)

**Purpose**: Creates schemas from graphql-java's `TypeDefinitionRegistry` — a parsed but not semantically validated schema.

**Key characteristics:**

- Fast to construct (no validation overhead)
- Supports partial schemas (no required query root)
- Used primarily during build-time code generation
- Each node's `data` holds a `TypeDefData` containing the base definition and extension definitions

**Factory functions**:

- Public: `ViaductSchema.Companion.fromTypeDefinitionRegistry(...)`
- Internal: `gjSchemaRawFromSDL()`, `gjSchemaRawFromRegistry()`, `gjSchemaRawFromFiles()`, `gjSchemaRawFromURLs()`

**Data access** (extension properties):
```kotlin
val typeDef = schema.types["User"]!!
val objectDef: ObjectTypeDefinition = (typeDef as SchemaWithData.Object).gjrDef
val extensionDefs: List<ObjectTypeDefinition> = (typeDef as SchemaWithData.Object).gjrExtensionDefs
```

**When to use**: When the input schema has already been validated (e.g., by a previous build step) and you want higher performance.

### 2. GJSchema (Validated GraphQL-Java)

**Purpose**: Creates schemas from graphql-java's `GraphQLSchema` — a parsed, validated, and semantically analyzed schema.

**Key characteristics:**

- Slower to construct because `GraphQLSchema` validation is expensive
- Requires a query root type
- Each node's `data` holds the corresponding `graphql.schema.*` type

**Factory functions**:

- Public: `ViaductSchema.Companion.fromGraphQLSchema(...)`
- Internal: `gjSchemaFromSchema()`, `gjSchemaFromRegistry()`, `gjSchemaFromFiles()`, `gjSchemaFromURLs()`

**Data access** (extension properties):
```kotlin
val typeDef = schema.types["User"]!!
val objectDef: GraphQLObjectType = (typeDef as SchemaWithData.Object).gjDef
val field = (typeDef as SchemaWithData.Object).field("name")!!
val fieldDef: GraphQLFieldDefinition = (field as SchemaWithData.Field).gjOutputDef
```

**When to use**: When you need guaranteed schema validity and can afford the validation cost.

### 3. Binary Schema (BSchema)

**Purpose**: A compact binary serialization format that enables fast loading and reduced memory footprint.

**Key characteristics:**

- Native `ViaductSchema` implementation (no underlying representation to wrap)
- String deduplication via an interned string table in the binary format
- Significantly faster to load than parsing SDL text (10-20x speedup on large schemas)
- Lower memory overhead than graphql-java's object model
- Round-trip tested: binary → ViaductSchema → binary produces identical output
- Each node's `data` is `null` (no auxiliary data needed)

**Factory functions**:

- Public reading: `ViaductSchema.Companion.fromBinaryFile(file)`, `fromBinaryFile(inputStream)`
- Public writing: `ViaductSchema.toBinaryFile(file)`, `toBinaryFile(outputStream)`
- Internal: `readBSchema(inputStream)`, `writeBSchema(schema, outputStream)`

**When to use**: For production schema loading where startup time and memory usage matter. Schemas can be pre-compiled to binary format during build time, then loaded quickly at runtime.

### 4. Filtered Schema

**Purpose**: Projects an existing `ViaductSchema` through a filter to create a restricted view.

**Key characteristics:**

- Wraps another `ViaductSchema` (each node's `data` holds the unfiltered def)
- Applies filtering rules via `SchemaFilter` interface
- **Important:** filtered schemas do _not_ need to be valid GraphQL schemas

**Factory functions**:

- Public: `ViaductSchema.filter(filter, options)` (extension function)
- Internal: `filteredSchema(filter, schemaEntries, directiveEntries, ...)`

**Example usage**:
```kotlin
val filteredSchema = baseSchema.filter(
    filter = object : SchemaFilter {
        override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) =
            !typeDef.hasAppliedDirective("internal")
        override fun includeField(field: ViaductSchema.Field) =
            !field.hasAppliedDirective("admin")
        // ... other filter methods
    }
)
```

**Data access** (extension properties):
```kotlin
val filteredType = filteredSchema.types["User"]!!
val unfilteredType: ViaductSchema.Object = (filteredType as SchemaWithData.Object).unfilteredDef
```

**Filtering capabilities:**

- Remove types, fields, enum values
- Remove interface implementations
- Filter union members
- Custom filtering logic via `SchemaFilter` interface

The primary use-case is "compilation schemas," i.e., a projection of an application's full schema to just the parts needed by a particular tenant module.

## Testing Expectations

The library includes comprehensive testing infrastructure organized into black-box tests (verifying GraphQL semantics across implementations) and glass-box tests (verifying implementation-specific behavior). All `ViaductSchema` flavors should:

1. **Use contract testing**: Extend `ViaductSchemaContract` and `ViaductSchemaSubtypeContract`
2. **Use black-box testing**: Run the shared `TestSchemas` cases through your implementation
3. **Add glass-box tests**: Test implementation-specific behavior (encoding limits, caching, error handling)
4. **Verify invariants**: Schemas must satisfy structural invariants

See [TESTING.md](TESTING.md) for detailed testing guidelines for contributors.

See [TEST_FIXTURES.md](TEST_FIXTURES.md) for documentation on test utilities and sample schemas available to consumers of and contributors to this library.

## Common Patterns

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

### Accessing Underlying GraphQL-Java Types

```kotlin
// For validated schemas (GJSchema)
val schema = gjSchemaFromFiles(listOf(schemaFile))
val userType = schema.types["User"] as SchemaWithData.Object
val graphqlObjectType: GraphQLObjectType = userType.gjDef

// For raw schemas (GJSchemaRaw)
val rawSchema = gjSchemaRawFromSDL(sdl)
val rawUserType = rawSchema.types["User"] as SchemaWithData.Object
val objectTypeDef: ObjectTypeDefinition = rawUserType.gjrDef
```

## Contribution Guidelines

When modifying this library:

1. **Maintain contracts**: Don't break `ViaductSchemaContract` tests
2. **Preserve immutability**: Never expose mutable state
3. **Test with real schemas**: Use actual Viaduct tenant schemas for integration testing
4. **Consider performance**: This code runs during every build
5. **Update this doc**: Keep AGENTS.md current with architectural changes
