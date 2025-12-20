package viaduct.graphql.schema.binary

import viaduct.graphql.schema.ViaductSchema

/**
 * Logic for encoding an entire schema (assuming schema-level and
 * constant-level information is collected first).
 */
internal class SchemaEncoder(
    val out: BOutputStream,
    val schemaInfo: SchemaInfo,
    val constantsEncoder: ConstantsEncoder
) {
    fun encode(schema: ViaductSchema) {
        // Write Header section
        HeaderSection
            .from(schemaInfo, constantsEncoder)
            .encode(out)

        // Write Identifiers section (inlined logic)
        out.writeInt(MAGIC_IDENTIFIERS)
        for (s in schemaInfo.identifiers) {
            out.writeIdentifier(s, schemaInfo.inputSchema.types[s], schemaInfo.inputSchema.directives[s])
        }
        out.pad()

        // Write Source Locations section (inlined logic)
        out.writeInt(MAGIC_SOURCE_LOCATIONS)
        out.writeUTF8String("") // Write null placeholder (single 0 byte)
        for (s in schemaInfo.sourceNames) {
            out.writeUTF8String(s)
        }
        out.pad()

        // Write Simple Constants section
        constantsEncoder.encodeSimpleValues(out)

        // Write Compound Constants section
        constantsEncoder.encodeCompoundValues(out, schemaInfo::identifierIndex)

        // Write TypeExpr section (inlined logic)
        out.writeInt(MAGIC_TYPE_EXPRS)
        for (entry in schemaInfo.typeExprs.entries.sortedBy { it.value }) {
            val te = entry.key
            val firstWord = TexprWordOne(schemaInfo.identifierIndex(te.baseTypeDef.name), te)
            out.writeInt(firstWord.word)
            if (firstWord.needsWordTwo()) out.writeInt(TexprWordTwo(te).word)
        }

        // Write Root Types section (inlined logic)
        out.writeInt(MAGIC_ROOT_TYPES)
        val queryTypeName = schemaInfo.inputSchema.queryTypeDef?.name
        out.writeInt(queryTypeName?.let { schemaInfo.identifierIndex(it) } ?: UNDEFINED_ROOT_MARKER)

        val mutationTypeName = schemaInfo.inputSchema.mutationTypeDef?.name
        out.writeInt(mutationTypeName?.let { schemaInfo.identifierIndex(it) } ?: UNDEFINED_ROOT_MARKER)

        val subscriptionTypeName = schemaInfo.inputSchema.subscriptionTypeDef?.name
        out.writeInt(subscriptionTypeName?.let { schemaInfo.identifierIndex(it) } ?: UNDEFINED_ROOT_MARKER)

        // Write Definitions section (directives and type defs interleaved by identifier order)
        encodeDefinitions()
    }
}
