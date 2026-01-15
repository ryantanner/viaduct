---
title: Scopes
description: Schema visibility and access control in Viaduct.
---


## Scopes

This document provides an overview on how to define scopes on the Viaduct GraphQL schema.

### The scoop on scopes

In order to make schema visibility control more explicit and intuitive, we introduced Scopes.

Scopes are a mechanism for encapsulating parts of your schema for information-hiding purposes. Two commonly-used scopes are `viaduct`, which has a bigger schema available to all of your internal systems, and `viaduct:public`, a smaller schema available publicly to your frontend clients. Almost all data types will have `viaduct` scope, which allows them to be queried by backend clients. Some data types, or a subset of fields within data types that can be queried by frontend clients, also have the `viaduct:public` scope.

Scopes leverage GraphQL type extensions to separate fields within a type that belong to different scopes. This convention avoids the need for annotating each field in the schema with `@scope` directives, and provides a way to separate different types of fields in the SDL to optimize for human readability.

For example, if we are to expose only `field1` from `Foo` to `viaduct:public` but not `field2` or `field3`, we will organize our schema like this:

```graphql
type Foo @scope(to: ["viaduct", "viaduct:public"]) {
  field1: Bar
}

extend type Foo @scope(to: ["viaduct"]) {
  field2: Int
  field3: String
}

type Bar @scope(to: ["viaduct", "viaduct:public"]) {
  field4: Boolean
  field5: String
}
```

As you will also learn from the later sections, the scope validation system will also enforce explicitness so that each type (including `object`, `input`, `interface`, `union`, and `enum`) and their extensions will always have at least one scope value.

!!! warning
    Either nothing is scoped or *everything* has a scope applied to it. There is no default scope.

### Multiple Schemas

A single instance of the Viaduct framework can expose multiple schemas. Within Airbnb, for instance, the Viaduct service itself, exposes a different, more complete schema to internal clients than it exposes to external Web and mobile clients. Scopes also provide encapsulation that allows us to hide implementation details present in your central schema.

Every schema exported by an instance of the Viaduct framework is called a scope set. A scope set is identified by a schema ID. The particular scope set seen by a given request to the Viaduct framework is controlled by the `schemaId` field of {{ kdoc("viaduct.service.api.ExecutionInput") }}. In the above example, `viaduct` and `viaduct:public` are both schema IDs. You can use as many schema IDs as you like with whatever naming scheme fits your use case.

### Guidelines for annotating types with @scope

#### Always append `@scope` to the main type

Whenever you are creating a new type (including `object type`, `input type`, `interface`, `union`, and `enum`), always append `@scope(to: ["scope1", "scope2", ...])` to the type itself. (Replace `"scope1"`, `"scope2"`, etc. with your desired scopes)

All the attributes within the main type will share and be exposed to ALL the scopes defined in the `@scope` values.

```graphql
# field1 and field2 will be visible to both Viaduct data API and public API
type Foo @scope(to: ["viaduct", "viaduct:public"]) {
  field1: Int
  field2: String
}

# CONSTANT1 and CONSTANT2 will be visible to Viaduct data API, as well as both foo and bar.
enum Bar @scope(to: ["viaduct", "foo", "bar"]) {
  CONSTANT1
  CONSTANT2
}

# field from Baz is only visible to Viaduct data API
input Baz @scope(to: ["viaduct"]) {
  field: Boolean
}
```

#### Create type extensions and move fields with narrower scopes to this extension

If you need to limit the visible scopes for certain fields, create a type extension, move those fields over, and append `@scope` with proper scopes.

For example, the following definition will expose `field3` to `private` only.

```graphql
type Foo @scope(to: ["public", "private"]) {
  field1: Int
  field2: String
}

extend type Foo @scope(to: ["private"]) {
  field3: Boolean
}

```

### Validation

In GraphQL SDL, scopes are referenced by their string value, and in Kotlin are referenced by the strongly typed enum member. The SDL will be validated at build time to ensure invalid scope names were not referenced.

In addition to detecting invalid scope names, the Viaduct Bazel validators will perform other static analysis on the schema in order to detect invalid or confusing scope usage.

#### Detecting inaccessible fields when referencing another type

Static analysis tooling will detect `@scope` usages that cause inaccessible fields. Take the following schema excerpt:

```graphql
type User @scope(to: ["scope1","scope2"]) {
  id: ID!
  firstName: String
  lastName: String
}

type Listing @scope(to: ["scope3"]) {
  user: User # this field would never be accessible
}
```

In the above example, the User type is only available in the `scope1` and `scope2` scopes, and the Listing type is only available in the `scope3` scope. The `user` field in `Listing` is of type `User`, but `User` is not visible to the scopes associated with the `Listing` type. Thus, filtering the schema to any of the three scopes used in this schema excerpt would result in the `user` field within `Listing` being filtered out of the schema.

This invariant can be corrected in two ways:

* The `Listing` type’s scope should be modified to include `scope1` or `scope2`, OR
* The `User` type’s scope should be modified to include the `scope3` scope.

#### Auto prune a type when all of its fields are out of scope

When all the fields of a type are out of scope, this type will not be accessible, even if it is within the scope. Therefore, Viaduct prunes such empty types recursively in the generated schema. For example:

```graphql
type StaySpace @scope(to: ["viaduct", "listing-block", "viaduct:private"]) {
  spaceId: Long
  metadata: SpaceMetadata
}

type SpaceMetadata @scope(to: ["viaduct", "listing-block"]) {
  bathroom: StayBathroomMetadata
  bedroom: StayBedroomMetadata
}

type StayBathroomMetadata @scope(to: ["viaduct", "viaduct:private"]) {
  spaceName: String
}

type StayBedroomMetadata @scope(to: ["viaduct", "viaduct:private"]) {
  spaceName: String
}
```

Filtering the above schema excerpt to `listing-block` will make `SpaceMetadata` an empty type, since both `StayBathroomMetadata` and `StayBedroomMetadata` will not be in the scope. Thus, `SpaceMetadata` attribute will be pruned from `StaySpace` in the `listing-block` scope, as it will not be reachable, despite that this type has annotated with `listing-block` scope.

#### Detect invalid scope usage within a type

When leveraging a type extension to define fields that are available in a specific scope, it’s essential that the scope specified in the directive on the type extension be one of the scopes specified in the original definition of the type.

Take for example the following schema excerpt:

```graphql
type User @scope(to: ["viaduct","user-block"]) {
  id: ID!
  firstName: String
  lastName: String
}

extend type User @scope(to: ["viaduct:internal-tools"]) {
  aSpecialInternalField: String
}
```

This example shows an incorrect usage of the scope directive on the type extension, as the `viaduct:internal-tools` scope is not specified as a scope on the original User type definition.

The correct version of the above example would be:

```graphql
type User @scope(to: ["viaduct","viaduct:internal-tools", "user-block"]) {
  id: ID!
  firstName: String
  lastName: String
}

extend type User @scope(to: ["viaduct:internal-tools"]) {
  aSpecialInternalField: String
}
```

This validation rule enforces the convention that common fields be defined in the original type definition, and fields that are defined in specific scopes be specified using type extensions.

#### Troubleshooting

If you get an error like `Unable to find concrete type ... for interface ... in the type map` or `Unable to find concrete type ... for union ... in the type map`, it's because the interface or union type is defined in schema module A, and the concrete type that implements the interface or extends the union is defined in schema module B, and B does not depend on A.

For example, this is not allowed because `UnionA` is defined in the data module and its member `TypeA` is defined in the entity module. The entity module does not depend on the data module.

```graphql
# modules/entity
type TypeA {
  fieldA: String
}

# modules/data
type TypeB {
  fieldB: String
}

union UnionA = TypeB

extend UnionA = TypeA # <-- not ok
```

To fix it, make sure you define the concrete type of interface or union in a schema module that is dependent on the schema module where the interface or union type is defined.

For example, you can "lift" up the type definition for `UnionA` to the entity module:

```graphql
# modules/entity
type TypeA {
  fieldA: String
}

union UnionA = TypeA

# modules/data
type TypeB {
  fieldB: String
}

extend type UnionA = TypeB # <-- ok
```
