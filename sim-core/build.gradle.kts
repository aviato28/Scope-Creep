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

// data/airlines.json lives once at the repo root; it is bundled as a classpath resource here and as an asset in :app.
sourceSets.main { resources.srcDir(rootProject.file("data")) }

tasks.test {
    useJUnitPlatform()
    systemProperty("airports.dir", rootProject.file("airports").absolutePath)
    // Optional: -PrenderOut=/some/dir makes AirportRenderTest write VOBL.png / VOBL.svg there.
    providers.gradleProperty("renderOut").orNull?.let { systemProperty("render.out", it) }
    testLogging { events("passed", "failed", "skipped") }
}

// Replay a bug-report file headless: ./gradlew :sim-core:replay -Pfile=/path/to/replay.json[.gz]   (see docs/debugging.md)
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Re-runs a replay file from the in-app \"Report a problem\" and prints the final state."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.aviato28.scopecreep.sim.ReplayRunner")
    workingDir = rootProject.projectDir
    argumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull(providers.gradleProperty("file").orNull, providers.gradleProperty("airports").orNull, providers.gradleProperty("until").orNull?.let { "until=$it" })
    })
}
