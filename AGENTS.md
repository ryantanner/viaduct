This is the Viaduct Open Source Software (OSS) root directory.

Viaduct is an opinionated GraphQL server.

A systems builder wanting to embed Viaduct into their web server would creat an instance of [`viaduct.service.api.Viaduct`](service/api/src/main/kotlin/viaduct/service/api/Viaduct.kt) and create a route that would call the `Viaduct.execute` method.  This method, under the covers, calls [`viaduct.engine.api.Engine.execute`](engine/api/src/main/kotlin/viaduct/engine/api/Engine.kt).

For an end-to-end example of a service that embeds Viaduct, see the demonstration applications in `demoapps`, especially `demoapps/starwars`.

For more information on constructing a `Viaduct` object see `service/AGENTS.md`.

For more information about the details of how Viaduct executes GraphQL operations, you can look at `engine/AGENTS.md`, although it is often helpful to start with `service/AGENTS.md` to understand how `viaduct.engine.api.Engine` instances get configured.
