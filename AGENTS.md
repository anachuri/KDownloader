# AGENTS.md

Kotlin Multiplatform + Compose Multiplatform app targeting Android, iOS, desktop JVM, and web (JS + Wasm). The shared UI accepts a video link and shows mock resolution/audio options plus a real thumbnail. Metadata is still mock, but **desktop downloads are real**: on JVM the app runs the system `yt-dlp` through `ProcessBuilder` and streams its progress into the UI.

## Modules

- `:shared` — all UI and logic. `App()` in `shared/src/commonMain/kotlin/com/app/kdownloader/App.kt` applies the theme and hosts `KDownloaderScreen` (`ui/HomeScreen.kt`); add cross-platform code here, not in app modules. Platform code goes in `expect`/`actual` files (`Platform.kt`).
  - `model/VideoAnalysis.kt` generates the deterministic demo metadata (titles, durations, sizes, YouTube/Vimeo thumbnail URLs). Replace `analyzeLink` when a real backend exists.
  - `platform/ImageLoader.kt` is an `expect` fetch of thumbnail bytes: real on Android/desktop/web, returns `null` on iOS.
  - `platform/YtDlp.kt` is the `expect`/`actual` download API (`probeYtDlp`, `ytDlpDownload`). The real implementation is `jvmMain/.../YtDlp.jvm.kt`: resolves the `yt-dlp` binary (`YT_DLP_PATH` env var, `PATH`, then common install dirs), builds the argv, parses `KDLPROGRESS|`/`KDLFILE|` markers, and kills the child process when the flow is cancelled. Android/iOS/web return "not supported". JVM arg-building and progress parsing are covered by `YtDlpJvmTest`.
  - `platform/FileReveal.kt` is the `expect`/`actual` "Mostrar en carpeta" API used by the success state of the download card. `jvmMain/.../FileReveal.jvm.kt` is OS-aware: `explorer.exe /select,<path>` on Windows, `open -R <path>` on macOS, freedesktop `FileManager1.ShowItems` via `gdbus` then `xdg-open <parent>` on Linux. Android/iOS/web return `false` (downloads are not available there). `revealAttempts` is a pure helper covered by `FileRevealJvmTest`.
- `:androidApp` — `MainActivity` just hosts `App()`. AGP 9 compiles Kotlin itself; do not add a `kotlin-android` plugin (none exists in the catalog).
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
- "Mostrar en carpeta" depends on host tooling: Linux needs a file manager exposing `org.freedesktop.FileManager1` (called through `gdbus`) or `xdg-open`; if none is available `revealInFolder` returns `false` and the UI shows a snackbar instead of crashing. Do not use `dbus-send` for `ShowItems`: it splits `array:string:` values on commas, which corrupts filenames like `Artist, Song.webm`. The button only appears after a *successful* desktop download.
- Android modules target JVM 11; keep that when adding JVM compiler settings.
- `iosApp/Configuration/Config.xcconfig` has an empty `TEAM_ID`; simulator builds work, device/signing builds need it set. `:shared:checkXcodeProjectConfiguration` validates Xcode/Gradle consistency.
- `kotlin-js-store/yarn.lock` is Kotlin/JS tooling output and currently untracked; leave it in place.
- `README.md` is the stock template README; its command list is accurate.
