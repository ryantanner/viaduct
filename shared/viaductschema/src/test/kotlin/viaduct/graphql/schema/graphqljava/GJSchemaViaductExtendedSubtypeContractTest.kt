package viaduct.graphql.schema.graphqljava

import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

class GJSchemaViaductExtendedSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass() = SchemaWithData::class
}
