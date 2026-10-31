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

val desktopToolsDir = layout.buildDirectory.dir("desktopTools")
val downloadDesktopTools = tasks.register<DownloadDesktopTools>("downloadDesktopTools") {
    group = "distribution"
    description = "Descarga yt-dlp y ffmpeg verificados para empaquetarlos con la app de escritorio"
    ytDlpVersion.set(providers.gradleProperty("kdownloader.ytdlp.version").orElse("2026.10.26"))
    ffmpegVersion.set(providers.gradleProperty("kdownloader.ffmpeg.version").orElse("b6.1.1"))
    bundleFfmpeg.set(
        providers.gradleProperty("kdownloader.bundleFfmpeg")
            .map { it.trim().toBoolean() }
            .orElse(true),
    )
    outputDir.set(desktopToolsDir)
}

compose.desktop {
    application {
        mainClass = "com.app.kdownloader.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.app.kdownloader"
            packageVersion = distributionVersion
            // `common/` is copied into every package; the runtime reads it through the
            // `compose.application.resources.dir` system property.
            appResourcesRootDir.set(downloadDesktopTools.flatMap { it.outputDir })

            linux { iconFile.set(project.file("icons/icon.png")) }
            windows { iconFile.set(project.file("icons/icon.ico")) }
            macOS { iconFile.set(project.file("icons/icon.icns")) }
        }
    }
}

// The resources provider should already carry the dependency, but these tasks need the binaries
// on disk before Compose snapshots the resources directory.
tasks.matching {
    it.name in setOf(
        "createDistributable",
        "run",
        "runDistributable",
        "packageDistributionForCurrentOS",
    )
}.configureEach { dependsOn(downloadDesktopTools) }
