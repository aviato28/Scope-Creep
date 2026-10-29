plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("airports.dir", rootProject.file("airports").absolutePath)
    // Optional: -PrenderOut=/some/dir makes AirportRenderTest write VOBL.png / VOBL.svg there.
    providers.gradleProperty("renderOut").orNull?.let { systemProperty("render.out", it) }
    testLogging { events("passed", "failed", "skipped") }
}
