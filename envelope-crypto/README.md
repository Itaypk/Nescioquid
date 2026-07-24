# envelope-crypto

Per-entity envelope encryption for JVM apps: a random 32-byte **DEK** (data encryption key) per
entity, each wrapped under one app-wide **KEK** (key encryption key), with every ciphertext bound to
its owning entity via AAD. Pure JDK (`javax.crypto`) — **zero runtime dependencies**.

## What's in it

| Class | Role |
| --- | --- |
| `AesGcmCipher` | The raw primitive: AES-256-GCM with a 1-byte version prefix and a 12-byte random nonce. `seal`/`open` take an explicit AAD. Use directly if you only need authenticated encryption. |
| `EnvelopeCipher` | Per-entity DEK-under-KEK envelope encryption over `AesGcmCipher`, with an in-memory DEK cache and UUID→AAD binding. Carries no persistence/framework dependency. |
| `DekStore` | The persistence seam you implement — `exists` / `findWrappedDek` / `saveWrappedDek`, keyed by the entity UUID. |

## Design

- **Envelope layout:** `[version:1][nonce:12][ciphertext+tag:N]`. The version byte lets a future key
  rotation produce envelopes that older code refuses to decrypt rather than silently mis-handling.
- **AAD binding:** each per-entity value is sealed with the entity's UUID bytes as AAD, so a
  ciphertext from one entity cannot be opened in another's context (moving a blob between rows fails
  authentication).
- **KEK indirection:** `EnvelopeCipher` takes the KEK as a `() -> ByteArray` provider, resolved
  lazily on first cryptographic use — so a missing/malformed key surfaces at first encryption, not at
  construction, and the library never binds to a config framework.
- **System envelopes:** `encryptSystem` / `decryptSystem` encrypt directly under the KEK (bound to a
  fixed `systemAad`) for data not yet owned by any entity — e.g. a pending magic-link email before an
  account exists.

### ⚠️ Random-nonce bound

Nonces are random 96-bit values, so a single key is safe for up to on the order of **2³²
encryptions** (the GCM birthday bound; a nonce collision under GCM is catastrophic — it leaks
plaintext XOR and enables forgery). The per-entity DEK design keeps every key far below this: a DEK
encrypts only one tenant's field values, and the KEK only wraps DEKs and the occasional system blob.
A caller that puts a single key on a high-volume hot path must rotate keys (or switch to a
nonce-reuse-resistant mode) before approaching that bound.

## Usage

Implement `DekStore` over your persistence layer, then construct an `EnvelopeCipher`:

```kotlin
val cipher = EnvelopeCipher(
    kekProvider = { base64Decode(System.getenv("APP_KEK")) }, // 32 bytes
    dekStore = myDekStore,
    systemAad = "my-app-system".toByteArray(),
)

val userId: UUID = /* ... */
cipher.ensureKey(userId)                          // once, when the entity is created
val sealed = cipher.encrypt(userId, "secret")     // ByteArray? (null in → null out)
val clear  = cipher.decrypt(userId, sealed)       // "secret"
```

`ensureKey` is idempotent. `encrypt`/`decrypt` short-circuit `null` to `null`. Decrypting before a
key exists throws `IllegalStateException`.

## Coordinates

```kotlin
implementation("com.github.Itaypk.Nescioquid:envelope-crypto:0.1.0")
```

Requires JVM 25+. Apache-2.0.
