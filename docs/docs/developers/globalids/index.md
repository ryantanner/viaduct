---
title: Global IDs
description: Global identifiers for nodes
---


Viaduct uses two different Kotlin types to represent GraphQL `ID` types: `GlobalID<T>` and String. {{ kdoc("viaduct.api.globalid.GlobalID") }} is an object that consists of a type and an internal ID. They are used to uniquely identify node objects in the graph. `GlobalID` values support structural equality, as opposed to referential equality.

There are two conditions under which `GlobalID<T>` will be used:
1. The `id` field of a `Node` object type
2. A field of type `ID` with the `@idOf(type:"T")` directive, where `T` must be a GraphQL object or interface type that implements `Node`

Elsewhere in the Kotlin code, String will be used for IDs.

For the examples below, `id`, `id3` and `f2` are GlobalIDs and while `id2` and `f1` are Strings.

```graphqls
type MyNode implements Node {
  id: ID!
  id2: ID!
  id3: ID! @idOf(type: "MyNode")
}

input Input {
  f1: ID!
  f2: ID! @idOf(type: "MyNode")
}
```

If a Node object type implements an interface, and that interface has an id field, then that interface must also implement Node.

Instances of `GlobalID` can be created using the `Context` objects provided to resolvers, e.g., `ctx.globalIDFor(User.Reflection, "123")`.
