# Avisos de terceros

KDownloader se distribuye bajo **GPL-3.0-or-later** (ver [LICENSE](LICENSE)). Esta página resume las licencias de los componentes que se enlazan o se empaquetan en los binarios publicados, y por qué el conjunto queda bajo GPLv3.

## Componentes empaquetados en el binario

| Componente | Licencia | Dónde entra | Efecto |
| :--- | :--- | :--- | :--- |
| [`youtubedl-android`](https://github.com/JunkFood02/youtubedl-android) (`library`, `ffmpeg`, `aria2c`) | GPL-3.0 | `:shared` → `androidMain` (`io.github.junkfood02.youtubedl-android:*:0.18.1`) | Copyleft fuerte: obliga a que la app de Android se distribuya bajo GPLv3. |
| FFmpeg prebuilt que incluye `youtubedl-android` | GPL-2.0-or-later (build `--enable-gpl`) | Empaquetado por `youtubedl-android:ffmpeg` | Compatible con GPLv3; incompatible con licencias permisivas como MIT. |
| Python embebido en `youtubedl-android` | PSF License | Empaquetado por `youtubedl-android:library` | Permisiva, compatible con GPLv3. |
| [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) | Unlicense (dominio público) | Empaquetado en Android; en escritorio se invoca el binario del sistema como proceso externo | Sin obligaciones de atribución; compatible con cualquier licencia. |
| aria2c (vía `youtubedl-android:aria2c`) | GPL-2.0-or-later | Empaquetado por el artefacto `aria2c` | Compatible con GPLv3. |

## Dependencias de compilación (no se redistribuyen como código fuente)

Todas son **Apache-2.0**, compatible con GPLv3 (aunque no con GPLv2):

- Kotlin / Kotlin Multiplatform, Compose Multiplatform, Material 3 — JetBrains, Apache-2.0.
- AndroidX (`core-ktx`, `activity-compose`, `lifecycle-*`, `appcompat`) — Apache-2.0.
- `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-browser`, `kotlin-wrappers` — Apache-2.0.
- Gradle, AGP y el Kotlin Gradle Plugin — Apache-2.0.

## Herramientas opcionales del sistema (escritorio)

En escritorio, KDownloader **no** enlaza `yt-dlp` ni `ffmpeg`: los ejecuta como procesos externos mediante `ProcessBuilder`, resolviendo el binario del `PATH` (`YT_DLP_PATH`, rutas habituales). La app no los redistribuye, así que sus licencias no condicionan la del proyecto. Si los empaquetaras dentro de un instalador, sí pasarían a formar parte de la distribución.

## Qué hacer al redistribuir

1. Conserva el texto de [LICENSE](LICENSE) y este archivo.
2. Mantén los avisos de copyright de los componentes de la tabla.
3. Si distribuyes un binario modificado, publica el código fuente correspondiente bajo GPLv3.
4. El texto exacto de las licencias de `youtubedl-android`, FFmpeg, Python y aria2c se puede obtener de los artefactos de Maven y de sus repositorios enlazados arriba.
