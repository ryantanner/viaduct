package viaduct.gradle

open class ViaductApplicationExtension(objects: org.gradle.api.model.ObjectFactory) {
    /** Kotlin package name for generated GRT classes. */
    val grtPackageName = objects.property(String::class.java).convention("viaduct.api.grts")

    /** Kotlin package name prefix for all modules. */
    val modulePackagePrefix = objects.property(String::class.java)

    /** Port for the development server. Defaults to 8080. Set to 0 for dynamic port allocation. */
    val servePort = objects.property(Int::class.java).convention(8080)

    /** Host address for the development server. Defaults to "0.0.0.0". */
    val serveHost = objects.property(String::class.java).convention("0.0.0.0")
}
