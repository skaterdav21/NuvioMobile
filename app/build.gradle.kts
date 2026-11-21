plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.begati.youtubeauto"
    compileSdk = 35
    defaultConfig {
        // TubeAuto: its own package, so it installs beside YT Auto. The code keeps YT Auto's namespace.
        applicationId = "com.dave.tubeauto"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "dev")
    }
    // CI signs with TUBEAUTO_KEYSTORE (decoded from a repo secret) so every build installs over the last one;
    // without it, the debug key.
    val ciKeystore = System.getenv("TUBEAUTO_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ciKeystore != null) create("ci") {
            storeFile = ciKeystore
            storePassword = System.getenv("TUBEAUTO_KEYSTORE_PASSWORD") ?: "tubeauto"
            keyAlias = System.getenv("TUBEAUTO_KEY_ALIAS") ?: "tubeauto"
            keyPassword = System.getenv("TUBEAUTO_KEY_PASSWORD") ?: "tubeauto"
        }
    }
    buildTypes {
        release {
            signingConfig = if (ciKeystore != null) signingConfigs.getByName("ci") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Lists the values-* languages for Android 13+'s per-app language setting (Settings > Apps > YT Auto > Language);
    // the default language is in res/resources.properties.
    androidResources { generateLocaleConfig = true }
}

// APK named yt-auto-<version>-<buildType>.apk
base.archivesName = "TubeAuto-" + android.defaultConfig.versionName

// One line per passed test: the spec checks are proven by these lines.
tasks.withType<Test>().configureEach {
    testLogging { events("passed", "failed", "skipped") }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    // Full-screen app mode: the unofficial Android Auto SDK (projection CarActivity), from Fermata.
    implementation(files("libs/aauto.aar"))
    // Document-start script (ad data stripped before the page reads it).
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a throwing stub on the JVM; the real one runs the feed parser in tests.
    testImplementation("org.json:json:20240303")
}
