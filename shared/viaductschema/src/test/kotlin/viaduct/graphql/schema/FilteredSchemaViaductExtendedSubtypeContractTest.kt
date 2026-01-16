package viaduct.graphql.schema

import viaduct.graphql.schema.test.ViaductSchemaSubtypeContract

internal class FilteredSchemaViaductExtendedSubtypeContractTest : ViaductSchemaSubtypeContract() {
    override fun getSchemaClass() = FilteredSchema::class

    override val skipExtensionTests = true
}
