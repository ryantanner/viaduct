package viaduct.tenant.runtime.bootstrap

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries

/**
 * An implementations of the TenantPackageFinder interface that will scan
 * the canonical place in the local filesystem for tenant modules.
 */
class ViaductTenantPackageFinder : TenantPackageFinder {
    override fun tenantPackages(): Set<String> {
        return try {
            // Note that this convention for where to find tenant modules is shared with
            // viaduct.tenant.runtime.bootstrap.ViaductTenantResolverClassFinder
            Paths.get("/srv/viaduct/modules/")
                .listDirectoryEntries()
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.endsWith(".jar") }
                .map { it.removeSuffix(".jar").replace('_', '.') }
                .toSet()
        } catch (e: java.nio.file.NoSuchFileException) {
            emptySet()
        }
    }
}
