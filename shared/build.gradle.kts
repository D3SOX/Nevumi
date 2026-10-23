plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val generateAppVersion = tasks.register("generateAppVersion") {
    val appVersion = providers.gradleProperty("appVersion")
    val output = layout.buildDirectory.dir("generated/appVersion")
    inputs.property("appVersion", appVersion)
    outputs.dir(output)
    doLast {
        output.get().file("me/d3sox/nevumi/ui/AppVersion.desktop.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("""
                package me.d3sox.nevumi.ui

                import androidx.compose.runtime.Composable

                @Composable
                internal actual fun appVersion(): String = "${appVersion.get()}"

                const val NEVUMI_VERSION = "${appVersion.get()}"
            """.trimIndent() + "\n")
        }
    }
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "me.d3sox.nevumi.shared"
        compileSdk = 37
        minSdk = 31
        withHostTest {}
        androidResources { enable = true }
    }
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines)
            implementation(libs.serialization.json)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(compose.components.resources)
            implementation(libs.material3)
            implementation(compose.materialIconsExtended)
            implementation("sh.calvin.reorderable:reorderable:3.1.0")
            implementation(libs.material.kolor.get().copy()) {
                // MaterialKolor declares an alpha version; use the app's stable Material 3.
                exclude(group = "org.jetbrains.compose.material3", module = "material3")
            }
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.ui.test)
        }
        getByName("androidMain") {
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jaudiotagger)
                implementation(libs.sshj)
                implementation(libs.bouncycastle.provider)
                implementation(libs.bouncycastle.pkix)
                implementation(libs.activity.compose)
                implementation(libs.camera.camera2)
                implementation(libs.camera.lifecycle)
                implementation(libs.camera.view)
                implementation(libs.zxing.core)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.session)
            }
        }
        getByName("desktopMain") {
            kotlin.srcDir(generateAppVersion)
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.jna)
                implementation(libs.zxing.core)
                api(libs.dbus.core)
                implementation(libs.dbus.unix)
                implementation(libs.jaudiotagger)
                implementation(libs.sshj)
                implementation(libs.bouncycastle.provider)
                implementation(libs.bouncycastle.pkix)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

compose.resources { packageOfResClass = "me.d3sox.nevumi.resources" }

val visualizerNative = tasks.register<Exec>("buildVisualizerNative") {
    inputs.property("buildType", providers.environmentVariable("NEVUMI_NATIVE_BUILD_TYPE").orElse("Release"))
    inputs.dir(rootProject.file("native"))
    inputs.file(rootProject.file("scripts/build-visualizer.py"))
    inputs.file(rootProject.file("scripts/build-tailscale.py"))
    outputs.dir(layout.buildDirectory.dir("native/runtime"))
    environment("JAVA_HOME", javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().metadata.installationPath.asFile.absolutePath)
    commandLine("python3", rootProject.file("scripts/build-visualizer.py"), "--output", layout.buildDirectory.dir("native/runtime").get().asFile)
}

tasks.withType<Test>().configureEach {
    systemProperty("nevumi.testRoot", rootDir.absolutePath)
    if (name == "desktopTest") {
        dependsOn(visualizerNative)
        systemProperty("java.library.path", layout.buildDirectory.dir("native/runtime").get().asFile.absolutePath)
        System.getenv("LD_LIBRARY_PATH")?.let { systemProperty("jna.library.path", it) }
    }
}

tasks.register<JavaExec>("pairedFixture") {
    maxHeapSize = "256m"
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    dependsOn(compilation.compileTaskProvider)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("me.d3sox.nevumi.sync.PairedFixtureKt")
    args(rootProject.file(".test-environment/paired-fixture").absolutePath)
}
