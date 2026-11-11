// H1 spike (not shipped): measures libmpv rendering paths inside Compose Desktop. See docs/spikes/h1-linux-video.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "io.github.roberbarras.racha.spike.video.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
    }
}
