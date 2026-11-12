// Persistence adapter: implements the application's outbound ports on SQLite through SQLDelight.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:application"))
            implementation(libs.sqldelight.coroutines)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}

sqldelight {
    databases {
        create("RachaDatabase") {
            packageName.set("io.github.roberbarras.racha.data.db")
            // Android 8 (minSdk 26) ships SQLite 3.18: queries are checked against that dialect at build time.
            dialect(libs.sqldelight.dialect.sqlite318)
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED") // sqlite-jdbc loads its native library
}
