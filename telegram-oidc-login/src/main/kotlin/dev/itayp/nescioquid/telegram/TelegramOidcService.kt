package dev.itayp.nescioquid.telegram

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Telegram login via the OIDC Authorization Code + PKCE flow
 * (https://core.telegram.org/widgets/login): build the authorization request, then on callback
 * exchange the code for an `id_token` (a signed JWT) and validate it against Telegram's JWKS.
 *
 * This is deliberately just the verification core. Session creation, account linking, and
 * CSRF/state storage stay the consuming app's concern — the `state` value round-tripped through
 * [buildAuthorizationRequest] is itself the anti-forgery token for a callback endpoint reached by
 * top-level browser redirect, so no separate CSRF handling is needed there.
 */
@Component
class TelegramOidcService(
    private val properties: TelegramOidcProperties,
    @Qualifier("telegramJwtDecoder") private val telegramJwtDecoder: JwtDecoder,
    @Qualifier("telegramTokenRestClient") private val telegramTokenRestClient: RestClient,
) {
    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** Login can only proceed once the bot's Web Login credentials are configured. */
    fun isConfigured(): Boolean =
        properties.clientId.isNotBlank() && properties.clientSecret.isNotBlank()

    /** Builds a fresh authorization request (random state + PKCE) and the URL to redirect to. */
    fun buildAuthorizationRequest(redirectUri: String): TelegramAuthorizationRequest {
        val state = randomUrlSafe()
        val codeVerifier = randomUrlSafe()
        val codeChallenge = s256(codeVerifier)
        val url = buildString {
            append(properties.authorizationUri)
            append("?client_id=").append(enc(properties.clientId))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&response_type=code")
            append("&scope=").append(enc("openid profile"))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(codeChallenge))
            append("&code_challenge_method=S256")
        }
        return TelegramAuthorizationRequest(authorizationUrl = url, state = state, codeVerifier = codeVerifier)
    }

    /** Exchanges the authorization [code] for an id_token, validates it, and maps the claims. */
    fun completeAuthorization(code: String, codeVerifier: String, redirectUri: String): TelegramAuthData =
        verifyIdToken(exchangeCodeForIdToken(code, codeVerifier, redirectUri))

    private fun exchangeCodeForIdToken(code: String, codeVerifier: String, redirectUri: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", redirectUri)
            add("client_id", properties.clientId)
            add("code_verifier", codeVerifier)
        }
        val body = try {
            telegramTokenRestClient.post()
                .uri(properties.tokenUri)
                .headers { it.setBasicAuth(properties.clientId, properties.clientSecret) }
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map::class.java)
        } catch (e: RestClientException) {
            throw TelegramAuthException("Token exchange failed: ${e.message}")
        }
        val idToken = body?.get("id_token") as? String
        return idToken?.takeIf { it.isNotBlank() }
            ?: throw TelegramAuthException("Token response missing id_token")
    }

    /** Validates the id_token (signature, issuer, audience, expiry) and maps it to [TelegramAuthData]. */
    fun verifyIdToken(idToken: String): TelegramAuthData {
        val jwt = try {
            telegramJwtDecoder.decode(idToken)
        } catch (e: JwtException) {
            throw TelegramAuthException("Invalid id_token: ${e.message}")
        }
        val telegramId = telegramId(jwt) ?: throw TelegramAuthException("id_token missing numeric id claim")
        return TelegramAuthData(
            telegramId = telegramId,
            username = jwt.getClaimAsString("preferred_username"),
            firstName = jwt.getClaimAsString("name"),
            photoUrl = jwt.getClaimAsString("picture"),
            authDate = jwt.issuedAt ?: Instant.EPOCH,
        )
    }

    private fun telegramId(jwt: Jwt): Long? = when (val raw = jwt.getClaim<Any>("id")) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }

    private fun randomUrlSafe(): String = urlEncoder.encodeToString(ByteArray(32).also(random::nextBytes))

    private fun s256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return urlEncoder.encodeToString(digest)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

/** A pending Telegram authorization: the URL to send the user to, plus the state/PKCE to stash. */
data class TelegramAuthorizationRequest(
    val authorizationUrl: String,
    val state: String,
    val codeVerifier: String,
)

data class TelegramAuthData(
    val telegramId: Long,
    val username: String?,
    val firstName: String?,
    val photoUrl: String?,
    val authDate: Instant,
)

class TelegramAuthException(message: String) : RuntimeException(message)
