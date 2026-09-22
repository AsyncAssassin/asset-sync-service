plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.2.0"
description = "Asset sync backend service"

// Jackson: Spring Boot 3.5.15 manages jackson-bom 2.21.4, which sits inside the vulnerable range of
// GHSA-5jmj-h7xm-6q6v (CVE-2026-54515, ">= 2.19.0, < 2.21.5"). Pin the BOM to the latest 2.21.x
// patch instead (all modules used here are published at 2.21.7; verified on Maven Central
// 2026-09-22). `jackson-bom.version` is the property Boot's dependency management honors; the
// `jackson.version` property is ignored. Drop the override once Spring Boot manages >= 2.21.5;
// Dependabot (.github/dependabot.yml) proposes the Boot bump that makes that possible.
// No exploit path exists in this codebase either way (no @JsonIgnoreProperties, no @JsonFormat
// case-insensitive properties, no default/polymorphic typing).
extra["jackson-bom.version"] = "2.21.7"

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

springBoot {
    // Exposes the build name and version at /actuator/info. The build timestamp is excluded so the
    // generated properties, and therefore the jar, stay reproducible between builds.
    buildInfo {
        excludes.set(setOf("time"))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The documentation drift guards (DocsConsistencyTests) and the generated-source guard read
    // these files at test time. Declaring them as inputs makes Gradle re-run the tests when only
    // the docs change instead of treating the task as up-to-date.
    inputs.files(fileTree("docs") { include("*.md") }, ".gitignore")
        .withPropertyName("repositoryFilesReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
