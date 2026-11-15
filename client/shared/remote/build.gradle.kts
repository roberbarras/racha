// HTTP adapter for the sync server ports (ADR-0012), shared by desktop and Android (OkHttp runs on both).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:application"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            // End-to-end sync test: two local databases against a real server (RACHA_E2E_URL).
            implementation(project(":shared:data"))
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}

tasks.withType<Test>().configureEach {
    environment("RACHA_E2E_URL", System.getenv("RACHA_E2E_URL") ?: "")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
