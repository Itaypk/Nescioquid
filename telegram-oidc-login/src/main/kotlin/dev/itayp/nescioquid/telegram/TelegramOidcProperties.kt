package dev.itayp.nescioquid.telegram

/**
 * The minimal configuration [TelegramOidcService] needs. [clientId] / [clientSecret] come from
 * BotFather → Bot Settings → Web Login; the consumer supplies this as a bean (typically derived
 * from its own application config, since [clientSecret] is a credential).
 */
data class TelegramOidcProperties(
    /** OAuth2/OIDC client id (the bot id) — also the expected `aud` claim of the id_token. */
    val clientId: String,
    /** OAuth2 client secret, used as HTTP Basic credentials for the token exchange. */
    val clientSecret: String,
    val authorizationUri: String = "https://oauth.telegram.org/auth",
    val tokenUri: String = "https://oauth.telegram.org/token",
    val jwkSetUri: String = "https://oauth.telegram.org/.well-known/jwks.json",
    val issuer: String = "https://oauth.telegram.org",
)
