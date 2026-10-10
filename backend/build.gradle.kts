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
    implementation("org.springframework.boot:spring-boot-starter-mail")          // E7-43: notification email (SMTP; Mailpit in dev)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // E7-14: access JWT (HS512) signed and verified by Spring Security's resource server with Nimbus (arch 10 §2.3).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    // Compile scope (E7-12): the API error mapper reads the SQLSTATE and CONSTRAINT diagnostics from PSQLException.
    implementation("org.postgresql:postgresql")
    // E7-13: Spring Security's Argon2PasswordEncoder needs Bouncy Castle (arch 10 §2.2, Argon2id).
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    // E7-23 PR 3: venue photos in S3-compatible object storage (SeaweedFS locally, arch 12 §2) with signed links.
    implementation(platform("software.amazon.awssdk:bom:2.55.11"))
    implementation("software.amazon.awssdk:s3")
    // E7-23 PR 3: the small and medium copies without GPS/EXIF (PO decision 3 Oct 2026). Thumbnailator applies the EXIF
    // orientation and writes no metadata; the TwelveMonkeys plugins let ImageIO read WebP and CMYK/odd JPEGs.
    implementation("net.coobird:thumbnailator:0.4.21")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.2")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.15.2")

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

// E7-28 seed data. backend/src/seed/java is its own source set, so the seed is never in the application jar and the
// ArchUnit rules (which scan the main classes) do not see it. `./gradlew seedDev` / `seedPerf` boot the application
// context against the database in E7GZ_DB_URL (default: docker compose), write through the modules' services and end.
// The seed refuses a database that is not local or not fresh (see ps.e7gz.seed.SeedGuard).
val seed: SourceSet by sourceSets.creating {
    java.srcDir("src/seed/java")
    resources.srcDir("src/seed/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[seed.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[seed.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

// The seed's own test (the done-when "works on a fresh DB"): a separate source set and test task, run by `check`.
val seedTest: SourceSet by sourceSets.creating {
    java.srcDir("src/seed/test/java")
    compileClasspath += seed.output + sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += seed.output + sourceSets.main.get().output + sourceSets.test.get().output
}
configurations[seedTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[seedTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val seedTestTask = tasks.register<Test>("seedTest") {
    description = "Runs the dev and perf seeds against fresh databases (E7-28)."
    group = "verification"
    testClassesDirs = seedTest.output.classesDirs
    classpath = seedTest.runtimeClasspath
    useJUnitPlatform { excludeTags("discovery-perf") }
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(seedTestTask) }

// E7-34 done-when: the venue list's p95 on the perf seed, reported in CI and never blocking it (NFR-11). Not part of
// `check`: it seeds 150 venues and 20,000 customers into a fresh Testcontainers database (a few minutes), rebuilds the
// Nearest Availability projection and times GET /customer/venues over HTTP. The report is printed and written to
// build/reports/discovery-perf.md.
tasks.register<Test>("discoveryPerf") {
    description = "Reports the customer venue list's p95 on the perf seed (E7-34, NFR-11); fails when p95 >= 1 s."
    group = "verification"
    testClassesDirs = seedTest.output.classesDirs
    classpath = seedTest.runtimeClasspath
    useJUnitPlatform { includeTags("discovery-perf") }
    systemProperty("e7gz.discoveryPerf.report", layout.buildDirectory.file("reports/discovery-perf.md").get().asFile.absolutePath)
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

fun registerSeed(name: String, mode: String, text: String) = tasks.register<JavaExec>(name) {
    description = text
    group = "seed"
    classpath = seed.runtimeClasspath
    mainClass = "ps.e7gz.seed.SeedApplication"
    args(mode)
}
registerSeed("seedDev", "dev", "Fills a fresh local database with the dev seed: 2 areas, 6 venues, customers (E7-28).")
registerSeed("seedPerf", "perf", "Fills a fresh local database with launch-city volume x10 (E7-28).")
