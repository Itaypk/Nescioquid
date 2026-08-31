plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.4.10"
    `java-library`
    `maven-publish`
}

dependencies {
    // Spring Boot BOM aligns Spring and JUnit versions with a consuming Spring Boot 4.1 app, so
    // nothing here pins a version explicitly.
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    // Spring types appear in the public surface (RestClient/JwtDecoder in constructors, @Component
    // beans), so they are `api` — consumers are expected to be Spring Boot apps.
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("org.springframework.boot:spring-boot")
    api("org.springframework.security:spring-security-oauth2-jose")

    // The token exchange decodes a JSON body via RestClient, which needs a JSON
    // HttpMessageConverter on the classpath — but a consuming Spring Boot app already brings
    // Jackson 3 (`tools.jackson`) transitively via spring-boot-starter-web/-security. `compileOnly`
    // (Gradle's equivalent of Maven's `provided`) declares that expectation without forcing a
    // second copy — and a possible version clash — onto every consumer.
    compileOnly("tools.jackson.module:jackson-module-kotlin")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework:spring-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    // The tests exercise the real RestClient codepath (MockRestServiceServer), so they need the
    // JSON converter the compileOnly dependency above only promises at the consumer's expense.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
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
