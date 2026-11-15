import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.coroutines)
}
val visualizerResources = tasks.register<Sync>("visualizerResources") {
    dependsOn(":shared:buildVisualizerNative")
    from(project(":shared").layout.buildDirectory.dir("native/runtime"))
    into(layout.buildDirectory.dir("visualizer-resources/common/native"))
}
// The JDK 21 native launcher aborts during library shutdown on current Arch, so desktop runs and distributions use
// JDK 25. Compose's `javaHome` takes a resolved path, so set its tasks lazily and only desktop tasks need JDK 25.
val desktopJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val desktopJavaHome = desktopJdk.map { it.metadata.installationPath.asFile.absolutePath }
// Compose configures these tasks after evaluation, so override them afterwards.
afterEvaluate {
    tasks.withType<AbstractJvmToolOperationTask>().configureEach { javaHome.set(desktopJavaHome) }
    tasks.withType<AbstractSuggestModulesTask>().configureEach { javaHome.set(desktopJavaHome) }
    tasks.withType<AbstractProguardTask>().configureEach { javaHome.set(desktopJavaHome) }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach { jdkHome.set(desktopJavaHome) }
    tasks.withType<JavaExec>().matching { it.name == "run" || it.name == "runRelease" }.configureEach {
        setExecutable(desktopJdk.get().executablePath.asFile.absolutePath)
    }
}
tasks.matching { it.name == "prepareAppResources" || it.name == "createDistributable" || it.name == "run" }.configureEach { dependsOn(visualizerResources) }
compose.desktop {
    application {
        mainClass = "me.d3sox.nevumi.desktop.MainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("visualizer-resources"))
            targetFormats(TargetFormat.Deb)
            packageName = "nevumi"
            packageVersion = providers.gradleProperty("appVersion").get()
            linux { iconFile.set(rootProject.file("packaging/nevumi.png")) }
            modules("java.sql", "java.net.http", "jdk.unsupported", "jdk.security.auth")
        }
    }
}
