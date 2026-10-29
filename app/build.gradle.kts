import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.aviato28.scopecreep"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.aviato28.scopecreep"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionName=1.2.3 -PversionCode=N; local builds are 0.0.0-dev / 1.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
        buildConfigField("boolean", "SHOW_FPS", "false")
    }

    // Release key comes from the environment (CI secrets); absent locally, so builds fall back to the debug key.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // The in-app updater and REQUEST_INSTALL_PACKAGES live in src/direct only; `play` gets a no-op stub.
        create("direct") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    buildTypes {
        debug { buildConfigField("boolean", "SHOW_FPS", "true") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Release-grade performance (R8, not debuggable) signed with the debug key, installable beside the debug build.
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            matchingFallbacks += "release"
            buildConfigField("boolean", "SHOW_FPS", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
    lint { abortOnError = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// The airport data lives once, in <repo>/airports. Copy it into the assets at build time (review/ is not shipped).
abstract class CopyAirports : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:OutputDirectory abstract val out: DirectoryProperty

    @TaskAction
    fun run() {
        val dest = out.get().asFile.resolve("airports")
        dest.deleteRecursively()
        source.get().asFile.copyRecursively(dest)
        dest.walkBottomUp().filter { it.isDirectory && it.name == "review" }.forEach { it.deleteRecursively() }
    }
}

val copyAirports = tasks.register<CopyAirports>("copyAirports") {
    source.set(rootProject.layout.projectDirectory.dir("airports"))
    out.set(layout.buildDirectory.dir("generated/airportAssets"))
}

extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyAirports, CopyAirports::out)
}

dependencies {
    implementation(project(":sim-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
