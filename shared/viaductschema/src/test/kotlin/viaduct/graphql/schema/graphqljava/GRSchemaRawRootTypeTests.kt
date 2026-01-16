package viaduct.graphql.schema.graphqljava

class GRSchemaRawRootTypeTests : RootTypeFactoryContractForRaw {
    override fun makeSchema(schema: String) = GJSchemaRaw.fromRegistry(readTypes(schema))
}
