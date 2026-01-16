package viaduct.graphql.schema.graphqljava

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import viaduct.graphql.schema.graphqljava.test.ValueConverterContract

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DefaultValueConverterTest : ValueConverterContract() {
    @BeforeAll // @BeforeAll reports errors in a better format should any of this setup fail
    fun setup() {
        valueConverter = ValueConverter.default

        viaductExtendedSchema =
            GJSchema.fromRegistry(
                readTypes(schema)
            )

        defaultValues =
            mapOf(
                "SubjectConstants.boolean" to BooleanValue.of(true),
                "SubjectConstants.float" to FloatValue.of(3.14159),
                "SubjectConstants.int" to IntValue.of(2),
                "SubjectConstants.long" to IntValue.of(3),
                "SubjectConstants.short" to IntValue.of(4),
                "SubjectConstants.string" to StringValue.of("hello"),
                "SubjectConstants.rstring" to StringValue.of("world"),
                "SubjectConstants.longList"
                    to ArrayValue(listOf(ArrayValue(listOf(IntValue.of(1), IntValue.of(2), IntValue.of(3))))),
                "SubjectConstants.in" to
                    ObjectValue(
                        listOf(
                            ObjectField("f1", IntValue.of(1)),
                            ObjectField("f2", ArrayValue(listOf(IntValue.of(2), IntValue.of(3))))
                        )
                    ),
                "SubjectConstants.enumA" to EnumValue("A"),
                "SubjectConstantsArgs.field.enumA" to EnumValue("A"),
                "SubjectConstantsArgs.field.boolean" to BooleanValue.of(true),
                "SubjectConstantsArgs.field.float" to FloatValue.of(1.0),
                "SubjectConstantsArgs.field.int" to FloatValue.of(2.0),
                "SubjectConstantsArgs.field.long" to IntValue.of(3),
                "SubjectConstantsArgs.field.short" to IntValue.of(4),
                "SubjectConstantsArgs.field.string" to StringValue.of("hello"),
                "SubjectConstantsArgs.field.rstring" to StringValue.of("world"),
                "SubjectConstantsArgs.field.longList"
                    to ArrayValue(listOf(ArrayValue(listOf(IntValue.of(1), IntValue.of(2), IntValue.of(3))))),
                "SubjectConstantsArgs.field.in" to
                    ObjectValue(
                        listOf(
                            ObjectField("f1", IntValue.of(1)),
                            ObjectField("f2", ArrayValue(listOf(IntValue.of(2), IntValue.of(3))))
                        )
                    )
            )

        defaultsToEffectiveDefaults()
    }

    /** The default impl of [ValueConverter] renders default values as
     *  [graphql.language.Value]s, whose `equals` function implements
     *  reference equality, not value equality.  So we need an [xform]
     *  to fix that. */
    override fun xform(value: Any?): Any? {
        if (value !is Value<*>) return value
        return when (value) {
            is ArrayValue -> value.values.map { xform(it) }
            is BooleanValue -> value.isValue
            is EnumValue -> "EnumValue{${value.name}}"
            is FloatValue -> value.value.toDouble()
            is IntValue -> value.value.toDouble()
            is NullValue -> NULL_STANDIN
            is ObjectValue -> value.objectFields.associate { it.name to xform(it.value) }
            is StringValue -> "StringValue{${value.value}}"
            else -> throw IllegalArgumentException("Unknown value type: ${value::class.java}")
        }
    }

    companion object {
        private val NULL_STANDIN = Any()
    }
}
