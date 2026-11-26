// Fix for AGP 9.0+ strict environment variable check: both ANDROID_PREFS_ROOT and ANDROID_USER_HOME are set.
// We remove the deprecated ANDROID_PREFS_ROOT from the runtime environment map to satisfy the check.
@file:Suppress("UNCHECKED_CAST")

try {
    val processEnv = Class.forName("java.lang.ProcessEnvironment")
    val theEnvironment = processEnv.getDeclaredField("theEnvironment")
    theEnvironment.isAccessible = true
    (theEnvironment.get(null) as MutableMap<String, String>).remove("ANDROID_PREFS_ROOT")
    val theCaseInsensitiveEnvironment = processEnv.getDeclaredField("theCaseInsensitiveEnvironment")
    theCaseInsensitiveEnvironment.isAccessible = true
    (theCaseInsensitiveEnvironment.get(null) as MutableMap<String, String>).remove("ANDROID_PREFS_ROOT")
} catch (t: Throwable) {
    // Fails silently if reflection is blocked; the sync error will persist.
}

// Reparație sync Android Studio (AGP 9.4.1): la sync, IDE-ul căuta un JDK numit exact
// "25" (cerut de gradle/gradle-daemon-jvm.properties) și nu-l găsea în jdk.table.xml,
// ceea ce producea ExternalSystemPartialResolutionException ("Unknown cause") deși
// build-ul Gradle reușea. .idea/gradle.xml setează acum explicit gradleJvm=jbr-25,
// iar acest bloc doar documentează JDK-ul ales pentru diagnosticare.
val studioJdkCandidates = listOf(
    "C:/Program Files/Android/Android Studio/jbr",
    File(gradle.gradleUserHomeDir, "jdks/eclipse_adoptium-25-amd64-windows.2").absolutePath,
)
val studioJdk = studioJdkCandidates.firstOrNull { File(it, "bin/java.exe").isFile() }
if (studioJdk != null) {
    logger.lifecycle("OxigenPlayer: Gradle JDK pentru Android Studio sync = $studioJdk")
}

pluginManagement {
    // Resolve official plugins directly to their implementation modules. This
    // avoids an extra marker-artifact lookup and keeps offline/CI builds stable.
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application", "com.android.test" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "org.jetbrains.kotlin.plugin.compose" ->
                    useModule("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${requested.version}")
            }
        }
    }

    repositories {
        // Google repository with content filtering for performance
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android\\.tools.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // Fail fast on repository issues
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        // Optimized repository order for faster resolution
        google()
        mavenCentral()
    }
}

rootProject.name = "OxigenPlayer"
include(":app")
include(":baselineprofile")
