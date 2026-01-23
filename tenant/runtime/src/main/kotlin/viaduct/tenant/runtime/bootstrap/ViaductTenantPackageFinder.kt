package viaduct.tenant.runtime.bootstrap

import io.github.classgraph.ClassGraph
import viaduct.api.TenantModule

/**
 * An implementations of the TenantPackageFinder interface that will scan
 * the canonical place in the local filesystem for tenant modules.
 */
class ViaductTenantPackageFinder : TenantPackageFinder {
    private val classGraphScan = ClassGraph().acceptPackages("com.airbnb.viaduct").enableClassInfo().scan()

    override fun tenantPackages(): Set<String> {
        val tenantInterfaceClass = TenantModule::class.java
        val tenantModuleClasses = classGraphScan.getClassesImplementing(tenantInterfaceClass).loadClasses()
        return tenantModuleClasses.map { it.packageName }.toSet()
    }
}
