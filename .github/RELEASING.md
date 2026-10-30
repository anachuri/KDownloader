# Publicar una release de KDownloader

El workflow [`.github/workflows/release.yml`](workflows/release.yml) compila y publica los binarios en **GitHub Releases** cuando se empuja un tag `v*`. También se puede lanzar a mano desde **Actions → Release → Run workflow**.

| Job | Qué genera |
| :--- | :--- |
| `test` | `:shared:jvmTest` (bloquea el release si falla) |
| `android` | APK universal firmado + certificado de firma |
| `desktop` | `.deb` (Linux), `.msi` (Windows), `.dmg` (macOS) |
| `release` | `SHA256SUMS`, firma GPG opcional, atestaciones de procedencia y el Release |

Todos los artefactos se publican con nombres `KDownloader-<versión>-*`.

---

## 1. Crear el keystore de Android (una sola vez)

Android exige que **todas las actualizaciones de una app se firmen con la misma clave**. Si pierdes este archivo, no podrás actualizar la app instalada con la misma identidad.

```bash
keytool -genkeypair \
  -keystore kdownloader-release.jks \
  -storetype PKCS12 \
  -alias kdownloader \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=KDownloader, O=KDownloader"
```

Guarda `kdownloader-release.jks` en un lugar seguro (gestor de contraseñas o backup cifrado). **No lo subas al repositorio**: `*.jks`, `*.keystore` y `keystore.properties` ya están en `.gitignore`.

### Convertir el keystore a base64

GitHub Secrets solo admite texto, así que el binario se guarda codificado:

```bash
# Linux
base64 -w0 kdownloader-release.jks > keystore.b64

# macOS
base64 -i kdownloader-release.jks -o keystore.b64
```

```powershell
# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("kdownloader-release.jks")) |
  Set-Content keystore.b64
```

### Añadir los secretos

En GitHub: **Settings → Secrets and variables → Actions → New repository secret**.

| Secreto | Valor |
| :--- | :--- |
| `ANDROID_KEYSTORE_BASE64` | Contenido completo de `keystore.b64` (una sola línea) |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | `kdownloader` |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave (normalmente igual a la del keystore) |

Si falta `ANDROID_KEYSTORE_BASE64`, el job de Android falla a propósito: un APK sin firmar no sirve para distribuir.

Para probar la firma en local sin secretos, crea un `keystore.properties` en la raíz (ignorado por git):

```properties
storeFile=/ruta/absoluta/kdownloader-release.jks
storePassword=...
keyAlias=kdownloader
keyPassword=...
```

```bash
./gradlew :androidApp:assembleRelease \
  -Pkdownloader.version=1.2.3 \
  -Pkdownloader.versionCode=10203
```

---

## 2. Firma GPG de los checksums (opcional)

Los checksums SHA-256 ya se generan siempre. Si además quieres una firma criptográfica del archivo `SHA256SUMS`:

```bash
gpg --full-generate-key          # RSA 4096, uso: firma
gpg --list-secret-keys --keyid-format=long
gpg --armor --export-secret-keys <KEY_ID> > gpg-private.asc
```

Añade dos secretos más:

| Secreto | Valor |
| :--- | :--- |
| `GPG_PRIVATE_KEY` | Contenido completo de `gpg-private.asc` (incluidas las líneas `BEGIN/END`) |
| `GPG_PASSPHRASE` | Passphrase de la clave (vacío si no tiene) |

El workflow publica `SHA256SUMS.asc` y la clave pública `kdownloader-signing-key.asc`. Si estos secretos no existen, el release se publica igualmente, solo con checksums y atestaciones.

> Los secretos de GitHub admiten hasta 48 KB; una clave RSA-4096 blindada ocupa unos pocos KB.

---

## 3. Antes de publicar

El tag apunta a un commit concreto. Si `.github/workflows/release.yml` no está en ese commit, GitHub Actions no ejecutará nada. Asegúrate de subir el workflow y los cambios de build:

```bash
git add .github .gitignore androidApp/build.gradle.kts desktopApp/build.gradle.kts README.md AGENTS.md
git commit -m "ci: release workflow con firma Android"
git push origin main
```

Comprueba también que los cuatro secretos de Android existen en **Settings → Secrets and variables → Actions**.

Si tienes instalada una APK de prueba firmada con otra clave, desinstálala antes de instalar el APK del release: Android rechaza instalar encima una app con firma distinta.

---

## 4. Publicar la versión

```bash
git tag -a v1.2.3 -m "KDownloader 1.2.3"
git push origin v1.2.3
```

- `v1.2.3` → release estable.
- `v1.2.3-beta.1` → release marcado como *prerelease*; los instaladores usan la parte numérica `1.2.3`.
- `versionCode` se calcula como `major * 10000 + minor * 100 + patch` (ej. `1.2.3` → `10203`), así que **nunca reutilices una versión**: Android necesita versionCodes crecientes para las actualizaciones.

También puedes lanzarlo sin tag desde **Actions → Release → Run workflow**, indicando la versión en el campo `version`. En ese caso el workflow crea el tag.

---

## 5. Verificar una descarga

```bash
# Integridad
sha256sum -c SHA256SUMS

# Firma GPG (si existe SHA256SUMS.asc)
gpg --import kdownloader-signing-key.asc
gpg --verify SHA256SUMS.asc SHA256SUMS

# Firma del APK: debe coincidir con android-signing-certificate.txt
apksigner verify --print-certs KDownloader-1.2.3-android-universal.apk

# Procedencia (repos públicos, requiere GitHub CLI)
gh attestation verify KDownloader-1.2.3-android-universal.apk --repo anachuri/KDownloader
```

---

## Notas y limitaciones

- **Escritorio** necesita `yt-dlp` en el `PATH`; `ffmpeg` es opcional pero recomendado. La app no los empaqueta.
- Los instaladores de escritorio **no están firmados** con Authenticode (Windows) ni notarizados (macOS): SmartScreen y Gatekeeper pueden pedir confirmación manual.
- Las atestaciones de procedencia (`actions/attest-build-provenance`) solo funcionan en repositorios públicos; en privados el paso se omite.
- Los artefactos intermedios de Actions se conservan 7 días; los assets del Release son permanentes.
- Si el build de escritorio falla solo en Windows por WiX, el workflow intenta instalar `wixtoolset` con Chocolatey antes de empaquetar.
