import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "io.github.roberbarras.racha"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(libs.springdoc.openapi)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Version shown in the admin screen (`-Pracha.version=1.0.0` for releases).
version = providers.gradleProperty("racha.version").getOrElse("0.0.1-SNAPSHOT")

springBoot {
    buildInfo()
}

tasks.withType<JavaCompile>().configureEach {
    // -serial: exceptions here are never Java-serialized.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    // `./gradlew test -Pracha.updateOpenApi` rewrites docs/api/openapi.json from the running server (OpenApiTest).
    systemProperty("racha.updateOpenApi", providers.gradleProperty("racha.updateOpenApi").isPresent.toString())
}
