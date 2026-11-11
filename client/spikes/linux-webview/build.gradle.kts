// H1 spike (not shipped): local HTML resources in an embedded WebKit (JavaFX WebView) inside Compose Desktop.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(25)
}

val javafxVersion = "27.0.1"

dependencies {
    implementation(compose.desktop.currentOs)
    // openjfx POMs pick the platform jar through Maven profiles, which Gradle ignores: list each module explicitly.
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:linux")
    }
}

compose.desktop {
    application {
        mainClass = "io.github.roberbarras.racha.spike.webview.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
    }
}

// Lets the safe runner start the JVM directly (one killable process, no Gradle-forked child).
tasks.register("printRuntimeClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    inputs.files(classpath)
    doLast { println(classpath.asPath) }
}
