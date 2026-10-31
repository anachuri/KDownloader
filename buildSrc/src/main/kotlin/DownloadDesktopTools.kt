import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

private const val YTDLP_RELEASES_BASE = "https://github.com/yt-dlp/yt-dlp/releases/download"
private const val FFMPEG_STATIC_BASE =
    "https://github.com/eugeneware/ffmpeg-static/releases/download"

/**
 * SHA-256 of the compressed `ffmpeg-<platform>.gz` assets published by
 * `eugeneware/ffmpeg-static` (GPL builds). Pinned so a compromised upstream release cannot
 * silently replace the binary we ship.
 */
private val FFMPEG_SHA256 = mapOf(
    "linux-x64" to "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa",
    "linux-arm64" to "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0",
    "win32-x64" to "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77",
    "darwin-arm64" to "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa",
    "darwin-x64" to "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106",
)

/**
 * Downloads the desktop download engine into `build/desktopTools/common`, which Compose packages
 * into the installer through `appResourcesRootDir`. Binaries are never committed: CI (or a local
 * build) fetches the pinned versions and verifies their published checksums.
 */
abstract class DownloadDesktopTools : DefaultTask() {
    @get:Input
    abstract val ytDlpVersion: Property<String>

    @get:Input
    abstract val ffmpegVersion: Property<String>

    @get:Input
    abstract val bundleFfmpeg: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val (osName, archName) = hostPlatform()
        val resourcesDir = outputDir.get().asFile.resolve("common").apply { mkdirs() }

        downloadYtDlp(resourcesDir, osName, archName)
        if (bundleFfmpeg.get()) {
            downloadFfmpeg(resourcesDir, osName, archName)
        } else {
            removeFfmpeg(resourcesDir)
        }
    }

    private fun removeFfmpeg(dir: File) {
        File(dir, if (isWindowsHost()) "ffmpeg.exe" else "ffmpeg").delete()
        File(dir, "licenses").deleteRecursively()
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun downloadYtDlp(dir: File, osName: String, archName: String) {
        val asset = when {
            osName == "windows" -> "yt-dlp.exe"
            osName == "macos" -> "yt-dlp_macos"
            osName == "linux" && archName == "x64" -> "yt-dlp_linux"
            osName == "linux" && archName == "arm64" -> "yt-dlp_linux_aarch64"
            else -> error("No hay binario de yt-dlp para $osName-$archName")
        }
        val version = ytDlpVersion.get()
        val sums = fetchText("$YTDLP_RELEASES_BASE/$version/SHA2-256SUMS")
        val expected = checksumFor(sums, asset)
            ?: error("no se encontró el checksum de $asset en SHA2-256SUMS ($version)")
        val target = File(dir, if (osName == "windows") "yt-dlp.exe" else "yt-dlp")

        download("$YTDLP_RELEASES_BASE/$version/$asset", target, expected)
    }

    private fun downloadFfmpeg(dir: File, osName: String, archName: String) {
        val platform = when {
            osName == "windows" && archName == "x64" -> "win32-x64"
            osName == "linux" && archName == "x64" -> "linux-x64"
            osName == "linux" && archName == "arm64" -> "linux-arm64"
            osName == "macos" && archName == "x64" -> "darwin-x64"
            osName == "macos" && archName == "arm64" -> "darwin-arm64"
            else -> error("No hay binario de ffmpeg para $osName-$archName")
        }
        val version = ffmpegVersion.get()
        val expected = FFMPEG_SHA256[platform]
            ?: error("Falta el checksum de ffmpeg para $platform")
        val target = File(dir, if (osName == "windows") "ffmpeg.exe" else "ffmpeg")

        downloadGzip("$FFMPEG_STATIC_BASE/$version/ffmpeg-$platform.gz", target, expected)

        // GPL compliance: ship the license and the build/source details next to the binary.
        val licenses = File(dir, "licenses").apply { mkdirs() }
        downloadTextIfMissing(
            "$FFMPEG_STATIC_BASE/$version/$platform.LICENSE",
            File(licenses, "ffmpeg-LICENSE"),
        )
        downloadTextIfMissing(
            "$FFMPEG_STATIC_BASE/$version/$platform.README",
            File(licenses, "ffmpeg-README"),
        )
    }

    private fun download(url: String, target: File, expectedSha256: String) {
        if (target.isFile && sha256(target).equals(expectedSha256, ignoreCase = true)) return

        val temp = File(target.parentFile, "${target.name}.part")
        try {
            val connection = openConnection(url, followRedirects = true)
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            verify(temp, expectedSha256, url)
            replace(temp, target)
            target.setExecutable(true, false)
        } finally {
            temp.delete()
        }
    }

    private fun downloadGzip(url: String, target: File, expectedSha256: String) {
        val archive = File(target.parentFile, "${target.name}.gz.part")
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            val connection = openConnection(url, followRedirects = true)
            connection.inputStream.use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
            verify(archive, expectedSha256, url)
            GZIPInputStream(archive.inputStream()).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            replace(temp, target)
            target.setExecutable(true, false)
        } finally {
            archive.delete()
            temp.delete()
        }
    }

    private fun downloadTextIfMissing(url: String, target: File) {
        if (target.isFile) return
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            openConnection(url, followRedirects = true).inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            replace(temp, target)
        } finally {
            temp.delete()
        }
    }

    private fun verify(file: File, expectedSha256: String, url: String) {
        val actual = sha256(file)
        check(actual.equals(expectedSha256, ignoreCase = true)) {
            "Checksum inválido para $url: esperado $expectedSha256, obtenido $actual"
        }
    }

    private fun replace(source: File, target: File) {
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun openConnection(url: String, followRedirects: Boolean): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", "KDownloader-build")
            connectTimeout = 30_000
            readTimeout = 120_000
            if (responseCode !in 200..299) {
                error("No se pudo descargar $url (HTTP $responseCode)")
            }
        }

    private fun fetchText(url: String): String =
        openConnection(url, followRedirects = true).inputStream
            .bufferedReader()
            .use { it.readText() }

    private fun hostPlatform(): Pair<String, String> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val osName = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"
            else -> error("Sistema operativo no soportado: $os")
        }
        val archName = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("x86_64") || arch.contains("amd64") -> "x64"
            else -> error("Arquitectura no soportada: $arch")
        }
        return osName to archName
    }

    private fun checksumFor(sums: String, asset: String): String? = sums.lineSequence()
        .map { it.trim() }
        .firstOrNull { line -> line.substringAfterLast(' ').trimStart('*') == asset }
        ?.substringBefore(' ')
        ?.trim()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
