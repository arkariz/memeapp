plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // kapt, not ksp: ksp releases are pinned to exact Kotlin patch versions,
    // kapt ships inside the Kotlin Gradle plugin itself so it can't drift
    // out of lockstep with whatever Kotlin version this project is on.
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.arkarizdev.grudge.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
