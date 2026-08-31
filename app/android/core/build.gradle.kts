import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // kapt, not ksp: ksp releases are pinned to exact Kotlin patch versions,
    // kapt ships inside the Kotlin Gradle plugin itself so it can't drift
    // out of lockstep with whatever Kotlin version this project is on.
    id("org.jetbrains.kotlin.kapt")
}

// T-208: read GIPHY_API_KEY the same way settings.gradle.kts already reads
// flutter.sdk — local.properties is git-ignored, so this must default to
// empty rather than fail when the file or key is absent (fresh checkout,
// CI, or simply "no key yet") — a blank key is a fully supported runtime
// state (GiphyMemeGifProvider is just never constructed), not a build error.
// Uses the imported `Properties` rather than `java.util.Properties()`
// inline — the AGP/Java plugins bind a `java` extension property on the
// project that shadows the `java.util` package in unqualified script code.
val giphyApiKey: String = run {
    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { properties.load(it) }
    }
    properties.getProperty("GIPHY_API_KEY", "")
}

// T-203: POSTHOG_API_KEY, same local.properties pattern as GIPHY_API_KEY
// above — blank is a fully supported state (AnalyticsCore never builds a
// PostHogCaptureClient, AnalyticsGateway.flush() becomes a no-op), not a
// build error.
val posthogApiKey: String = run {
    val properties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { properties.load(it) }
    }
    properties.getProperty("POSTHOG_API_KEY", "")
}

android {
    namespace = "com.arkarizdev.grudge.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "GIPHY_API_KEY", "\"$giphyApiKey\"")
        buildConfigField("String", "POSTHOG_API_KEY", "\"$posthogApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.room:room-runtime:2.8.0")
    implementation("androidx.room:room-ktx:2.8.0")
    kapt("androidx.room:room-compiler:2.8.0")

    // T-110: WatchdogWorker — periodic self-heal check, survives app being closed.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // T-208: GIPHY search + GIF download — no JSON library needed alongside
    // it, org.json (already used by RoastEngine) is enough for the response
    // shape. Pinned to 5.0.0, not the newer 5.5.0: 5.5.0's transitive
    // okhttp-android artifact requires compileSdk 37, but this project (and
    // the current stable AGP 9.0.1) is on compileSdk 36 — bumping that is
    // its own separate decision, not a side effect of adding one library.
    implementation("com.squareup.okhttp3:okhttp:5.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Real org.json.* on the unit test classpath — android.jar's org.json is a
    // stub (isReturnDefaultValues silently returns null instead of throwing),
    // which is why RoastEngine's JSON parsing was previously untestable off-device.
    testImplementation("org.json:json:20260814")
}
