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
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED",
        )
    }
}

// Lets the safe benchmark runner start the JVM directly (one killable process, no Gradle-forked child).
tasks.register("printRuntimeClasspath") {
    val classpath = sourceSets.main.get().runtimeClasspath
    inputs.files(classpath)
    doLast { println(classpath.asPath) }
}
