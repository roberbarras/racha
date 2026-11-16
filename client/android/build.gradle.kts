plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release version (`-Pracha.version=1.0.0`, `-Pracha.versionCode=…` in CI); a development build otherwise.
val appVersion = providers.gradleProperty("racha.version").getOrElse("0.0.1")
val appVersionCode = providers.gradleProperty("racha.versionCode").getOrElse("1").toInt()

// Release signing (ADR-0009): the keystore comes from the environment (CI decodes it from its secrets), never from
// the repository. Without it, release builds are signed with the debug key, only to test them (R8) locally.
val releaseKeystore = providers.environmentVariable("RACHA_KEYSTORE").orNull?.let(::file)?.takeIf { it.isFile }

android {
    namespace = "io.github.roberbarras.racha.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.roberbarras.racha"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["androidTest"].assets.directories += listOf("../testdata/media", "../testdata/web", "../testdata/documents")

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("RACHA_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RACHA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RACHA_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:data"))
    implementation(project(":shared:files"))
    implementation(project(":shared:remote"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Compose testing pulls Espresso 3.5, which reflects on InputManager.getInstance (gone in recent Android).
    androidTestImplementation(libs.androidx.test.espresso.core)
}
