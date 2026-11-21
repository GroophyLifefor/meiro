plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "com.muratkirazkaya.meiro"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.muratkirazkaya.meiro"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // Live tests (two engines against the LOCAL dev server) run only when MEIRO_LIVE_URL is set,
                // and then they are the only ones that run. See android/tools/live-sync.sh.
                it.useJUnit {
                    if (System.getenv("MEIRO_LIVE_URL").isNullOrBlank()) excludeCategories("com.muratkirazkaya.meiro.LiveTest")
                    else includeCategories("com.muratkirazkaya.meiro.LiveTest")
                }
                System.getenv("MEIRO_LIVE_URL")?.let { v -> it.environment("MEIRO_LIVE_URL", v) }
                // Reference images and inventory live in docs/; check.sh reads the same output dir.
                it.systemProperty("meiro.mockDir", rootProject.file("../docs/android/mock").absolutePath)
                it.systemProperty("meiro.outDir", rootProject.file("build/check").absolutePath)
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("roborazzi.output.dir", rootProject.file("build/check/roborazzi").absolutePath)
                it.maxHeapSize = "2g"
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            }
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.session)
    implementation(libs.ytdl.library)
    implementation(libs.ytdl.common)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
