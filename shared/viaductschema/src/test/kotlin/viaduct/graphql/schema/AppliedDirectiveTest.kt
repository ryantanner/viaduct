package viaduct.graphql.schema

import graphql.language.IntValue
import graphql.language.StringValue
import graphql.language.Value
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppliedDirectiveTest {
    @Test
    fun `AppliedDirective smoke test`() {
        val args: Map<String, Value<*>> = mapOf(
            "foo" to StringValue.of("bar"),
            "baz" to IntValue.newIntValue(BigInteger.valueOf(1)).build()
        )
        val appliedDirective = ViaductSchema.AppliedDirective.of("name", args)
        assertEquals(appliedDirective.name, "name")
        assertEquals(appliedDirective.arguments.entries, args.entries)
    }
}
