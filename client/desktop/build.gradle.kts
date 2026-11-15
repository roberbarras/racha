import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(25)
}

// libmpv and sqlite-jdbc are native; mpv's `wid` needs the X11 window id of an AWT canvas (JDK internals).
val appVersion = "0.0.1"

val desktopJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "-Dracha.version=$appVersion", // sent with the device when signing in to the sync server
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED",
)

dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:data"))
    implementation(project(":shared:files"))
    implementation(project(":shared:remote"))
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.pdfbox)
    // openjfx POMs pick the platform jar through Maven profiles, which Gradle ignores: one artifact per module.
    listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.controls, libs.javafx.media, libs.javafx.web, libs.javafx.swing).forEach {
        implementation(variantOf(it) { classifier("linux") })
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jb.navigation.compose)
}

compose.desktop {
    application {
        mainClass = "io.github.roberbarras.racha.desktop.MainKt"
        jvmArgs += desktopJvmArgs
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            // From `suggestRuntimeModules` (rerun it when adding libraries): sqlite-jdbc needs java.sql, the course
            // file server jdk.httpserver, JavaFX WebKit jdk.jsobject/jdk.xml.dom/jdk.unsupported.desktop.
            modules(
                "java.instrument", "java.net.http", "java.sql", "jdk.httpserver", "jdk.jfr", "jdk.jsobject",
                "jdk.unsupported", "jdk.unsupported.desktop", "jdk.xml.dom",
            )
            packageName = "racha"
            packageVersion = appVersion
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs(desktopJvmArgs)
    // PDFBox's font index goes to the build folder, never to the developer's home.
    systemProperty("pdfbox.fontcache", layout.buildDirectory.dir("pdfbox").get().asFile.also { it.mkdirs() }.path)
}

// Classpath of the test sources, for GUI harnesses started directly by spikes/safe-run.sh (no Gradle-forked JVM).
tasks.register("printTestRuntimeClasspath") {
    val classpath = sourceSets.test.get().runtimeClasspath
    inputs.files(classpath)
    dependsOn(tasks.named("testClasses"))
    doLast { println(classpath.asPath) }
}
