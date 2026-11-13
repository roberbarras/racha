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
val desktopJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-exports=java.desktop/sun.awt.X11=ALL-UNNAMED",
)

dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:data"))
    implementation(project(":shared:files"))
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

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
            packageName = "racha"
            packageVersion = "0.0.1"
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs(desktopJvmArgs)
}

// Classpath of the test sources, for GUI harnesses started directly by spikes/safe-run.sh (no Gradle-forked JVM).
tasks.register("printTestRuntimeClasspath") {
    val classpath = sourceSets.test.get().runtimeClasspath
    inputs.files(classpath)
    dependsOn(tasks.named("testClasses"))
    doLast { println(classpath.asPath) }
}
