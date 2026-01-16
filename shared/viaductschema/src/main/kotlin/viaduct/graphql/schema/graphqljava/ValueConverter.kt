package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.ScalarValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.InputValueWithState
import viaduct.graphql.schema.ViaductSchema

/**
 *  Used to convert the default values of fields and arguments and
 *  applied-directive argument values found in the graphql-java
 *  schema (i.e., [graphql.language.Value] objects) into whatever
 *  universe of values the consumer of the schema would like.  The
 *  resulting values must implement "value type" semantics, meaning
 *  [equals] and [hashCode] are based on value equality, not on
 *  reference equality.
 */
interface ValueConverter {
    /** Default implementation calls [convert] on [valueWithState.value] if
     *  [valueWithState.isNotSet] is false, otherwise returns null.
     */
    fun convert(
        type: ViaductSchema.TypeExpr<*>,
        valueWithState: InputValueWithState
    ): Any? =
        when {
            valueWithState.isNotSet -> null
            valueWithState.isLiteral ->
                /** GJ uses "Literal" values for describing values that have been parsed into a
                 *  [graphql.language.Value]. A common source for these values are values that
                 *  are defined in SDL, such as default values for arguments and input fields.
                 */
                convert(type, valueWithState.value as Value<*>)
            valueWithState.isExternal ->
                /** map external values to Value before passing to [convert] */
                convert(type, externalToLiteral(type, valueWithState.value) ?: NullValue.of())
            else -> throw IllegalArgumentException("unsupported value state")
        }

    /** Convert a value that was parsed as a literal [graphql.language.Value] */
    fun convert(
        type: ViaductSchema.TypeExpr<*>,
        value: Value<*>
    ): Any?

    /** Default impl returns null.  If an override returns a non-null
     *  value, then this function is used by the [GJSchemaChecker] classes
     *  to check that the conversion functions are returning objects of
     *  the expected class.
     */
    fun javaClassFor(type: ViaductSchema.TypeExpr<*>): Class<*>? = null

    companion object {
        val default =
            object : ValueConverter {
                override fun convert(
                    type: ViaductSchema.TypeExpr<*>,
                    value: Value<*>
                ): Any? = defaultValueMapper(type, value)

                override fun javaClassFor(type: ViaductSchema.TypeExpr<*>): Class<*> =
                    when {
                        type.isList -> ArrayValue::class.java
                        type.baseTypeDef is ViaductSchema.Enum -> EnumValue::class.java
                        type.baseTypeDef is ViaductSchema.Input -> ObjectValue::class.java
                        type.baseTypeDef.name == "String" -> StringValue::class.java
                        type.baseTypeDef.name == "Int" -> IntValue::class.java
                        type.baseTypeDef.name == "Float" -> ScalarValue::class.java // Cheat...
                        type.baseTypeDef.name == "Boolean" -> BooleanValue::class.java
                        type.baseTypeDef.name == "ID" -> StringValue::class.java
                        else -> throw IllegalArgumentException("Bad type for default (${type.baseTypeDef}).")
                    }
            }

        val standard =
            object : ValueConverter {
                override fun convert(
                    type: ViaductSchema.TypeExpr<*>,
                    value: Value<*>
                ): Any? = standardValueMapper(type, value)
            }
    }
}
