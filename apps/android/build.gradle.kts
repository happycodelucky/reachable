@file:Suppress("UnstableApiUsage")

/*
 * Reachable — apps/android sample.
 *
 * Minimal SwiftUI-equivalent: a Jetpack Compose UI that subscribes to
 * `reachability.status` and renders the live ReachabilityStatus. Useful as
 * a manual end-to-end test (toggle airplane mode, switch Wi-Fi / cellular,
 * watch the UI react).
 *
 * Standalone Android application module — separate Gradle subproject from
 * the headless `:reachable` library. Consumes :reachable as a project
 * dependency for local development; downstream apps would consume the AAR
 * via Maven coordinates instead.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9.0+ ships Kotlin support built in — applying `kotlin.android`
    // separately is now a hard error. Compose Compiler still ships as a
    // standalone Kotlin plugin and is applied explicitly.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.happycodelucky.reachable.example.android"
    compileSdk =
        libs.versions.android.compile.sdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.happycodelucky.reachable.example.android"
        minSdk =
            libs.versions.android.min.sdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.compile.sdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // The library under test.
    implementation(project(":reachable"))

    // Compose runtime + UI + Material3.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Activity entry point + lifecycle-aware Flow collection.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
