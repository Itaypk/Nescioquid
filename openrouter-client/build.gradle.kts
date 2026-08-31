plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.4.10"
    `java-library`
    `maven-publish`
}

dependencies {
    // Spring Boot BOM aligns Spring, Jackson 3 (tools.jackson) and JUnit versions with a
    // consuming Spring Boot 4.1 app, so nothing here pins a version explicitly.
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    // Spring types appear in the public surface (RestClient in constructors, @Component beans,
    // ApplicationReadyEvent), so they are `api` — consumers are expected to be Spring Boot apps.
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot")

    // Jackson 3 (tools.jackson) for the request/response DTOs and the LLM-output JSON helpers.
    api("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))

    // Coroutines: `AiClient.chatStream` returns a Flow, so this is on the public surface. Chosen
    // over Reactor deliberately — it keeps spring-webflux/reactor-core off consumers' classpaths.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // SLF4J API for logging. Pulled in explicitly because the narrow Spring deps above don't bring
    // it transitively (in a full Spring Boot app it arrives via spring-boot-starter-logging).
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework:spring-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Gradle prints nothing for skipped tests by default, which hides the case that matters here:
    // the live OpenRouter tests skip themselves when the key is absent, the run is rate-limited, or
    // the configured model lacks a capability they need. Without this, a build that verified nothing
    // against the real API looks identical to one that verified everything.
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
