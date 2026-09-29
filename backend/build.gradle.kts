plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ps.e7gz"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springdocVersion = "2.8.17"
val archunitVersion = "1.5.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    // Compile scope (E7-12): the API error mapper reads the SQLSTATE and CONSTRAINT diagnostics from PSQLException.
    implementation("org.postgresql:postgresql")
    // E7-13: Spring Security's Argon2PasswordEncoder needs Bouncy Castle (arch 10 §2.2, Argon2id).
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Tests read files outside backend/: the DB init script (infra/) and the DB contract scripts (docs/).
    systemProperty("e7gz.repoRoot", rootDir.parentFile.absolutePath)
}

// The OpenAPI contract (E7-11). packages/api-client/openapi.json is committed and written only by
// `generateOpenApi` (run through `pnpm api:gen`); `test` only compares it.
val openApiContract = rootDir.parentFile.resolve("packages/api-client/openapi.json")
val sampleContract = layout.buildDirectory.file("generated-contracts/sample.openapi.json")

tasks.test {
    // OpenApiContractTest reads the committed contract, so editing only that file must re-run the tests.
    inputs.files(openApiContract).withPropertyName("openApiContract").withPathSensitivity(PathSensitivity.RELATIVE)
    // OpenApiSampleContractTest writes a test-only contract from sample controllers; packages/api-client
    // generates types from it and mocks it (`test:chain`). CI hands it to that job as an artifact.
    outputs.file(sampleContract).withPropertyName("sampleContract")
    systemProperty("e7gz.sampleContract", sampleContract.get().asFile.absolutePath)
}

tasks.register<Test>("generateOpenApi") {
    description = "Writes the OpenAPI 3.1 contract to packages/api-client/openapi.json."
    group = "api"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("ps.e7gz.openapi.OpenApiContractTest.committedContractMatchesTheControllers") }
    systemProperty("e7gz.openapi.write", "true")
    outputs.upToDateWhen { false }
}
