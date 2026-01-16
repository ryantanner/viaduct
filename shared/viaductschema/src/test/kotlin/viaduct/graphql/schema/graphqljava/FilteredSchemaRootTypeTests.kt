package viaduct.graphql.schema.graphqljava

class FilteredSchemaRootTypeTests : RootTypeFactoryContractForBoth {
    override fun makeSchema(schema: String) = GJSchema.fromRegistry(readTypes(schema)).filter(NoopSchemaFilter())
}
