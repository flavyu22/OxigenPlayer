import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No version here: AGP is already pinned on the shared classpath by the root
    // build.gradle.kts (com.android.application … apply false). Re-declaring the version
    // would fail with "plugin is already on the classpath with an unknown version".
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
}

// Keep in sync with :app defaultConfig.applicationId (including the OXIGEN_APPLICATION_ID
// override), otherwise profile collection targets a package that is not installed.
val targetApplicationId = providers
    .gradleProperty("OXIGEN_APPLICATION_ID")
    .orElse(providers.environmentVariable("OXIGEN_APPLICATION_ID"))
    .getOrElse("com.oxigenplayer.app")

android {
    namespace = "com.oxigenplayer.baselineprofile"
    compileSdk = 37

        defaultConfig {
        // Baseline Profiles are effective on API 28+; below that Android fully AOT-compiles
        // apps at install time, so a profile provides no benefit.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TARGET_APPLICATION_ID", "\"$targetApplicationId\"")
    }

    // The tests in this module exercise the :app release builds.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

baselineProfile {
    // Allow generation on connected devices (API 33+ or rooted) in addition to
    // Gradle-managed devices. Generation itself is only triggered by the explicit
    // :app:generate<Variant>ReleaseBaselineProfile tasks, never by the CI gates.
    useConnectedDevices = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
