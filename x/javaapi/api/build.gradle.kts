plugins {
    id("conventions.java")
}

description = "Java Tenant API interfaces"

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.assertj.core)
}
