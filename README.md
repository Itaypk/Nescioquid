# Nescioquid

Small, reusable Kotlin/JVM libraries extracted from my own projects. Published via
[JitPack](https://jitpack.io) for reuse across my Spring Boot apps — deliberately narrow (reuse of
exactly what I already need, not general-purpose flexibility) and permissively licensed.

> **Nescioquid** (Latin, "I-know-not-what") — a placeholder name for a grab-bag of unrelated,
> independently useful pieces.

## Modules

| Module | Coordinates (JitPack) | What it is |
| --- | --- | --- |
| [`envelope-crypto`](envelope-crypto/) | `com.github.Itaypk.Nescioquid:envelope-crypto:<tag>` | Versioned AES-256-GCM plus per-entity DEK-under-KEK envelope encryption with AAD binding. Pure JDK — zero runtime dependencies. |
| [`openrouter-client`](openrouter-client/) | `com.github.Itaypk.Nescioquid:openrouter-client:<tag>` | A minimal, Spring-native [OpenRouter](https://openrouter.ai) chat client: request/response DTOs, a retrying transport, model-capability fetching, reasoning-effort resolution, and a small function-tool abstraction. |

The two modules are unrelated and version together only because they live in one repo; depend on
whichever you need.

## Using it (JitPack)

Add the JitPack repository and the module(s) you want:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.Itaypk.Nescioquid:envelope-crypto:0.4.0")
    implementation("com.github.Itaypk.Nescioquid:openrouter-client:0.4.0")
}
```

Replace `0.4.0` with a released Git tag (or a commit hash / `main-SNAPSHOT`). JitPack builds each
module on first request.

### JVM 25 required

Both modules target the **JVM 25 toolchain**. JitPack must therefore build on JDK 25 (configured in
[`jitpack.yml`](jitpack.yml)), and consumers must run on JDK 25+. This is a hard requirement, not a
minimum — the artifacts are compiled for 25.

## Building locally

```bash
./gradlew build            # compile + test both modules
./gradlew :envelope-crypto:test
./gradlew publishToMavenLocal   # install to ~/.m2 for local consumption
```

Requires a JDK 25 toolchain (Gradle will provision one if a matching toolchain is discoverable).

## License

Apache-2.0. See the repository's `LICENSE` file.
