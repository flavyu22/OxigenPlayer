import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Packages the generated Baseline Profile (src/**/generated/baselineProfiles) into
    // release artifacts so ART AOT-compiles the startup path at install time.
    alias(libs.plugins.androidx.baselineprofile)
}

private val clientCredentialNames = listOf(
    "OS_API_KEY",
    "SUBS_RO_API_KEY",
    "SUBDL_API_KEY",
    "TMDB_API_KEY",
    "DEEPL_AUTH_KEY"
)

private fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")}\""

// Client-side values can always be extracted from an APK. Keep them out of every
// build by default and require an explicit local opt-in for provider integration
// testing. Production builds always receive empty values until these services are
// moved behind a trusted server.
val embedProviderCredentials = providers
    .gradleProperty("OXIGEN_EMBED_PROVIDER_CREDENTIALS")
    .orElse(providers.environmentVariable("OXIGEN_EMBED_PROVIDER_CREDENTIALS"))
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

val localCredentialsFile = providers
    .gradleProperty("OXIGEN_SECRETS_FILE")
    .orElse(providers.environmentVariable("OXIGEN_SECRETS_FILE"))
    .orNull
    ?.let(rootProject::file)
    ?: gradle.gradleUserHomeDir.resolve("oxigenplayer-secrets.properties")

val providerCredentials = Properties().apply {
    if (embedProviderCredentials && localCredentialsFile.isFile) {
        localCredentialsFile.inputStream().use(::load)
    }
}

fun releaseProperty(name: String): String? =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?.takeIf(String::isNotBlank)

val releaseStoreFile = releaseProperty("OXIGEN_RELEASE_STORE_FILE")
val releaseStorePassword = releaseProperty("OXIGEN_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseProperty("OXIGEN_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseProperty("OXIGEN_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

val configuredApplicationId = providers
    .gradleProperty("OXIGEN_APPLICATION_ID")
    .orElse(providers.environmentVariable("OXIGEN_APPLICATION_ID"))
    .getOrElse("com.oxigenplayer.app")

android {
    namespace = "com.oxigenplayer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = configuredApplicationId
        minSdk = 24
        targetSdk = 37
        versionCode = 20
        versionName = "2.2.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Single all-inclusive edition: direct updates, explicit LAN cleartext playback and
        // packaged FFmpeg software decoders. Gemini Nano was removed (no AICore on TVs).
        buildConfigField("boolean", "DIRECT_UPDATES_ENABLED", "true")
        buildConfigField("boolean", "CLEARTEXT_LAN_ENABLED", "true")
        buildConfigField("boolean", "SOFTWARE_CODEC_PACK_INCLUDED", "true")
        buildConfigField("boolean", "GEMINI_NANO_INCLUDED", "false")

        // Empty unless OXIGEN_EMBED_PROVIDER_CREDENTIALS=true and the secrets file exists
        // (applies to debug AND release). Embedded values are extractable from the APK.
        for (name in clientCredentialNames) {
            buildConfigField(
                "String",
                name,
                providerCredentials.getProperty(name, "").asBuildConfigString()
            )
        }
    }

    androidResources {
        // Package only locales supported by the in-app language selector.
        localeFilters += listOf("en", "ro", "es", "fr", "de", "it", "pt", "ru")
    }

    bundle {
        // Runtime locale switching must work from an App Bundle without a
        // separate Play Core language download flow.
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile?.let(rootProject::file)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            setProguardFiles(listOf(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            ))

            ndk {
                debugSymbolLevel = "none"
                // Android TV boxes, phones and head units are ARM. Dropping the x86 emulator
                // ABIs roughly halves the native payload (FFmpeg, ML Kit) of the shipped APK.
                // Debug builds keep every ABI so emulators still work.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }

        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            // Exclude only ProGuard consumer metadata; license and notice files remain packaged.
            excludes += "/META-INF/proguard/**"
        }

        dex {
            useLegacyPackaging = false
        }
        jniLibs {
            useLegacyPackaging = false
            // Keep native libraries uncompressed for faster loading on supported Android versions.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Disable build features that are not required by the application.
        aidl = false
        shaders = false
        viewBinding = false
        dataBinding = false
        prefab = false
        resValues = true // Required by external libraries.
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller) // Improves startup after profile installation.
    implementation(libs.androidx.startup)
    implementation(libs.androidx.tracing)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Media3 (ExoPlayer) - Ultra-Optimized
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose) // Provides PlayerSurface.
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.smoothstreaming)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.datasource.rtmp)

    // ML Kit & Coroutines
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.nextlib.media3ext) // GPLv3 FFmpeg decoders, see SOURCE_OFFER_FULL.md
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Debug & Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Baseline Profile generator module. Contributes no runtime dependency; it only feeds
    // the collected baseline-prof.txt into release packaging.
    add("baselineProfile", project(":baselineprofile"))
}

// Resolve the keystore File once at configuration time.
val releaseKeyStoreFile: File? = releaseStoreFile?.let { rootProject.file(it) }

// Capture signing state at configuration time so `doFirst` blocks below only hold
// simple, serializable values (Boolean / File?), not buildscript-class references.
// That keeps the configuration cache happy.
val configured = releaseSigningConfigured
val storeFile = releaseKeyStoreFile

tasks.matching { it.name.startsWith("validateSigning") && it.name.endsWith("Release") }.configureEach {
    doFirst {
        check(configured) {
            "Release signing is not configured. Provide OXIGEN_RELEASE_STORE_FILE, " +
                "OXIGEN_RELEASE_STORE_PASSWORD, OXIGEN_RELEASE_KEY_ALIAS and " +
                "OXIGEN_RELEASE_KEY_PASSWORD as Gradle properties or environment variables."
        }
        check(requireNotNull(storeFile) { "OXIGEN_RELEASE_STORE_FILE is not set." }.isFile) {
            "OXIGEN_RELEASE_STORE_FILE does not point to a readable keystore."
        }
    }
}
