import groovy.json.JsonSlurper

// Shared Compose UI: the driving adapter common to Android and desktop (design system, screens, view models).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val generatedTokens = layout.buildDirectory.dir("generated/tokens/commonMain/kotlin")

kotlin {
    jvmToolchain(25)
    jvm("desktop")
    android {
        namespace = "io.github.roberbarras.racha.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generatedTokens)
            dependencies {
                api(project(":shared:application"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.ui.backhandler)
                implementation(libs.jb.navigation.compose)
                implementation(libs.jb.lifecycle.viewmodel.compose)
                implementation(libs.jb.lifecycle.runtime.compose)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.compose.ui.test)
                implementation(compose.desktop.currentOs)
                implementation(project(":shared:data"))
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.sqlite.jdbc)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.roberbarras.racha.ui.resources"
    publicResClass = false
}

/** design/tokens.json (single source of truth, spec §10.3) -> Kotlin constants. */
val generateDesignTokens by tasks.registering {
    val source = rootProject.file("../design/tokens.json")
    val outputDir = generatedTokens
    inputs.file(source)
    outputs.dir(outputDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(source) as Map<String, Any>
        val colors = json["color"] as Map<String, Map<String, String>>
        val light = colors.getValue("light")
        val dark = colors.getValue("dark")
        require(light.keys == dark.keys) { "light and dark palettes must define the same keys" }
        fun argb(hex: String) = "Color(0xFF${hex.removePrefix("#").uppercase()})"
        val type = (json["typography"] as Map<String, Any>)["styles"] as Map<String, Map<String, Number>>
        fun numbers(group: String) = json[group] as Map<String, Number>
        val out = StringBuilder()
        out.appendLine("// Generated from design/tokens.json by :shared:ui:generateDesignTokens. Do not edit.")
        out.appendLine("package io.github.roberbarras.racha.ui.design")
        out.appendLine()
        out.appendLine("import androidx.compose.ui.graphics.Color")
        out.appendLine("import androidx.compose.ui.unit.dp")
        out.appendLine()
        out.appendLine("data class RachaPalette(")
        light.keys.forEach { out.appendLine("    val $it: Color,") }
        out.appendLine(")")
        out.appendLine()
        out.appendLine("object Tokens {")
        listOf("Light" to light, "Dark" to dark).forEach { (name, palette) ->
            out.appendLine("    val $name = RachaPalette(")
            palette.forEach { (key, value) -> out.appendLine("        $key = ${argb(value)},") }
            out.appendLine("    )")
        }
        out.appendLine("    object Type {")
        type.forEach { (key, s) ->
            out.appendLine("        val $key = TypeToken(size = ${s["size"]}f, lineHeight = ${s["lineHeight"]}f, weight = ${s["weight"]}, tracking = ${s["tracking"]}f)")
        }
        out.appendLine("    }")
        out.appendLine("    object Space {")
        numbers("spacing").forEach { (k, v) -> out.appendLine("        val $k = $v.dp") }
        out.appendLine("    }")
        out.appendLine("    object Radius {")
        numbers("radius").forEach { (k, v) -> out.appendLine("        val $k = $v.dp") }
        out.appendLine("    }")
        out.appendLine("    object Motion {")
        numbers("motion").forEach { (k, v) -> out.appendLine("        const val $k = $v") }
        out.appendLine("    }")
        out.appendLine("    object Layout {")
        numbers("layout").forEach { (k, v) -> out.appendLine("        val $k = $v.dp") }
        out.appendLine("    }")
        out.appendLine("}")
        out.appendLine()
        out.appendLine("data class TypeToken(val size: Float, val lineHeight: Float, val weight: Int, val tracking: Float)")
        val file = outputDir.get().file("io/github/roberbarras/racha/ui/design/Tokens.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(out.toString())
    }
}

tasks.matching { it.name.startsWith("compileKotlin") || it.name.contains("SourcesJar") || it.name.startsWith("ksp") }
    .configureEach { dependsOn(generateDesignTokens) }

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
