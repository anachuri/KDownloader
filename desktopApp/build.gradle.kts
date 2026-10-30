import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

// CI overrides this with the numeric part of the release tag:
//   ./gradlew :desktopApp:packageDistributionForCurrentOS -Pkdownloader.version=1.2.3
val distributionVersion = providers.gradleProperty("kdownloader.version").getOrElse("1.0.0")

compose.desktop {
    application {
        mainClass = "com.app.kdownloader.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.app.kdownloader"
            packageVersion = distributionVersion

            linux { iconFile.set(project.file("icons/icon.png")) }
            windows { iconFile.set(project.file("icons/icon.ico")) }
            macOS { iconFile.set(project.file("icons/icon.icns")) }
        }
    }
}