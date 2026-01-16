package viaduct.graphql.schema.binary

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema

/**
 * Tests for BSchema population prevention and guarded property access.
 *
 * These tests verify that:
 * 1. Accessing properties on an unpopulated type throws IllegalStateException
 * 2. Once a type has been populated, calling populate() again throws IllegalStateException
 */
class PopulateOnceTest {
    // ========================================================================
    // Tests for accessing unpopulated types
    // ========================================================================

    @Test
    fun `accessing unpopulated Directive throws helpful error`() {
        val directive = BSchema.Directive("TestDirective")

        val exception = shouldThrow<IllegalStateException> {
            directive.isRepeatable
        }
        exception.message shouldContain "TestDirective"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Scalar throws helpful error`() {
        val scalar = BSchema.Scalar("TestScalar")

        val exception = shouldThrow<IllegalStateException> {
            scalar.appliedDirectives
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Scalar sourceLocation throws helpful error`() {
        val scalar = BSchema.Scalar("TestScalar")

        val exception = shouldThrow<IllegalStateException> {
            scalar.sourceLocation
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Enum throws helpful error`() {
        val enumType = BSchema.Enum("TestEnum")

        val exception = shouldThrow<IllegalStateException> {
            enumType.extensions
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Union throws helpful error`() {
        val union = BSchema.Union("TestUnion")

        val exception = shouldThrow<IllegalStateException> {
            union.extensions
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Interface throws helpful error`() {
        val iface = BSchema.Interface("TestInterface")

        val exception = shouldThrow<IllegalStateException> {
            iface.extensions
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Input throws helpful error`() {
        val input = BSchema.Input("TestInput")

        val exception = shouldThrow<IllegalStateException> {
            input.extensions
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }

    @Test
    fun `accessing unpopulated Object throws helpful error`() {
        val obj = BSchema.Object("TestObject")

        val exception = shouldThrow<IllegalStateException> {
            obj.extensions
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    // ========================================================================
    // Tests for calling populate twice
    // ========================================================================

    @Test
    fun `calling populate twice on Directive throws helpful error`() {
        val directive = BSchema.Directive("TestDirective")

        // First populate succeeds
        directive.populate(
            isRepeatable = false,
            allowedLocations = emptySet(),
            sourceLocation = null,
            args = emptyList()
        )

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            directive.populate(
                isRepeatable = false,
                allowedLocations = emptySet(),
                sourceLocation = null,
                args = emptyList()
            )
        }
        exception.message shouldContain "TestDirective"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Scalar throws helpful error`() {
        val scalar = BSchema.Scalar("TestScalar")

        val ext = ViaductSchema.Extension.of<BSchema.Scalar, Nothing>(
            def = scalar,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )

        // First populate succeeds
        scalar.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            scalar.populate(listOf(ext))
        }
        exception.message shouldContain "TestScalar"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Enum throws helpful error`() {
        val enumType = BSchema.Enum("TestEnum")

        // First populate succeeds
        val ext = ViaductSchema.Extension.of<BSchema.Enum, BSchema.EnumValue>(
            def = enumType,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        enumType.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            enumType.populate(emptyList())
        }
        exception.message shouldContain "TestEnum"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Union throws helpful error`() {
        val union = BSchema.Union("TestUnion")

        // First populate succeeds
        val ext = ViaductSchema.Extension.of<BSchema.Union, BSchema.Object>(
            def = union,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        union.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            union.populate(emptyList())
        }
        exception.message shouldContain "TestUnion"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Interface throws helpful error`() {
        val iface = BSchema.Interface("TestInterface")

        // First populate succeeds
        val ext = ViaductSchema.ExtensionWithSupers.of<BSchema.Interface, BSchema.Field>(
            def = iface,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            supers = emptyList(),
            sourceLocation = null
        )
        iface.populate(listOf(ext), emptySet())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            iface.populate(emptyList(), emptySet())
        }
        exception.message shouldContain "TestInterface"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Input throws helpful error`() {
        val input = BSchema.Input("TestInput")

        // First populate succeeds
        val ext = ViaductSchema.Extension.of<BSchema.Input, BSchema.Field>(
            def = input,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            sourceLocation = null
        )
        input.populate(listOf(ext))

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            input.populate(emptyList())
        }
        exception.message shouldContain "TestInput"
        exception.message shouldContain "populate"
    }

    @Test
    fun `calling populate twice on Object throws helpful error`() {
        val obj = BSchema.Object("TestObject")

        // First populate succeeds
        val ext = ViaductSchema.ExtensionWithSupers.of<BSchema.Object, BSchema.Field>(
            def = obj,
            memberFactory = { emptyList() },
            isBase = true,
            appliedDirectives = emptyList(),
            supers = emptyList(),
            sourceLocation = null
        )
        obj.populate(listOf(ext), emptyList())

        // Second populate throws
        val exception = shouldThrow<IllegalStateException> {
            obj.populate(emptyList(), emptyList())
        }
        exception.message shouldContain "TestObject"
        exception.message shouldContain "populate"
    }

    // ========================================================================
    // Test that Object.possibleObjectTypes overrides TypeDefImpl default
    // ========================================================================

    @Test
    fun `Object possibleObjectTypes is not empty when cast to TypeDefImpl`() {
        // This test verifies that BSchema.Object.possibleObjectTypes correctly
        // returns setOf(this) even when accessed through the TypeDefImpl base class.
        // This works because Object.possibleObjectTypes overrides the interface
        // declaration, not the TypeDefImpl implementation.
        val obj = BSchema.Object("TestObject")

        // Access through the concrete type
        obj.possibleObjectTypes.shouldNotBeEmpty()

        // Access through TypeDefImpl - should still return the Object's override
        val asTypeDefImpl: BSchema.TypeDefImpl = obj
        asTypeDefImpl.possibleObjectTypes.shouldNotBeEmpty()
    }
}
