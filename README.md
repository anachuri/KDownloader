# KDownloader <img width="40" height="40" alt="icon" src="https://github.com/user-attachments/assets/c268091a-597f-4d69-9dd5-2ba6efca7c31" />


**KDownloader** es una aplicación multiplataforma moderna para la previsualización y descarga de videos y audios, desarrollada con **Kotlin Multiplatform (KMP)** y **Compose Multiplatform**, impulsada por el motor **yt-dlp**.

 Permite analizar enlaces de video (YouTube, Vimeo y cientos de sitios soportados), consultar resoluciones disponible (hasta 4K) y formatos de audio (MP3, M4A, Opus), previsualizar la miniatura y realizar descargas reales con monitoreo de progreso en tiempo real.

---

## Descargas

Las versiones publicadas están en [GitHub Releases](https://github.com/anachuri/KDownloader/releases):

| Plataforma | Formato |
| :--- | :--- |
| Android | APK universal firmado |
| Linux | `.deb` (amd64) |
| Windows | `.msi` (x64) |
| macOS | `.dmg` (Apple Silicon, sin notarizar) |

Cada release incluye `SHA256SUMS`, una firma GPG opcional (`SHA256SUMS.asc`) y atestaciones de procedencia de GitHub. Las instrucciones para verificar descargas y para publicar nuevas versiones están en [.github/RELEASING.md](.github/RELEASING.md).

---

## Características Principales

- **Análisis de Enlaces & Previsualización**: Inspecciona URLs para obtener información del video, duración, resoluciones de video disponibles y opciones de audio con vista previa de miniatura.
- **Descargas Reales con yt-dlp**: Integración nativa con `yt-dlp` para descargas de alta velocidad en escritorio y móvil.
- **Progreso en Tiempo Real**: Muestra porcentaje de avance, velocidad de descarga, tiempo estimado (ETA) y registros del proceso en vivo.
- **Gestión de Destino**: Permite elegir la carpeta de guardado e incluye la función **"Mostrar en carpeta"** integrada con el gestor de archivos nativo de tu sistema (Windows Explorer, macOS Finder, Linux File Manager, Android Downloads).
- **Manejo Inteligente de Errores**: Mapea fallos comunes de yt-dlp (errores 403, verificación de bots, contenido privado o geo-restringido, falta de FFmpeg) a mensajes explicativos y sugerencias en español.
- **Diseño Moderno & Adaptativo**: Creado con **Material 3**, con soporte para temas claros/oscuros y diseño adaptativo para móviles y escritorio.

---

## Estado por Plataforma

| Plataforma | Estado de Descarga | Motor de Descarga | Notas |
| :--- | :--- | :--- | :--- |
| **Escritorio (Windows / macOS / Linux)** | 🟢 Real | `yt-dlp` del sistema (vía `ProcessBuilder`) | Requiere `yt-dlp` en el sistema. Se recomienda `ffmpeg` para combinación de formatos y conversión MP3. |
| **Android** | 🟢 Real | `youtubedl-android` (Python + FFmpeg integrados) | Descarga automática e instalación interna de herramientas. Auto-actualización diaria de yt-dlp. |
| **Web (Wasm / JS)** | 🟡 Prototipo UI | Simulación | Interfaz interactiva para probar el flujo de análisis y selección. |
| **iOS** | 🟡 Prototipo UI | Simulación | Integración Compose/SwiftUI. |

---

## Requisitos Previos

### Para Escritorio (JVM):
Para realizar descargas reales en PC o Mac, se recomienda tener instalados en el sistema:
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (agregado al PATH de tu sistema).
- [FFmpeg](https://ffmpeg.org/) (opcional, pero recomendado para unir pistas de video/audio en máxima calidad o convertir a MP3).

### Para Android:
- No requiere instalar herramientas adicionales: la app empaqueta su propia versión de Python y FFmpeg.

---

## Cómo Ejecutar la Aplicación

Puedes ejecutar las distintas versiones del proyecto mediante los siguientes comandos Gradle:

### Escritorio (Desktop)
```bash
# Modo estándar
./gradlew :desktopApp:run

# Modo con Hot Reload (recarga automática al guardar cambios)
./gradlew :desktopApp:hotRun --auto
```

### Android
```bash
./gradlew :androidApp:assembleDebug
```

### Web Browser
```bash
# Target Wasm (Navegadores modernos, mayor rendimiento)
./gradlew :webApp:wasmJsBrowserDevelopmentRun

# Target JS (Navegadores legados)
./gradlew :webApp:jsBrowserDevelopmentRun
```

### iOS
Abre la carpeta `iosApp/` en **Xcode** y ejecuta el proyecto en el simulador o dispositivo.

---

## Ejecución de Pruebas (Tests)

Puedes correr las pruebas unitarias de la lógica compartida mediante Gradle:

```bash
# Pruebas en Desktop (JVM)
./gradlew :shared:jvmTest

# Pruebas en Android
./gradlew :shared:testAndroidHostTest

# Pruebas Web (Karma + Chrome Headless)
./gradlew :shared:wasmJsTest
./gradlew :shared:jsTest

# Pruebas iOS Simulator (Solo macOS)
./gradlew :shared:iosSimulatorArm64Test
```

---

## Estructura del Proyecto

```text
KDownloader/
├── shared/          # Módulo principal Kotlin Multiplatform (UI Compose, modelos, platform expect/actual)
│   ├── commonMain/  # Lógica compartida, UI HomeScreen, análisis de URLs y parser de yt-dlp
│   ├── jvmMain/     # Implementación ejecutable de yt-dlp y revelación de archivos para Desktop
│   ├── androidMain/ # Integración con youtubedl-android, Scoped Storage y App Context
│   └── webMain/     # Adaptación para JS y WebAssembly
├── androidApp/      # Punto de entrada de la aplicación Android (MainActivity)
├── desktopApp/      # Punto de entrada de la aplicación Desktop JVM (main.kt)
├── webApp/          # Configuración Web (index.html, JS/Wasm main)
└── iosApp/          # Proyecto Xcode y código SwiftUI para iOS
```

---

## Tecnologías Utilizadas

- **Lenguaje**: Kotlin 2.x
- **UI Framework**: Compose Multiplatform (Material 3)
- **Concurrencia**: Kotlin Coroutines & Flows
- **Descargas**: `yt-dlp` / `youtubedl-android`
- **Gestión de dependencias**: Gradle Version Catalog (`gradle/libs.versions.toml`)
