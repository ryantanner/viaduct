# Java GRTs Code Generation CLI

This package contains CLI commands for generating Java GraphQL Representational Types (GRTs) from schema files.

## Commands Overview

### JavaGRTsGenerator

Generates **Java source code** for GraphQL types from schema files.

**Key Benefits:**

- Pure Java output for Java-first projects
- Full IDE support (autocomplete, navigation, refactoring)
- Source-level debugging capabilities
- Supports type extensions (`extend enum`, `extend type`, `extend input`, `extend interface`, `extend union`)

## Building

Build the fat JAR with all dependencies:

```bash
./gradlew :core:x:javaapi:x-javaapi-codegen:shadowJar
```

The JAR will be created at `x/javaapi/codegen/build/libs/java-grts-codegen-<version>.jar`.

## Usage

```bash
java -jar x/javaapi/codegen/build/libs/java-grts-codegen-<version>.jar \
  --schema_files /path/to/schema.graphqls \
  --output_dir /path/to/generated \
  --package com.mycompany.graphql.types \
  --verbose
```

**Parameters:**

| Parameter        | Required | Description                                                   |
|------------------|----------|---------------------------------------------------------------|
| `--schema_files` | Yes      | Comma-separated list of GraphQL schema files (absolute paths) |
| `--output_dir`   | Yes      | Output directory for generated Java source files              |
| `--package`      | Yes      | Java package name for generated types                         |
| `--verbose`      | No       | Print generation results (file list and type counts)          |

## Generated Output

The output directory will contain Java source files organized by package, where
`com.mycompany.graphql.types` was the value of the `--package param`:

```
output_dir/
└── com/
    └── mycompany/
        └── graphql/
            └── types/
                ├── MyEnum.java
                ├── MyObject.java
                ├── MyInput.java
                ├── MyInterface.java
                └── MyUnion.java
```

## Supported Types

- **Enums** - Including extended enums (`extend enum`)
- **Objects** - GraphQL object types with fields, getters/setters, and builder pattern
- **Inputs** - GraphQL input types with fields, getters/setters, and builder pattern
- **Interfaces** - GraphQL interface types
- **Unions** - GraphQL union types

## Testing

```bash
./gradlew :core:x:javaapi:x-javaapi-codegen:test
```
