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
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "SHOW_FPS", "false")
    }

    flavorDimensions += "distribution"
    productFlavors {
        // TODO(M5): the in-app updater and REQUEST_INSTALL_PACKAGES live in src/direct only; `play` never gets them.
        create("direct") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    buildTypes {
        debug { buildConfigField("boolean", "SHOW_FPS", "true") }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    debugImplementation(libs.androidx.compose.ui.tooling)
}
