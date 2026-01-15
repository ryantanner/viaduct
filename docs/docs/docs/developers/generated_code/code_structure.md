---
title: Code Structure
description: Tenant API Code Structure
---


## Code Structure

### Packages

There are three main packages in particular making up the Tenant API:

* `viaduct.api`: These are classes like `FieldExecutionContext` which are the foundation of our tenant developer API.

* `viaduct.api.grts`: This is where we put generated code for classes used to represent GraphQL types, see description of GRTs below.

* `<module-prefix>.resolverbases`: This is where we put generated base classes to be inherited by resolver classes (more on these shortly).
