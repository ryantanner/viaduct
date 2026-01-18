package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.InputValueWithState
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import viaduct.graphql.schema.ViaductSchema

/**
 * Converts graphql-java [Value] objects found in schema definitions (default values,
 * applied-directive arguments) into ViaductSchema's value representation.
 *
 * The conversion is a pass-through: values remain as [Value] objects (including [NullValue]).
 */
internal object ValueConverter {
    /**
     * Convert an [InputValueWithState] to a value representation.
     * Handles literal values, external values, and unset values.
     *
     * Returns null for unset values (indicating no default), or a [Value] for set values.
     */
    fun convert(
        type: ViaductSchema.TypeExpr<*>,
        valueWithState: InputValueWithState
    ): Value<*>? =
        when {
            valueWithState.isNotSet -> null
            valueWithState.isLiteral ->
                // GJ uses "Literal" values for describing values that have been parsed into a
                // [graphql.language.Value]. A common source for these values are values that
                // are defined in SDL, such as default values for arguments and input fields.
                convert(type, valueWithState.value as Value<*>)
            valueWithState.isExternal ->
                // Map external values to Value before passing to [convert]
                convert(type, externalToLiteral(type, valueWithState.value))
            else -> throw IllegalArgumentException("unsupported value state")
        }

    /**
     * Convert a literal [Value] to a value representation.
     * Returns the value as-is.
     */
    fun convert(
        @Suppress("UNUSED_PARAMETER") type: ViaductSchema.TypeExpr<*>,
        value: Value<*>
    ): Value<*> = value

    /**
     * Returns the expected Java class for a converted value of the given type.
     * Used by [GJSchemaCheck] to validate that conversions produce expected types.
     */
    fun javaClassFor(type: ViaductSchema.TypeExpr<*>): Class<*> =
        when {
            type.isList -> ArrayValue::class.java
            type.baseTypeDef is ViaductSchema.Enum -> EnumValue::class.java
            type.baseTypeDef is ViaductSchema.Input -> ObjectValue::class.java
            type.baseTypeDef is ViaductSchema.Scalar -> scalarValueClass(type.baseTypeDef.name)
            else -> throw IllegalArgumentException("Bad type for default (${type.baseTypeDef}).")
        }

    /**
     * Returns the expected Value subclass for a scalar type name.
     *
     * For built-in GraphQL scalars, returns the specific value class.
     * For custom scalars, returns [Value] as the base class since custom
     * scalars can use any GraphQL literal syntax in SDL - not just scalar
     * literals (Int, Float, String, Boolean) but also Object literals
     * (for JSON-like scalars), Array literals, and Enum literals.
     */
    private fun scalarValueClass(scalarName: String): Class<out Value<*>> =
        when (scalarName) {
            // Built-in GraphQL scalars have specific value types
            "Boolean" -> BooleanValue::class.java
            "Float" -> FloatValue::class.java
            "Int" -> IntValue::class.java
            "ID", "String" -> StringValue::class.java
            // Custom scalars can use any GraphQL literal syntax
            else -> Value::class.java
        }

    /**
     * Maps "external" values (programmatically defined, not parsed from SDL)
     * to [Value] literals. Used when handling [InputValueWithState.isExternal].
     *
     * GJ uses "External" values to describe values that have not been parsed into a
     * [graphql.language.Value]. A common source for these are input values
     * that have been defined "programmatically", like with
     * [graphql.schema.GraphQLArgument.Builder.defaultValueProgrammatic].
     * In particular, these values appear around arguments to built-in
     * directives, which may not be defined by SDL.
     */
    internal fun externalToLiteral(
        type: ViaductSchema.TypeExpr<*>,
        value: Any?,
        listDepth: Int = 0,
    ): Value<*> {
        if (value == null) {
            require(type.nullableAtDepth(listDepth)) {
                "$type not nullable at depth $listDepth"
            }
            return NullValue.of()
        }

        if (listDepth < type.listDepth) {
            require(value is List<*>) {
                "$type not List at depth $listDepth ($value)"
            }
            return ArrayValue(
                value.map { externalToLiteral(type, it, listDepth + 1) }
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
