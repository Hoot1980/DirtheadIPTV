import java.io.File
import java.util.Properties
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val omdbApiKey: String = (localProperties.getProperty("OMDB_API_KEY") ?: "").trim()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "app.dirthead.iptv"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.dirthead.iptv"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional: set OMDB_API_KEY in local.properties for IMDb-linked plots (via omdbapi.com).
        buildConfigField("String", "OMDB_API_KEY", "\"$omdbApiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file("C:/Users/jhoot/dirthead-key.jks")
            storePassword = "dirthead"
            keyAlias = "dirthead"
            keyPassword = "dirthead"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // assembleRelease / packageRelease produce APKs under build/outputs/apk/release/.
    // App bundles (AAB) are separate tasks (e.g. bundleRelease), not the default for assembleRelease.
}

// AGP 9: ApplicationExtension no longer exposes applicationVariants in the Kotlin DSL.
// Rename the APK after each package* task (debug/release output dirs stay separate).
tasks.configureEach {
    val taskName = name
    if (!taskName.startsWith("package")) return@configureEach
    if (taskName.contains("AndroidTest") || taskName.contains("UnitTest")) return@configureEach
    if (!taskName.endsWith("Debug") && !taskName.endsWith("Release")) return@configureEach
    doLast {
        val type = if (taskName.endsWith("Release")) "release" else "debug"
        val dir = layout.buildDirectory.dir("outputs/apk/$type").get().asFile
        if (!dir.isDirectory) return@doLast
        val apks = dir.listFiles()?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?: return@doLast
        val source = apks.firstOrNull { !it.name.equals("DirtheadIPTV.apk", ignoreCase = true) }
            ?: return@doLast
        val dest = File(dir, "DirtheadIPTV.apk")
        if (dest.exists()) dest.delete()
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
    }
}

// Same location as legacy $buildDir/outputs/apk/release/ (use layout.buildDirectory with AGP 8+).
val dirtheadDropboxReleaseDir = File("C:/Users/jhoot/Dropbox/DirtheadIPTV")

tasks.register<Copy>("copyApkToDropbox") {
    group = "build"
    description = "After assembleRelease, copy *.apk from outputs/apk/release to Dropbox as DirtheadIPTV.apk"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(dirtheadDropboxReleaseDir)
    rename { _: String -> "DirtheadIPTV.apk" }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    onlyIf {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val hasApk = dir.isDirectory &&
            dir.listFiles()?.any { it.isFile && it.extension.equals("apk", ignoreCase = true) } == true
        if (!hasApk) {
            logger.warn(
                "copyApkToDropbox: no *.apk in ${dir.absolutePath} — skipping copy (assembleRelease may have failed or output layout changed).",
            )
        }
        hasApk
    }
    doFirst {
        dirtheadDropboxReleaseDir.mkdirs()
    }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("copyApkToDropbox")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
