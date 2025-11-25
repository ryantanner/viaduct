package viaduct.service.runtime.globalid

import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefaults

/**
 * Default implementation of GlobalIDCodec used when no custom codec is configured.
 */
class DefaultGlobalIDCodec : GlobalIDCodec {
    override fun serialize(
        typeName: String,
        localID: String
    ): String = GlobalIDCodecDefaults.serialize(typeName, localID)

    override fun deserialize(globalID: String): Pair<String, String> = GlobalIDCodecDefaults.deserialize(globalID)
}
