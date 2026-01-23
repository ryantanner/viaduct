package viaduct.tenant.runtime.bootstrap

import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult
import java.lang.ClassLoader
import java.net.URI
import java.net.URLClassLoader
import kotlin.reflect.KClass
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments
import viaduct.utils.slf4j.logger

class ViaductTenantResolverClassFinder(
    packageName: String,
    private val grtPackagePrefix: String
) : TenantResolverClassFinder {
    companion object {
        private val log by logger()
    }

    private val filename: String = "file:///srv/viaduct/modules/${packageName
        .replace("/", "")
        .replace('.', '_')
    }.jar"

    private val classLoader: ClassLoader
    private val classGraphScan: ScanResult

    init {
        // The default (for java.lang.Class.forName()) is actually to
        // use the context classloader, not the system classloader.
        // We emulate the same behavior here explicitly.
        val ctxClassLoader = Thread.currentThread().contextClassLoader
        val urlClassLoader = URLClassLoader(arrayOf(URI(filename).toURL()), ctxClassLoader)
        val urlClassGraphScan = ClassGraph()
            .overrideClassLoaders(urlClassLoader)
            .ignoreParentClassLoaders()
            .enableAnnotationInfo()
            .acceptPackages(packageName)
            .scan()
        if (urlClassGraphScan.allClasses.isNotEmpty()) {
            log.info("Using URLClassLoader({}) for {}", filename, packageName)
            classLoader = urlClassLoader
            classGraphScan = urlClassGraphScan
        } else {
            log.info("Using standard ClassLoader for {}", packageName)
            classLoader = ctxClassLoader
            classGraphScan = ClassGraph()
                .enableAnnotationInfo()
                .acceptPackages(packageName)
                .scan()
        }
    }

    override fun resolverClassesInPackage(): Set<Class<*>> =
        classGraphScan
            .getClassesWithAnnotation(ResolverFor::class.java.name)
            .loadClasses()
            .toSet()

    override fun nodeResolverForClassesInPackage(): Set<Class<*>> =
        classGraphScan
            .getClassesWithAnnotation(NodeResolverFor::class.java.name)
            .loadClasses().toSet()

    override fun getSubTypesOf(typeName: String): Set<Class<*>> =
        classGraphScan
            .getSubclasses(typeName)
            .loadClasses()
            .map {
                // This is redundant with the previous call to .loadClasses(),
                // but shouldn't hurt because the JVM will cache once-loaded
                // classes.  However, upon load failure, this is giving us a
                // better (or at least some form of an) error message.
                try {
                    it.classLoader.loadClass(it.name)
                } catch (e: Exception) {
                    log.info("ignored loadClass(${it.name}) error: $e")
                }
                it
            }.toSet()

    override fun grtClassForName(typeName: String): KClass<ObjectBase> {
        @Suppress("UNCHECKED_CAST")
        return classLoader.loadClass("$grtPackagePrefix.$typeName").kotlin as KClass<ObjectBase>
    }

    override fun argumentClassForName(typeName: String): KClass<out Arguments> {
        @Suppress("UNCHECKED_CAST")
        return classLoader.loadClass("$grtPackagePrefix.$typeName").kotlin as KClass<out Arguments>
    }
}
