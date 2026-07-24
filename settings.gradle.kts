plugins {
    // Lets Gradle auto-provision the JVM 25 toolchain on any machine that lacks it
    // (CI, JitPack, a fresh clone), so the build isn't tied to a preinstalled JDK 25.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nescioquid"

include("envelope-crypto")
include("openrouter-client")
