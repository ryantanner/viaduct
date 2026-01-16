package viaduct.graphql.schema.graphqljava

import graphql.language.EnumTypeDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.UnionTypeDefinition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** Tests for error checking in GJSchemaRaw TypeDef classes. */
class GJSchemaRawErrorTest {
    @Test
    fun `accessing unpopulated Scalar throws helpful error`() {
        val def = ScalarTypeDefinition.newScalarTypeDefinition().name("TestScalar").build()
        val scalar = GJSchemaRaw.Scalar(def, emptyList(), def.name)

        val exception = shouldThrow<IllegalStateException> {
            scalar.appliedDirectives
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Enum throws helpful error`() {
        val def = EnumTypeDefinition.newEnumTypeDefinition().name("TestEnum").build()
        val enum = GJSchemaRaw.Enum(def, emptyList(), def.name)

        val exception = shouldThrow<IllegalStateException> {
            enum.values
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Union throws helpful error`() {
        val def = UnionTypeDefinition.newUnionTypeDefinition().name("TestUnion").build()
        val union = GJSchemaRaw.Union(def, emptyList(), def.name)

        val exception = shouldThrow<IllegalStateException> {
            union.possibleObjectTypes
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Interface throws helpful error`() {
        val def = InterfaceTypeDefinition.newInterfaceTypeDefinition().name("TestInterface").build()
        val iface = GJSchemaRaw.Interface(def, emptyList(), def.name)

        val exception = shouldThrow<IllegalStateException> {
            iface.fields
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Object throws helpful error`() {
        val def = ObjectTypeDefinition.newObjectTypeDefinition().name("TestObject").build()
        val obj = GJSchemaRaw.Object(def, emptyList(), def.name)

        val exception = shouldThrow<IllegalStateException> {
            obj.fields
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Input throws helpful error`() {
        val def = InputObjectTypeDefinition.newInputObjectDefinition().name("TestInput").build()
        val input = GJSchemaRaw.Input(def, emptyList(), def.name)

        val exception = shouldThrow<IllegalStateException> {
            input.fields
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Scalar throws helpful error`() {
        val def = ScalarTypeDefinition.newScalarTypeDefinition().name("TestScalar").build()
        val scalar = GJSchemaRaw.Scalar(def, emptyList(), def.name)

        // First populate succeeds
        scalar.populate(emptyList(), null)

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            scalar.populate(emptyList(), null)
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Enum throws helpful error`() {
        val def = EnumTypeDefinition.newEnumTypeDefinition().name("TestEnum").build()
        val enum = GJSchemaRaw.Enum(def, emptyList(), def.name)

        // First populate succeeds
        enum.populate(emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            enum.populate(emptyList())
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Union throws helpful error`() {
        val def = UnionTypeDefinition.newUnionTypeDefinition().name("TestUnion").build()
        val union = GJSchemaRaw.Union(def, emptyList(), def.name)

        // First populate succeeds
        union.populate(emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            union.populate(emptyList())
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Interface throws helpful error`() {
        val def = InterfaceTypeDefinition.newInterfaceTypeDefinition().name("TestInterface").build()
        val iface = GJSchemaRaw.Interface(def, emptyList(), def.name)

        // First populate succeeds
        iface.populate(emptyList(), emptySet())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            iface.populate(emptyList(), emptySet())
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Object throws helpful error`() {
        val def = ObjectTypeDefinition.newObjectTypeDefinition().name("TestObject").build()
        val obj = GJSchemaRaw.Object(def, emptyList(), def.name)

        // First populate succeeds
        obj.populate(emptyList(), emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            obj.populate(emptyList(), emptyList())
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Input throws helpful error`() {
        val def = InputObjectTypeDefinition.newInputObjectDefinition().name("TestInput").build()
        val input = GJSchemaRaw.Input(def, emptyList(), def.name)

        // First populate succeeds
        input.populate(emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            input.populate(emptyList())
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }
}
