plugins {
    kotlin("jvm") version "2.4.10" apply false
}

allprojects {
    group = "dev.itayp.nescioquid"
    version = rootProject.version // sourced from gradle.properties; the release workflow reads it too

    repositories {
        mavenCentral()
    }
}
