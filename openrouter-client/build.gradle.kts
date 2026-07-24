plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.4.10"
    `java-library`
    `maven-publish`
}

dependencies {
    // Spring Boot BOM aligns Spring, Jackson 3 (tools.jackson) and JUnit versions with a
    // consuming Spring Boot 4.1 app, so nothing here pins a version explicitly.
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    // Spring types appear in the public surface (RestClient in constructors, @Component beans,
    // ApplicationReadyEvent), so they are `api` — consumers are expected to be Spring Boot apps.
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot")

    // Jackson 3 (tools.jackson) for the request/response DTOs and the LLM-output JSON helpers.
    api("tools.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))

    // SLF4J API for logging. Pulled in explicitly because the narrow Spring deps above don't bring
    // it transitively (in a full Spring Boot app it arrives via spring-boot-starter-logging).
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation("org.springframework:spring-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
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
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
