# AGENTS.md

Kotlin Multiplatform + Compose Multiplatform app targeting Android, iOS, desktop JVM, and web (JS + Wasm). The shared UI accepts a video link and shows mock resolution/audio options plus a real thumbnail. Metadata is still mock, but **desktop and Android downloads are real**: desktop runs the system `yt-dlp` through `ProcessBuilder`; Android runs a bundled `yt-dlp` through `youtubedl-android`. Both stream progress into the same UI.

## Modules

- `:shared` — all UI and logic. `App()` in `shared/src/commonMain/kotlin/com/app/kdownloader/App.kt` applies the theme and hosts `KDownloaderScreen` (`ui/HomeScreen.kt`); add cross-platform code here, not in app modules. Platform code goes in `expect`/`actual` files (`Platform.kt`).
  - `model/VideoAnalysis.kt` generates the deterministic demo metadata (titles, durations, sizes, YouTube/Vimeo thumbnail URLs). Replace `analyzeLink` when a real backend exists. `analyzeLink(url, metadata)` accepts the real title/duration/thumbnail resolved by yt-dlp and recomputes estimated sizes from the real duration; without metadata it keeps the demo values.
  - `platform/ImageLoader.kt` is an `expect` fetch of thumbnail bytes: real on Android/desktop/web, returns `null` on iOS.
  - `platform/YtDlp.kt` is the `expect`/`actual` download API (`probeYtDlp`, `ytDlpDownload`) plus `fetchVideoMetadata` (real title/duration/thumbnail, no download) and the `VideoMetadata` model. `fetchVideoMetadata` returns `null` on iOS/web. Arg building, metadata template and `KDLPROGRESS|`/`KDLFILE|`/`KDLMETA|` parsing live in `commonMain/.../YtDlpOptions.kt` (`buildYtDlpOptions`, `metadataOptions`, `parseMetadataLine`, `parseProgressLine`) so both real platforms behave identically; the URL is appended by the desktop argv builder while `youtubedl-android` owns it in `YoutubeDLRequest`.
    - `jvmMain/.../YtDlp.jvm.kt`: resolves the `yt-dlp` binary (`YT_DLP_PATH` env var, `PATH`, then common install dirs), spawns it, and kills the child process when the flow is cancelled.
    - `androidMain/.../YtDlp.android.kt`: real downloads via `io.github.junkfood02.youtubedl-android` (`YoutubeDL` + `FFmpeg`). iOS/web return "not supported". Arg building and progress parsing are covered by `YtDlpJvmTest`.
  - `platform/YtDlpErrors.kt` (`describeYtDlpFailure`) maps raw yt-dlp lines to actionable Spanish hints (403/update, bot check, broken extractor, private/geo-restricted, missing ffmpeg, unsupported URL) and appends the original line. Both real platforms use it; add new cases there instead of duplicating per-platform `when`s.
  - `platform/FileReveal.kt` is the `expect`/`actual` "Mostrar en carpeta" API used by the success state of the download card. `jvmMain/.../FileReveal.jvm.kt` is OS-aware: `explorer.exe /select,<path>` on Windows, `open -R <path>` on macOS, freedesktop `FileManager1.ShowItems` via `gdbus` then `xdg-open <parent>` on Linux. `androidMain` opens the system Downloads screen (`DownloadManager.ACTION_VIEW_DOWNLOADS`) because Android has no "reveal file" intent; iOS/web return `false`. `revealAttempts` is a pure helper covered by `FileRevealJvmTest`.
  - `androidMain/.../AndroidAppContext.kt`: captures the application `Context` in a `ContentProvider` (`YtDlpInitializer`, authority `${applicationId}.kdownloader.initializer`, declared in `shared/src/androidMain/AndroidManifest.xml`) so shared code needs no glue in `:androidApp`. Providers start before any Activity, but the heavy Python/ffmpeg unpacking is deliberately *not* done in `onCreate`; `probeYtDlp`/`ytDlpDownload` run it lazily on `Dispatchers.IO` behind a `Mutex`.
- `:androidApp` — `MainActivity` just hosts `App()`. AGP 9 compiles Kotlin itself; do not add a `kotlin-android` plugin (none exists in the catalog). Its manifest adds `android:configChanges` (orientation/screen size/uiMode) so rotating does not recreate the Activity and cancel an in-flight download; Compose state survives rotation instead.
- `:desktopApp` — `main.kt` → class `com.app.kdownloader.MainKt` (filename is lowercase; matters for `mainClass`). Opens a 1180×840 window.
- `:webApp` — `src/webMain` is shared by both the `js` and `wasmJs` targets (one `main.kt` + `index.html`). Both targets depend on `:shared`.
- `iosApp/` — Xcode project. Swift calls `MainViewControllerKt.MainViewController()` from the static framework `Shared`. An Xcode build phase runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode`.

## Commands

```bash
./gradlew :androidApp:assembleDebug
./gradlew :desktopApp:run              # :desktopApp:hotRun --auto for hot reload
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # modern browsers
./gradlew :webApp:jsBrowserDevelopmentRun       # older browsers
```

Tests (only `:shared` has tests: template placeholders per target plus `VideoAnalysisTest` in `commonTest`):

```bash
./gradlew :shared:jvmTest
./gradlew :shared:testAndroidHostTest   # source set is androidHostTest, not androidTest/androidUnitTest
./gradlew :shared:wasmJsTest            # also :shared:jsTest
./gradlew :shared:iosSimulatorArm64Test # macOS only
```

- Single test: `./gradlew :shared:jvmTest --tests "com.app.kdownloader.SharedLogicDesktopTest"`
- Web tests run through Karma with headless Chrome; Chrome/Chromium must be installed.
- No ktlint/detekt/formatting tooling and no CI workflows exist. `:androidApp:lint` is the only lint task. Verification = build + tests.
- iOS builds require macOS/Xcode; Gradle iOS tasks fail on this Linux host.

## Dependencies and versions

- All versions are in `gradle/libs.versions.toml`; add entries there and reference `libs.*`, never hardcode versions.
- `material3` has its own version (currently alpha) independent of `composeMultiplatform`; don't assume they move together.
- `kotlinx-browser` is declared on the `webMain` source set (shared by `js` + `wasmJs`) and `kotlinx-coroutines-core` on `commonMain`. `webMain` is created by the default KMP hierarchy; no manual `dependsOn` is needed.
- `settings.gradle.kts` limits the `google()` repo to the `androidx`, `com.android`, and `com.google` groups. A Google-hosted artifact from another group needs that filter widened or it won't resolve.

## Gotchas

- `local.properties` is gitignored and holds `sdk.dir`; a fresh clone needs it before any Android task runs.
- Gradle configuration cache is enabled. If a task misbehaves, retry once with `--no-configuration-cache` before changing build files.
- Compose resources live in `shared/src/commonMain/composeResources/`, but the generated `Res` package is `kdownloader.shared.generated.resources`, not `com.app.kdownloader.*`. Use that package when adding resources.
- Thumbnail fetching needs `android.permission.INTERNET` (already declared in `androidApp/src/main/AndroidManifest.xml`). YouTube previews use `img.youtube.com`, which sends permissive CORS headers, so browser `fetch` works; other hosts degrade to the gradient placeholder.
- Desktop downloads need the `yt-dlp` binary on the host. `ffmpeg` is optional: without it the app only downloads combined formats and audio keeps its original container, and the UI shows a warning. Video quality limits and format availability depend on yt-dlp/ffmpeg, not on the mock options shown in the shared UI.
- Android downloads ship their own toolchain (`youtubedl-android`). The first `probeYtDlp`/download unpacks Python (~14 MB) and ffmpeg (~35 MB) into `noBackupFilesDir/youtubedl-android`, which takes several seconds and blocks nothing else; later launches skip it. Uninstalling or clearing app data repeats the unpack. ffmpeg failure is non-fatal and falls back to combined formats/original audio.
- `youtubedl-android` pins the yt-dlp release that existed when the library shipped, and stale versions fail on YouTube with `HTTP Error 403` (the downloaded URL is signed for a client the old extractor no longer negotiates). `refreshYtDlp` in `YtDlp.android.kt` therefore calls `YoutubeDL.updateYoutubeDL` at startup: at most once per 24 h (timestamp in the `kdownloader-ytdlp` prefs), 30 s timeout, silent fallback to the bundled binary when offline/GitHub is unreachable. The first launch of a release downloads ~3 MB, so the status line can sit on "Comprobando yt-dlp…" a little longer. Do not remove this silently: without it downloads rot within months.
- Some sites break inside yt-dlp itself (Bilibili's "Unable to extract initial state" is one example, still failing on the latest release). That is upstream, not this app; `describeYtDlpFailure` explains it instead of dumping the raw log.
- `androidApp/build.gradle.kts` must keep `packaging { jniLibs { useLegacyPackaging = true } }`. AGP 9 defaults to `extractNativeLibs=false`, which leaves `nativeLibraryDir` empty; `YoutubeDL.init` then throws while reading `libpython.zip.so` and every download fails with "No se pudo preparar yt-dlp". The tradeoff is a large (≈235 MB) debug APK containing every ABI.
- Android writes are staged in `Android/data/<pkg>/files/Download/KDownloader` because scoped storage forbids the Python process from writing to public `Downloads`; finished files are then published through `MediaStore.Downloads` into `Download/KDownloader` (`IS_PENDING` toggled, staging copy deleted). On API < 29 the staged file stays in the app-specific folder. `YtDlpInfo.fixedDownloadDirectory` makes the UI show that fixed location instead of the editable "Carpeta de destino" field, since a typed path would be ignored by the publish step.
- Android passes `redirectErrorStream = true` to `YoutubeDL.execute`: the library's progress callback reads stdout while yt-dlp prints progress to stderr. Cancelling the flow calls `destroyProcessById(processId)` to kill the native child; a partial file can be left in the staging folder.
- "Mostrar en carpeta" depends on host tooling: Linux needs a file manager exposing `org.freedesktop.FileManager1` (called through `gdbus`) or `xdg-open`; if none is available `revealInFolder` returns `false` and the UI shows a snackbar instead of crashing. Do not use `dbus-send` for `ShowItems`: it splits `array:string:` values on commas, which corrupts filenames like `Artist, Song.webm`. Android opens the system Downloads screen. The button only appears after a *successful* download.
- Android modules target JVM 11; keep that when adding JVM compiler settings.
- `iosApp/Configuration/Config.xcconfig` has an empty `TEAM_ID`; simulator builds work, device/signing builds need it set. `:shared:checkXcodeProjectConfiguration` validates Xcode/Gradle consistency.
- `kotlin-js-store/yarn.lock` is Kotlin/JS tooling output and currently untracked; leave it in place.
- `README.md` is the stock template README; its command list is accurate.
