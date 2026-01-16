package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.StringValue
import graphql.language.Value
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import viaduct.graphql.schema.ViaductSchema

/** Implements a 1-way mapping of a value from a `From` type to a `To` type */
interface ValueMapper<A, B> : ((ViaductSchema.TypeExpr<*>, A?) -> B?)

/** GJ uses "External" values to describe values that have not been parsed into a
 *  [graphql.language.Value]. A common source for these are input values
 *  that have been defined "programmatically", like with
 *  [graphql.schema.GraphQLArgument.Builder.defaultValueProgrammatic].
 *  In particular, these values appear around arguments to built-in
 *  directives, which may not be defined by SDL.
 *
 *  ExternalToLiteral maps External values to [graphql.langauge.Value] values.
 */
val externalToLiteral =
    object : ValueMapper<Any, Value<*>> {
        override fun invoke(
            type: ViaductSchema.TypeExpr<*>,
            value: Any?
        ): Value<*>? = map(type, value, 0)

        private fun map(
            type: ViaductSchema.TypeExpr<*>,
            value: Any?,
            listDepth: Int
        ): Value<*>? {
            if (value == null) {
                if (!type.nullableAtDepth(listDepth)) {
                    throw IllegalArgumentException("$type not nullable at depth $listDepth")
                }
                return NullValue.of()
            }

            if (listDepth < type.listDepth) {
                if (value !is List<*>) {
                    throw IllegalArgumentException("$type not List at depth $listDepth ($value)")
                }
                return ArrayValue(
                    value.map { map(type, it, listDepth + 1) }
                )
            }

            return when (val bt = type.baseTypeDef) {
                is ViaductSchema.Scalar ->
                    when (bt.name) {
                        "Boolean" -> BooleanValue(value as Boolean)
                        "Date" -> StringValue((value as LocalDate).toString())
                        "DateTime" -> StringValue((value as Instant).toString())
                        "Float" -> FloatValue(BigDecimal(value.toString()))
                        "Int", "Long", "Short" -> IntValue(BigInteger(value.toString()))
                        "ID" -> StringValue(value as String)
                        "String" -> StringValue(value as String)
                        "Time" -> StringValue((value as OffsetTime).toString())
                        else -> throw UnsupportedOperationException("Can't convert $value to $type")
                    }
                else -> throw UnsupportedOperationException("Can't convert $value to $type")
            }
        }
    }

/** A pass-thru ValueMapper for GJ literals */
internal val defaultValueMapper =
    object : ValueMapper<Value<*>, Value<*>> {
        override fun invoke(
            type: ViaductSchema.TypeExpr<*>,
            value: Value<*>?
        ): Value<*>? =
            if (value is NullValue) {
                null
            } else {
                value
            }
    }
