# telegram-oidc-login

Telegram login via the modern OIDC flow (https://core.telegram.org/widgets/login): an OAuth2
Authorization Code + PKCE redirect against `oauth.telegram.org`. Deliberately narrow — it builds
the authorization URL, exchanges the returned code for an id_token, and validates that id_token
(signature, issuer, audience, expiry) against Telegram's JWKS. It stops there: creating a session,
registering/linking an account, and storing the OAuth `state`/PKCE verifier across the redirect are
the consuming app's concern, since those are shaped by whatever identity model the app already has.

Consumers are expected to be **Spring Boot apps** (the client uses `RestClient`, Spring Security's
`JwtDecoder`, and component beans).

**Runtime requirement, not a compile-time one:** the token exchange decodes a JSON body via
`RestClient`, which needs *some* JSON `HttpMessageConverter` on the classpath at runtime (Jackson —
either the Jackson 3 `tools.jackson` this module targets, or legacy Jackson 2 — Gson, and JSON-B all
work equally well here, since the response is decoded into a plain `Map`). This module declares
Jackson `compileOnly` rather than `implementation`, on the assumption that any real consumer already
has one: an OIDC browser-redirect login needs an HTTP endpoint to receive the callback, so the
consumer necessarily runs `spring-boot-starter-web` or `-webflux`, both of which pull in
`spring-boot-starter-json` (Jackson) by default. The gap is a Spring Boot app that has deliberately
excluded its default JSON support in favor of something `RestClient` can't autodetect — in that
case, `completeAuthorization` fails at the first real login attempt with "no suitable
HttpMessageConverter found", not at build time. If that's your setup, add a JSON converter
dependency yourself (e.g. `implementation("tools.jackson.module:jackson-module-kotlin")`).

## What's in it

| Class | Role |
| --- | --- |
| `TelegramOidcService` | The verification core. `buildAuthorizationRequest(redirectUri)` builds a fresh PKCE authorization URL plus the `state`/`codeVerifier` to stash for the callback; `completeAuthorization(code, codeVerifier, redirectUri)` exchanges the code and validates the id_token in one call; `verifyIdToken(idToken)` is exposed separately for testing or a caller that already has a token. `isConfigured()` reports whether `clientId`/`clientSecret` are set, so a deployment without Web Login credentials can degrade gracefully instead of 500ing. |
| `TelegramOidcProperties` | `clientId` / `clientSecret` (from BotFather → Bot Settings → Web Login) plus the OAuth endpoints, defaulted to Telegram's. Supply it as a bean. |
| `TelegramOidcConfiguration` | Provides the `telegramJwtDecoder` (`JwtDecoder`, JWKS-backed, issuer + audience validated) and `telegramTokenRestClient` (`RestClient`) beans `TelegramOidcService` depends on — named explicitly so they don't collide with a consumer's own `JwtDecoder`/`RestClient` beans. |
| `TelegramAuthData` | The mapped claims: `telegramId`, `username`, `firstName`, `photoUrl`, `authDate`. |
| `TelegramAuthorizationRequest` | `authorizationUrl` plus the `state`/`codeVerifier` pair to stash (HTTP session, signed cookie, wherever your app keeps per-request OAuth state). |
| `TelegramAuthException` | Thrown for a failed token exchange or a failed/invalid id_token — catch this one type at the call site. |

## Usage

`TelegramOidcService` and `TelegramOidcConfiguration` are `@Component`/`@Configuration` — component-scan
`dev.itayp.nescioquid.telegram` (or just `@Import` the configuration) and supply a `TelegramOidcProperties` bean:

```kotlin
@Bean
fun telegramOidcProperties() = TelegramOidcProperties(
    clientId = env.telegramClientId,
    clientSecret = env.telegramClientSecret,
)
```

Then, in your own login/callback controller:

```kotlin
@GetMapping("/telegram/start")
fun start(request: HttpServletRequest): ResponseEntity<Void> {
    if (!telegramOidcService.isConfigured()) return unavailable()
    val authz = telegramOidcService.buildAuthorizationRequest(redirectUri)
    request.session.setAttribute("tgState", authz.state)
    request.session.setAttribute("tgVerifier", authz.codeVerifier)
    return redirectTo(authz.authorizationUrl)
}

@GetMapping("/telegram/callback")
fun callback(
    @RequestParam code: String,
    @RequestParam state: String,
    request: HttpServletRequest,
): ResponseEntity<Void> {
    val expectedState = request.session.getAttribute("tgState") as? String
    val verifier = request.session.getAttribute("tgVerifier") as? String
    require(state == expectedState && verifier != null) { "state mismatch" }

    val data = try {
        telegramOidcService.completeAuthorization(code, verifier, redirectUri)
    } catch (e: TelegramAuthException) {
        return failureRedirect()
    }

    // data.telegramId is the stable identity handle — look up or create your own user record,
    // then start your own session however your app normally does.
    val user = myUserService.loginOrRegisterByTelegram(data)
    mySessionAuthenticator.authenticate(user, request, response)
    return redirectTo("/")
}
```

The `state` round-tripped through the session is itself the anti-forgery token for the callback —
a top-level browser redirect can't carry a custom CSRF header, so this is the standard OAuth
mitigation rather than a gap. `codeVerifier` never leaves the server (PKCE): Telegram only ever sees
its SHA-256 challenge.

**Distinct from the Telegram Bot API.** This module only speaks OIDC for *login*. If your app also
runs a Telegram bot (commands, notifications, `sendMessage`), that's the separate
[`java-telegram-bot-api`](https://github.com/rubenlagus/TelegramBots) client — nothing here manages
a bot session or long-polls updates.

## Coordinates

```kotlin
implementation("com.github.Itaypk.Nescioquid:telegram-oidc-login:0.12.0")
```

Requires JVM 25+ and a Spring Boot 4.x runtime with `spring-boot-starter-security` (for
`spring-security-oauth2-jose`). Apache-2.0.
