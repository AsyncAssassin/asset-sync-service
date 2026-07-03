plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "Asset sync backend service"

// No jackson.version override: Spring Boot 3.5.15 manages Jackson (2.21.4) as a tested set.
// GHSA-5jmj-h7xm-6q6v (CVE-2026-54515) patched releases (2.21.5 / 2.22.1) are not yet on Maven
// Central (verified 2026-07-03; latest published is 2.22.0, itself in the affected range), so a
// fixed bump is impossible today. When a fixed patch appears, override Spring Boot with the
// jackson-bom.version BOM property, not the ineffective jackson.version property. No exploit path
// exists in this codebase (no @JsonIgnoreProperties, no @JsonFormat case-insensitive properties,
// no default/polymorphic typing).

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val generatedJooqDir = layout.buildDirectory.dir("generated/sources/jooq/main/kotlin")

sourceSets {
    create("jooqCodegen") {
        java.srcDir("src/jooqCodegen/java")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.liquibase:liquibase-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.16")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "jooqCodegenImplementation"("org.jooq:jooq-codegen")
    "jooqCodegenImplementation"("org.jooq:jooq-kotlin")
    "jooqCodegenImplementation"("org.liquibase:liquibase-core")
    "jooqCodegenImplementation"("org.testcontainers:postgresql")
    "jooqCodegenRuntimeOnly"("org.postgresql:postgresql")
}

kotlin {
    sourceSets {
        named("main") {
            kotlin.srcDir(generatedJooqDir)
        }
    }

    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Disable the plain jar so `build/libs` holds exactly one artifact (the boot jar). This keeps the
// Dockerfile COPY and the CI `docker build --build-arg JAR_FILE=build/libs/*.jar` glob unambiguous.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.register<JavaExec>("generateJooq") {
    group = "jooq"
    description = "Generates jOOQ Kotlin sources from a Liquibase-migrated PostgreSQL schema."
    inputs.files(fileTree("src/main/resources/db/changelog"))
    outputs.dir(generatedJooqDir)
    classpath = sourceSets["jooqCodegen"].runtimeClasspath
    mainClass.set("com.example.assetsync.codegen.JooqCodegenRunner")
    args(layout.projectDirectory.asFile.absolutePath, generatedJooqDir.get().asFile.absolutePath)
    doFirst {
        delete(generatedJooqDir)
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    dependsOn("generateJooq")
}
