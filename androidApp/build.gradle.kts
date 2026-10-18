import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "me.d3sox.nevumi"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "me.d3sox.nevumi"
        minSdk = 31
        targetSdk = 37
        versionCode = 9
        versionName = providers.gradleProperty("appVersion").get()
        manifestPlaceholders["appLabel"] = "Nevumi"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared") } }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["notAnnotation"] = "me.d3sox.nevumi.ColdStartupCheck"
    }
    buildFeatures { compose = true }
    packaging.resources.merges += "META-INF/LICENSE.md"
    signingConfigs.create("development") {
        storeFile = rootProject.file("packaging/development.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    externalNativeBuild { cmake { path = rootProject.file("native/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("performance") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("development") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("development")
            manifestPlaceholders["appLabel"] = "Nevumi dev"
            matchingFallbacks += "debug"
        }
    }
    testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

abstract class TailscaleLibraries : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val source: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val script: RegularFileProperty
    @get:Internal abstract val ndk: DirectoryProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction fun build() {
        for (abi in abis.get()) exec.exec {
            commandLine("python3", script.get().asFile, "--android-abi", abi, "--ndk", ndk.get().asFile,
                "--output", output.get().dir(abi).asFile)
        }
    }
}

val tailscaleLibraries = tasks.register<TailscaleLibraries>("tailscaleLibraries") {
    source.set(rootProject.layout.projectDirectory.dir("native/tailscale"))
    script.set(rootProject.layout.projectDirectory.file("scripts/build-tailscale.py"))
    ndk.set(androidComponents.sdkComponents.ndkDirectory)
    abis.set(listOf("arm64-v8a", "x86_64"))
    output.set(layout.buildDirectory.dir("generated/tailscale/jniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(tailscaleLibraries, TailscaleLibraries::output)
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.startup.runtime)
    implementation(libs.compose.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.core)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.coroutines)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.jaudiotagger)
    androidTestUtil(libs.android.test.orchestrator)
}
