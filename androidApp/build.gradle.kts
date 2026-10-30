import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Release metadata can be overridden from CI or the command line:
//   ./gradlew :androidApp:assembleRelease -Pkdownloader.version=1.2.3 -Pkdownloader.versionCode=10203
val appVersionName = providers.gradleProperty("kdownloader.version").getOrElse("1.0")
val appVersionCode = providers.gradleProperty("kdownloader.versionCode").map { it.toInt() }.getOrElse(1)

// Release signing credentials: environment variables win (CI), keystore.properties is the
// local fallback for developers. The file is gitignored and must never be committed.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingCredential(envName: String, propertyName: String): String? =
    providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseKeystorePath = signingCredential("KDOWNLOADER_KEYSTORE_FILE", "storeFile")

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(libs.youtubedl.android.aria2c)
}

android {
    namespace = "com.app.kdownloader"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.app.kdownloader"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // youtubedl-android reads libpython.zip.so from nativeLibraryDir and runs
            // libpython.so/libffmpeg.so from the same place, so the shared objects must be
            // extracted from the APK at install time. Without this, init() fails with
            // FileNotFoundException.
            useLegacyPackaging = true
        }
    }
    signingConfigs {
        // Only created when credentials are available, so debug builds and unsigned
        // local release builds keep working without a keystore.
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath)
                storePassword = signingCredential("KDOWNLOADER_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingCredential("KDOWNLOADER_KEY_ALIAS", "keyAlias")
                keyPassword = signingCredential("KDOWNLOADER_KEY_PASSWORD", "keyPassword")
            }
        }
    }
    val releaseSigning = signingConfigs.findByName("release")
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}