# Avisos de terceros

KDownloader se distribuye bajo **GPL-3.0-or-later** (ver [LICENSE](LICENSE)). Esta página resume las licencias de los componentes que se enlazan o se empaquetan en los binarios publicados, y por qué el conjunto queda bajo GPLv3.

## Componentes empaquetados en el binario

| Componente | Licencia | Dónde entra | Efecto |
| :--- | :--- | :--- | :--- |
| [`youtubedl-android`](https://github.com/JunkFood02/youtubedl-android) (`library`, `ffmpeg`, `aria2c`) | GPL-3.0 | `:shared` → `androidMain` (`io.github.junkfood02.youtubedl-android:*:0.18.1`) | Copyleft fuerte: obliga a que la app de Android se distribuya bajo GPLv3. |
| FFmpeg prebuilt que incluye `youtubedl-android` | GPL-2.0-or-later (build `--enable-gpl`) | Empaquetado por `youtubedl-android:ffmpeg` | Compatible con GPLv3; incompatible con licencias permisivas como MIT. |
| Python embebido en `youtubedl-android` | PSF License | Empaquetado por `youtubedl-android:library` | Permisiva, compatible con GPLv3. |
| [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) | Unlicense (dominio público) | Empaquetado en Android y en los instaladores de escritorio (se ejecuta como proceso externo); en escritorio se actualiza a datos de usuario | Sin obligaciones de atribución; compatible con cualquier licencia. |
| [FFmpeg estático de escritorio](https://github.com/eugeneware/ffmpeg-static) (`b6.1.1`) | GPL-3.0 (builds con libx264/libx265) | Empaquetado en los instaladores de escritorio; se ejecuta como proceso externo | Copyleft fuerte, compatible con la GPLv3 del proyecto. La licencia y los detalles de build se incluyen en `resources/licenses/`. |
| aria2c (vía `youtubedl-android:aria2c`) | GPL-2.0-or-later | Empaquetado por el artefacto `aria2c` | Compatible con GPLv3. |

## Dependencias de compilación (no se redistribuyen como código fuente)

Todas son **Apache-2.0**, compatible con GPLv3 (aunque no con GPLv2):

- Kotlin / Kotlin Multiplatform, Compose Multiplatform, Material 3 — JetBrains, Apache-2.0.
- AndroidX (`core-ktx`, `activity-compose`, `lifecycle-*`, `appcompat`) — Apache-2.0.
- `kotlinx-coroutines`, `kotlinx-serialization`, `kotlinx-browser`, `kotlin-wrappers` — Apache-2.0.
- Gradle, AGP y el Kotlin Gradle Plugin — Apache-2.0.

## Herramientas del sistema (escritorio)

Los instaladores de escritorio empaquetan una copia verificada de `yt-dlp` y de `ffmpeg`. La app los ejecuta como procesos externos mediante `ProcessBuilder`, no enlaza sus bibliotecas. El orden de resolución es:

1. `YT_DLP_PATH`, si está definido (útil para desarrollo o para usar una versión propia).
2. Copia actualizada en los datos del usuario (`%LOCALAPPDATA%\KDownloader\tools`, `~/Library/Application Support/KDownloader/tools` o `~/.local/share/KDownloader/tools`).
3. Copia incluida en el instalador (se copia a datos de usuario en el primer arranque porque el directorio de instalación puede ser de solo lectura).
4. El `PATH` y las rutas habituales del sistema.

`yt-dlp` se comprueba como máximo una vez cada 24 h y, si hay una versión nueva, se descarga desde GitHub verificando el `SHA2-256SUMS` publicado; si falla (sin red, proxy, checksum), se sigue usando la copia incluida. `ffmpeg` es opcional: sin él solo se descargan formatos combinados y el audio conserva su contenedor original. Se puede compilar sin empaquetar ffmpeg con `-Pkdownloader.bundleFfmpeg=false`.

El texto de las licencias de FFmpeg y los detalles de build se copian junto al binario en `resources/licenses/`.

## Qué hacer al redistribuir

1. Conserva el texto de [LICENSE](LICENSE) y este archivo.
2. Mantén los avisos de copyright de los componentes de la tabla.
3. Si distribuyes un binario modificado, publica el código fuente correspondiente bajo GPLv3.
4. El texto exacto de las licencias de `youtubedl-android`, FFmpeg, Python y aria2c se puede obtener de los artefactos de Maven y de sus repositorios enlazados arriba.
