package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.StringValue
import graphql.language.Value
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema

class StandardValueMapperTest {
    private val sdl = """
        type Query { empty: Int }
        schema { query: Query }

        scalar Date
        scalar DateTime
        scalar Time
        scalar Long
        scalar Short
        scalar UnsupportedScalar

        # builtin scalars can get removed if not used
        type Keep {
          id: ID
          float: Float
        }

        input Input { int: Int! list: [[Int]!] }
        enum Enum { A }
    """.trimIndent()

    private val schema = GJSchema.fromRegistry(readTypes(sdl))

    private fun expr(name: String): ViaductSchema.TypeExpr<*> = schema.types[name]!!.asTypeExpr()

    @Test
    fun standardValueMapper() {
        fun assertConverts(
            expected: Any?,
            typeName: String,
            value: Value<*>?
        ) = expr(typeName).let { type ->
            assertEquals(expected, standardValueMapper(type, value))
        }

        assertConverts(null, "String", NullValue.of())
        assertConverts("a", "String", StringValue("a"))
        (schema.types["Enum"] as ViaductSchema.Enum).value("A")!!.let { enumA ->
            assertConverts(enumA, "Enum", EnumValue("A"))
        }
        assertConverts(true, "Boolean", BooleanValue(true))
        LocalDate.MAX.let { date ->
            assertConverts(date, "Date", StringValue(date.toString()))
        }
        Instant.MAX.let { inst ->
            assertConverts(inst, "DateTime", StringValue(inst.toString()))
        }
        assertConverts(3.14, "Float", FloatValue.of(3.14))
        assertConverts(42, "Int", IntValue.of(42))
        assertConverts(42L, "Long", IntValue.of(42))
        assertConverts(42.toShort(), "Short", IntValue.of(42))
        assertConverts("a", "ID", StringValue("a"))
        assertConverts("a", "String", StringValue("a"))
        OffsetTime.MAX.let { time ->
            assertConverts(time, "Time", StringValue(time.toString()))
        }

        assertThrows<IllegalArgumentException> {
            standardValueMapper(expr("UnsupportedScalar"), StringValue(""))
        }

        assertThrows<IllegalArgumentException> {
            standardValueMapper(expr("Query"), StringValue(""))
        }

        (schema.types["Input"] as ViaductSchema.Input).field("list")!!.type.let { type ->
            assertNull(standardValueMapper(type, NullValue.of()))
            assertEquals(emptyList<Any>(), standardValueMapper(type, ArrayValue(emptyList())))

            assertThrows<IllegalArgumentException> {
                standardValueMapper(type, ArrayValue(listOf(NullValue.of())))
            }
        }
    }
}
